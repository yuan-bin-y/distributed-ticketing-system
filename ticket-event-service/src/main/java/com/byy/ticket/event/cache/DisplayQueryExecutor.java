package com.byy.ticket.event.cache;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.common.trace.TraceIdContext;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

/** 同一实例、同一展示参数共用一个查询；Tomcat线程异步退出，有界工作池负责跨实例缓存等待。 */
@Component
public class DisplayQueryExecutor {
    private final TaskExecutor executor;
    private final ConcurrentHashMap<String, CompletableFuture<Object>> running = new ConcurrentHashMap<>();

    public DisplayQueryExecutor(@Qualifier("eventDisplayExecutor") TaskExecutor executor) { this.executor = executor; }

    public <T> DeferredResult<Result<T>> submit(String key, Supplier<T> query) {
        var response = new DeferredResult<Result<T>>(5000L);
        String trace = TraceIdContext.getOrCreate();
        response.onTimeout(() -> response.setErrorResult(new CacheBusyException()));
        var candidate = new CompletableFuture<Object>();
        var previous = running.putIfAbsent(key, candidate);
        var result = previous == null ? candidate : previous;
        result.whenComplete((value, error) -> {
            if (error != null) response.setErrorResult(error);
            else {
                @SuppressWarnings("unchecked") T typed = (T) value;
                response.setResult(new Result<>("OK", "success", typed, trace));
            }
        });
        if (previous == null) {
            try {
                executor.execute(() -> {
                    try { candidate.complete(query.get()); }
                    catch (RuntimeException failure) { candidate.completeExceptionally(failure); }
                    finally { running.remove(key, candidate); }
                });
            } catch (TaskRejectedException full) {
                running.remove(key, candidate); candidate.completeExceptionally(new CacheBusyException());
            }
        }
        return response;
    }
}
