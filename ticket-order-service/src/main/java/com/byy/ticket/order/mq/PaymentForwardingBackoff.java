package com.byy.ticket.order.mq;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 同一进程的转发失败共享退避进度；消息重新入队或换编号不会重置预算。 */
@Component
public class PaymentForwardingBackoff {
    private final long baseMs,maxMs;
    private final AtomicInteger failures=new AtomicInteger();
    private final MeterRegistry metrics;

    public PaymentForwardingBackoff(@Value("${ticket.mq.forwarding-backoff-base:1s}") Duration base,
                                    @Value("${ticket.mq.forwarding-backoff-max:30s}") Duration max,
                                    MeterRegistry metrics) {
        if(base.toMillis()<1 || max.compareTo(base)<0 || max.compareTo(Duration.ofMinutes(1))>0)
            throw new IllegalArgumentException("MQ转发退避应为正数，最大值不超过一分钟且不小于基础值");
        baseMs=base.toMillis();maxMs=max.toMillis();this.metrics=metrics;
        Gauge.builder("ticket.mq.forwarding.consecutive.failures",failures,AtomicInteger::get).register(metrics);
    }
    /** 每次失败逐步增加，最大等待有界；正向抖动避免多个实例同步重投。 */
    public long failureDelayMillis() {
        int count=failures.updateAndGet(value->Math.min(30,value+1));
        long delay=Math.min(maxMs,baseMs*(1L<<Math.min(20,count-1)));
        long jitter=ThreadLocalRandom.current().nextLong(Math.max(1,delay/5));
        long result=Math.min(maxMs,delay+jitter);
        metrics.counter("ticket.mq.forwarding.failures").increment();
        metrics.summary("ticket.mq.forwarding.backoff.milliseconds").record(result);
        return result;
    }
    /** 只有目标转发已经收到确认时，才认为转发链路恢复。 */
    public void recovered() {failures.set(0);metrics.counter("ticket.mq.forwarding.confirmed").increment();}
    public void delivered(boolean redelivered) {
        metrics.counter("ticket.mq.deliveries","redelivered",Boolean.toString(redelivered)).increment();
    }
}
