package cn.zcj.aether.domain.agent.service.agent.intervention;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 默认拦截处理器组合器 —— 空操作实现，便于子类化覆盖所需方法。
 * 灵感来源：AutoGen DefaultInterventionHandler。
 */
public class DefaultInterventionHandler implements InterventionHandler {

    @Override
    public InterventionResult onSend(String message, InterventionContext ctx) {
        return InterventionResult.pass();
    }

    @Override
    public InterventionResult onPublish(String message, InterventionContext ctx) {
        return InterventionResult.pass();
    }

    @Override
    public InterventionResult onResponse(String message, InterventionContext ctx) {
        return InterventionResult.pass();
    }

    /**
     * 组合多个拦截处理器 —— 按注册顺序链式执行。
     * 任一处理器返回非 PASS 结果即短路返回。
     */
    public static InterventionHandler chain(List<InterventionHandler> handlers) {
        if (handlers == null || handlers.isEmpty()) {
            return new DefaultInterventionHandler();
        }
        List<InterventionHandler> snapshot = List.copyOf(handlers);
        return new InterventionHandler() {
            @Override
            public InterventionResult onSend(String message, InterventionContext ctx) {
                for (InterventionHandler h : snapshot) {
                    InterventionResult r = h.onSend(message, ctx);
                    if (!r.isPass()) return r;
                }
                return InterventionResult.pass();
            }

            @Override
            public InterventionResult onPublish(String message, InterventionContext ctx) {
                for (InterventionHandler h : snapshot) {
                    InterventionResult r = h.onPublish(message, ctx);
                    if (!r.isPass()) return r;
                }
                return InterventionResult.pass();
            }

            @Override
            public InterventionResult onResponse(String message, InterventionContext ctx) {
                for (InterventionHandler h : snapshot) {
                    InterventionResult r = h.onResponse(message, ctx);
                    if (!r.isPass()) return r;
                }
                return InterventionResult.pass();
            }
        };
    }
}
