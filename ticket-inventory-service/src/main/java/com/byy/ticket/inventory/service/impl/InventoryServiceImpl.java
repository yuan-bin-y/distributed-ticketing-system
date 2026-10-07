package com.byy.ticket.inventory.service.impl;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.common.trace.PerformanceSpan;
import com.byy.ticket.inventory.dto.inventory.ReserveStockDTO;
import com.byy.ticket.inventory.exception.InventoryConflictException;
import com.byy.ticket.inventory.mapper.StockReservationMapper;
import com.byy.ticket.inventory.mapper.TicketStockMapper;
import com.byy.ticket.inventory.model.ReservationStatus;
import com.byy.ticket.inventory.model.StockReservation;
import com.byy.ticket.inventory.model.TicketStock;
import com.byy.ticket.inventory.service.InventoryService;
import com.byy.ticket.inventory.service.HotStockGate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.byy.ticket.inventory.vo.inventory.StockReservationVO;
import com.byy.ticket.inventory.vo.inventory.TicketStockVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 库存业务实现：用本地数据库事务把预留记录与库存计数一起提交或回滚。
 * 唯一订单编号实现重复请求幂等，行锁协调同一预留的并发操作，条件更新防止库存扣成负数。
 */
@Service
public class InventoryServiceImpl implements InventoryService {
    private final TicketStockMapper stockMapper;
    private final StockReservationMapper reservationMapper;
    private final Clock clock;
    private final HotStockGate hotStockGate;
    private final TransactionTemplate reservationTransaction;

    /**
     * 注入库存 Mapper、预留 Mapper 和时钟，所有写入都落在库存服务自己的数据库。
     */
    public InventoryServiceImpl(TicketStockMapper stockMapper, StockReservationMapper reservationMapper, Clock clock,
                               HotStockGate hotStockGate, PlatformTransactionManager transactionManager) {
        this.stockMapper = stockMapper;
        this.reservationMapper = reservationMapper;
        this.clock = clock;
        this.hotStockGate=hotStockGate;
        this.reservationTransaction=new TransactionTemplate(transactionManager);
        this.reservationTransaction.setIsolationLevel(Isolation.READ_COMMITTED.value());
        this.reservationTransaction.setTimeout(10);
    }

    /**
     * 校验参数后尝试写入预留记录，并按订单编号加行锁读取已有结果。
     * 重复请求只核对原参数并返回原记录；首次请求检查到期时间，再条件更新库存。
     * 任一步失败都会回滚此事务中的记录写入和库存修改，避免产生无库存支撑的预留。
     */
    @Override
    public StockReservationVO reserve(ReserveStockDTO request) {
        validateRequest(request);
        // 必须在开启事务前领取，且在事务提交/回滚以后释放。Redis只保护并发，SQL仍决定库存。
        try (HotStockGate.Permit permit=hotStockGate.acquire(request.ticketTierId())) {
            return reservationTransaction.execute(status -> reserveInTransaction(request));
        }
    }

    /** 仅在TransactionTemplate事务中执行；保持原预留幂等、条件扣减及两表原子提交。 */
    private StockReservationVO reserveInTransaction(ReserveStockDTO request) {
        StockReservation candidate = new StockReservation();
        candidate.setReservationId(UUID.randomUUID().toString().replace("-", ""));
        candidate.setOrderId(request.orderId());
        candidate.setSessionId(request.sessionId());
        candidate.setTicketTierId(request.ticketTierId());
        candidate.setQuantity(request.quantity());
        candidate.setExpiresAt(request.expiresAt());
        candidate.setStatus(ReservationStatus.RESERVED.name());

        // 并发的相同 orderId 在唯一键处等待。不能依赖 JDBC 的“影响行数”区分插入和重复。
        PerformanceSpan.measure("sql.reservation.insert",()->reservationMapper.insertOrKeep(candidate));
        StockReservation existing = PerformanceSpan.measure("sql.reservation.lock",()->reservationMapper.selectByOrderIdForUpdate(request.orderId()));
        if (existing == null) {
            throw new IllegalStateException("无法读取已锁定的库存预留记录");
        }
        if (!candidate.getReservationId().equals(existing.getReservationId())) {
            requireSameRequest(existing, request);
            // 返回当前状态，即使已到期或已释放，也不会再次预留。
            return toVO(existing);
        }

        // 仅首次请求检查未来时间，相同请求在到期后仍能幂等重试。
        if (!request.expiresAt().isAfter(LocalDateTime.now(clock))) {
            throw new IllegalArgumentException("首次预留的到期时间必须晚于当前时间");
        }
        if (PerformanceSpan.measure("sql.stock.update",()->stockMapper.reserve(request.ticketTierId(), request.sessionId(), request.quantity())) != 1) {
            TicketStock stock = stockMapper.selectForUpdate(request.ticketTierId());
            if (stock == null) {
                throw new ResourceNotFoundException("票档库存不存在");
            }
            if (!request.sessionId().equals(stock.getSessionId())) {
                throw new InventoryConflictException("票档库存不属于该场次");
            }
            throw new InventoryConflictException("可用库存不足");
        }
        return toVO(existing);
    }

