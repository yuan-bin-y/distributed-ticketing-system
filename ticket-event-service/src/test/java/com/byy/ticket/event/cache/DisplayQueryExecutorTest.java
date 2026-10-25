package com.byy.ticket.event.cache;

import com.byy.ticket.common.result.Result;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import static org.junit.jupiter.api.Assertions.*;

class DisplayQueryExecutorTest {
    @Test void sameKeySharesWorkAndFailureDoesNotPoisonLaterRequests() {
        List<Runnable> jobs = new ArrayList<>();
        var executor = new DisplayQueryExecutor(jobs::add);
        var loads = new AtomicInteger();
        var a = executor.submit("detail:1", () -> loads.incrementAndGet());
        var b = executor.submit("detail:1", () -> loads.incrementAndGet());
        assertEquals(1, jobs.size()); assertFalse(a.hasResult());
        jobs.remove(0).run();
        assertEquals(1, loads.get()); assertEquals(1, ((Result<?>)a.getResult()).data());
        assertEquals(1, ((Result<?>)b.getResult()).data());
        var failed = executor.submit("detail:1", () -> { throw new IllegalStateException("injected"); });
        jobs.remove(0).run(); assertInstanceOf(IllegalStateException.class, failed.getResult());
        executor.submit("detail:1", () -> 2); assertEquals(1, jobs.size());
    }
    @Test void exhaustedWorkerQueueRejectsWithoutSynchronousDatabaseFallback() {
        TaskExecutor full = task -> { throw new TaskRejectedException("full"); };
        var loads = new AtomicInteger(); var executor = new DisplayQueryExecutor(full);
        assertInstanceOf(CacheBusyException.class, executor.submit("a", loads::incrementAndGet).getResult());
        assertEquals(0, loads.get());
    }
}
