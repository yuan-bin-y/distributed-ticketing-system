package com.byy.ticket.order.client;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.client.dto.StockReservationRequest;
import com.byy.ticket.order.client.exception.InventoryServiceCallException;
import com.byy.ticket.order.client.exception.InventoryServiceCallException.Reason;
import com.byy.ticket.order.config.InventoryClientProperties;
import com.byy.ticket.security.service.OrderInventoryCredential;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/** 下单前的库存准入调用。超时保留未知结果，相同请求编号用于核对，不能直接归还数量。 */
@Component
public class StockAdmissionClient {
    public record Admission(String orderNo,Long ticketTierId,Long sessionId,Integer quantity,LocalDateTime expiresAt,String status){}
    private static final ParameterizedTypeReference<Result<Admission>> TYPE=new ParameterizedTypeReference<>(){};
    private final RestClient client;
    private final boolean enabled;
    private final com.byy.ticket.resilience.HttpCallProtection protection;
    public StockAdmissionClient(@Qualifier("inventoryRestClientBuilder") RestClient.Builder builder,InventoryClientProperties properties,
            OrderInventoryCredential credential,@Value("${ticket.stock-admission.enabled:false}") boolean enabled){
        this(builder,properties,credential,enabled,new com.byy.ticket.resilience.HttpResilienceProperties(com.byy.ticket.resilience.HttpResilienceProperties.defaults(),com.byy.ticket.resilience.HttpResilienceProperties.defaults(),com.byy.ticket.resilience.HttpResilienceProperties.defaults()));
    }
    @Autowired
    public StockAdmissionClient(@Qualifier("inventoryRestClientBuilder") RestClient.Builder builder,InventoryClientProperties properties,
            OrderInventoryCredential credential,@Value("${ticket.stock-admission.enabled:false}") boolean enabled,com.byy.ticket.resilience.HttpResilienceProperties settings){
        client=builder.clone().baseUrl("http://"+properties.serviceId()).defaultHeader(OrderInventoryCredential.HEADER,credential.value()).build();this.enabled=enabled;
        protection=new com.byy.ticket.resilience.HttpCallProtection("order-inventory-admission",settings.inventory(),
            error->error instanceof InventoryServiceCallException remote&&remote.getReason()!=Reason.CONFLICT&&remote.isCircuitBreakerFailure(),
            denied->new InventoryServiceCallException(Reason.UNAVAILABLE,"库存准入暂被保护组件拒绝，请保持原请求核对",denied));
    }
    public boolean enabled(){return enabled;}
    public record Capability(int protocolVersion, boolean stockAdmission, boolean selectedSeats) { }
    private volatile long capabilityCheckedAt;
    private volatile Capability capability;

