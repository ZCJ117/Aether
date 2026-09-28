package cn.zcj.aether.api.dto;

import lombok.Data;

@Data
public class ChatRequestDTO {

    private String agentId;
    private String userId;
    private String sessionId;
    private String message;

    /** 权限模式：default | plan | accept_edits | bypass（可选，大小写不敏感，缺省 default）。 */
    private String permissionMode;

}
