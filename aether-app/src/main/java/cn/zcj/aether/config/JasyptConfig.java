package cn.zcj.aether.config;

import com.ulisesbocchio.jasyptspringboot.annotation.EnableEncryptableProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * P0: Jasypt 配置加密。
 *
 * <p>启用 jasypt-spring-boot-starter 的自动属性加密。
 * 配置文件中的 ENC(...) 值会被自动解密。
 *
 * <p>主密码通过启动参数传入（不写入任何文件）：
 * {@code --jasypt.encryptor.password=<master-password>}
 */
@Slf4j
@Configuration
@EnableEncryptableProperties
public class JasyptConfig {
    // jasypt-spring-boot-starter auto-configures the encryptor.
}
