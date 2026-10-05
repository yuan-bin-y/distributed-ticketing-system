import com.sun.net.httpserver.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 真实Auth/MySQL/Redis与独立网关进程；业务HTTP桩只验证准入及请求头转发，不冒充订单身份接入。 */
public class GatewayAuthVerification extends AuthVerification {
    HttpServer business, jwks;
    final List<Process> gateways=new ArrayList<>();
    final AtomicInteger protectedCalls=new AtomicInteger(), publicCalls=new AtomicInteger(), keyCalls=new AtomicInteger();
    final AtomicBoolean keyAvailable=new AtomicBoolean(true);
    final AtomicReference<String> forwardedBearer=new AtomicReference<>();
    final AtomicReference<String> forwardedDevId=new AtomicReference<>(), forwardedUserId=new AtomicReference<>();
    int authPort, gatewayPort;
    Path directory;
    GatewayAuthVerification(Path root){super(root);}
    /** 测试结果不输出完整凭证；结束关闭本次网关/HTTP桩并清理隔离数据。 */
    public static void main(String[] args) throws Exception {
        var test=new GatewayAuthVerification(Path.of(args[0]).toAbsolutePath());
        try{test.run();}finally{test.cleanup();}
        System.out.println("PASS gateway auth: "+test.checks+" checks; isolated data and owned processes cleaned.");
    }
    @Override
    void run() throws Exception {
        admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",System.getenv("LOCAL_MYSQL_USERNAME"),System.getenv("LOCAL_MYSQL_PASSWORD"));
        try(var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+schema);created=true;}
        directory=root.resolve(".local/gateway-auth-verification/"+suffix);Files.createDirectories(directory);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();
        Files.writeString(directory.resolve("private.pem"),pem("PRIVATE KEY",keys.getPrivate().getEncoded()));
        Files.writeString(directory.resolve("public.pem"),pem("PUBLIC KEY",keys.getPublic().getEncoded()));
        first=start(directory);authPort=port(first);
        business=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);business.setExecutor(pool);
        business.createContext("/",exchange->{
            boolean event=exchange.getRequestURI().getPath().startsWith("/api/events");
            if(event)publicCalls.incrementAndGet();else protectedCalls.incrementAndGet();
            forwardedBearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            forwardedDevId.set(exchange.getRequestHeaders().getFirst("X-Dev-User-Id"));
            forwardedUserId.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
            byte[] body="{\"code\":\"OK\",\"message\":\"stub\",\"data\":{\"forwarded\":true}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });business.start();
        byte[] document=JSON.writeValueAsBytes(new JWKSet(new RSAKey.Builder((RSAPublicKey)keys.getPublic()).keyID("ticket-auth-v1").build()).toJSONObject());
        jwks=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);jwks.setExecutor(pool);
        jwks.createContext("/jwks",exchange->{keyCalls.incrementAndGet();byte[] body=keyAvailable.get()?document:"{}".getBytes();
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(keyAvailable.get()?200:503,body.length);
            exchange.getResponseBody().write(body);exchange.close();});jwks.start();
        gatewayPort=startGateway();
        status(call(gatewayPort,"GET","/api/events/ping",null,null),200);
        check(publicCalls.get()>0,"anonymous event forwarded");
        status(call(gatewayPort,"POST","/api/events/test",Map.of(),null),401);
        var unauthorized=call(gatewayPort,"GET","/api/orders/test",null,null);status(unauthorized,401);
        check(JSON.readTree(unauthorized.body()).path("code").asText().equals("UNAUTHORIZED"),"uniform unauthenticated result");
        check(JSON.readTree(unauthorized.body()).path("traceId").asText().equals(unauthorized.headers().firstValue("X-Trace-Id").orElse("")),"trace in error body/header");
        check(protectedCalls.get()==0,"anonymous never reached business");
        status(call(gatewayPort,"POST","/api/auth/register",Map.of("username","buyer","password","Testing_123","nickname","购票用户"),null),200);
        var tokens=login(gatewayPort);String access=tokens.path("accessToken").asText(),refresh=tokens.path("refreshToken").asText();
        var success=spoofRequest(access);status(success,200);
        check(forwardedBearer.get().equals("Bearer "+access),"bearer forwarded unchanged");
        check(forwardedDevId.get()==null&&forwardedUserId.get()==null,"untrusted identity headers removed");
        check(success.headers().firstValue("Set-Cookie").isEmpty(),"no gateway login session cookie");
        status(call(gatewayPort,"GET","/api/payments/test",null,access),200);
        int cachedCalls=keyCalls.get();
        for(int i=0;i<3;i++)status(call(gatewayPort,"GET","/api/orders/test",null,access),200);
        check(keyCalls.get()==cachedCalls,"public key reused from cache");
        keyAvailable.set(false);
        status(call(gatewayPort,"GET","/api/orders/test",null,access),200);
        keyAvailable.set(true);
        int before=protectedCalls.get();
        status(call(gatewayPort,"GET","/api/orders/test",null,refresh),401);
        status(call(gatewayPort,"GET","/api/orders/test",null,access.substring(0,access.lastIndexOf('.')+1)+"AAAA"),401);
        var claims=JWTParser.parse(access).getJWTClaimsSet();
        status(call(gatewayPort,"GET","/api/orders/test",null,sign(new JWTClaimsSet.Builder(claims).issuer("wrong").build())),401);
        status(call(gatewayPort,"GET","/api/orders/test",null,sign(new JWTClaimsSet.Builder(claims).audience("wrong").build())),401);
        status(call(gatewayPort,"GET","/api/orders/test",null,sign(new JWTClaimsSet.Builder(claims).claim("sid","invalid").build())),401);
        status(call(gatewayPort,"GET","/api/orders/test",null,sign(new JWTClaimsSet.Builder(claims).subject("9999999999999999999").build())),401);
        status(call(gatewayPort,"GET","/api/orders/test",null,sign(new JWTClaimsSet.Builder(claims).expirationTime(null).build())),401);
        status(call(gatewayPort,"GET","/api/orders/test",null,sign(new JWTClaimsSet.Builder(claims)
                .issueTime(java.util.Date.from(Instant.now().minusSeconds(120))).expirationTime(java.util.Date.from(Instant.now().minusSeconds(1))).build())),401);
        status(call(gatewayPort,"GET","/internal/payments/test",null,access),403);
        check(protectedCalls.get()==before,"invalid credentials/internal path never forwarded");
        // 标准刷新仍允许匿名路由，由Auth原子核验Refresh；旧Refresh不能复用。
        var rotated=call(gatewayPort,"POST","/api/auth/refresh",Map.of("refreshToken",refresh),null);status(rotated,200);
        var newTokens=JSON.readTree(rotated.body()).path("data");
        status(call(gatewayPort,"POST","/api/auth/refresh",Map.of("refreshToken",refresh),null),401);
        status(call(gatewayPort,"GET","/api/orders/test",null,newTokens.path("accessToken").asText()),200);
        status(call(gatewayPort,"POST","/api/auth/logout",Map.of(),newTokens.path("accessToken").asText()),200);
        status(call(gatewayPort,"GET","/api/orders/test",null,access),401);
        status(call(gatewayPort,"GET","/api/orders/test",null,newTokens.path("accessToken").asText()),401);
        var again=login(gatewayPort);
        String valid=again.path("accessToken").asText();
        // 修改本次会话的userId验证归属核对，不修改其他用户/业务会话。
        var healthyJwt=first.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(valid);
        var redis=first.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        String sessionKey=prefix+"{"+healthyJwt.getSubject()+"}:session:"+healthyJwt.getClaimAsString("sid");
        redis.opsForHash().put(sessionKey,"userId","other-user");
        status(call(gatewayPort,"GET","/api/orders/test",null,valid),401);
        redis.opsForHash().put(sessionKey,"userId",healthyJwt.getSubject());
        int deadRedis=freePort();
        int down=startGateway("--spring.data.redis.port="+deadRedis,"--spring.data.redis.connect-timeout=200ms","--spring.data.redis.timeout=200ms");
        before=protectedCalls.get();
        var storageError=call(down,"GET","/api/orders/test",null,valid);status(storageError,503);
        check(JSON.readTree(storageError.body()).path("code").asText().equals("SERVICE_BUSY"),"Redis failure explicit503");
        check(protectedCalls.get()==before,"Redis failure no forwarding");
        status(call(down,"GET","/api/events/ping",null,null),200);
        // 冷启动时公钥源故障也必须拒绝，不转发；缓存实例仍可验证原签名。
        keyAvailable.set(false);int cold=startGateway();before=protectedCalls.get();
        check(call(cold,"GET","/api/orders/test",null,valid).statusCode()!=200,"cold unavailable JWKS denied");
        check(protectedCalls.get()==before,"JWKS failure no forwarding");keyAvailable.set(true);
        status(call(cold,"GET","/api/orders/test",null,valid),200);
    }
    /** 独立网关进程使用固定测试路由和公钥HTTP桩；不借助开发身份头。 */
    int startGateway(String...extra) throws Exception {
        int port=freePort();
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-jar",
                root.resolve("ticket-gateway/target/ticket-gateway-1.0-SNAPSHOT.jar").toString(),"--server.port="+port,
                "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.discovery.enabled=false",
                "--ticket.security.redis-prefix="+prefix,"--ticket.security.jwk-set-uri=http://127.0.0.1:"+jwks.getAddress().getPort()+"/jwks",
                "--spring.cloud.gateway.server.webflux.routes[0].uri=http://127.0.0.1:"+business.getAddress().getPort(),
                "--spring.cloud.gateway.server.webflux.routes[1].uri=http://127.0.0.1:"+business.getAddress().getPort(),
                "--spring.cloud.gateway.server.webflux.routes[2].uri=http://127.0.0.1:"+business.getAddress().getPort(),
                "--spring.cloud.gateway.server.webflux.routes[3].uri=http://127.0.0.1:"+authPort,"--logging.level.root=ERROR"));
        command.addAll(List.of(extra));
        // 配置列表按整体覆盖，需要完整声明测试路由，而不是只覆盖uri。
        String[] ids={"ticket-event-service","ticket-order-service","ticket-payment-service","ticket-auth-service"};
        String[] paths={"/api/events/**","/api/orders/**","/api/payments/**","/api/auth/**"};
        for(int i=0;i<ids.length;i++){
            command.add("--spring.cloud.gateway.server.webflux.routes["+i+"].id="+ids[i]);
            command.add("--spring.cloud.gateway.server.webflux.routes["+i+"].predicates[0]=Path="+paths[i]);
        }
        var process=new ProcessBuilder(command).directory(root.toFile()).redirectOutput(directory.resolve("gateway-"+port+".out.log").toFile())
                .redirectError(directory.resolve("gateway-"+port+".err.log").toFile()).start();gateways.add(process);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
        while(System.nanoTime()<deadline){
            if(!process.isAlive())throw new IllegalStateException("Gateway failed; inspect isolated logs");
            try{if(call(port,"GET","/api/auth/ping",null,null).statusCode()==200)return port;}catch(Exception ignored){}
            Thread.sleep(200);
        }
        throw new IllegalStateException("Gateway startup timed out; inspect isolated logs");
    }
    /** 恶意身份头不能替换有效Token的身份。 */
    HttpResponse<String> spoofRequest(String token)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+gatewayPort+"/api/orders/test"))
                .header("Authorization","Bearer "+token).header("X-Dev-User-Id","999").header("X-User-Id","999")
                .timeout(Duration.ofSeconds(10)).GET().build();
        return HTTP.send(request,HttpResponse.BodyHandlers.ofString());
    }
    int freePort()throws Exception{try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}}
    @Override
    void cleanup()throws Exception{
        for(var process:gateways){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}
        if(business!=null)business.stop(0);if(jwks!=null)jwks.stop(0);super.cleanup();
    }
}
