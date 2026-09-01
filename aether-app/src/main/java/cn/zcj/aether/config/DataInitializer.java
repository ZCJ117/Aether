package cn.zcj.aether.config;

import cn.zcj.aether.infrastructure.persistence.UserRepository;
import cn.zcj.aether.types.enums.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 数据初始化器 — 初始管理员创建（O16: 默认无后门账号）。
 *
 * <p>仅当 {@code aether.security.bootstrap-admin=true} 且环境变量
 * {@code AETHER_ADMIN_PASSWORD} 非空时才创建 admin，密码取自环境变量。
 * 否则 WARN 跳过，不内置默认凭据。</p>
 */
@Slf4j
@Component
public class DataInitializer implements CommandLineRunner {

    /** 用户仓储（可选注入）：无数据源/未装配仓储时跳过初始化。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    /** O16: 是否允许创建初始管理员（默认 false） */
    private final boolean bootstrapAdmin;

    /** O16: 初始管理员密码（来自环境变量 AETHER_ADMIN_PASSWORD，无默认值） */
    private final String adminPassword;

    public DataInitializer(PasswordEncoder passwordEncoder,
            @Value("${aether.security.bootstrap-admin:false}") boolean bootstrapAdmin,
            @Value("${AETHER_ADMIN_PASSWORD:}") String adminPassword) {
        this.passwordEncoder = passwordEncoder;
        this.bootstrapAdmin = bootstrapAdmin;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (!bootstrapAdmin) {
            log.warn("aether.security.bootstrap-admin=false，跳过初始管理员创建");
            return;
        }
        if (userRepository == null) {
            log.info("未装配 UserRepository（无数据源），跳过默认管理员初始化");
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("aether.security.bootstrap-admin=true 但环境变量 AETHER_ADMIN_PASSWORD 未设置，跳过管理员创建");
            return;
        }
        if (userRepository.findByUsername("admin").isEmpty()) {
            UserRepository.UserEntity admin = new UserRepository.UserEntity();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setEmail("admin@aether.local");
            admin.setRole(UserRole.ADMIN.getCode());
            admin.setEnabled(true);
            userRepository.save(admin);
            log.info("============================================");
            log.info("  初始管理员已创建（密码来自 AETHER_ADMIN_PASSWORD 环境变量）");
            log.info("============================================");
        } else {
            log.info("管理员用户已存在，跳过初始化");
        }
    }
}
