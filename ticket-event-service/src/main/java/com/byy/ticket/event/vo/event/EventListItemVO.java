package com.byy.ticket.event.vo.event;


/**
 * 活动列表的一条展示记录。
 * 
 * @param id 当前记录的数据库主键。
 * @param name 当前活动、场次或票档的名称。
 * @param category 活动分类。
 * @param coverUrl 活动封面地址。
 */
public record EventListItemVO(Long id, String name, String category, String coverUrl) {
}
