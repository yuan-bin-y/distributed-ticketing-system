package com.byy.ticket.event.client;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.common.trace.TraceIdContext;
import com.byy.ticket.event.config.EventPreparationProperties;
import com.byy.ticket.security.service.EventInventoryCredential;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
/** 只传初始化快照并核对实际事实；超时不能证明对方没有提交。 */
@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.byy.ticket.resilience.HttpResilienceProperties.class)
public class InventoryInitializationClient {
    private static final ParameterizedTypeReference<Result<StockFact>> TYPE=new ParameterizedTypeReference<>(){};
    private final RestClient client;
    private final com.byy.ticket.resilience.HttpCallProtection protection;
    public InventoryInitializationClient(@Qualifier("initializationRestClientBuilder") RestClient.Builder builder,
            EventPreparationProperties properties,EventInventoryCredential credential){
        this(builder,properties,credential,com.byy.ticket.resilience.HttpResilienceProperties.defaults());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public InventoryInitializationClient(@Qualifier("initializationRestClientBuilder") RestClient.Builder builder,
            EventPreparationProperties properties,EventInventoryCredential credential,
            com.byy.ticket.resilience.HttpResilienceProperties settings){
        this(builder,properties,credential,settings.inventory());
    }
    /** 初始化和幂等事实核对共用下游保护；明确拒绝仍暂停准备，瞬时拒绝继续保留PENDING。 */
    public InventoryInitializationClient(RestClient.Builder builder,EventPreparationProperties properties,
            EventInventoryCredential credential,com.byy.ticket.resilience.HttpResilienceProperties.Policy policy){
        client=builder.clone().baseUrl("http://"+properties.serviceId()).defaultHeader(EventInventoryCredential.HEADER,credential.value()).build();
        protection=new com.byy.ticket.resilience.HttpCallProtection("event-inventory",policy,
                error -> !(error instanceof IllegalArgumentException)
                        && !(error instanceof com.byy.ticket.resilience.RemoteRequestRejectedException)
                        && (!(error instanceof InitializationRejected rejected) || rejected.healthFailure),
                denied -> new IllegalStateException("库存初始化暂被保护组件拒绝，尚未发送请求，保留准备进度",denied));
    }
    public void initialize(Long tier,Long session,Integer quantity){
        if(tier==null||tier<1||session==null||session<1||quantity==null||quantity<1)
            throw new IllegalArgumentException("库存初始化参数不正确");
        protection.execute(() -> { initializeHttp(tier,session,quantity); return null; });
    }
    /** 发送原初始化快照，核对权威库存事实；超时仍可能已经提交。 */
    private void initializeHttp(Long tier,Long session,Integer quantity){
        client.post().uri("/internal/stocks/initializations").header(TraceIdContext.HTTP_HEADER,TraceIdContext.getOrCreate())
                .body(Map.of("ticketTierId",tier,"sessionId",session,"totalQuantity",quantity)).exchange((sent,received)->{
                    int status=received.getStatusCode().value();
                    if(status==400||status==409||status==401||status==403)throw new InitializationRejected("库存初始化被拒绝，HTTP "+status);
                    if(received.getStatusCode().is4xxClientError())throw new com.byy.ticket.resilience.RemoteRequestRejectedException("库存初始化HTTP拒绝，status="+status);
                    if(!received.getStatusCode().is2xxSuccessful())throw new IllegalStateException("库存初始化暂未确认，HTTP "+status);
                    Result<StockFact> result=received.bodyTo(TYPE);
                    var fact=result==null?null:result.data();
                    if(result==null||!"OK".equals(result.code())||fact==null||!tier.equals(fact.ticketTierId())||!session.equals(fact.sessionId())
                            ||!quantity.equals(fact.totalQuantity())||fact.availableQuantity()==null||fact.reservedQuantity()==null||fact.soldQuantity()==null
                            ||fact.availableQuantity()<0||fact.reservedQuantity()<0||fact.soldQuantity()<0
                            ||(long)fact.availableQuantity()+fact.reservedQuantity()+fact.soldQuantity()!=quantity)
                        throw new InitializationRejected("库存初始化响应与草稿快照不一致",true);
                    return fact;
                });
    }
    public record StockFact(Long ticketTierId,Long sessionId,Integer totalQuantity,Integer availableQuantity,Integer reservedQuantity,Integer soldQuantity){}
    /** 参数或事实矛盾暂停自动尝试，管理员核对后可用原参数重新准备。 */
    public static class InitializationRejected extends RuntimeException {
        private final boolean healthFailure;
        public InitializationRejected(String message){this(message,false);}
        public InitializationRejected(String message,boolean healthFailure){super(message);this.healthFailure=healthFailure;}
    }
    public com.byy.ticket.resilience.HttpCallProtection protection() { return protection; }
}
