package com.byy.ticket.common.trace;

import org.slf4j.MDC;
import java.util.Locale;
import java.util.UUID;

/** 请求追踪标识存入 MDC，由各服务在请求结束时清理。 */
public final class TraceIdContext {
    public static final String HTTP_HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    private TraceIdContext() {
    }

    public static String getOrCreate() {
        String current = MDC.get(MDC_KEY);
        return isValid(current) ? current : setOrCreate(null);
    }

    public static String setOrCreate(String value) {
        String traceId = isValid(value) ? value.toLowerCase(Locale.ROOT)
                : UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    private static boolean isValid(String value) {
        return value != null && value.matches("[0-9a-fA-F]{32}");
    }
}
