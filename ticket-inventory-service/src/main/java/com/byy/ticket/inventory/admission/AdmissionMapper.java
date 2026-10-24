package com.byy.ticket.inventory.admission;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** 所有写方法在持有对应库存行锁时使用，统一与正式预留及重建的串行顺序。 */
@Mapper
public interface AdmissionMapper {
    @Select("SELECT * FROM t_stock_admission WHERE order_no=#{id}")
    StockAdmission find(String id);

    @Insert("""
        INSERT INTO t_stock_admission(order_no,ticket_tier_id,session_id,quantity,expires_at,status)
        VALUES(#{orderNo},#{ticketTierId},#{sessionId},#{quantity},#{expiresAt},#{status})
        ON DUPLICATE KEY UPDATE order_no=t_stock_admission.order_no
        """)
    int insert(StockAdmission record);

    @Update("UPDATE t_stock_admission SET status=#{status} WHERE order_no=#{id}")
    int state(@Param("id") String id,@Param("status") String status);

    @Select("SELECT * FROM t_stock_admission WHERE ticket_tier_id=#{tier} AND status='HELD' ORDER BY order_no")
    List<StockAdmission> pending(long tier);

    @Select("SELECT COALESCE(SUM(quantity),0) FROM t_stock_admission WHERE ticket_tier_id=#{tier} AND status='HELD'")
    long heldQuantity(long tier);

    @Select("SELECT admission_epoch FROM t_ticket_stock WHERE ticket_tier_id=#{tier}")
    String epoch(long tier);

    @Update("UPDATE t_ticket_stock SET admission_epoch=#{epoch},admission_refresh_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)) WHERE ticket_tier_id=#{tier}")
    int rebuilt(@Param("tier") long tier,@Param("epoch") String epoch);

    @Update("UPDATE t_ticket_stock SET admission_refresh_at=CURRENT_TIMESTAMP(3) WHERE ticket_tier_id=#{tier}")
    int dirty(long tier);

    @Select("SELECT ticket_tier_id FROM t_ticket_stock WHERE admission_refresh_at<=CURRENT_TIMESTAMP(3) ORDER BY admission_refresh_at,ticket_tier_id LIMIT 20")
    List<Long> due();
}
