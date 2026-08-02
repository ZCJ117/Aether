package cn.zcj.aether.api.dto;

import lombok.Data;

/**
 * P0: 认证响应 DTO。
 */
@Data
public class AuthResponseDTO {
    /** JWT access token */
    private String accessToken;
    /** JWT refresh token（UUID） */
    private String refreshToken;
    /** Token 类型，固定为 "Bearer" */
    private String tokenType = "Bearer";
    /** Access token 过期时间（秒） */
    private long expiresIn;
    /** 用户基本信息 */
    private UserInfo user;

    @Data
    public static class UserInfo {
        private Long id;
        private String username;
        private String role;
    }
}
