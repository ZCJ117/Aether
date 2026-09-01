# 第三部分：大疆创新 AI Agent 开发面经

## 56. 简单介绍一下自己的背景，以及为什么选择 AI Agent 相关方向？

【技术点】自我介绍 / 职业动机

【答案】

**答题框架（三块）**：
1. **技术背景**：Java 后端出身（Java 17 + Spring Boot + 微服务），做过完整的 Agent 运行时后端项目（Aether：DDD 六模块多 Agent 框架，从零实现了 ReAct 循环、工具调用、记忆、编排），对 LLM 应用层有体系化实践；
2. **为什么选 Agent**：① 看到了 LLM 从"问答工具"到"自主执行体"的范式转移——Agent 是 LLM 落地的关键形态（工具调用、规划、记忆让模型从"会说"到"会做"）；② 自己的后端功底（并发、持久化、安全、可观测）正好是 Agent 工程化的核心短板——Agent 缺的不是模型，而是**工程化能力**（容错、状态管理、成本控制），这正是我的优势；
3. **动机落点**：想在真实业务场景把 Agent 稳定跑起来（不是 demo），解决工程化问题。

**常见误区**：只说"我对 AI 感兴趣"（没有具体实践）；纯聊论文概念（没有工程证据）；不把自己后端优势与 Agent 需求挂钩（浪费差异化机会）。

## 57. 平时开发过程中主要使用哪些 AI 辅助工具？比如 Claude Code 等工具，在实际使用中有哪些经验？

【技术点】AI 工程化实践 / 工具经验

【答案】

**答题要点**：诚实报工具（Claude Code、Cursor、Copilot、ChatGPT 等），重点在"**经验**"——要讲出方法论：
1. **用工具加速理解**：让 AI 解释陌生代码库（Aether 项目代码量大，先让 AI 总结模块结构再定位细节），比直接读快；
2. **用工具写骨架/测试**：让 AI 生成 DTO/样板/单测，人负责**审查与关键设计**（AI 生成的代码必须 review，尤其是并发与安全相关）；
3. **上下文管理经验**：给 AI 喂"精确的上下文"（关键文件 + 行号 + 明确任务）比贴整段代码效果好——这本身就是 Agent 上下文工程的经验；
4. **Spec Coding 经验**：写清需求规格再让 AI 实现，迭代成本低于边聊边改；
5. **边界认知**：AI 擅长已知模式，不擅长需要业务判断的设计决策；验证 AI 输出（写测试跑过再信）。

**常见误区**：只报工具名不讲经验（面试官要的是"你怎么用、踩过什么坑"）；把 AI 输出当正确结果直接上线（缺少验证环节）；没有"AI 辅助 vs 人工判断"的边界意识。

## 58. 如果让你从零搭建一个 Agent 系统，你认为需要设计哪些核心组成部分？

【技术点】Agent 架构设计（高频必考）

【答案】

**（结合 Aether 项目作答——Aether 就是"从零搭建"的完整答案）**

按"**四模块 + 一横切**"拆解：
1. **模型接入层（Model Provider SPI）**：抽象统一模型接口，支持多 Provider（OpenAI/Anthropic/DashScope）与**容错**（`ResilientChatModelExecutor`：错误分类 + 重试 + fallback；`RotatingCredentialPool` 凭据轮换；`TokenBudget` 成本熔断）；
2. **Agent 执行内核**：`Agent` 接口 + `BaseAgent`（Hook/State），实现 ReAct 循环（`ReActAgent`，`MAX_TURNS=100` 防死循环）与 Plan-and-Execute（`PlanActAgent`）；事件流式输出（`Flowable<RuntimeEvent>`）；
3. **工具系统（Tool/MCP）**：统一 `Tool` 抽象 + `ToolRegistry`/`ToolExecutor`；MCP 三种接入（SSE/Stdio/Local，对应 `SSEToolMcpCreateService` 等）；技能书（`SkillsToolAdapter`）；**工具调用安全**（`PermissionEngine` 审批挂起 + 用户确认）；
4. **记忆与上下文**：短期（会话上下文 + `ContextManager` 压缩）与长期（`MemoryFacade` + pgvector 向量检索 + 用户画像）；`{memory}` 占位符注入；
5. **横切能力**：① **编排**（`GraphExecutor`：sequential/parallel/loop + `SubAgentOrchestrator` 子 Agent 委派）；② **会话持久化与恢复**（`PgSessionRepository` + Checkpoint 检查点）；③ **安全**（JWT + 审计 + SSRF/限流）；④ **可观测**（JSON 日志 + Prometheus + OpenTelemetry）。

**答题策略**：先给"规划 / 记忆 / 工具 / 执行控制"四个抽象模块（这是通用认知），再落到自己的实现细节，最后点一句"**Agent 工程化的核心在可靠性、成本、安全，而不是模型本身**"。

