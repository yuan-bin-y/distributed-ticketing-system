package com.byy.ticket.common.result;

import java.util.List;

/** 通用分页响应，不依赖数据库分页插件。 */
public record PageVO<T>(List<T> records, long total, long page, long pageSize) {
    public PageVO {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
