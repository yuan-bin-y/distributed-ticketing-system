package com.byy.ticket.inventory.service;

import com.byy.ticket.inventory.dto.inventory.ReserveStockDTO;
import com.byy.ticket.inventory.vo.inventory.StockReservationVO;
import com.byy.ticket.inventory.vo.inventory.TicketStockVO;

/**
 * 定义库存服务的预留、确认、释放及查询能力，由 InventoryServiceImpl 实现本地事务。
 */
public interface InventoryService {
    /**
     * 首次按订单编号预留库存；重复请求校验参数相同后返回已有记录，不重复扣减。
     */
    StockReservationVO reserve(ReserveStockDTO request);
    /**
     * 将 RESERVED 预留确认成 SOLD，同时把预留数量转入售出数量；重复确认保持原结果。
     */
    StockReservationVO confirm(String reservationId);
    /**
     * 将 RESERVED 预留释放成 RELEASED，同时把预留数量归还可用数量；重复释放保持原结果。
     */
    StockReservationVO release(String reservationId);
    /**
     * 按预留编号读取记录和状态，不修改库存。
     */
    StockReservationVO getReservation(String reservationId);
    /**
     * 按票档 ID 查询库存计数，不访问活动或订单数据库。
     */
    TicketStockVO getStock(Long ticketTierId);
}