**常见误区**：只背概念（ReAct、CoT）不落地；漏掉容错/成本/安全这些"非功能性"模块（面试官最看重）；把编排（多 Agent）当必需（单 Agent 够用就别上，讲清边界）。

## 59. Agent 中的记忆机制是如何设计的？短期记忆和长期记忆分别适用于哪些场景？

【技术点】Agent 记忆（高频必考）

【答案】

**（结合 Aether 项目作答）**

**总体设计**：Aether 记忆系统对齐"hermes 记忆段"设计：`MemoryFacade`（`DefaultMemoryFacade`）统一入口，`EncodingFlow`（编码/embedding 流程）+ `RecallFlow`（召回流程）+ `VectorStore`（存储抽象）三层；配置挂 `aether.memory.*`。

**短期记忆**：
- **定义**：当前会话的上下文（对话轮次 + 工具结果 + 中间推理）；
- **实现**：`ContextManager` 管理，token 预算（`TokenBudget`）监控，超限触发**压缩**（微压缩/`CompactionPipeline` 自动摘要，`compactBoundary` 事件通知前端）；
- **适用场景**：当前任务的连续性（多轮追问、工具结果上下文）——会话内有效，会话结束即失效（或摘要沉淀到长期）。

**长期记忆**：
- **定义**：跨会话的稳定信息——用户画像、偏好、历史结论、业务背景；
- **实现**：`MemoryStore`（存储）+ `PgvectorVectorStore`（pgvector 向量检索，语义召回 Top-K `aether.memory.recall.max-results=10`）+ 用户画像（`aether.memory.user-profile-enabled`）；写入有**筛选**（不是所有话都值得记，`SessionMemoryExtractor` 抽取关键信息），召回按**权重 + 时间衰减**排序；`{memory}` 占位符在指令中注入（`ChatService.injectMemory`）；存量向量回填 `MemoryEmbeddingBackfillRunner`；
- **适用场景**：新会话开头（注入用户画像）、需要历史结论的延续任务、个性化推荐。

**关键设计决策（面试加分点）**：
1. **写入策略**：不是什么都进长期记忆——要有抽取/筛选（`SessionMemoryExtractor`），否则全是噪音，检索反而带歪模型；
2. **过期与去重**：TTL / 时间衰减（`MemorySearchResult` 带权重），相似记忆合并而非追加；
3. **注入预算**：`aether.memory.memory-char-limit=2200`——记忆注入有字符预算，避免占满上下文。

**常见误区**：长期记忆"全量存"（噪音污染）；短期记忆不做压缩（上下文爆窗）；记忆注入无预算（占满上下文反而影响回答质量）；记忆写错无纠正机制（要能删除/覆盖，`clear(scope)`）。

## 60. 在 Agent 执行任务过程中，如果调用外部工具或 API 出现异常、超时等情况，通常如何设计容错和恢复机制？

【技术点】Agent 容错 / 工具异常处理（必考）

【答案】

**（结合 Aether 项目作答）**

**分层容错（三层防御，字节真问过"异常处理写在哪一层"）**：

**① 工具层（调用本身）**：
- 每个 tool 调用 try-catch，异常**不抛给框架**，转为**结构化错误返回给模型**（`ToolResult` 带 `toolError` 标记——Aether 的 `toolResult` 事件有 `"toolError": false` 字段）；让模型知道发生了什么（`{"status":"failed","error_type":"Timeout","retry_after":5}`）而不是拿到堆栈；
- **超时控制**：MCP 请求超时（`request-timeout` 可配，如 baidu-search 500000ms）；工具执行设置超时上限；
- **重试**：`ResilientChatModelExecutor` 对模型调用按 `DefaultModelErrorClassifier` 分类（可重试错误：超时/429/5xx → 指数退避 `RetryBackoff`；不可重试：400/认证错误 → 不重试直接 failover）。

**② 推理层（循环控制）**：
- **迭代上限**：`MAX_TURNS = 100`（`ReActAgent`），超限发 `maxTurnsReached` 事件终止；
- **循环检测**：同一 tool 同参数连续调用 N 次无进展 → 熔断终止当前 reasoning chain；
- **取消令牌**：`config.getCancelToken().isCancelled()` 支持外部中断（`OrchestrationController` interrupt）。

**③ 系统层（全局兜底）**：
- **全局超时**：单次 Agent 任务总时长上限；
- **成本熔断**：`TokenBudget` 按 `maxCostUsd` 熔断（钱烧到线就停）；
- **降级链**：模型 fallback 链（OpenAI → Anthropic → DashScope 跨 Provider）；
- **恢复**：检查点（`checkpointInterval=5`）让失败任务可 `resumeFromCheckpoint` 恢复，而非从头再来。

**应用场景**：Agent 执行中搜索 API 超时 → 返回结构化错误 → 模型决定重试或换工具或告知用户；模型 429 限流 → 退避后换 key（`RotatingCredentialPool`）重试。

