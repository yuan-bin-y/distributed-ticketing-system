package com.byy.ticket.inventory.admission;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 所有写方法在持有对应库存行锁时使用，统一与正式预留及重建的串行顺序。 */
@Mapper
public interface AdmissionMapper {
    @Select(
            "SELECT order_no,ticket_tier_id,session_id,quantity,expires_at,status,seat_fingerprint"
                + " FROM t_stock_admission WHERE order_no=#{id} UNION ALL SELECT"
                + " order_no,ticket_tier_id,session_id,quantity,expires_at,status,seat_fingerprint"
                + " FROM t_stock_admission_receipt WHERE order_no=#{id} LIMIT 1")
    StockAdmission find(String id);

    @Insert(
            """
INSERT INTO t_stock_admission(order_no,ticket_tier_id,session_id,quantity,expires_at,status,seat_fingerprint)
SELECT #{orderNo},#{ticketTierId},#{sessionId},#{quantity},#{expiresAt},#{status},#{seatFingerprint}
WHERE NOT EXISTS (SELECT 1 FROM t_stock_admission_receipt WHERE order_no=#{orderNo})
ON DUPLICATE KEY UPDATE order_no=t_stock_admission.order_no
""")
    int insert(StockAdmission record);

    @Update("UPDATE t_stock_admission SET status=#{status} WHERE order_no=#{id}")
    int state(@Param("id") String id, @Param("status") String status);

    @Select(
            "SELECT order_no,ticket_tier_id,session_id,quantity,expires_at,status,seat_fingerprint"
                + " FROM t_stock_admission WHERE ticket_tier_id=#{tier} AND status='HELD' ORDER BY"
                + " order_no")
    List<StockAdmission> pending(long tier);

    @Select(
            "SELECT"
                + " a.order_no,a.ticket_tier_id,a.session_id,a.quantity,a.expires_at,a.status,a.seat_fingerprint"
                + " FROM t_stock_admission a LEFT JOIN t_stock_reservation r ON"
                + " r.order_id=a.order_no WHERE a.expires_at<#{cutoff} AND (a.status IN"
                + " ('CANCELLED','RELEASED') OR (a.status='APPLIED' AND r.status='SOLD')) ORDER BY"
                + " a.expires_at,a.order_no LIMIT 200")
    List<StockAdmission> archiveCandidates(java.time.LocalDateTime cutoff);

    @Insert(
            "INSERT INTO"
                + " t_stock_admission_receipt(order_no,ticket_tier_id,session_id,quantity,expires_at,status,seat_fingerprint)"
                + " SELECT"
                + " a.order_no,a.ticket_tier_id,a.session_id,a.quantity,a.expires_at,a.status,a.seat_fingerprint"
                + " FROM t_stock_admission a LEFT JOIN t_stock_reservation r ON"
                + " r.order_id=a.order_no WHERE a.order_no=#{id} AND a.expires_at<#{cutoff} AND"
                + " (a.status IN ('CANCELLED','RELEASED') OR (a.status='APPLIED' AND"
                + " r.status='SOLD')) ON DUPLICATE KEY UPDATE"
                + " order_no=t_stock_admission_receipt.order_no")
    int archive(@Param("id") String id, @Param("cutoff") java.time.LocalDateTime cutoff);

    @Delete(
            "DELETE FROM t_stock_admission WHERE order_no=#{id} AND EXISTS (SELECT 1 FROM"
                + " t_stock_admission_receipt WHERE order_no=#{id})")
    int removeArchived(String id);

    @Select(
            "SELECT COALESCE(SUM(quantity),0) FROM t_stock_admission WHERE ticket_tier_id=#{tier}"
                + " AND status='HELD'")
    long heldQuantity(long tier);

    @Select("SELECT admission_epoch FROM t_ticket_stock WHERE ticket_tier_id=#{tier}")
    String epoch(long tier);

    @Update(
            "UPDATE t_ticket_stock SET"
                + " admission_epoch=#{epoch},admission_refresh_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3))"
                + " WHERE ticket_tier_id=#{tier}")
    int rebuilt(@Param("tier") long tier, @Param("epoch") String epoch);

    @Update(
            "UPDATE t_ticket_stock SET admission_refresh_at=CURRENT_TIMESTAMP(3) WHERE"
                + " ticket_tier_id=#{tier}")
    int dirty(long tier);

    @Select(
            "SELECT ticket_tier_id FROM t_ticket_stock WHERE"
                + " admission_refresh_at<=CURRENT_TIMESTAMP(3) ORDER BY"
                + " admission_refresh_at,ticket_tier_id LIMIT 20")
    List<Long> due();
}
