package com.byy.ticket.event.dto.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 活动分页查询参数，与电表项目统一使用 page 和 pageSize。 */
public record EventPageQueryDTO(
        @Min(value = 1, message = "页码不能小于1") Integer page,
        @Min(value = 1, message = "每页数量不能小于1")
        @Max(value = 100, message = "每页数量不能超过100") Integer pageSize
) {
    public EventPageQueryDTO {
        page = page == null ? 1 : page;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}
