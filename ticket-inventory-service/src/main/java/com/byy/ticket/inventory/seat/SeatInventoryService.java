package com.byy.ticket.inventory.seat;

import com.byy.ticket.common.seat.*;
import com.byy.ticket.inventory.dto.inventory.ReserveStockDTO;
import com.byy.ticket.inventory.exception.InventoryConflictException;
import com.byy.ticket.inventory.mapper.TicketStockMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 座位与数量在同一个库存事务里变更；所有写操作都先持有对应票档库存行锁。 */
@Service
public class SeatInventoryService {
    public record Layout(
            long ticketTierId, long sessionId, String layoutHash, List<SeatGrid.Region> regions) {}

    public record State(String seatId, int ordinal, String status, long version) {}

    public record Changes(List<State> records, long version, int nextAfter, boolean hasMore) {}

    private final JdbcTemplate sql;
    private final TicketStockMapper stocks;

    public SeatInventoryService(JdbcTemplate sql, TicketStockMapper stocks) {
        this.sql = sql;
        this.stocks = stocks;
    }

    /** 原参数重复初始化不会重置任何座位；首次仅允许没有购买历史的完整可用库存。 */
    @Transactional
    public Layout initialize(long tier, Layout layout) {
        var stock = stocks.selectForUpdate(tier);
        var regions = SeatGrid.validate(layout.regions());
        String hash = SeatGrid.hash(regions);
        if (stock == null
                || layout.ticketTierId() != tier
                || layout.sessionId() != stock.getSessionId()
                || !hash.equals(layout.layoutHash())
                || SeatGrid.total(regions) != stock.getTotalQuantity())
            throw new InventoryConflictException("座位布局与库存快照不一致");
        if (stock.getSeatMode() == 1) {
            if (!hash.equals(stock.getLayoutHash()))
                throw new InventoryConflictException("座位布局不可修改");
            return new Layout(tier, layout.sessionId(), hash, regions);
        }
        if (!stock.getAvailableQuantity().equals(stock.getTotalQuantity())
                || sql.queryForObject(
                                "SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id=?",
                                Long.class,
                                tier)
                        > 0
                || sql.queryForObject(
                                "SELECT COUNT(*) FROM t_stock_admission WHERE ticket_tier_id=? AND"
                                    + " status='HELD'",
                                Long.class,
                                tier)
                        > 0) throw new InventoryConflictException("已有购买行为，不能切换选座模式");
        var seats = SeatGrid.seats(regions);
        sql.batchUpdate(
                "INSERT INTO"
                    + " t_session_seat(ticket_tier_id,seat_id,region,seat_row,seat_column,ordinal,status,change_version)"
                    + " VALUES(?,?,?,?,?,?,'AVAILABLE',1)",
                seats,
                500,
                (ps, seat) -> {
                    ps.setLong(1, tier);
                    ps.setString(2, seat.id());
                    ps.setString(3, seat.region());
                    ps.setInt(4, seat.row());
                    ps.setInt(5, seat.column());
                    ps.setInt(6, seat.ordinal());
                });
        sql.update(
                "UPDATE t_ticket_stock SET"
                    + " seat_mode=1,layout_hash=?,seat_version=1,admission_refresh_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE ticket_tier_id=?",
                hash,
                tier);
        return new Layout(tier, layout.sessionId(), hash, regions);
    }

    /** 持久化预扣时确认每一个座位都可用。任意一席冲突则整个事务回滚。 */
    public void holdWithinTransaction(ReserveStockDTO request) {
        int mode =
                sql.queryForObject(
                        "SELECT seat_mode FROM t_ticket_stock WHERE ticket_tier_id=?",
                        Integer.class,
                        request.ticketTierId());
        if ((mode == 1) == request.seatIds().isEmpty())
            throw new InventoryConflictException(mode == 1 ? "该票档必须选座" : "该票档不支持选座");
        if (mode == 0) return;
        long version = nextVersion(request.ticketTierId());
        for (String seat : request.seatIds()) {
            if (sql.update(
                            "UPDATE t_session_seat SET status='HELD',owner_order=?,change_version=?"
                                + " WHERE ticket_tier_id=? AND seat_id=? AND status='AVAILABLE'",
                            request.orderId(),
                            version,
                            request.ticketTierId(),
                            seat)
                    != 1) throw new InventoryConflictException("所选座位已被占用或不属于该票档");
            sql.update(
                    "INSERT INTO t_stock_admission_seat(order_no,ticket_tier_id,seat_id)"
                        + " VALUES(?,?,?)",
                    request.orderId(),
                    request.ticketTierId(),
                    seat);
        }
    }

