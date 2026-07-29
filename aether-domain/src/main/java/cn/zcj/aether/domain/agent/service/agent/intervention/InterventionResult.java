package cn.zcj.aether.domain.agent.service.agent.intervention;

/**
 * 拦截结果 —— 拦截处理器对消息的处置决策。
 * 灵感来源：AutoGen DropMessage 标记类型 + InterventionHandler 返回协议。
 *
 * <p>三种处置方式：
 * <ul>
 *   <li>{@link #pass()} — 放行，消息正常投递</li>
 *   <li>{@link #drop(String)} — 丢弃，消息不投递（带原因）</li>
 *   <li>{@link #block(String)} — 阻断，抛出异常回传调用方（仅 DIRECT 通道有效，BROADCAST 通道降级为 drop）</li>
 * </ul>
 */
public record InterventionResult(
        Decision decision,
        String reason
) {
    public enum Decision {
        /** 放行 */
        PASS,
        /** 丢弃（静默，仅记日志） */
        DROP,
        /** 阻断（抛异常，直连通道回传调用方） */
        BLOCK
    }

    public static InterventionResult pass() {
        return new InterventionResult(Decision.PASS, null);
    }

    public static InterventionResult drop(String reason) {
        return new InterventionResult(Decision.DROP, reason);
    }

    public static InterventionResult block(String reason) {
        return new InterventionResult(Decision.BLOCK, reason);
    }

    public boolean isPass() { return decision == Decision.PASS; }
    public boolean isDrop() { return decision == Decision.DROP; }
    public boolean isBlock() { return decision == Decision.BLOCK; }
}