**常见误区**：把异常直接抛给上层（模型看到堆栈无法决策）；只设"最大循环次数"（没到上限前一直烧 token——要有成本/时长兜底）；重试无退避（加重故障）；没有"恢复"能力（失败后只能重头跑）。

## 61. 是否考虑过让大模型参与异常检测、问题分析以及自动修复？这种方案有什么优缺点？

【技术点】LLM 运维 / 自愈 Agent

【答案】

**核心概念**：让 LLM 作为"运维/自愈 Agent"：读取监控指标、日志、trace → 分析根因 → 提出/执行修复动作。

**优点**：
1. **长尾覆盖**：规则告警覆盖不了的长尾问题（模糊日志、跨链路根因）LLM 能理解；
2. **快速定位**：把"人查日志"变成"Agent 查日志"，缩短 MTTR；
3. **知识沉淀**：修复经验可沉淀为规则/Skill，形成闭环（badcase 回流）。

**缺点（要讲透）**：
1. **幻觉风险**：LLM 可能自信地给出错误根因 → 自动修复更危险（**修复动作要有审批闸门**，只读诊断可放开，写操作必须人工确认）；
2. **成本与延迟**：分析大量日志 token 成本高（可用预筛选/摘要先缩小范围）；
3. **不可解释/难审计**：LLM 的推理链要落审计（trace + 理由）；
4. **依赖质量**：给它的数据（日志、指标）不准，分析就不准——"垃圾进垃圾出"；
5. **安全**：LLM 被注入攻击时可能执行恶意修复指令（要沙箱 + 白名单动作）。

**落地建议（结合 Aether）**：诊断 Agent 用**只读工具**（查指标、读日志——Aether 已有可观测基础：JSON 日志 + Prometheus + OTel），修复动作走 `PermissionEngine` 审批（PAUSED + 用户确认）——**"读可自动，写必审批"**是安全底线；诊断结果由 `ResultRefiner`/Critic 校验。

**常见误区**：一上来就"全自动修复"（必须人机协同）；忽略审计（LLM 决策要有记录）；不考虑注入攻击面；把"LLM 分析"当万能（先有好的可观测数据才有好的分析）。

## 62. 当模型出现回答幻觉时，有哪些常见的优化方式？如何降低幻觉带来的影响？

【技术点】LLM 幻觉治理（必考）

【答案】

**核心概念**：幻觉 = 模型生成与事实不符/无依据的内容。优化分"**源头减少**"与"**影响兜底**"两层：

**减少幻觉的常见手段**：
1. **RAG（检索增强）**：给模型喂**事实依据**（相关文档/知识库片段），让生成有据可依——这是工程上最有效的手段（Aether 的 `CodeExplorer`/记忆召回就是 RAG 思维）；
2. **约束生成**：结构化输出（JSON Schema/Function Calling）、few-shot 示例、限定格式（`{outputKey}` 占位符约束跨 Agent 输出）；
3. **Prompt 工程**：明确"不知道就说不知道"、要求引用来源、低 temperature（减少发散）；
4. **模型与温度调优**：选用更可靠模型、`temperature` 调低、关闭发散性采样；
5. **分步推理**：CoT/ReAct 让模型先想后答（复杂问题减少跳步幻觉）；
6. **微调**：领域数据 SFT 提升领域事实把握（但成本高，通常 RAG 优先）。

**降低影响的兜底**：
1. **事实校验/引用溯源**：要求输出带引用，后端用检索结果**交叉验证**（提取实体/关键句与来源比对，置信度低则标注/拒绝）；
2. **Critic/评审 Agent**：生成后由 Critic 检查（Aether 的 `CurationPipeline` 策展管道、`ResultRefiner` 精修）；
3. **置信度与降级**：低置信度场景降级为"告知用户不确定/建议核实"；
4. **用户侧标注**：标注"AI 生成内容，请核实关键信息"（旅行 Agent 的 instruction 就内置了"绝不虚构预订号码"的边界——**把幻觉红线写进系统提示词**）。

**Aether 项目实例**：`agents.yml` 的旅游规划 Agent instruction 明确"你基于知识截止前的信息提供建议…绝不虚构预订号码或声称能直接完成支付"——这是 prompt 层的幻觉边界；对话流程可接 RAG/检索工具获取实时事实。

**常见误区**：只调 prompt 期望根治幻觉（幻觉是模型固有特性，要"减少 + 兜底"组合）；RAG 检索质量差却说"RAG 没用"（先查检索召回）；无任何校验直接对外输出（关键业务必须带校验/标注）。

## 63. Prompt 设计过程中有哪些优化方法？除了增加规则限制，还可以从哪些方向提升效果？

【技术点】Prompt 工程

【答案】

