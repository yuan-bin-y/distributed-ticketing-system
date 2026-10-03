package com.byy.ticket.event.dto.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 活动分页查询参数；通过 Bean Validation 校验取值范围。
 * 
 * @param page 页码，从 1 开始。
 * @param pageSize 每页条数，活动列表最多 100 条。
 */
public record EventPageQueryDTO(
        @Min(value = 1, message = "页码不能小于1") Integer page,
        @Min(value = 1, message = "每页数量不能小于1")
        @Max(value = 100, message = "每页数量不能超过100") Integer pageSize
) {
    /**
     * 未传页码时默认第 1 页，未传每页条数时默认 20 条。
     */
    public EventPageQueryDTO {
        page = page == null ? 1 : page;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}
