package com.byy.ticket.event.vo.event;


/**
 * 活动详情展示数据，包含活动介绍。
 * 
 * @param id 当前记录的数据库主键。
 * @param name 当前活动、场次或票档的名称。
 * @param category 活动分类。
 * @param coverUrl 活动封面地址。
 * @param description 活动介绍内容。
 */
public record EventDetailVO(Long id, String name, String category, String coverUrl, String description) {
}