**优化方法清单（不止规则限制）**：
1. **角色与人格设定**：给 Agent 明确角色（如 Aether 旅游 Agent："旅程设计师"，温暖专业）+ 沟通风格 + 价值观——一致性更好；
2. **结构化输出要求**：要求分级标题、列表、表格（旅游 Agent 要求"总体概览/每日行程/住宿建议"结构）——可解析、可读性高；
3. **Few-shot 示例**：给出输入→输出的示例（示例对话流程）——比规则描述更有效；
4. **明确边界与降级**：什么不能做（"不提供实时价格"）、需求超出边界怎么办（"温和解释并给替代方案"）——减少越界行为；
5. **引导澄清**：需求模糊时主动提问（旅游 Agent"至少确认出发地、日期、预算"）——减少一次生成失败；
6. **占位符与变量注入**：`{memory}`、`{outputKey}` 动态注入上下文（Aether 的 `InstructionResolver`）——让 prompt 适配会话状态；
7. **迭代自检**：让模型先自问缺失信息（self-reflection），或 Critic 二次评审；
8. **压缩与去噪**：长 prompt 做摘要/精简（信息密度比长度重要）；
9. **指令与数据分离**：提示词与用户输入分隔（防注入，配合 `InjectionGuardRule`）；
10. **评测驱动**：prompt 改动用评测集验证（badcase 回流）——工程化而不是拍脑袋。

**常见误区**：只堆"你不许 xxx"规则（负向规则多反而降低理解与发挥）；不改提示词只换模型；没有评测就调 prompt（改了不知道好没好）；忽略注入防护（用户输入直接拼进指令）。

## 64. 如果发现 Agent 运行过程中 Token 消耗增长过快，会如何定位问题并进行优化？

【技术点】Token 成本优化（字节高频）

【答案】

**定位问题（先算账）**：
1. **按轮次/按会话统计 token 分布**：哪个 Agent、哪个阶段（system prompt / 历史 / 工具结果 / 输出）占大头——Aether 有 `ModelPricingRegistry` + `TokenBudget`，配合日志（`logs/aether-agent.json` 结构化）可算每次调用的 token；
2. **查上下文膨胀**：多轮历史累积（最常见）——看消息数 × 每轮 token；
3. **查工具结果回流**：工具返回大文本（搜索全文、日志）直接进上下文；
4. **查重试放大**：模型失败重试（`ResilientChatModelExecutor` 退避重试）导致 token 翻倍；
5. **查多 Agent 放大**：子 Agent 各自带完整上下文，编排 N 个 Agent = N 倍输入 token。

**优化手段（对应）**：
1. **上下文压缩**：`ContextManager` 微压缩/摘要压缩（`CompactionPipeline`）——历史摘要化而不是全量保留；`memory-char-limit` 控制记忆注入预算；
2. **裁剪工具输出**：工具结果截断/摘要（只把关键片段给模型）；
3. **缓存 system prompt 与工具定义**：`cache_control`（Anthropic prompt caching）/系统消息缓存，降低重复计费；
4. **减少重试**：错误分类准确（不可重试的别重试）、退避合理；
5. **多 Agent 优化**：子 Agent 只注入必要上下文（上下文隔离）、减少委派次数、合并小任务；
6. **模型选型**：简单任务用便宜模型（`inferProvider` 按 modelId 路由）、`maxCostUsd` 成本上限熔断；
7. **语义缓存**：相似请求命中缓存（RAG 场景）。

**常见误区**：只看总费用不看分布（无法定位）；把历史全量保留到爆窗（必须压缩策略）；工具输出不经裁剪直接进上下文（大文本工具是 token 黑洞）；忽略重试与多 Agent 的放大效应。

## 65. 上下文为什么需要进行压缩处理？压缩过程中如果导致关键信息丢失，应该如何解决？

【技术点】上下文管理 / 压缩策略

【答案】

**为什么压缩**：
1. **窗口硬限制**：模型有最大上下文（如 128K/200K token），超限报错或截断；
2. **成本**：输入 token 计费，历史越长越贵（`TokenBudget` 熔断逻辑）；
3. **质量衰减**：**Lost in the Middle**——超长上下文中模型对中间部分关注度下降，无关历史干扰注意力，回答质量下降；
4. **延迟**：长输入处理时间长。

**关键信息丢失的解决（重点）**：
1. **分层压缩而非一刀切**：按价值分层——完全移除（闲聊/重复）、摘要（中间过程）、保留原文（关键结论、工具结果）——`CompactionPipeline` 多步管道就是"不同内容不同处理"；
2. **摘要时要求结构化保留**：压缩 prompt 明确"保留：结论、数字、用户偏好、未完成事项、承诺的后续动作"——Aether 的压缩会产出结构化摘要；
3. **关键信息下沉到长期记忆**：压缩时把高价值信息**抽取进 `MemoryStore`**（`SessionMemoryExtractor`），上下文丢了但记忆还在，后续可 `{memory}` 召回——**压缩 + 记忆双保险**；
4. **显式提醒模型**：压缩边界事件（`compactBoundary`）通知前端展示"上下文已压缩"——用户可感知，模型也知道自己"忘了前面的细节"；
5. **引用回退**：摘要中保留"来源指针"（哪个会话/文档），需要时按指针重取原文（RAG 化）；
6. **评测兜底**：压缩后跑评测集验证关键信息召回率，badcase 回流优化压缩策略。

