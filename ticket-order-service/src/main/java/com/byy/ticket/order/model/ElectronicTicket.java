package com.byy.ticket.order.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 每张实际获得的电子票一条记录；用户和活动归属从订单及购买快照读取。 */
@Data
@TableName("t_ticket")
public class ElectronicTicket {
    /** 订单库内的主键。 */
    @TableId(type=IdType.AUTO)
    private Long id;
    /** 对外唯一票号；重试保留已保存的原票号。 */
    private String ticketNo;
    /** 同一数据库中的订单项编号。 */
    private Long orderItemId;
    /** 该订单项的第几张票，从1开始；与订单项一起组成唯一键。 */
    private Integer ticketIndex;
    /** 本阶段首次生成均为VALID，后续再增加核销和作废。 */
    private String status;
    /** 数据库维护的出票时间。 */
    private LocalDateTime createdAt;
}