    /** 成功结果仅缓存两秒；失败不沿用旧能力，不自动改变扣量协议。 */
    public void requireCompatible() {
        if (capability != null && System.nanoTime() - capabilityCheckedAt < 2_000_000_000L) return;
        synchronized (this) {
            if (capability != null && System.nanoTime() - capabilityCheckedAt < 2_000_000_000L) return;
            Capability observed;
            try {
                observed = client.get().uri("/internal/inventory/capabilities").retrieve()
                        .body(new ParameterizedTypeReference<Result<Capability>>() { }).data();
            } catch (RuntimeException failure) {
                capability = null;
                throw new InventoryServiceCallException(Reason.UNAVAILABLE, "库存能力探测失败，请稍后使用原编号重试", failure);
            }
            if (observed == null || observed.protocolVersion() != 1 || observed.stockAdmission() != enabled
                    || (enabled && !observed.selectedSeats())) {
                capability = null;
                throw new InventoryServiceCallException(Reason.UNAVAILABLE,
                        "Order与Inventory库存准入配置或协议不一致，请核对ticket.stock-admission.enabled");
            }
            capability = observed;
            capabilityCheckedAt = System.nanoTime();
        }
    }
    /** 新请求采用建议到期时间，重试以库存服务返回的首次到期时间为准。 */
    public Admission hold(StockReservationRequest request){return call(request,"");}
    /** 仅明确没有正式预留时取消；库存服务的墓碑会防止迟到请求重新扣票。 */
    public Admission cancel(StockReservationRequest request){return call(request,"/cancel");}
    public record SeatState(String seatId,int ordinal,String status,long version){}
    public record SeatChanges(java.util.List<SeatState> records,long version,int nextAfter,boolean hasMore){}
    /** 只转发有界的区域视窗，不公开库存服务地址或服务凭证。 */
    public SeatChanges seats(long tier,String region,int rowStart,int rowCount,int columnStart,int columnCount,long since,long through,int after,int limit){
        return protection.execute(()->seatsHttp(tier,region,rowStart,rowCount,columnStart,columnCount,since,through,after,limit));
    }
    private SeatChanges seatsHttp(long tier,String region,int rowStart,int rowCount,int columnStart,int columnCount,long since,long through,int after,int limit){
        if(tier<1||region==null||!region.matches("[A-Za-z0-9_-]{1,20}")||rowStart<1||rowStart>500||rowCount<1||rowCount>20||since<0||through<0||after<0||limit<1||limit>200)throw new IllegalArgumentException("座位视窗参数无效");
        if(columnStart<1||columnStart>500||columnCount<1||columnCount>50)throw new IllegalArgumentException("列视窗范围无效");
        try{return client.get().uri(builder->builder.path("/internal/stocks/{tier}/seats").queryParam("region",region).queryParam("rowStart",rowStart).queryParam("rowCount",rowCount).queryParam("columnStart",columnStart).queryParam("columnCount",columnCount).queryParam("since",since).queryParam("through",through).queryParam("after",after).queryParam("limit",limit).build(tier))
            .exchange((sent,received)->{
                if(!received.getStatusCode().is2xxSuccessful())throw new InventoryServiceCallException(received.getStatusCode().value()==409?Reason.CONFLICT:Reason.UNAVAILABLE,"座位状态暂时无法读取");
                var result=received.bodyTo(new ParameterizedTypeReference<Result<SeatChanges>>(){});var value=result==null?null:result.data();
                if(result==null||!"OK".equals(result.code())||value==null||value.records()==null||value.records().size()>limit||value.version()<since||value.nextAfter()<after)throw new InventoryServiceCallException(Reason.INVALID_RESPONSE,"座位视窗响应不完整");return value;
            });
        }catch(RestClientException failure){throw new InventoryServiceCallException(Reason.UNAVAILABLE,"座位状态读取失败",failure);}
    }
    private Admission call(StockReservationRequest request,String suffix){
        return protection.execute(()->callHttp(request,suffix));
    }
    private Admission callHttp(StockReservationRequest request,String suffix){
        try{return client.post().uri("/internal/stock-admissions"+suffix).contentType(MediaType.APPLICATION_JSON)
            .header(com.byy.ticket.common.trace.TraceIdContext.HTTP_HEADER,com.byy.ticket.common.trace.TraceIdContext.getOrCreate())
            .body(request).exchange((sent,received)->{
                int code=received.getStatusCode().value();
                if(code==409)throw new InventoryServiceCallException(Reason.CONFLICT,"库存准入拒绝，库存不足、原请求已取消或参数冲突");
                if(!received.getStatusCode().is2xxSuccessful())throw new InventoryServiceCallException(Reason.UNAVAILABLE,"库存准入结果暂时未知，请使用同一编号核对");
                var result=received.bodyTo(TYPE);var value=result==null?null:result.data();
                if(result==null||!"OK".equals(result.code())||value==null||!request.orderId().equals(value.orderNo())
                    ||!request.ticketTierId().equals(value.ticketTierId())||!request.sessionId().equals(value.sessionId())||!request.quantity().equals(value.quantity())
                    ||value.expiresAt()==null||value.status()==null||!java.util.Set.of("HELD","APPLIED","CANCELLED","RELEASED").contains(value.status()))
                    throw new InventoryServiceCallException(Reason.INVALID_RESPONSE,"库存准入响应不完整或归属错误");
                return value;
            });
        }catch(ResourceAccessException failure){throw new InventoryServiceCallException(Reason.TIMEOUT,"库存准入结果未知，不能直接回补库存",failure);}
        catch(RestClientException failure){throw new InventoryServiceCallException(Reason.INVALID_RESPONSE,"库存准入响应读取失败",failure);}
        catch(IllegalStateException failure){if(failure.getMessage()!=null&&failure.getMessage().startsWith("No instances available for "))throw new InventoryServiceCallException(Reason.UNAVAILABLE,"库存服务无可用实例",failure);throw failure;}
    }
}