**常见误区**：压缩 = 截断（直接砍尾部，丢失最早的关键用户偏好）；不区分信息价值一刀切摘要；压缩后信息永久消失（要与记忆/存储配合）；不做压缩（爆窗后整段截断更糟）。

## 66. 是否设计或开发过 Agent Skill？一个 Skill 从设计、实现到效果评估需要考虑哪些因素？

【技术点】Agent Skill / 技能体系

【答案】

**（结合 Aether 项目作答——Aether 有完整 Skills 体系）**

**Aether 的 Skill 体系**：`resource/agent/skills` 下的**技能书**，通过 `SkillsToolAdapter` / `ToolSkillsCreateService` 接入，在 YAML 的 `tool-skills-list`（`type: resource, path: agent/skills`）中声明，与 MCP 工具并列成为 Agent 可用工具。

**Skill 设计考虑因素（全生命周期）**：
1. **设计（定位）**：① 解决什么问题（明确场景边界）；② 输入输出契约（参数 schema 可被模型理解）；③ 与已有 Tool 的边界（避免重复/职责不清）；④ 可复用性（技能 vs 一次性逻辑——能泛化的抽象成 Skill）；
2. **实现**：① 执行逻辑（确定性代码为主，模型只在决策点介入）；② 错误处理（结构化错误返回给模型）；③ 超时/幂等；④ 权限（敏感操作挂 `PermissionEngine` 审批）；⑤ 文档与示例（few-shot 让模型学会调用——技能书本身就是教模型"何时用、怎么用"）；
3. **效果评估**：① 功能正确性（黄金用例集，调用成功率）；② 端到端效果（任务成功率、badcase 回流）；③ 资源成本（token、耗时）；④ 误用率（模型在不该用时调用 / 该用时不用）；⑤ 对比基线（有 Skill vs 无 Skill）；⑥ 回归（新 Skill 不破坏旧任务）。

**常见误区**：把 Skill 当"功能清单"堆砌（每个 Skill 增加模型选择成本，少而精）；没有契约与文档（模型不会用）；不做评估（Skill 效果好坏无数据）；忽略安全（Skill 内敏感操作无审批）。

## 67. 如何理解 Spec Coding 和 Harness？为什么 Harness 可能帮助 Agent 提升复杂任务的完成效果？

【技术点】Spec Coding / Harness（2025 高频新概念）

【答案】

**Spec Coding（规格化编码）**：以**规格/契约（Spec）**驱动编码的实践——先写清楚"做什么、输入输出、验收标准"（甚至用测试/类型/接口定义作为可执行规格），再让模型实现。核心价值：① 把模糊需求变成可验证契约（验收测试）；② 减少模型"自由发挥"与需求漂移；③ 便于迭代（改 spec 而不是改聊天）；④ 可评测（通过/失败明确）。"大疆"这题的语境下，Spec Coding 也指"用 Agent 按规格写代码"的工程方法——关键在于**规格先行**。

**Harness（约束框架/外壳）**：Agent 之外的**一层"壳/脚手架"**，提供运行 Agent 所需的一切工程能力而不改变模型本身：
- **上下文管理**（窗口、压缩、注入）；**工具调度**（注册、路由、超时、重试、幂等）；**状态管理**（AgentState、检查点、恢复）；**安全闸门**（权限审批、注入检测、脱敏）；**可观测性**（trace、日志、成本）；**执行控制**（循环上限、终止条件、熔断）。

**为什么 Harness 提升复杂任务完成效果**：
1. **可靠性**：没有 Harness，长任务 = 模型一路裸奔，任何一次工具失败/上下文爆窗/死循环都会中断；Harness 提供重试、恢复（检查点）、兜底 → 任务不因单点失败而亡；
2. **上下文质量**：Harness 的压缩/记忆注入保证模型看到的始终是"高质量、不超限"的上下文 → 决策质量提升；
3. **安全边界**：权限审批让模型敢于尝试危险操作而不出事故（Human-in-the-loop）；
4. **可观测可调优**：trace 定位是哪一跳失败，迭代更快；
5. **可组合**：Harness 让"换模型/换工具"成为配置而非重写（Aether 的 YAML 装配就是 Harness 思想——`agents.yml` 声明模型/工具/工作流，引擎负责执行）。

**Aether 项目实例**：Aether 本身就是一套 Harness：`Agent` + Hook/Middleware 生命周期、`ContextManager` 上下文、`ToolExecutor` 工具执行、`PermissionEngine` 审批、`CheckpointCollector` 检查点、`AgentEventPublisher` 事件可观测——Agent（模型 + 提示词）只负责"思考决策"，其余全部由 Harness 保障。

**常见误区**：把 Harness 说成"某个框架的名字"（它是设计思想：Agent 外层的一切工程化能力）；认为 Harness = 复杂化（正确设计是"无侵入加分"）；忽视 Spec Coding 的"可验证"本质（没有验收标准的 spec 不是 spec）。

