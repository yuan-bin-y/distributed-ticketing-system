package com.byy.ticket.gateway.web;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Security认证后按可信Principal或直连IP限流；两个桶在Redis内原子扣减，全程非阻塞。 */
@Component
public class GatewayRateLimitFilter implements GlobalFilter,Ordered {
    private static final DefaultRedisScript<Long> LIMIT=new DefaultRedisScript<>(
        "if redis.replicate_commands then redis.replicate_commands() end; "
        +"local tm=redis.call('TIME'); local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000); "
        +"local rates={tonumber(ARGV[1]),tonumber(ARGV[3])}; local caps={tonumber(ARGV[2]),tonumber(ARGV[4])}; "
        +"local tokens={}; local ok=1; "
        +"for i=1,2 do local v=redis.call('HMGET',KEYS[i],'tokens','time'); "
        +"tokens[i]=math.min(caps[i],(tonumber(v[1]) or caps[i])+math.max(0,now-(tonumber(v[2]) or now))*rates[i]/1000); "
        +"if tokens[i]<1 then ok=0 end end; "
        +"for i=1,2 do if ok==1 then tokens[i]=tokens[i]-1 end; "
        +"redis.call('HMSET',KEYS[i],'tokens',tokens[i],'time',now); "
        +"redis.call('PEXPIRE',KEYS[i],math.ceil(caps[i]/rates[i]*2000)+1000) end; return ok",Long.class);
    private final ReactiveStringRedisTemplate redis; private final GatewaySecurityResponses responses;
    private final boolean enabled; private final String prefix;
    private final int eventRate,eventBurst,eventClientRate,eventClientBurst,orderRate,orderBurst,orderClientRate,orderClientBurst;
    public GatewayRateLimitFilter(ReactiveStringRedisTemplate redis,GatewaySecurityResponses responses,
        @Value("${ticket.rate-limit.enabled:true}") boolean enabled,
        @Value("${ticket.rate-limit.prefix:ticket:{gateway-limit}:}") String prefix,
        @Value("${ticket.rate-limit.event-rate:200}") int eventRate,
        @Value("${ticket.rate-limit.event-burst:400}") int eventBurst,
        @Value("${ticket.rate-limit.event-client-rate:20}") int eventClientRate,
        @Value("${ticket.rate-limit.event-client-burst:40}") int eventClientBurst,
        @Value("${ticket.rate-limit.order-rate:100}") int orderRate,
        @Value("${ticket.rate-limit.order-burst:200}") int orderBurst,
        @Value("${ticket.rate-limit.order-client-rate:5}") int orderClientRate,
        @Value("${ticket.rate-limit.order-client-burst:10}") int orderClientBurst) {
        if(java.util.stream.IntStream.of(eventRate,eventBurst,eventClientRate,eventClientBurst,orderRate,orderBurst,
            orderClientRate,orderClientBurst).anyMatch(v->v<1))throw new IllegalArgumentException("限流参数必须大于零");
        this.redis=redis;this.responses=responses;this.enabled=enabled;this.prefix=prefix;
        this.eventRate=eventRate;this.eventBurst=eventBurst;this.eventClientRate=eventClientRate;this.eventClientBurst=eventClientBurst;
        this.orderRate=orderRate;this.orderBurst=orderBurst;this.orderClientRate=orderClientRate;this.orderClientBurst=orderClientBurst;
    }
    @Override public int getOrder(){return -100;}
    /** 根据路由范围取得可信身份，异步检查两个桶后转发或返回429/503。 */
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain){
        String path=exchange.getRequest().getPath().value();
        boolean event=exchange.getRequest().getMethod()==HttpMethod.GET &&
            (path.equals("/api/events")||path.startsWith("/api/events/"));
        boolean order=path.equals("/api/orders")||path.startsWith("/api/orders/");
        if(!enabled||(!event&&!order)||path.endsWith("/ping"))return chain.filter(exchange);
        String scope=event?"events":"orders";
        // 不信任客户端X-Forwarded-For或X-User-Id；反向代理部署需另行配置可信代理边界。
        var address=exchange.getRequest().getRemoteAddress();
        String ip=address==null?"unknown":address.getAddress().getHostAddress();
        return exchange.getPrincipal().map(p->"user:"+p.getName()).defaultIfEmpty("ip:"+ip).flatMap(identity->{
            String client=hash(identity);
            List<String> args=List.of(Integer.toString(event?eventRate:orderRate),Integer.toString(event?eventBurst:orderBurst),
                Integer.toString(event?eventClientRate:orderClientRate),Integer.toString(event?eventClientBurst:orderClientBurst));
            return redis.execute(LIMIT,List.of(prefix+scope+":global",prefix+scope+":client:"+client),args)
                .next().timeout(Duration.ofMillis(500)).defaultIfEmpty(-1L).onErrorReturn(-1L)
                .flatMap(allowed->{
                    if(allowed==1)return chain.filter(exchange);
                    if(allowed==0){
                        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,"1");
                        return responses.write(exchange,HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","请求过于频繁，请稍后重试");
                    }
                    return responses.write(exchange,HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_BUSY","入口限流暂时不可用，请稍后重试");
                });
        });
    }
    /** 哈希身份用于Redis key，避免在key中直接保存用户标识和IP。 */
    private String hash(String identity){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
