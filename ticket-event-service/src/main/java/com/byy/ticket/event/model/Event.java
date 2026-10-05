package com.byy.ticket.event.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 活动数据库 t_event 的实体，一条记录描述一个活动。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_event")
public class Event {
    /**
     * 当前记录的数据库主键。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 当前活动、场次或票档的名称。
     */
    private String name;
    /**
     * 活动分类。
     */
    private String category;
    /**
     * 活动封面地址。
     */
    private String coverUrl;
    /**
     * 活动介绍内容。
     */
    private String description;
    /**
     * 发布状态，活动查询只对外展示 PUBLISHED 的记录。
     */
    private String status;
    /** 管理端创建幂等键与完整请求摘要，重试不生成另一组票档ID。 */
    private String creationKey;
    private String creationHash;
    /**
     * 记录创建时间，由数据库默认值写入。
     */
    private LocalDateTime createdAt;
    /**
     * 记录最后更新时间，由数据库维护。
     */
    private LocalDateTime updatedAt;
}