## 68. 介绍一下之前参与过的 AI 相关项目，这个项目是完全自主开发，还是基于已有开源方案进行二次开发？

【技术点】项目真实性 / 自主性

【答案】

**（结合 Aether 项目作答——诚实 + 有料的回答）**

**项目定位**：Aether 是**自主开发为主、参考开源设计**的项目：核心架构与实现（DDD 六模块、ReAct 循环、模型 SPI 容错、记忆系统、MCP 工具接入、子 Agent 编排）均为自研；同时**借鉴/对齐**了 AgentScope、CrewAI、AutoGen、MetaGPT 与 hermes-agent 的设计（README 明确记载），如状态双模式访问、检查点恢复、权限挂起等模式。

**回答策略（关键）**：
1. **明确自主边界**：框架选型用开源生态（Spring AI、RxJava、pgvector），但**业务框架自研**——不直接套 LangChain/LangGraph，而是基于 Spring AI 的 `ChatModel` 抽象自建 `Agent` 接口与执行引擎；
2. **讲自研动机**：开源框架（LangChain/LangGraph）的黑盒循环与 Java 生态适配问题，自研可控性高（循环、状态、检查点都可定制）；
3. **讲借鉴了什么**：对比 6 个开源项目提炼设计模式（ReAct 主循环、记忆分段、权限挂起）——体现"看源码、有判断"；
4. **诚实回应**：若面试官质疑"是否自己写的"，用**代码细节**证明（类名、方法名、配置项信手拈来，如 `ReActAgent.MAX_TURNS=100`、`ResilientChatModelExecutor`、`RotatingCredentialPool`）。

**常见误区**：说"纯自主"（不真实，任何项目都站在开源肩膀上）或"纯二次开发"（无亮点）；讲不出"借鉴了什么、为什么自研、差异在哪"——这是面试官区分"用过"与"做过"的关键。

## 69. 在项目过程中遇到过哪些比较有挑战的问题？你主要负责哪些内容？最终效果如何？

【技术点】项目深挖 / STAR 法则

【答案】

**（结合 Aether 项目，用 STAR 结构答 2-3 个）**

**挑战一：Agent 模型调用链路的高可用与成本可控**
- 背景（S）：Agent 高频调模型 API，超时/限流/配额导致任务中断，成本不可控；
- 任务（T）：设计容错与成本熔断体系；
- 行动（A）：实现 `ResilientChatModelExecutor`（`DefaultModelErrorClassifier` 错误分类 + `RetryBackoff` 指数退避 + 跨 Provider fallback 链）、`RotatingCredentialPool` 凭据轮换、`TokenBudget`（`maxCostUsd` 熔断）；
- 结果（R）：单点模型故障不再中断任务（自动降级）；成本有硬上限。

**挑战二：多 Agent 工作流的上下文隔离与结果聚合**
- 背景：子 Agent 各自执行，上下文互相污染、结果无结构；
- 行动：`GraphExecutor` 实现 sequential/parallel/loop 编排；`InstructionResolver` 的 `{outputKey}` 占位符做跨 Agent 输出传递；`ChildResultAggregator` 聚合；异步委派 `PgAsyncDelegationStore` + `LeaseManager` 租约 + `StaleDelegationScanner` 兜底；
- 结果：代码审查流水线（`CodeWriterAgent → CodeReviewerAgent → CodeRefactorerAgent`）等场景可稳定编排运行。

**挑战三：Agent 安全边界（危险工具 + 注入）**
- 行动：`PermissionEngine` 规则集（`DangerousToolRule`/`InjectionGuardRule`/`SensitiveArgMaskRule`/`ToolAllowlistRule`）+ PAUSED 挂起 + `/api/v1/confirm` 人工审批 + `SsrfSafeInterceptor`；
- 结果：危险操作不直接执行，全部人工可审；敏感参数脱敏后才进模型。

**答题要点**：每个挑战讲"问题 → 权衡 → 方案 → 量化效果"；"你主要负责"要明确（不要全揽功劳）；效果要有数据（可用性、成本、成功率）。

**常见误区**：只讲"做了功能"不讲"遇到什么问题"；效果无量化；把所有模块都说成自己做的（被追问细节会穿帮）。

## 70. Java 线程池有哪些重要参数？任务提交之后线程池内部的执行流程是什么？

【技术点】Java 并发（见第 11 题，此处补充完整）

【答案】

**七个参数**（详见第 11 题）：corePoolSize、maximumPoolSize、keepAliveTime+unit、workQueue、threadFactory、RejectedExecutionHandler。

