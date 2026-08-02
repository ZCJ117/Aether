package cn.zcj.aether.api.dto;

import lombok.Data;

/**
 * P0: 登录请求 DTO。
 */
@Data
public class LoginRequestDTO {
    /** 用户名，3-64 字符，仅字母数字下划线 */
    private String username;
    /** 密码，8-128 字符 */
    private String password;
}
