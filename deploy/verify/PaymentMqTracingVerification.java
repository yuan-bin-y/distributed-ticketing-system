import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** 原 MQ 故障验收加真实 Tempo 核对：重试、重复发布、重启和历史消息不破坏业务幂等。 */
public class PaymentMqTracingVerification extends PaymentMqVerification {
    String scenario="other", currentTrace;
    final Map<String,String> evidence=new LinkedHashMap<>();
    PaymentMqTracingVerification(Path root) throws Exception { super(root); }
    public static void main(String[] args)throws Exception {
        var test=new PaymentMqTracingVerification(Path.of(args[0]).toAbsolutePath());
        try { test.runMq();Files.writeString(test.root.resolve(".local/mq-tracing-verification-result.json"),JSON.writeValueAsString(test.evidence)); }
        finally { test.cleanup(); }
        System.out.println("PASS MQ tracing and faults: "+test.checks+" checks; owned resources cleaned.");
    }
    @Override HttpResponse<String> pay(String number)throws Exception {
        currentTrace=UUID.randomUUID().toString().replace("-","");
        var response=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+paymentPort+"/api/payments/"+number+"/simulate-success"))
                .header("X-Dev-User-Id","1").header("traceparent","00-"+currentTrace+"-1234567890abcdef-01")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==200,"traced payment succeeds");
        check(response.headers().firstValue("X-Trace-Id").orElse("").equals(currentTrace),"payment header matches W3C trace");
        evidence.put(scenario,currentTrace);return response;
    }
    @Override void normalMq()throws Exception {scenario="normal";super.normalMq();verify(currentTrace,1,1,false);}
    @Override void temporaryFailure()throws Exception {scenario="retry";super.temporaryFailure();verify(currentTrace,1,2,true);}
    @Override void lostPublishedProgress()throws Exception {scenario="republish";super.lostPublishedProgress();verify(currentTrace,2,2,false);}
    @Override void stop(Process process)throws Exception {
        // Windows 的强制终止无法保证 SDK flush；先核对重发 Span，再测试业务进程重启。
        if (process==orderProcess && scenario.equals("republish")) verify(currentTrace,2,2,false);
        super.stop(process);
    }
    void verify(String id,int publishers,int consumers,boolean retry)throws Exception {
        var spans=new ArrayList<Map.Entry<String,JsonNode>>();
        String tempo=System.getenv().getOrDefault("TICKET_TEMPO_URL","http://127.0.0.1:3200");
        await(()->{
            var response=HTTP.send(HttpRequest.newBuilder(URI.create(tempo+"/api/traces/"+id))
                    .header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==404)return false;
            if(response.statusCode()!=200)throw new AssertionError("Tempo query status "+response.statusCode());
            spans.clear();collect(JSON.readTree(response.body()),"",spans);
            long publish=spans.stream().filter(e->e.getValue().path("name").asText().equals("payment.succeeded publish")).count();
            long consume=spans.stream().filter(e->e.getValue().path("name").asText().equals("payment.succeeded consume")).count();
            boolean retried=spans.stream().anyMatch(e->e.getValue().path("name").asText().equals("payment.succeeded retry publish"));
            var ids=new HashSet<String>();for(var e:spans)ids.add(normalize(e.getValue().path("spanId").asText()));
            return publish>=publishers&&consume>=consumers&&(!retry||retried)&&spans.stream().allMatch(e->{
                String parent=normalize(e.getValue().path("parentSpanId").asText());return parent.isEmpty()||parent.equals("1234567890abcdef")||ids.contains(parent);});
        },"real MQ trace captures "+scenario);
        var ids=new HashSet<String>();
        for(var entry:spans) {
            check(normalize(entry.getValue().path("traceId").asText()).equals(id),"same original trace after asynchronous retry");
            check(ids.add(normalize(entry.getValue().path("spanId").asText())),"every attempt gets unique Span ID");
        }
        if(retry)check(spans.stream().anyMatch(e->e.getValue().path("status").path("code").asText().equals("STATUS_CODE_ERROR")
                ||e.getValue().path("status").path("code").asText().equals("2")),"temporary failure marks an error Span");
        Files.writeString(root.resolve(".local/mq-trace-"+id+".json"),JSON.writeValueAsString(spans.stream().map(e->Map.of("service",e.getKey(),"span",e.getValue())).toList()));
    }
    String normalize(String v) {return v.isEmpty()||v.matches("[0-9a-fA-F]{16}|[0-9a-fA-F]{32}")?v.toLowerCase(Locale.ROOT):HexFormat.of().formatHex(Base64.getDecoder().decode(v));}
    void collect(JsonNode node,String service,List<Map.Entry<String,JsonNode>> out) {
        if(node.isArray()){for(var child:node)collect(child,service,out);return;}if(!node.isObject())return;
        for(var attr:node.path("resource").path("attributes"))if(attr.path("key").asText().equals("service.name"))service=attr.path("value").path("stringValue").asText();
        if(node.has("spanId")&&node.has("traceId")){out.add(Map.entry(service,node));return;}
        for(var property:node.properties())collect(property.getValue(),service,out);
    }
}
