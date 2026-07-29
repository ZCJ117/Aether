/**
 * 模型容错与 Fallback 链包。
 *
 * <h3>设计来源</h3>
 * <ul>
 *   <li>hermes-agent error_classifier.py — FailoverReason 枚举 + ClassifiedError 动作提示</li>
 *   <li>hermes-agent retry_utils.py — 去相关抖动退避算法</li>
 *   <li>hermes-agent conversation_loop.py — fallback 链分发语义</li>
 *   <li>hermes-agent chat_completion_helpers.py — try_activate_fallback 切换逻辑</li>
 * </ul>
 *
 * <h3>核心类型</h3>
 * <ul>
 *   <li>{@link cn.zcj.aether.domain.agent.service.model.failover.FailoverReason} — 14 种失败原因枚举</li>
 *   <li>{@link cn.zcj.aether.domain.agent.service.model.failover.ClassifiedError} — 分类结果 + 恢复动作提示</li>
 *   <li>{@link cn.zcj.aether.domain.agent.service.model.failover.ModelErrorClassifier} — 分类器 SPI 端口</li>
 *   <li>{@link cn.zcj.aether.domain.agent.service.model.failover.ResilientChatModelExecutor} — 容错执行器</li>
 *   <li>{@link cn.zcj.aether.domain.agent.service.model.failover.ModelRoute} — fallback 链路由</li>
 * </ul>
 */
package cn.zcj.aether.domain.agent.service.model.failover;
