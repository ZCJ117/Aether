package cn.zcj.aether.config;

import cn.zcj.aether.infrastructure.persistence.UserRepository;
import cn.zcj.aether.types.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 数据初始化器 — 确保默认管理员用户存在。
 *
 * <p>首次启动时自动创建 admin/admin 管理员账户。
 * 生产环境部署后应立即修改密码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.findByUsername("admin").isEmpty()) {
            UserRepository.UserEntity admin = new UserRepository.UserEntity();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin"));
            admin.setEmail("admin@aether.local");
            admin.setRole(UserRole.ADMIN.getCode());
            admin.setEnabled(true);
            userRepository.save(admin);
            log.info("============================================");
            log.info("  默认管理员已创建: admin / admin");
            log.info("  生产环境请立即修改密码！");
            log.info("============================================");
        } else {
            log.info("管理员用户已存在，跳过初始化");
        }
    }
}