    /** 与正式数量扣减同事务，将本请求持有的全部座位变为RESERVED。 */
    public void applyWithinTransaction(ReserveStockDTO request) {
        transition(request.orderId(), request.ticketTierId(), "HELD", "RESERVED");
    }

    /** 取消HELD或释放RESERVED，只有原持有者可以改变座位，重复操作不增加可用量。 */
    public void cancelWithinTransaction(String id, long tier) {
        transition(id, tier, "HELD", "AVAILABLE");
    }

    public void finishWithinTransaction(String id, long tier, boolean sold) {
        transition(id, tier, "RESERVED", sold ? "SOLD" : "AVAILABLE");
    }

    private void transition(String id, long tier, String expected, String target) {
        var seats =
                sql.queryForList(
                        "SELECT seat_id FROM t_stock_admission_seat WHERE order_no=? AND"
                            + " ticket_tier_id=? ORDER BY seat_id",
                        String.class,
                        id,
                        tier);
        if (seats.isEmpty()) return;
        long version = nextVersion(tier);
        for (String seat : seats)
            if (sql.update(
                            "UPDATE t_session_seat SET status=?,owner_order=?,change_version=?"
                                + " WHERE ticket_tier_id=? AND seat_id=? AND owner_order=? AND"
                                + " status=?",
                            target,
                            "AVAILABLE".equals(target) ? null : id,
                            version,
                            tier,
                            seat,
                            id,
                            expected)
                    != 1) throw new IllegalStateException("座位状态与预留事实不一致");
    }

    /** Redis重建仅加载真正可用的座位，待落实HELD座位已经在本地事务中排除。 */
    public List<String> available(long tier) {
        return sql.queryForList(
                "SELECT seat_id FROM t_session_seat WHERE ticket_tier_id=? AND status='AVAILABLE'"
                    + " ORDER BY ordinal",
                String.class,
                tier);
    }

    private long nextVersion(long tier) {
        sql.update(
                "UPDATE t_ticket_stock SET seat_version=seat_version+1 WHERE ticket_tier_id=?",
                tier);
        return sql.queryForObject(
                "SELECT seat_version FROM t_ticket_stock WHERE ticket_tier_id=?", Long.class, tier);
    }

    /** 增量分页固定through版本；本批全部取完后才推进since，分页期间的新变化由下一批取得。 */
    @Transactional(readOnly = true)
    public Changes changes(
            long tier,
            String region,
            int rowStart,
            int rowCount,
            long since,
            long through,
            int after,
            int limit) {
        return changes(tier, region, rowStart, rowCount, 1, 500, since, through, after, limit);
    }

    @Transactional(readOnly = true)
    public Changes changes(
            long tier,
            String region,
            int rowStart,
            int rowCount,
            int columnStart,
            int columnCount,
            long since,
            long through,
            int after,
            int limit) {
        if (tier < 1
                || region == null
                || !region.matches("[A-Za-z0-9_-]{1,20}")
                || rowStart < 1
                || rowStart > 500
                || rowCount < 1
                || rowCount > 20
                || since < 0
                || through < 0
                || after < 0
                || limit < 1
                || limit > 200) throw new IllegalArgumentException("座位视窗或游标参数无效");
        if (columnStart < 1 || columnStart > 500 || columnCount < 1 || columnCount > 500)
            throw new IllegalArgumentException("座位列视窗无效");
        var stock = stocks.selectById(tier);
        if (stock == null || stock.getSeatMode() != 1)
            throw new InventoryConflictException("票档未启用选座");
        long upper = through == 0 ? stock.getSeatVersion() : through;
        if (since > upper || upper > stock.getSeatVersion())
            throw new IllegalArgumentException("座位版本游标无效");
        var values =
                sql.query(
                        "SELECT seat_id,ordinal,status,change_version FROM t_session_seat WHERE"
                            + " ticket_tier_id=? AND region=? AND seat_row>=? AND seat_row<? AND"
                            + " seat_column>=? AND seat_column<? AND ordinal>? AND change_version>?"
                            + " AND change_version<=? ORDER BY ordinal LIMIT ?",
                        (rs, i) ->
                                new State(
                                        rs.getString(1),
                                        rs.getInt(2),
                                        rs.getString(3),
                                        rs.getLong(4)),
                        tier,
                        region,
                        rowStart,
                        rowStart + rowCount,
                        columnStart,
                        columnStart + columnCount,
                        after,
                        since,
                        upper,
                        limit + 1);
        boolean more = values.size() > limit;
        var result = more ? values.subList(0, limit) : values;
        return new Changes(
                List.copyOf(result),
                upper,
                result.isEmpty() ? after : result.get(result.size() - 1).ordinal(),
                more);
    }
}
