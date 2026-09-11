package cn.zcj.aether.config;

import cn.zcj.aether.trigger.http.filter.JwtAuthFilter;
import cn.zcj.aether.trigger.http.handler.JwtAccessDeniedHandler;
import cn.zcj.aether.trigger.http.handler.JwtAuthEntryPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.DispatcherType;

/**
 * Web 层安全配置 —— 采用 fail-closed 的认证/授权默认拒绝策略。
 *
 * <p><b>【架构亮点 · 权限体系 fail-closed】</b><br>
 * 面试举证点：authorizeHttpRequests 以 anyRequest().authenticated() 收底（:75），所有未显式 permitAll 的请求一律需认证；/actuator/** 与 /api/v1/admin/** 强制 ROLE_ADMIN（:71-72），认证失败由 JwtAuthEntryPoint 统一返回 401，未授权访问绝不放行。
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final JwtAuthEntryPoint jwtAuthEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    /**
     * MODE_INHERITABLETHREADLOCAL — 辅助修复 SSE 流式对话的异步分发问题。
     *
     * <p>问题背景：
     * Spring Security 6.x + STATELESS session 下，SecurityContext 默认存于 ThreadLocal。
     * 异步分发（ASYNC dispatch）运行在 Tomcat 线程池复用的线程上（非请求线程的子线程），
     * InheritableThreadLocal 仅在创建新线程时传递值，对线程池复用线程无效。
     * Async 线程上 SecurityContextHolderFilter 从 RequestAttributeSecurityContextRepository
     * 加载上下文时，上下文尚未被初始请求的 filter 链保存 → AuthorizationFilter 发现空上下文
     * → 抛出 AuthorizationDeniedException。此时 SSE 数据已写响应（response committed），
     * ExceptionTranslationFilter 无法写入 403，继而抛出 ServletException。
     *
     * <p>双重修复策略：
     * 1. {@code SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)} —
     *    覆盖线程池复用以外的"真正子线程"场景（如 RxJava 的某些调度器行为）。
     * 2. {@code dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()} —
     *    ASYNC 分发仅在初始 REQUEST 已通过认证的前提下才会发生，无需二次授权检查。
     *    这是主修复手段，直接避免了 AuthorizationFilter 在异步线程上的误判。
     */
    static {
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(jwtAuthEntryPoint)
                .accessDeniedHandler(jwtAccessDeniedHandler))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // 【fail-closed】仅暴露 health；其余 /actuator/** 与 /api/v1/admin/** 强制 ADMIN
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // ASYNC 分发已在 REQUEST 阶段通过认证，无需二次授权检查
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                // 【fail-closed】兜底策略：任何未显式放行的请求一律需认证，拒绝静默放行
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter,
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}
