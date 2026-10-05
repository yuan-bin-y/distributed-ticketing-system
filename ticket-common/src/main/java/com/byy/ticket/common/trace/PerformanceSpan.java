package com.byy.ticket.common.trace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 可选分段计时：默认关闭，不记录请求正文或凭证；每个作用域结束只输出一行。 */
public final class PerformanceSpan implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger("ticket.performance");
    private static final boolean ENABLED=Boolean.getBoolean("ticket.perf.enabled") || Boolean.parseBoolean(System.getenv("TICKET_PERF_ENABLED"));
    private static final ThreadLocal<PerformanceSpan> CURRENT=new ThreadLocal<>();
    private static final PerformanceSpan NOOP=new PerformanceSpan(null,null);
    private final String name;
    private final PerformanceSpan previous;
    private final long start=System.nanoTime();
    private final Map<String,Long> steps=new LinkedHashMap<>();
    private String outcome="ok";
    private PerformanceSpan(String name,PerformanceSpan previous){this.name=name;this.previous=previous;}
    /** 在事务代理外打开作用域，才能把获取连接、提交或回滚纳入总耗时。 */
    public static PerformanceSpan open(String name){
        if(!ENABLED)return NOOP;
        var span=new PerformanceSpan(name,CURRENT.get());CURRENT.set(span);return span;
    }
    /** 计时不捕获或替换业务异常；同名步骤累加，便于一次作用域只输出一行。 */
    public static <T> T measure(String step,Supplier<T> operation){
        var span=CURRENT.get();if(span==null)return operation.get();
        long start=System.nanoTime();
        try{return operation.get();}catch(RuntimeException error){span.outcome=error.getClass().getSimpleName();throw error;}
        finally{span.steps.merge(step,System.nanoTime()-start,Long::sum);}
    }
    /** 记录连接池原生回调的实测耗时，避免用事务总量减SQL来推断连接等待。 */
    public static void record(String step,long nanos){
        var span=CURRENT.get();if(span!=null)span.steps.merge(step,nanos,Long::sum);
    }
    /** SQL与外层事务为嵌套时间，不能把所有字段相加当作总耗时。 */
    @Override public void close(){
        if(name==null)return;
        long elapsed=System.nanoTime()-start;
        if(previous==null)CURRENT.remove();else CURRENT.set(previous);
        StringBuilder details=new StringBuilder();
        steps.forEach((step,nanos)->details.append(' ').append(step).append("_us=").append(nanos/1000));
        LOG.info("PERF epoch_ms={} scope={} trace={} outcome={} total_us={}{}",System.currentTimeMillis(),name,TraceIdContext.getOrCreate(),outcome,elapsed/1000,details);
    }
}
