package com.byy.ticket.common.trace;

import org.slf4j.MDC;
import java.util.Locale;
import java.util.UUID;

/**
 * 维护当前线程的请求追踪编号，让日志和跨服务 HTTP 请求能使用同一个 traceId。
 */
public final class TraceIdContext {
    public static final String HTTP_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    /**
     * 工具类不需要实例，私有构造方法限制外部创建对象。
     */
    private TraceIdContext() {
    }

    /**
     * 获取当前线程已有的有效 traceId；没有时生成并写入 MDC，供日志和响应使用。
     */
    public static String getOrCreate() {
        String current = MDC.get(MDC_KEY);
        return isValid(current) ? current : setOrCreate(null);
    }

    /**
     * 接收上游传来的 32 位十六进制编号；无效或缺失时生成新编号，并写入 MDC。
     * 统一转成小写，返回本次请求实际使用的 traceId。
     */
    public static String setOrCreate(String value) {
        String traceId = isValid(value) ? value.toLowerCase(Locale.ROOT)
                : UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    /**
     * 清除当前线程 MDC 中的 traceId，避免线程复用时把上一请求的编号带到下一请求。
     */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /**
     * 判断编号是否为 32 位十六进制字符串，避免直接使用任意外部输入。
     */
    private static boolean isValid(String value) {
        return value != null && value.matches("[0-9a-fA-F]{32}");
    }
}
