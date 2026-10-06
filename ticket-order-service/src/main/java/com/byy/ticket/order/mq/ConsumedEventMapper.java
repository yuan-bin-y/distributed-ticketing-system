package com.byy.ticket.order.mq;

import org.apache.ibatis.annotations.*;

/** 先占唯一键，再核对消息身份；并发消费者由数据库锁串行化。 */
@Mapper
public interface ConsumedEventMapper {
    @Insert("INSERT INTO t_consumed_event(consumer,event_id,payload_hash,order_no,payment_no) "
            + "VALUES('order-payment',#{event.eventId},#{hash},#{event.orderNo},#{event.paymentNo}) "
            + "ON DUPLICATE KEY UPDATE event_id=event_id")
    void insertOrKeep(@Param("event") PaymentSucceededMessage event,@Param("hash") String hash);

    @Select("SELECT payload_hash FROM t_consumed_event WHERE consumer='order-payment' AND event_id=#{id} FOR UPDATE")
    String hash(@Param("id") String id);
}
