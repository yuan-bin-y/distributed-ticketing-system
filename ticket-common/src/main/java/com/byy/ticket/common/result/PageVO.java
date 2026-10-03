package com.byy.ticket.common.result;

import java.util.List;

/**
 * 统一分页响应：记录列表、总条数、当前页码和每页条数。
 * 
 * @param <T> 业务数据或记录元素的类型。
 * @param records 当前页的记录列表。
 * @param total 符合查询条件的记录总条数。
 * @param page 页码，从 1 开始。
 * @param pageSize 每页条数，活动列表最多 100 条。
 */
public record PageVO<T>(List<T> records, long total, long page, long pageSize) {
    /**
     * 把空列表归一化为空集合，并复制列表，避免外部修改影响分页响应。
     */
    public PageVO {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
