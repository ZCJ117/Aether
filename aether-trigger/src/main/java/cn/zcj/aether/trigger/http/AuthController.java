package cn.zcj.aether.trigger.http;

import cn.zcj.aether.api.dto.AuthResponseDTO;
import cn.zcj.aether.api.dto.LoginRequestDTO;
import cn.zcj.aether.api.dto.RegisterRequestDTO;
import cn.zcj.aether.api.dto.TokenRefreshRequestDTO;
import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.domain.agent.service.security.JwtService;
import cn.zcj.aether.infrastructure.persistence.RefreshTokenRepository;
import cn.zcj.aether.infrastructure.persistence.UserRepository;
import cn.zcj.aether.types.enums.ResponseCode;
import cn.zcj.aether.types.enums.UserRole;
import cn.zcj.aether.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * P0: 认证控制器。
 *
 * <p>提供用户注册、登录和 Token 刷新端点。
 * 所有端点均无需认证（在 SecurityConfig 中 permitAll）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    /**
     * 用户注册。
     */
    @PostMapping("/register")
    public Response<AuthResponseDTO> register(@RequestBody RegisterRequestDTO request) {
        if (request.getUsername() == null || request.getUsername().length() < 3
                || request.getUsername().length() > 64) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "用户名长度必须为 3-64 字符");
        }
        if (request.getPassword() == null || request.getPassword().length() < 8
                || request.getPassword().length() > 128) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "密码长度必须为 8-128 字符");
        }
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "用户名已存在");
        }

        UserRepository.UserEntity entity = new UserRepository.UserEntity();
        entity.setUsername(request.getUsername());
        entity.setPassword(passwordEncoder.encode(request.getPassword()));
        entity.setEmail(request.getEmail());
        entity.setRole(UserRole.VIEWER.getCode());
        userRepository.save(entity);

        return buildAuthResponse(entity);
    }

    /**
     * 用户登录。
     */
    @PostMapping("/login")
    public Response<AuthResponseDTO> login(@RequestBody LoginRequestDTO request) {
        UserRepository.UserEntity entity = userRepository
                .findByUsername(request.getUsername())
                .orElseThrow(() -> {
                    log.info("登录失败: 用户不存在 [{}]", request.getUsername());
                    return new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                            "用户名或密码错误");
                });

        if (!passwordEncoder.matches(request.getPassword(), entity.getPassword())) {
            log.info("登录失败: 密码错误 [{}]", request.getUsername());
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "用户名或密码错误");
        }

        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "账户已被禁用");
        }

        log.info("登录成功: username={}", entity.getUsername());
        return buildAuthResponse(entity);
    }

    /**
     * 刷新 Access Token（轮转 Refresh Token）。
     */
    @PostMapping("/refresh")
    public Response<AuthResponseDTO> refresh(@RequestBody TokenRefreshRequestDTO request) {
        RefreshTokenRepository.RefreshTokenEntity storedToken = refreshTokenRepository
                .findByToken(request.getRefreshToken())
                .orElseThrow(() -> new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "无效的刷新令牌"));

        if (Boolean.TRUE.equals(storedToken.getRevoked())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "刷新令牌已被吊销");
        }

        if (Instant.now().isAfter(storedToken.getExpiresAt())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "刷新令牌已过期");
        }

        refreshTokenRepository.revokeByToken(request.getRefreshToken());

        UserRepository.UserEntity entity = userRepository.findById(storedToken.getUserId())
                .orElseThrow(() -> new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "用户不存在"));

        return buildAuthResponse(entity);
    }

    /**
     * 构建认证响应：生成 access + refresh token 并持久化 refresh token。
     */
    private Response<AuthResponseDTO> buildAuthResponse(UserRepository.UserEntity entity) {
        UserRole role;
        try {
            role = UserRole.valueOf(entity.getRole());
        } catch (IllegalArgumentException e) {
            role = UserRole.VIEWER;
        }

        String accessToken = jwtService.generateAccessToken(
                entity.getId(), entity.getUsername(), role);
        String refreshToken = jwtService.generateRefreshToken();

        RefreshTokenRepository.RefreshTokenEntity rtEntity =
                new RefreshTokenRepository.RefreshTokenEntity();
        rtEntity.setUserId(entity.getId());
        rtEntity.setToken(refreshToken);
        rtEntity.setExpiresAt(Instant.now().plusMillis(
                jwtService.getRefreshTokenExpirationMs()));
        refreshTokenRepository.save(rtEntity);

        AuthResponseDTO.UserInfo userInfo = new AuthResponseDTO.UserInfo();
        userInfo.setId(entity.getId());
        userInfo.setUsername(entity.getUsername());
        userInfo.setRole(entity.getRole());

        AuthResponseDTO response = new AuthResponseDTO();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setExpiresIn(jwtService.getAccessTokenExpirationMs() / 1000);

        return Response.<AuthResponseDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(response)
                .build();
    }
}
