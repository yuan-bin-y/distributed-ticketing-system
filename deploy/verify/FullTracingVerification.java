import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** 六个真实服务、真实 RabbitMQ、真实 Tempo；核对 HTTP、Outbox、消费和后台履约父子关系。 */
public class FullTracingVerification extends TracingVerification {
    final Map<String,String> phases = new LinkedHashMap<>();
    final String mqPrefix = "ticket_trace_verify_" + suffix;
    FullTracingVerification(Path root) { super(root); }
    public static void main(String[] args) throws Exception {
        var test = new FullTracingVerification(Path.of(args[0]).toAbsolutePath());
        try { test.run(); } finally { test.cleanup(); }
        System.out.println("PASS full tracing: " + test.checks + " checks; owned resources cleaned.");
    }
    @Override void launch(String module,int port,String database,List<String> extra) throws Exception {
        var args = new ArrayList<>(extra);
        if(module.contains("order") || module.contains("payment")) args.addAll(List.of("--ticket.mq.enabled=true",
                "--ticket.mq.prefix=" + mqPrefix, "--ticket.mq.outbox-delay-ms=150",
                "--ticket.mq.retry-delays-ms=300,600,1200"));
        super.launch(module,port,database,args);
    }
    /** 每个用户 HTTP 动作独立 Trace；只保存编号，不保存登录凭证。 */
    @Override HttpResponse<String> call(int port,String method,String path,Object body,String token) throws Exception {
        String id=UUID.randomUUID().toString().replace("-","");
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/json").header("traceparent","00-"+id+"-1234567890abcdef-01")
                .header("tracestate","ticket=verification");
        if(token!=null) request.header("Authorization","Bearer "+token);
        var response=HTTP.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():
                HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        check(response.headers().firstValue("X-Trace-Id").orElse("").equals(id),"HTTP response preserves standard context");
        if(port==gatewayPort && method.equals("POST") && response.statusCode()<300) {
            if(path.equals("/api/orders")) phases.put("createOrder",id);
            if(path.endsWith("/payments")) phases.put("createPayment",id);
            if(path.endsWith("/simulate-success")) phases.put("paymentSucceeded",id);
            if(path.equals("/api/auth/login")) phases.put("login",id);
        }
        // 首个 LOST 请求后夹具立刻杀进程，尚未批量导出的 Span 可能丢失；选稳定实例的显式准备。
        if(port==eventPort && path.endsWith("/prepare") && mode.get().equals("PASS") && response.statusCode()==200)
            phases.put("initializeStock",id);
        return response;
    }
    @Override void run() throws Exception {
        super.run();
        status(call(gatewayPort,"POST","/api/auth/login",Map.of("username","buyer","password","Testing_123"),null),200);
        Files.writeString(root.resolve(".local/full-tracing-verification-result.json"),JSON.writeValueAsString(phases));
        verifyFull(phases.get("login"),Set.of("ticket-gateway","ticket-auth-service"),Set.of());
        verifyFull(phases.get("initializeStock"),Set.of("ticket-event-service","ticket-inventory-service"),Set.of());
        verifyFull(phases.get("createOrder"),Set.of("ticket-gateway","ticket-order-service","ticket-event-service","ticket-inventory-service"),Set.of("order.workflow stock_pending"));
        verifyFull(phases.get("createPayment"),Set.of("ticket-gateway","ticket-order-service","ticket-payment-service"),Set.of());
        var succeeded=verifyFull(phases.get("paymentSucceeded"),Set.of("ticket-gateway","ticket-payment-service","ticket-order-service","ticket-inventory-service"),
                Set.of("payment.succeeded publish","payment.succeeded consume","order.workflow payment_confirming","order.workflow paid"));
        var producer=succeeded.stream().filter(e->e.getValue().path("name").asText().equals("payment.succeeded publish")).findFirst().orElseThrow().getValue();
        var consumer=succeeded.stream().filter(e->e.getValue().path("name").asText().equals("payment.succeeded consume")).findFirst().orElseThrow().getValue();
        check(normalize(consumer.path("parentSpanId").asText()).equals(normalize(producer.path("spanId").asText())),"MQ consumer parent is the actual publish Span");
        check(producer.path("kind").asText().equals("SPAN_KIND_PRODUCER")||producer.path("kind").asText().equals("4"),"producer kind");
        check(consumer.path("kind").asText().equals("SPAN_KIND_CONSUMER")||consumer.path("kind").asText().equals("5"),"consumer kind");
        try(var sql=admin.createStatement();var rows=sql.executeQuery("SELECT trace_parent,trace_state FROM "+paymentSchema+".t_outbox_event")) {
            check(rows.next()&&rows.getString(1).contains(phases.get("paymentSucceeded")),"Outbox persists original payment parent");
            check("ticket=verification".equals(rows.getString(2)),"Outbox preserves tracestate");
        }
        try(var sql=admin.createStatement();var rows=sql.executeQuery("SELECT trace_parent FROM "+orderSchema+".t_order WHERE status='COMPLETED'")) {
            check(rows.next()&&rows.getString(1).contains(phases.get("paymentSucceeded")),"receipt atomically persists MQ context for recovery");
        }
        Files.writeString(root.resolve(".local/full-tracing-verification-result.json"),JSON.writeValueAsString(phases));
        System.out.println("TRACE full payment="+phases.get("paymentSucceeded"));
    }
    List<Map.Entry<String,JsonNode>> verifyFull(String id,Set<String> services,Set<String> names) throws Exception {
        var spans=new ArrayList<Map.Entry<String,JsonNode>>();
        await(()->{
            var response=HTTP.send(HttpRequest.newBuilder(URI.create(tempo+"/api/traces/"+id))
                    .header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==404)return false;
            check(response.statusCode()==200,"Tempo full trace query");
            spans.clear();collect(JSON.readTree(response.body()),"",spans);
            var actualServices=new HashSet<String>();var actualNames=new HashSet<String>();
            var ids=new HashSet<String>();
            for(var entry:spans) { actualServices.add(entry.getKey());actualNames.add(entry.getValue().path("name").asText());ids.add(normalize(entry.getValue().path("spanId").asText())); }
            if(!actualServices.containsAll(services)||!actualNames.containsAll(names))return false;
            // 等待各批次父 Span 到齐；测试给定的外部父 ID 不在服务内导出。
            return spans.stream().allMatch(e->{String parent=normalize(e.getValue().path("parentSpanId").asText());
                return parent.isEmpty()||parent.equals("1234567890abcdef")||ids.contains(parent);});
        },"complete exported chain: "+services);
        for(var entry:spans)check(normalize(entry.getValue().path("traceId").asText()).equals(id),"one trace across asynchronous boundaries");
        Files.writeString(root.resolve(".local/full-trace-"+id+".json"),JSON.writeValueAsString(spans.stream().map(e->Map.of("service",e.getKey(),"span",e.getValue())).toList()));
        return spans;
    }
    @Override void cleanup() throws Exception {
        try { super.cleanup(); } finally {
            // 清理本次独有队列和交换机，不停 Broker、不操作用户前缀。
            var factory=new com.rabbitmq.client.ConnectionFactory();
            factory.setHost(System.getenv().getOrDefault("LOCAL_RABBITMQ_HOST","127.0.0.1"));
            factory.setPort(Integer.parseInt(System.getenv().getOrDefault("LOCAL_RABBITMQ_PORT","5679")));
            factory.setUsername(System.getenv().getOrDefault("LOCAL_RABBITMQ_USERNAME","guest"));
            factory.setPassword(System.getenv().getOrDefault("LOCAL_RABBITMQ_PASSWORD","guest"));
            factory.setVirtualHost(System.getenv().getOrDefault("LOCAL_RABBITMQ_VHOST","/"));
            try(var broker=factory.newConnection();var channel=broker.createChannel()) {
                for(String queue:List.of(".order.payment-succeeded",".order.payment-dead",".order.payment-retry-1",".order.payment-retry-2",".order.payment-retry-3"))
                    channel.queueDelete(mqPrefix+queue);
                channel.exchangeDelete(mqPrefix+".payment.exchange");
            }
        }
    }
}
