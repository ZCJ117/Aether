package cn.zcj.aether.api.dto;

import lombok.Data;

/**
 * P0: Token 刷新请求 DTO。
 */
@Data
public class TokenRefreshRequestDTO {
    /** 刷新令牌 */
    private String refreshToken;
}
