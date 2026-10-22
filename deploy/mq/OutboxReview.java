import java.sql.*;
import java.util.*;

/** 默认只列出待人工核对事件；重放必须指定原事件编号、操作人和原因。 */
public class OutboxReview {
    public static void main(String[] args) throws Exception {
        String url=required("PAYMENT_DB_URL");
        if(!url.startsWith("jdbc:mysql:"))throw new IllegalArgumentException("必须提供Payment库的MySQL JDBC地址");
        try(var db=DriverManager.getConnection(url,required("LOCAL_MYSQL_USERNAME"),required("LOCAL_MYSQL_PASSWORD"))) {
            if(args.length==0) {
                try(var sql=db.prepareStatement("SELECT event_id,review_reason,attempt_count,created_at FROM t_outbox_event WHERE status='REVIEW_REQUIRED' ORDER BY id LIMIT 50");var rows=sql.executeQuery()) {
                    while(rows.next())System.out.printf("%s reason=%s attempts=%d created=%s%n",rows.getString(1),rows.getString(2),rows.getInt(3),rows.getString(4));
                }
                return;
            }
            if(args.length!=4 || !args[0].equals("--replay") || !args[1].matches("[a-f0-9]{32}")
                    || args[2].isBlank() || args[2].length()>128 || args[3].isBlank() || args[3].length()>500)
                throw new IllegalArgumentException("用法：--replay 原eventId 操作人 核对原因");
            db.setAutoCommit(false);
            try {
                try(var lock=db.prepareStatement("SELECT o.aggregate_id,o.attempt_count,o.review_reason,p.status,p.order_no,JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.paymentNo')),JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.orderNo')),JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.eventId')),o.event_type,JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.eventType')),JSON_EXTRACT(o.payload,'$.schemaVersion') FROM t_outbox_event o JOIN t_payment p ON p.payment_no=o.aggregate_id WHERE o.event_id=? AND o.status='REVIEW_REQUIRED' FOR UPDATE")) {
                    lock.setString(1,args[1]);
                    try(var rows=lock.executeQuery()) {
                        if(!rows.next())throw new IllegalStateException("事件不存在、不是待核对状态或缺少原付款事实");
                        if(!"SUCCESS".equals(rows.getString(4)) || !rows.getString(1).equals(rows.getString(6))
                                || !rows.getString(5).equals(rows.getString(7)) || !args[1].equals(rows.getString(8))
                                || !"PAYMENT_SUCCEEDED".equals(rows.getString(9))
                                || !"PAYMENT_SUCCEEDED".equals(rows.getString(10)) || !"1".equals(rows.getString(11)))
                            throw new IllegalStateException("载荷与付款事实不一致，先核对数据，禁止自动修复或换号发送");
                        try(var audit=db.prepareStatement("INSERT INTO t_outbox_redrive(event_id,previous_attempt_count,previous_review_reason,operator_name,operation_reason) VALUES(?,?,?,?,?)")) {
                            audit.setString(1,args[1]);audit.setInt(2,rows.getInt(2));audit.setString(3,rows.getString(3));audit.setString(4,args[2]);audit.setString(5,args[3]);audit.executeUpdate();
                        }
                    }
                }
                try(var update=db.prepareStatement("UPDATE t_outbox_event SET status='PENDING',cycle_attempt_count=0,cycle_started_at=CURRENT_TIMESTAMP(3),next_attempt_at=CURRENT_TIMESTAMP(3),review_reason=NULL,last_error=NULL WHERE event_id=? AND status='REVIEW_REQUIRED' AND lease_token IS NULL")) {
                    update.setString(1,args[1]);if(update.executeUpdate()!=1)throw new IllegalStateException("事件状态已经变化");
                }
                db.commit(); System.out.println("已记录核对原因，原事件重新进入自动发布队列；付款事实、原载荷、事件编号和总尝试次数保留。");
            } catch(Exception failure) {db.rollback();throw failure;}
        }
    }
    static String required(String name) {String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("未设置环境变量："+name);return value;}
}
