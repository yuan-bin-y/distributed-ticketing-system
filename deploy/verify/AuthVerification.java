import com.byy.ticket.auth.TicketAuthApplication;
import com.byy.ticket.auth.token.RedisSessionService;
import com.byy.ticket.auth.config.AuthProperties;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 真实 MySQL/Redis + 两个 Auth 实例验证；只创建和清理本次随机数据库、Redis前缀。 */
public class AuthVerification {
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final JsonMapper JSON = JsonMapper.builder().build();
    final String suffix = UUID.randomUUID().toString().replace("-", "");
    final String schema = "ticket_auth_verify_" + suffix;
    final String prefix = "ticket:auth:verify:" + suffix + ":";
    final Path root;
    Connection admin;
    ConfigurableApplicationContext first, second;
    ConfigurableApplicationContext unavailable;
    ExecutorService pool = Executors.newFixedThreadPool(12);
    KeyPair keys;
    int checks;
    boolean created;
    AuthVerification(Path root) { this.root = root; }
    /** 启动验证，失败和成功都清理自己的会话和数据库，不 FLUSHDB。 */
    public static void main(String[] args) throws Exception {
        var test = new AuthVerification(Path.of(args[0]).toAbsolutePath());
        try { test.run(); }
        finally { test.cleanup(); }
        System.out.println("PASS Auth: " + test.checks + " checks; isolated database and sessions cleaned.");
    }
    /** 验证凭证类型、签名、会话、并发竞争、重启与用户禁用。 */
    void run() throws Exception {
        String username = System.getenv("LOCAL_MYSQL_USERNAME"), password = System.getenv("LOCAL_MYSQL_PASSWORD");
        if (username == null || password == null) throw new IllegalStateException("MySQL environment is missing");
        admin = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/", username, password);
        try (var statement = admin.createStatement()) { statement.execute("CREATE DATABASE " + schema); created = true; }
        Path directory = root.resolve(".local/auth-verification/" + suffix);
        Files.createDirectories(directory);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
        Files.writeString(directory.resolve("private.pem"), pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        Files.writeString(directory.resolve("public.pem"), pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        first = start(directory); second = start(directory);
        int port = port(first), other = port(second);
        status(call(port,"POST","/api/auth/register",Map.of("username","buyer","password","Testing_123","nickname","购票用户"),null),200);
        try(var statement = admin.createStatement(); var result = statement.executeQuery("SELECT password_hash FROM " + schema + ".t_user WHERE username='buyer'")) {
            result.next(); check(result.getString(1).startsWith("$2") && !result.getString(1).equals("Testing_123"),"BCrypt stored");
        }
        var jwks = call(port,"GET","/.well-known/jwks.json",null,null);
        status(jwks,200); check(!jwks.body().contains("\"d\"") && !jwks.body().contains("PRIVATE"),"no private key exposed");
        status(call(port,"POST","/api/auth/register",Map.of("username","BUYER","password","Testing_123","nickname","重复"),null),409);
        status(call(port,"POST","/api/auth/register",Map.of("username","b","password","short","nickname",""),null),400);
        status(call(port,"POST","/api/auth/register",Map.of("username","unicode","password","中".repeat(25),"nickname","测试"),null),400);
        status(call(port,"POST","/api/auth/login",Map.of("username","buyer","password","incorrect"),null),401);
        status(call(port,"POST","/api/auth/login",Map.of("username","unknown","password","incorrect"),null),401);
        var login = login(port);
        String access=login.path("accessToken").asText(), refresh=login.path("refreshToken").asText();
        check(first.getBean(JwtDecoder.class).decode(access).getSubject().equals("1"),"numeric user subject");
        check(second.getBean(JwtDecoder.class).decode(access).getSubject().equals("1"),"second instance accepts signature/session");
        check(!second.getBean(JwtDecoder.class).decode(access).getClaimAsString("sid").isBlank(),"session in token");
        check(call(port,"GET","/api/auth/ping",null,null).headers().firstValue("X-Trace-Id").isPresent(),"trace response header");
        status(call(port,"POST","/api/auth/logout",Map.of(),null),401);
        status(call(port,"POST","/api/auth/logout",Map.of(),refresh),401);
        status(call(port,"POST","/api/auth/refresh",Map.of("refreshToken",access),null),401);
        String tampered=access.substring(0,access.lastIndexOf('.')+1)+"AAAA";
        status(call(port,"POST","/api/auth/logout",Map.of(),tampered),401);
        var claims = JWTParser.parse(access).getJWTClaimsSet();
        var signedClaims = new JWTClaimsSet.Builder(claims).issuer("wrong-issuer").build();
        status(call(port,"POST","/api/auth/logout",Map.of(),sign(signedClaims)),401);
        status(call(port,"POST","/api/auth/logout",Map.of(),sign(new JWTClaimsSet.Builder(claims).audience("wrong-audience").build())),401);
        status(call(port,"POST","/api/auth/logout",Map.of(),sign(new JWTClaimsSet.Builder(claims)
                .issueTime(java.util.Date.from(Instant.now().minusSeconds(120))).expirationTime(java.util.Date.from(Instant.now().minusSeconds(1))).build())),401);
        List<Future<HttpResponse<String>>> futures=new ArrayList<>();
        var gate = new CountDownLatch(1);
        for(int i=0;i<12;i++) { final int p=i%2==0?port:other; futures.add(pool.submit(()->{gate.await();return call(p,"POST","/api/auth/refresh",Map.of("refreshToken",refresh),null);})); }
        gate.countDown(); int winners=0; JsonNode rotated=null;
        for(var future:futures) { var response=future.get(20,TimeUnit.SECONDS); if(response.statusCode()==200){winners++;rotated=JSON.readTree(response.body()).path("data");}else{status(response,401);} }
        check(winners==1,"refresh single winner across instances");
        status(call(port,"POST","/api/auth/refresh",Map.of("refreshToken",refresh),null),401);
        // 刷新不撤销旧 Access；两者仍属于同一会话，退出时一起失效。
        check(first.getBean(JwtDecoder.class).decode(access)!=null,"old access remains until expiry/logout");
        first.close(); first=start(directory); port=port(first);
        check(first.getBean(JwtDecoder.class).decode(rotated.path("accessToken").asText())!=null,"restart keeps key and session");
        status(call(port,"POST","/api/auth/logout",Map.of(),rotated.path("accessToken").asText()),200);
        status(call(other,"POST","/api/auth/logout",Map.of(),access),401);
        status(call(other,"POST","/api/auth/refresh",Map.of("refreshToken",rotated.path("refreshToken").asText()),null),401);
        var independent=login(other);
        check(first.getBean(JwtDecoder.class).decode(independent.path("accessToken").asText())!=null,"new login independent session");
        List<Future<HttpResponse<String>>> registrations=new ArrayList<>();
        final int activePort=port;
        for(int i=0;i<8;i++) registrations.add(pool.submit(()->call(activePort,"POST","/api/auth/register",Map.of("username","racer","password","Testing_123","nickname","并发用户"),null)));
        int registered=0; for(var f:registrations){var r=f.get(); if(r.statusCode()==200)registered++;else status(r,409);}
        check(registered==1,"unique concurrent registration");
        try(var statement=admin.createStatement()){statement.executeUpdate("UPDATE "+schema+".t_user SET status='DISABLED' WHERE username='buyer'");}
        status(call(port,"POST","/api/auth/login",Map.of("username","buyer","password","Testing_123"),null),401);
        status(call(port,"POST","/api/auth/refresh",Map.of("refreshToken",independent.path("refreshToken").asText()),null),401);
        // 模拟会话 TTL 到期，只修改本次测试拥有的单个键。
        Jwt jwt=first.getBean(JwtDecoder.class).decode(independent.path("accessToken").asText());
        var redis=first.getBean(StringRedisTemplate.class);
        redis.expire(prefix+"{"+jwt.getSubject()+"}:session:"+jwt.getClaimAsString("sid"),Duration.ofMillis(1));
        Thread.sleep(20);
        status(call(port,"POST","/api/auth/logout",Map.of(),independent.path("accessToken").asText()),401);
        // 已删除会话的刷新不得重新创建会话。
        check(!first.getBean(RedisSessionService.class).active(jwt.getSubject(),jwt.getClaimAsString("sid")),"session expired");
        // 关闭且无人监听的端口模拟 Redis 不可达，不停止用户正在运行的 Redis。
        try(var statement=admin.createStatement()){statement.executeUpdate("UPDATE "+schema+".t_user SET status='ACTIVE' WHERE username='buyer'");}
        var healthy=login(port);
        int deadPort; try(var socket=new java.net.ServerSocket(0)){deadPort=socket.getLocalPort();}
        unavailable=start(directory,"--spring.data.redis.port="+deadPort,"--spring.data.redis.connect-timeout=200ms","--spring.data.redis.timeout=200ms");
        int down=port(unavailable);
        status(call(down,"POST","/api/auth/login",Map.of("username","buyer","password","Testing_123"),null),503);
        status(call(down,"POST","/api/auth/refresh",Map.of("refreshToken",healthy.path("refreshToken").asText()),null),503);
        status(call(down,"POST","/api/auth/logout",Map.of(),healthy.path("accessToken").asText()),503);
        check(first.getBean(JwtDecoder.class).decode(healthy.path("accessToken").asText())!=null,"failed logout did not report success");
        try(var statement=admin.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM "+schema+".flyway_schema_history WHERE success=1")){
            result.next();check(result.getInt(1)==1,"Flyway migration applied once");
        }
    }
    /** 两实例使用相同密钥及Redis前缀；关闭Nacos，仅验证认证业务。 */
    ConfigurableApplicationContext start(Path directory, String... extra) {
        var arguments=new ArrayList<>(List.of(
                "--server.port=0", "--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.discovery.enabled=false",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/"+schema+"?characterEncoding=UTF-8",
                "--ticket.auth.private-key-path="+directory.resolve("private.pem"),
                "--ticket.auth.public-key-path="+directory.resolve("public.pem"), "--ticket.auth.redis-prefix="+prefix,
                "--logging.level.root=ERROR"));
        arguments.addAll(List.of(extra));
        return new SpringApplicationBuilder(TicketAuthApplication.class).run(arguments.toArray(String[]::new));
    }
    /** 真实 HTTP 登录，测试日志不输出返回凭证。 */
    JsonNode login(int port) throws Exception {
        var response=call(port,"POST","/api/auth/login",Map.of("username","BUYER","password","Testing_123"),null);
        status(response,200);return JSON.readTree(response.body()).path("data");
    }
    /** 可控制Bearer及正文的HTTP请求，失败提示只包含状态。 */
    HttpResponse<String> call(int port,String method,String path,Object body,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    /** 使用测试私钥构造签名正确但声明错误的JWT。 */
    String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("ticket-auth-v1").build(),claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey)keys.getPrivate()));return jwt.serialize();
    }
    int port(ConfigurableApplicationContext ctx){return ((WebServerApplicationContext)ctx).getWebServer().getPort();}
    void status(HttpResponse<String> response,int expected){check(response.statusCode()==expected,"HTTP expected "+expected+", received "+response.statusCode());}
    void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;}
    String pem(String label,byte[] bytes){return "-----BEGIN "+label+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+label+"-----\n";}
    /** 先删除本次独有前缀，再关闭连接、实例，最后只删除匹配随机名称的库。 */
    void cleanup() throws Exception {
        pool.shutdownNow();
        try {
            var ctx=first!=null&&first.isActive()?first:second;
            if(ctx!=null&&ctx.isActive()) {
                var redis=ctx.getBean(StringRedisTemplate.class);
                var keys=redis.keys(prefix+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
            }
        } finally {
            if(first!=null)first.close();if(second!=null)second.close();if(unavailable!=null)unavailable.close();
            if(admin!=null){try {
                if(created&&schema.matches("ticket_auth_verify_[0-9a-f]{32}"))try(var s=admin.createStatement()){s.execute("DROP DATABASE "+schema);}
            }finally{admin.close();}}
        }
    }
}
