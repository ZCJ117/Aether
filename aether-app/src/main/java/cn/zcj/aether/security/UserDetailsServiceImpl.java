package cn.zcj.aether.security;

import cn.zcj.aether.infrastructure.persistence.UserRepository;
import cn.zcj.aether.types.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserRepository.UserEntity entity = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.info("用户不存在: username={}", username);
                    return new UsernameNotFoundException("Invalid credentials");
                });

        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            log.info("用户已禁用: username={}", username);
            throw new UsernameNotFoundException("User is disabled");
        }

        UserRole role;
        try {
            role = UserRole.valueOf(
                    entity.getRole() != null ? entity.getRole() : "VIEWER");
        } catch (IllegalArgumentException e) {
            log.warn("未知角色值 [{}]，回退为 VIEWER", entity.getRole());
            role = UserRole.VIEWER;
        }

        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(role.toSecurityRole()));

        return new User(entity.getUsername(), entity.getPassword(), authorities);
    }
}
