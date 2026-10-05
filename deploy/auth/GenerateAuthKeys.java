import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Base64;

/** 一次性生成开发 RSA 密钥；已有文件时拒绝覆盖，私钥不输出到终端。 */
public class GenerateAuthKeys {
    /** 参数为目标目录，默认项目根目录下的 .local/auth-keys。 */
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length == 0 ? ".local/auth-keys" : args[0]);
        Files.createDirectories(directory);
        Path privateFile = directory.resolve("private.pem"), publicFile = directory.resolve("public.pem");
        if (Files.exists(privateFile) || Files.exists(publicFile)) {
            throw new IllegalStateException("密钥文件已存在，拒绝覆盖；请保留原密钥或选择新目录");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Files.writeString(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardOpenOption.CREATE_NEW);
        Files.writeString(publicFile, pem("PUBLIC KEY", pair.getPublic().getEncoded()), StandardOpenOption.CREATE_NEW);
        System.out.println("RSA 密钥已生成到 " + directory.toAbsolutePath() + "，请妥善保存私钥。");
    }
    /** 生成 Java/Spring 可读取的 PKCS8/X509 PEM 文本。 */
    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }
}
