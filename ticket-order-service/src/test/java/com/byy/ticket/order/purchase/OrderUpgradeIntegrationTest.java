package com.byy.ticket.order.purchase;
import com.byy.ticket.testing.MysqlFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
/** 旧版真实订单存在时验证额度回填，避免只验证空数据库迁移。 */
@EnabledIfSystemProperty(named="ticket.test.mysql",matches="true")
class OrderUpgradeIntegrationTest {
    @Test void v7OrdersKeepSnapshotsAndOnlyHeldSessionsOccupyQuotaAfterUpgrade()throws Exception{
        try(var fixture=new MysqlFixture("7")){
            var sql=new JdbcTemplate(fixture.dataSource);
            for(int id=1;id<=3;id++){
                sql.update("INSERT INTO t_order(id,order_no,user_id,idempotency_key,total_amount,status,expires_at,next_attempt_at,quota_status) VALUES(?,?,1,?,1,?,'2030-01-02','2030-01-01',?)",id,String.valueOf(id).repeat(32),"old_"+id,id==3?"CLOSED":"STOCK_PENDING",id==3?"RELEASED":"HELD");
                sql.update("INSERT INTO t_order_item(order_id,event_id,session_id,ticket_tier_id,ticket_tier_name,unit_price,quantity,subtotal_amount) VALUES(?,10,?,?, 'original',1,1,1)",id,100+id,200+id);
                if(id<3)sql.update("INSERT INTO t_user_session_quota(user_id,session_id,occupied_quantity) VALUES(1,?,1)",100+id);
            }
            Flyway.configure().dataSource(fixture.dataSource).locations("classpath:db/migration").load().migrate();
            assertEquals(2,sql.queryForObject("SELECT occupied_sessions FROM t_user_event_quota WHERE user_id=1 AND event_id=10",Integer.class));
            assertEquals(2,sql.queryForObject("SELECT COUNT(*) FROM t_user_event_session WHERE user_id=1 AND active_orders=1",Integer.class));
            assertEquals(3,sql.queryForObject("SELECT COUNT(*) FROM t_order_item WHERE ticket_tier_name='original' AND rule_version=0 AND seat_ids=''",Integer.class));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM t_purchase_acceptance",Integer.class));
        }
    }
}
