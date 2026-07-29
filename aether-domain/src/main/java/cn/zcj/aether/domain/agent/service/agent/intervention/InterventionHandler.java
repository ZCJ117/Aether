package cn.zcj.aether.domain.agent.service.agent.intervention;

/**
 * 消息拦截处理器接口。
 * 灵感来源：AutoGen InterventionHandler（三拦截点：on_send / on_publish / on_response）。
 *
 * <p>三种拦截点对应不同的消息通道语义：
 * <ul>
 *   <li>{@link #onSend} — 直连通道：消息发送给特定 Agent（SEQUENTIAL / LOOP / SUBAGENT）</li>
 *   <li>{@link #onPublish} — 广播通道：消息发布给多个 Agent（PARALLEL / GRAPHFLOW 并发批次）</li>
 *   <li>{@link #onResponse} — 响应通道：Agent 执行完成后的结果回传</li>
 * </ul>
 *
 * <p>实现者应遵循的原则（对齐 AutoGen L748-750）：
 * <ul>
 *   <li>DIRECT 通道的拦截异常应回传调用方（block），让等待方感知故障</li>
 *   <li>BROADCAST 通道的拦截异常应只记日志（drop），避免单个节点的审批故障扩散到整个图</li>
 * </ul>
 */
public interface InterventionHandler {

    /**
     * 直连发送拦截 —— 在消息发送给特定 Agent 之前。
     *
     * @param message 待发送的消息文本
     * @param ctx     拦截上下文（含通道类型）
     * @return 拦截结果（PASS/DROP/BLOCK）
     */
    default InterventionResult onSend(String message, InterventionContext ctx) {
        return InterventionResult.pass();
    }

    /**
     * 广播发布拦截 —— 在消息发布给多个 Agent 之前。
     *
     * @param message 待发布的消息文本
     * @param ctx     拦截上下文（含通道类型）
     * @return 拦截结果（PASS/DROP/BLOCK）。注意：BROADCAST 通道的 BLOCK 会被降级为 DROP，
     *         避免单个订阅者的拦截故障波及其他订阅者。
     */
    default InterventionResult onPublish(String message, InterventionContext ctx) {
        return InterventionResult.pass();
    }

    /**
     * 响应拦截 —— 在 Agent 执行完成后、结果回传前。
     *
     * @param message Agent 执行的输出文本
     * @param ctx     拦截上下文
     * @return 拦截结果
     */
    default InterventionResult onResponse(String message, InterventionContext ctx) {
        return InterventionResult.pass();
    }
}
