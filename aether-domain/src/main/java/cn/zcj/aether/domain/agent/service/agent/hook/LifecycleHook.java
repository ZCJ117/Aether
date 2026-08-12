package cn.zcj.aether.domain.agent.service.agent.hook;

import java.util.Set;

/**
 * 全局生命周期钩子 — 编排级、按 {@link HookPoint} 注册。
 * 对齐 hermes plugins.py：回调统一签名，invoke 时逐回调隔离异常。
 */
public interface LifecycleHook {

    /** 该钩子关心的挂点集合（一个钩子可监听多个挂点） */
    Set<HookPoint> points();

    /** 挂点触发回调 */
    void onHook(HookPoint point, HookContext ctx);

    /** 优先级：数值越小越先执行，默认 100（对齐 AgentHook.priority 语义） */
    default int order() {
        return 100;
    }
}
