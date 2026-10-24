package com.byy.ticket.common.result;
import java.util.List;
/** 按唯一递增 ID 翻页，不执行深 OFFSET 或全量计数；nextCursor 只在还有下一页时使用。 */
public record CursorPage<T>(List<T> records,long nextCursor,boolean hasMore){
    public CursorPage{records=List.copyOf(records);}
}
