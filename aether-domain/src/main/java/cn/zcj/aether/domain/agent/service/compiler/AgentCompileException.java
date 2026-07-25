package cn.zcj.aether.domain.agent.service.compiler;

/**
 * Agent 图编译异常 —— 启动时配置校验失败时抛出。
 */
public class AgentCompileException extends RuntimeException {

    public AgentCompileException(String message) {
        super(message);
    }

    public AgentCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
