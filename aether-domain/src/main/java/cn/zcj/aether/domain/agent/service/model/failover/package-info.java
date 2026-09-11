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
 *
 * <h3>错误 → 分类 → 恢复决策：关键代码链路</h3>
 * <p>整条恢复管线分散在四个位置，数据流如下：</p>
 * <pre>
 * call()/stream() 捕获异常
 *   ① 分类入口  ResilientChatModelExecutor#classifyError
 *   ② 分类实现  DefaultModelErrorClassifier#classify（aether-infrastructure classifier 包）
 *         ↓ 产出 ClassifiedError（FailoverReason 枚举）
 *   ③ 决策核心  TurnRetryState#nextDirective(ClassifiedError)
 *         ↓ 产出 RecoveryDirective(branch, backoffSec, reason)
 *   ④ 决策执行  call() 与 handleStreamError() 中对称的 switch(d.branch())
 * </pre>
 * <ol>
 *   <li><b>分类入口（二级策略）</b>：{@code ResilientChatModelExecutor#classifyError} 先询问
 *       当前 Provider 的 {@code classifyError}（Provider 特有分类优先），失败或返回 null 时
 *       回落到默认分类器。{@link cn.zcj.aether.domain.agent.service.model.failover.ModelErrorClassifier}
 *       是本包定义的 SPI 端口，实现在 infrastructure 层——端口在领域层、适配器在基础设施层。</li>
 *   <li><b>分类实现（四级优先级管线）</b>：{@code DefaultModelErrorClassifier} 对齐 hermes
 *       error_classifier.py：① 消息模式关键词匹配最高优先（CONTENT_POLICY / CONTEXT_OVERFLOW
 *       等模式表）；② HTTP 状态码（401/403→AUTH_TRANSIENT，402→BILLING，404→MODEL_NOT_FOUND，
 *       429 按消息拆 OVERLOADED / RATE_LIMIT / UPSTREAM_RATE_LIMIT 三分支，5xx→SERVER_ERROR）；
 *       ③ 传输异常（TimeoutException→TIMEOUT，SSL→SSL_CERT）；④ UNKNOWN 兜底（retryable=true）。
 *       入口用 NestedExceptionUtils.getMostSpecificCause 递归解包 Spring AI 的异常包装链。</li>
 *   <li><b>决策核心（纯状态机 + 分支账本）</b>：{@link cn.zcj.aether.domain.agent.service.model.failover.TurnRetryState}
 *       #nextDirective 按分类路由恢复分支，且每个分支有独立限次账本（attemptCount）——
 *       确定性终止（AUTH_PERMANENT / CONTENT_POLICY_BLOCKED / SSL_CERT）；压缩上限 2 次；
 *       凭据轮换上限 1 次；退避/重连上限 maxAttempts；UPSTREAM_RATE_LIMIT / MODEL_NOT_FOUND
 *       直接 fallback（重试无意义）。分支耗尽走 {@code exhaustedBranch}：fallback 链有存量则切换，
 *       否则 TERMINATE。本类无任何 I/O，纯函数式状态机，可独立单测。
 *       {@code resetPerModel} 在 fallback 切换成功后清空 per-model 账本但保留
 *       PROVIDER_FALLBACK 链位置——链消费是全局的，不随模型切换重置。</li>
 *   <li><b>决策执行（两处对称 switch）</b>：同步路径 {@code ResilientChatModelExecutor#call}
 *       与流式路径 {@code #handleStreamError} 对同一 RecoveryDirective 做相同处理
 *       （退避 / 压缩 / 凭据轮换 / 切 fallback / 终止）；区别仅在于流式版执行完恢复动作后
 *       重建流（streamWithRecovery），且首帧后失败不重放（避免下游收到重复内容）。</li>
 * </ol>
 * <p>速查：错误如何被识别 → infrastructure 层 {@code DefaultModelErrorClassifier}；
 * 识别后如何决策 → {@link cn.zcj.aether.domain.agent.service.model.failover.TurnRetryState}；
 * 决策如何落地 → {@link cn.zcj.aether.domain.agent.service.model.failover.ResilientChatModelExecutor}
 * 的 {@code call()} / {@code handleStreamError()}。</p>
 */
package cn.zcj.aether.domain.agent.service.model.failover;