**执行流程**（精确版）：
1. `execute(Runnable)` 被调用；
2. 若当前**工作线程数 < corePoolSize** → 创建新线程执行（**先判断 core，再判断队列**）；
3. 若工作线程数 ≥ corePoolSize → 尝试**入队**（`workQueue.offer`），入队成功则等线程空闲执行；
4. 若**入队失败（队列满）** → 尝试创建新线程（**若工作线程数 < maximumPoolSize**）；
5. 若工作线程数 **≥ maximumPoolSize** → 执行**拒绝策略**（`RejectedExecutionHandler`）；
6. 非核心线程空闲超过 keepAliveTime → 回收（allowCoreThreadTimeOut 时核心线程也可回收）。

**关键易错点**：**"队列满才扩线程"而非"线程满才入队"**；线程数的设置与队列类型强相关（有界队列才谈拒绝策略；无界队列 `LinkedBlockingQueue` 下 maximumPoolSize 形同虚设，任务全排队）。

**Aether 实例**：`ThreadPoolConfig`：core=20 / max=50 / queue=5000 / `CallerRunsPolicy`（任务不丢，调用线程执行实现背压）；异步审计线程池 `auditExecutor`（core=2/max=5/queue=100）。

**常见误区**：无界队列 + 大 maximumPoolSize 导致内存 OOM；拒绝策略选 `AbortPolicy` 且无告警（任务静默丢失）；线程池命名不规范（排查困难，应自定义 ThreadFactory 命名）。

## 71. 如果让你重新设计一个线程池，你会重点关注哪些设计因素？

【技术点】线程池设计（系统设计思维）

【答案】

**设计维度（展示工程思维）**：
1. **任务模型**：任务类型（CPU 密集 `n+1` / IO 密集 `2n` 经验值 vs 动态自适应）、任务依赖（独立 vs 有状态）、是否有长任务（影响 keepAlive 与队列策略）；
2. **线程生命周期管理**：核心/最大线程数可动态调整（`setCorePoolSize`/`setMaximumPoolSize`）、空闲回收策略、超时机制；
3. **队列策略**：有界队列（必须，防 OOM）+ 容量设计 + **拒绝策略可配置**（`CallerRunsPolicy` 背压 vs `AbortPolicy` 告警）；
4. **可观测性**：线程池指标暴露（活跃线程、队列深度、任务数、拒绝数、执行时间分布）——接入监控告警（Aether 的 Prometheus 可暴露 `jvm_threads` 与自定义指标）；
5. **任务隔离**：不同类型任务用独立线程池（IO/CPU 分离、核心业务与辅助任务分离——Aether 的 `auditExecutor` 独立于主线程池正是此设计），避免互相拖垮；
6. **优雅关闭**：`shutdown`（不接新任务）→ 等待队列清空 → 超时兜底 → `shutdownNow` 中断；配合 Spring 容器关闭钩子（`ThreadPoolTaskExecutor` 的 destroy 行为）；
7. **动态调优**：基于监控数据动态调整参数（或引入自适应算法）；
8. **异常处理**：任务内异常不吞（`ThreadFactory` 设 `UncaughtExceptionHandler`）、Future 异常要处理（`get()` 抛 ExecutionException）。

**常见误区**：只答参数（面试官要设计思路）；忽略可观测性（线程池没有监控等于盲跑）；不隔离任务池（一个慢任务拖垮全部）；忽略优雅关闭（服务下线丢任务）。

## 72. JVM 加载 class 文件的完整流程是什么？涉及哪些阶段？

【技术点】JVM 类加载机制（高频）

【答案】

**核心概念**：类加载完整流程 = **加载 → 验证 → 准备 → 解析 → 初始化**（前四步统称"加载阶段/连接阶段"，重点区分）：

1. **加载（Loading）**：通过**全限定名**读取 class 字节流（文件/网络/动态生成——如反射代理、`Class.forName`），将字节流转换为方法区（元空间）的**运行时数据结构**（`Klass`），并在堆中生成 `Class` 对象作为访问入口；
2. **验证（Verification）**：校验字节码**合法性**（格式、语义、字节码指令、符号引用验证）——防止恶意/损坏字节码；
3. **准备（Preparation）**：为**静态变量**分配内存并设**默认零值**（如 `static int a` 此时 = 0，`static final` 常量此时直接赋常量值）；
4. **解析（Resolution）**：将常量池中的**符号引用**替换为**直接引用**（内存地址/偏移）；
5. **初始化（Initialization）**：执行 **`<clinit>`（静态初始化块与静态变量赋值）**——只有主动使用（new、访问静态、反射、子类初始化触发父类）才会触发，被动使用（访问 `final` 常量、定义数组、引用父类静态字段不触发子类初始化）不触发。

**类加载器（双亲委派）**：Bootstrap（核心库，C++ 实现）→ Extension/Platform → Application（classpath）→ 自定义。**双亲委派**：先向上委派父加载器，父加载不了才自己加载——保证核心类不被篡改（如 `String` 永远由 Bootstrap 加载）。

**常见误区**：把"准备"当成"静态变量赋值完成"（只是零值，赋值在初始化）；静态 final 与静态变量的时机混淆；`Class.forName`（触发初始化）与 `ClassLoader.loadClass`（不触发初始化）混淆；忘记双亲委派的意义（安全性 + 唯一性）。