    /**
     * 在本地事务中把预留确认成已售出，具体状态校验和计数变化由 transition 执行。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StockReservationVO confirm(String reservationId) {
        return transition(reservationId, ReservationStatus.SOLD);
    }

    /**
     * 在本地事务中释放预留，把票数归还可用库存，具体状态校验由 transition 执行。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StockReservationVO release(String reservationId) {
        return transition(reservationId, ReservationStatus.RELEASED);
    }

    /**
     * 锁定预留记录；已处于目标状态时直接返回，其他终态转换视为冲突。
     * 把 RESERVED 改成目标状态，并同步修改库存计数；修改失败则抛异常使外层事务回滚。
     */
    private StockReservationVO transition(String reservationId, ReservationStatus target) {
        validateReservationId(reservationId);
        StockReservation reservation = reservationMapper.selectForUpdate(reservationId);
        if (reservation == null) {
            throw new ResourceNotFoundException("库存预留记录不存在");
        }
        if (target.name().equals(reservation.getStatus())) {
            return toVO(reservation);
        }
        if (!ReservationStatus.RESERVED.name().equals(reservation.getStatus())) {
            throw new InventoryConflictException("预留已为" + reservation.getStatus() + "，不能改为" + target.name());
        }
        if (reservationMapper.changeReservedStatus(reservationId, target.name()) != 1) {
            throw new IllegalStateException("库存预留状态更新失败");
        }
        int changed = target == ReservationStatus.SOLD
                ? stockMapper.confirm(reservation.getTicketTierId(), reservation.getSessionId(), reservation.getQuantity())
                : stockMapper.release(reservation.getTicketTierId(), reservation.getSessionId(), reservation.getQuantity());
        if (changed != 1) {
            // 数据不一致时回滚状态更新，不把未完成的库存修改返回为成功。
            throw new IllegalStateException("预留记录与库存数量不一致");
        }
        reservation.setStatus(target.name());
        return toVO(reservation);
    }

    /**
     * 只读查询预留记录，先验证预留编号格式；不存在时抛出资源不存在异常。
     */
    @Override
    @Transactional(readOnly = true)
    public StockReservationVO getReservation(String reservationId) {
        validateReservationId(reservationId);
        StockReservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null) {
            throw new ResourceNotFoundException("库存预留记录不存在");
        }
        return toVO(reservation);
    }

    /**
     * 只读查询票档库存，并转换为 TicketStockVO；票档库存尚未初始化时返回 404。
     */
    @Override
    @Transactional(readOnly = true)
    public TicketStockVO getStock(Long ticketTierId) {
        requirePositiveId(ticketTierId, "票档ID");
        TicketStock stock = stockMapper.selectById(ticketTierId);
        if (stock == null) {
            throw new ResourceNotFoundException("票档库存不存在");
        }
        return new TicketStockVO(stock.getTicketTierId(), stock.getSessionId(), stock.getTotalQuantity(),
                stock.getAvailableQuantity(), stock.getReservedQuantity(), stock.getSoldQuantity());
    }

    /**
     * 比较重复预留的场次、票档、数量和到期时间，确保同一订单编号没有被用于不同请求。
     */
    private void requireSameRequest(StockReservation reservation, ReserveStockDTO request) {
        if (!request.sessionId().equals(reservation.getSessionId())
                || !request.ticketTierId().equals(reservation.getTicketTierId())
                || !request.quantity().equals(reservation.getQuantity())
                || !request.expiresAt().equals(reservation.getExpiresAt())) {
            throw new InventoryConflictException("同一订单关联编号的预留参数必须一致");
        }
    }

    /**
     * 校验订单编号、ID、数量和日期范围、精度，避免无效数据进入事务。
     * 这里不检查是否已到期：原预留的重试允许到期，只有首次预留才要求到期时间晚于当前时间。
     */
    private void validateRequest(ReserveStockDTO request) {
        if (request == null || request.orderId() == null || !request.orderId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("订单关联编号须为1至64位字母、数字、下划线或短横线");
        }
        requirePositiveId(request.sessionId(), "场次ID");
        requirePositiveId(request.ticketTierId(), "票档ID");
        if (request.quantity() == null || request.quantity() < 1) {
            throw new IllegalArgumentException("数量必须大于零");
        }
        if (request.expiresAt() == null || request.expiresAt().getYear() < 1000
                || request.expiresAt().getYear() > 9999 || request.expiresAt().getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("到期时间须为有效的东八区时间，精度最多毫秒");
        }
    }

    /**
     * 验证业务 ID 非空且大于零，不满足时按参数错误处理。
     */
    private void requirePositiveId(Long id, String field) {
        if (id == null || id < 1) {
            throw new IllegalArgumentException(field + "必须大于零");
        }
    }

    /**
     * 验证预留编号为 32 位小写十六进制字符串，与生成规则保持一致。
     */
    private void validateReservationId(String reservationId) {
        if (reservationId == null || !reservationId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("预留ID须为32位小写十六进制字符");
        }
    }

    /**
     * 把数据库预留实体转换成接口响应，返回编号、请求参数和当前状态。
     */
    private StockReservationVO toVO(StockReservation reservation) {
        return new StockReservationVO(reservation.getReservationId(), reservation.getOrderId(),
                reservation.getSessionId(), reservation.getTicketTierId(), reservation.getQuantity(),
                reservation.getExpiresAt(), reservation.getStatus());
    }
}
