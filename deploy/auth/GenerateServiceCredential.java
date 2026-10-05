import java.nio.file.*;
import java.security.SecureRandom;
import java.util.HexFormat;

/** 一次性生成本地服务调用的随机凭证；不同方向指定不同文件，不打印内容、不覆盖旧文件。 */
public class GenerateServiceCredential {
    /** 可指定输出文件，默认保存项目忽略目录。 */
    public static void main(String[] args)throws Exception{
        Path file=Path.of(args.length==0?".local/service-credentials/payment-order.token":args[0]);
        Files.createDirectories(file.toAbsolutePath().getParent());
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        Files.writeString(file,HexFormat.of().formatHex(bytes),StandardOpenOption.CREATE_NEW);
        System.out.println("服务凭证已生成到 "+file.toAbsolutePath());
    }
}
