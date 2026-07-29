/**
 * 工具校验闭环与 Schema 提示回喂。
 *
 * <p>本包含三个组件：
 * <ul>
 *   <li>{@link ValidationResult} — 结构化校验结果（通过/失败原因）</li>
 *   <li>{@link ToolInputValidator} — 按 JSON Schema 校验工具入参</li>
 *   <li>{@link SchemaHintBuilder} — 构建面向 LLM 的 Schema 提示文本</li>
 * </ul>
 *
 * <p>设计参考 crewAI 的 {@code _validate_kwargs} + {@code build_schema_hint} 模式：
 * 校验失败时，错误信息附带完整的参数 Schema 提示，作为 ToolResult 回喂 LLM，
 * 使 LLM 在下一轮 ReAct 中能够自我修正参数。
 *
 * @see cn.zcj.aether.domain.agent.service.tool.ToolExecutor
 * @see cn.zcj.aether.domain.agent.service.tool.ToolResult.ErrorType
 */
package cn.zcj.aether.domain.agent.service.tool.validation;