## 73. MySQL 中的 undo log、redo log 和 binlog 分别有什么作用？三者之间有什么区别？

【技术点】MySQL 日志系统（高频必考）

【答案】

**作用**：
- **redo log（重做日志）**：**物理日志**（页级变更），**WAL（预写日志）**——事务提交前先写 redo log 再落数据页，崩溃后重放恢复已提交数据 → 保证**持久性（D）**；InnoDB 特有，循环写（ib_logfile）；
- **undo log（回滚日志）**：**逻辑日志**（记录变更前镜像/反向操作），用于**事务回滚**（原子性 A）与 **MVCC 版本链**（隐藏列 `trx_id` + `roll_pointer` 指向 undo 构建 ReadView 快照）——InnoDB 特有；
- **binlog（二进制日志）**：**逻辑日志**（SQL 语句/row 事件），**MySQL Server 层**（所有引擎共享），用于**主从复制**与**数据恢复/CDC**（Canal/Debezium 读它），追加写。

**三者区别**：
| 维度 | redo log | undo log | binlog |
|---|---|---|---|
| 层级 | InnoDB 存储引擎层 | InnoDB 存储引擎层 | MySQL Server 层 |
| 性质 | 物理日志（页） | 逻辑日志（反向变更） | 逻辑日志（变更事件） |
| 作用 | 崩溃恢复（持久性） | 回滚 + MVCC（原子性/隔离性） | 复制 + 恢复 + CDC |
| 写入时机 | 事务提交前（WAL） | 事务执行中 | 事务提交时 |
| 生命周期 | 循环覆盖 | 事务结束可清理（purge） | 追加保存 |

**常见误区**：把 binlog 当 InnoDB 专属（它是 Server 层）；以为 redo 与 binlog 重复（前者引擎崩溃恢复，后者复制/恢复，两阶段提交保证两者一致——见下题）；undolog 在 PG 里不存在（PG 用多版本 + vacuum，这是两套体系）。

## 74. MySQL 为什么需要两阶段提交？它主要解决什么问题？

【技术点】MySQL 两阶段提交

【答案】

**核心概念**：两阶段提交（Two-Phase Commit）协调 **redo log（InnoDB）与 binlog（Server 层）两个日志**的一致性——解决"**redo 提交了但 binlog 没写（或反之）**"导致的主从不一致与恢复不一致问题。

**流程（prepare → commit）**：
1. **prepare 阶段**：事务执行完成，redo log 写入并标记 **prepare**（事务处于"预提交"状态）；
2. **commit 阶段**：先写 **binlog**（保证 binlog 已落盘），再在 redo log 标记 **commit**。

**解决的问题**：如果不两阶段提交，崩溃可能发生在"redo 提交后、binlog 写前"——主库数据有了（redo 恢复），但从库没有该事务（binlog 缺失），主从不一致；反之 binlog 有而 redo 没有，恢复时主库丢事务但从库执行了。**两阶段提交保证：redo 与 binlog 要么都生效，要么都不生效**（崩溃恢复时以 binlog 为准对账 redo，`binlog` 中有的 `prepare` 事务强制提交，没有的回滚）。

**Aether 项目背景**：PostgreSQL 用**单份 WAL** 天然避免此问题（WAL 既承担崩溃恢复又承担逻辑复制源），这也是 PG 无两阶段提交问题的架构优势；MySQL 因 Server 层与引擎层日志分离必须两阶段。

**常见误区**：以为两阶段提交是分布式事务专有（MySQL 内部的两阶段是 redo/binlog 协调，与 Seata 的跨库两阶段是不同层面）；忽略崩溃恢复的裁定规则（以 binlog 为准）；混淆 prepare/commit 与分布式事务的 prepare/commit 术语。

## 75. 算法题：实现两个有序数组的合并。

【技术点】算法 / 双指针

【答案】

**核心概念**：归并思想（merge）——双指针分别遍历两数组，每次取较小者放入结果，O(m+n) 时间、O(m+n) 空间（原地归并可 O(1) 额外空间）。

```java
public int[] merge(int[] a, int[] b) {
    int[] res = new int[a.length + b.length];
    int i = 0, j = 0, k = 0;
    while (i < a.length && j < b.length) {
        res[k++] = a[i] <= b[j] ? a[i++] : b[j++];
    }
    while (i < a.length) res[k++] = a[i++];
    while (j < b.length) res[k++] = b[j++];
    return res;
}
```

**变体（面试常追）**：① 合并到 a 的末尾（从后往前填，避免覆盖）；② 有重复元素去重；③ 逆序合并（从大到小）；④ 多路归并（k 个有序数组合并，用堆）。

**常见误区**：忘记处理其中一个数组剩余的尾部；比较时边界写错（`<=` 保证稳定性）；合并到自身数组时正向覆盖（应该从尾部开始）。

---
