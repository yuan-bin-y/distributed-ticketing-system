package com.byy.ticket.auth.config;
import com.byy.ticket.auth.mapper.UserMapper;
import com.byy.ticket.auth.model.TicketUser;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
/** 可选首次管理员初始化；不提供公开提权接口，不覆盖已有普通用户或密码。 */
@Configuration
public class AdminBootstrap {
    @Bean
    ApplicationRunner bootstrapAdmin(UserMapper users,PasswordEncoder passwords,
            @Value("${AUTH_BOOTSTRAP_ADMIN_USERNAME:}") String username,@Value("${AUTH_BOOTSTRAP_ADMIN_PASSWORD:}") String password){
        return args->{
            if(username.isBlank()&&password.isBlank())return;
            String account=username.toLowerCase(Locale.ROOT);
            if(!account.matches("[a-z0-9_]{3,32}")||password.length()<8||password.length()>64||password.getBytes(StandardCharsets.UTF_8).length>72)
                throw new IllegalArgumentException("管理员初始化账号或密码不符合规则");
            var existing=users.findByUsername(account);
            if(existing==null){
                var user=new TicketUser();user.setUsername(account);user.setNickname("管理员");user.setRole("ADMIN");
                user.setStatus("ACTIVE");user.setPasswordHash(passwords.encode(password));
                try{users.insert(user);return;}catch(DuplicateKeyException race){existing=users.findByUsername(account);}
            }
            if(existing==null||!"ADMIN".equals(existing.getRole())||!"ACTIVE".equals(existing.getStatus())||!passwords.matches(password,existing.getPasswordHash()))
                throw new IllegalStateException("管理员初始化与已有账号冲突，请核对配置");
        };
    }
}
