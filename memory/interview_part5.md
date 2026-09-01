# 第六部分：阿里云 AI 开发一面（8.11）

## 106. 算法题：很多个区间代表开始和结束（都是整数），问哪个时候同时处于最多的区间里，如果有多个满足就只返回最小的值

【技术点】算法 / 差分数组 / 扫描线

【答案】

**核心概念**：求"最多重叠区间的点"，经典解法是**扫描线/差分**：把每个区间 `[start, end]` 拆成"起点 +1、终点后 -1"两个事件，按坐标排序后从左到右扫描，当前累计值 = 该点的重叠数，取最大值时的坐标（多个取最小）。

```java
// 差分数组法（坐标范围小时）：diff[start]++, diff[end+1]--
// 通用扫描线法：
public int maxOverlapMinPoint(int[][] intervals) {
    List<int[]> events = new ArrayList<>();
    for (int[] iv : intervals) {
        events.add(new int[]{iv[0], 1});   // 开始：+1
        events.add(new int[]{iv[1] + 1, -1}); // 结束+1：-1（保证 [s,e] 含端点）
    }
    events.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);
    int cur = 0, max = 0, ans = 0;
    for (int[] e : events) {
        cur += e[1];
        if (cur > max) { max = cur; ans = e[0]; } // 只在 > 时更新 → 保证取最小坐标
    }
    return ans;
}
```
注意"结束 +1 再 -1"（左闭右闭区间处理）与"只有 `>` 才更新（保证多个最大值时取最小坐标）"两个细节。

**常见误区**：end 后不减（区间端点重复计数）；`>=` 更新导致取最大坐标；坐标范围大时不选差分数组（用事件排序，O(n log n)）。

## 107. MySQL 索引介绍一下，为什么 B+ 树，不能是二叉树，不能是哈希？（阿里云）

【技术点】MySQL 索引（见第 20 题，此处按阿里口径回答）

【答案】

**（详见第 20 题）**：B+ 树 = 多路平衡树（矮宽、叶子有序链表、非叶子只存键）。**为什么不是二叉树**：树高随数据量线性增长（千万级 20+ 层），每次下探一次磁盘 IO，IO 次数爆炸——B+ 树每节点一页（16KB）可存上百键，3-4 层覆盖亿级。**为什么不是哈希**：哈希只支持等值查询（O(1)），无法范围查询（`BETWEEN`/`>`）、无法排序、无法最左前缀匹配——而 SQL 里范围/排序/分组是高频操作，B+ 树叶子有序链表天然支持顺序访问。

## 108. MySQL 慢查询怎么优化？explain 里面看什么？（阿里云）

【技术点】SQL 优化 / explain

【答案】

**慢查询定位**：开启慢查询日志（`slow_query_log` + `long_query_time`）→ 收集慢 SQL → `EXPLAIN` 分析执行计划。

**explain 关键列（必背）**：
1. **type（访问类型，重要）**：`system > const > eq_ref > ref > range > index > ALL`——目标是 **range 以上**，出现 `ALL`（全表扫）/`index`（全索引扫）要警惕；
2. **key（实际用的索引）/ possible_keys**：是否走索引、走哪个；
3. **rows（预估扫描行数）**：越小越好——`ALL` 时 rows 大 = 慢；
4. **Extra（重要）**：`Using index`（覆盖索引，好）、`Using where`、`Using temporary`（用了临时表，排序/去重优化）、`Using filesort`（文件排序，加索引优化）、`Using index condition`（ICP 下推）；
5. **filtered**：过滤比例（估）。

**优化手段（分层）**：① 索引优化：加联合索引（匹配 WHERE + ORDER BY + GROUP BY）、覆盖索引、最左前缀、避免索引失效（函数/隐式转换/`%like` 前缀/`or` 非索引列）；② SQL 改写：拆分大查询、避免 `SELECT *`、减少 `IN` 大列表、`LIMIT` 深分页用游标；③ 结构优化：分表、冗余字段、汇总表、缓存（Redis/Caffeine）；④ 参数调优：buffer pool、慢日志阈值。

**常见误区**：只加索引不看执行计划（加了不生效白加）；忽略 `Using filesort/temporary`（这两个是排序分组慢的元凶）；深分页 `OFFSET` 巨大还硬查（应游标）。

## 109. Redis 和 MySQL 区别？（阿里云）

【技术点】缓存 vs 数据库

【答案】

| 维度 | Redis | MySQL |
|---|---|---|
| 存储 | 内存（快，可持久化 RDB/AOF） | 磁盘（慢，缓存/索引优化） |
| 定位 | 缓存/计数器/队列/分布式锁等 | 持久化事实数据源（ACID） |
| 数据模型 | K-V（丰富结构） | 关系模型（SQL/事务/约束） |
| 一致性 | 最终一致为主（需业务保证双写一致） | 强一致（ACID 事务） |
| 容量 | 受内存限制（亿级 key 需分片） | TB 级 |
| 查询 | 简单键/结构操作 | 复杂关联/聚合/事务查询 |

**协作模式（重点）**：**Cache-Aside（旁路缓存）**——读：先查 Redis，miss 则查 MySQL 回填；写：先写 MySQL，再删 Redis（或延迟双删），配合消息/对账保证最终一致。Aether 项目：会话 `RedisSessionRepository`（缓存）+ `PgSessionRepository`（持久化）双存储。

**常见误区**：把 Redis 当数据库存关键数据（无强一致/持久化窗口）；缓存与 DB 一致性方案乱（先删缓存 vs 先写库的坑）；不设 TTL 导致缓存永远不一致。

## 110. Java 相关命令（javac、jstat 这种）？（阿里云）

【技术点】JDK 工具

【答案】

**常用 JDK 命令（按用途分组）**：
- **编译/运行**：`javac`（编译 .java → .class）、`java`（运行，`-Xmx/-Xms/-XX` 参数）、`jar`（打包）；
- **诊断（重点，面试常问）**：
  - `jps`（JVM 进程列表，含 pid）；
  - `jstat -gcutil <pid> 1000`（GC 统计：Eden/Survivor/Old 使用率、GC 次数与耗时——**看 GC 趋势**）；
  - `jmap -heap <pid>`（堆配置与使用概览）、`jmap -dump:format=b,file=x.hprof <pid>`（堆 dump，OOM 分析）、`jmap -histo <pid>`（对象分布 Top）；
  - `jstack <pid>`（线程栈 dump，**看死锁/线程阻塞**）；
  - `jcmd <pid> help`（综合诊断入口，替代部分 jmap/jstack）；
  - `jinfo <pid>`（查看/修改运行中 JVM 参数）；
- **类/反编译**：`javap -c/-p`（反汇编 class，查看字节码——面试可展示对 class 文件结构理解）；
- **其他**：`jdb`（调试）、`jconsole`/`jvisualvm`（GUI 监控）、`jhsdb`（底层）。

**Aether 项目实例**：线上排查 OOM 流程（见第 17 题）就是这些命令的组合：`jps` 定位 → `jstat` 看 GC → `jmap -dump` 取证 → MAT 分析。

**常见误区**：只背命令名不说用途（要能说"什么场景用哪个"）；混淆 `jstat`（GC 统计）与 `jstack`（线程栈）；生产上直接 `jmap -dump` 全堆（大堆会卡顿——先 `-histo` 或 `jcmd GC.heap_dump` 并选低峰期）。

## 111. 垃圾回收机制讲一下（阿里云）

【技术点】JVM GC（综合题）

【答案】

**① 对象存活判断**：**可达性分析**（GC Roots：栈帧局部变量、静态变量、常量引用、JNI 引用；从 Roots 出发不可达即垃圾）+ 引用计数（已弃用，循环引用问题）；四种引用（强/软/弱/虚）影响回收时机。

**② 分代假设 + 分代收集**：新生代（Eden + 两个 Survivor，比例 8:1:1，对象朝生夕灭）用**复制算法**（清理后幸存对象复制到 Survivor，无碎片）；老年代对象存活率高用**标记-清除**（有碎片）/ **标记-整理**（移动对象，无碎片但 STW）。

**③ 收集器演进**：Serial（单线程）→ Parallel（多线程吞吐优先，JDK8 默认）→ **CMS**（并发标记清除，追求低停顿，有碎片与浮动垃圾）→ **G1**（JDK9+ 默认，Region + 回收价值优先，见第 19 题）→ **ZGC/Shenandoah**（亚毫秒停顿，染色指针/读屏障）。

**④ GC 触发**：Minor GC（Eden 满）；Major/Old GC（老年代满）；Full GC（老年代 + 元空间满、`System.gc()`、晋升失败）；G1 的 Mixed GC。

**⑤ 调优目标**：减少 Full GC 频率与停顿（吞吐 vs 延迟取舍）；工具：`jstat`、GC 日志（`-Xlog:gc*`）。

**常见误区**：把"引用计数"当 JVM 实际算法（主流是可达性）；以为 CMS 无停顿（初始标记/重新标记仍 STW）；G1 与 CMS 对比分不清（Region 局部回收 vs 全堆标记）；忽略 GC 日志分析（调优的依据）。

## 112. 并发用什么机制保障安全？怎么在 Java 里实现多线程？给 static 方法加锁和非 static 方法加锁有什么区别？（阿里云）

【技术点】并发安全 / 锁

【答案】

**并发安全机制**：① **互斥同步**：`synchronized`、`ReentrantLock`（显式锁、可中断、公平）；② **无锁/乐观**：CAS（`AtomicInteger` 等）+ `volatile`（可见性/有序性）；③ **线程隔离**：`ThreadLocal`；④ **并发容器**：`ConcurrentHashMap`、`CopyOnWriteArrayList`；⑤ **协作工具**：`CountDownLatch`/`Semaphore`/`CyclicBarrier`/`CompletableFuture`；⑥ **不可变**：final + 不可变对象（最简单安全）。

**多线程实现**：继承 `Thread`、实现 `Runnable`、实现 `Callable`（有返回值）、线程池（生产首选）（详见第 12 题）。

**static 锁 vs 非 static 锁（重点，阿里必考）**：
- **`synchronized` 修饰 static 方法**：锁的是 **`类对象（Class 对象）`**——该类的**所有实例共享**这把锁；
- **`synchronized` 修饰非 static（实例）方法**：锁的是 **`this`（当前实例对象）**——**不同实例各有各的锁**；
- **区别**：static 方法锁 = 类级别（全局互斥，影响所有实例）；实例方法锁 = 对象级别（只互斥同一实例的调用）；
- **经典坑**：两个线程分别调用**两个不同实例**的**非 static** 同步方法 → **不互斥**（锁不同）；调用 **static** 同步方法 → **互斥**（同一把类锁）。

**常见误区**：以为 static 与非 static 同步方法互斥（它们锁不同对象不互斥）；以为不同实例的实例方法锁互斥（不互斥）；`volatile` 不保证原子性（i++ 需要 CAS 或锁）。

---

# 第七部分：Agent 面完整题单（春招复盘，按模块）

> 该部分为备考文章中的模块化题单，已在前文覆盖的题目给出指引，此处对新增题逐一作答。

## 113. 你怎么理解 Agent 系统？拆解一下核心模块（题单模块一）

【技术点】Agent 认知（见第 58 题完整版）

【答案】**（详见第 58 题）**：定义——Agent 是以 LLM 为核心、能自主规划任务、调用工具、根据环境反馈动态调整行动的系统，关键词"自主"与"反馈闭环"。四模块：规划（任务分解/反思）、记忆（短期 context + 长期存储）、工具（function calling/MCP）、执行控制（循环驱动、终止条件、异常兜底）。**补充（高分句）**：Agent 与 workflow 的本质区别是**路径决策权在谁手里**。

## 114. 什么场景该用 Agent，什么场景不该用？（题单模块一）

【技术点】场景判断（见第 76 题）

【答案】**该用**：路径无法预先枚举、强依赖中间反馈、需多工具协作的复杂任务（研究、规划、调试、代码审查流水线）。**不该用**：确定性流程（订单流转）、单步简单问答（普通 LLM 应用即可）、对延迟/成本敏感且可枚举的场景（Workflow 更优）。判断标准：**决策权 + 路径可枚举性**（详见第 76 题）。

## 115. 讲一下 ReAct 的原理，画一遍完整执行流程（题单模块二，见第 100 题）

【技术点】ReAct（见第 100 题完整版）

【答案】**（详见第 100 题）**：Thought → Action → Observation 循环；Aether 的 `ReActAgent`（`MAX_TURNS=100`）实现细节。

## 116. ReAct 和 Plan-and-Execute 分别适合什么场景？（题单模块二）怎么避免 Agent 死循环、无限调用工具？（模块二）

【技术点】规划范式对比 / 循环防护

【答案】

**ReAct vs Plan-and-Execute**：
- **ReAct**：边想边做（Thought→Action→Observation），**适合路径无法预先枚举、强依赖中间反馈**的任务（查资料、调试、实时搜索）；缺点：无全局规划，容易局部最优、绕圈子；
- **Plan-and-Execute（P&E）**：先出**完整计划**再逐步执行（Aether 的 `PlanActAgent`），**适合步骤相对确定的长任务**（多步骤流水线），调用次数少、延迟低；缺点：计划本身可能错，错了要 replan，对模型规划能力要求高；
- **生产实践**：混用——先粗粒度规划，执行到具体节点允许局部 ReAct（Aether 的 `GraphExecutor` 编排 + 节点内 `ReActAgent` 正是这种形态）。

**避免死循环/无限调用（三层防御，字节必问"异常处理写在哪一层"）**：
1. **工具层**：每个 tool 调用 try-catch，异常转结构化错误返回模型（`{"status":"failed","error_type":"Timeout"}`），让模型知道发生了什么；
2. **推理层**：**迭代硬上限**（`MAX_TURNS=100`，超限发 `maxTurnsReached` 事件）+ **循环检测**（同一工具同参数连续 N 次 / 状态多轮无推进 → 熔断终止 reasoning chain）+ **取消令牌**（`config.getCancelToken().isCancelled()` 支持外部中断）；
3. **系统层**：**全局超时** + **单任务 token/成本上限**（`TokenBudget` 按 `maxCostUsd` 熔断）+ 降级。

**常见误区**：只答"设置最大循环次数"（没到上限前一直在烧 token——要有成本/时长兜底）；无循环检测（同参数反复调）；异常直接抛给模型（模型不会处理堆栈）。

## 117. 多 Agent 之间互相推诿任务怎么办？（题单模块二）

【技术点】多 Agent 协调

【答案】

**核心概念**：多 Agent 互相推诿 = 任务被反复转交无人真正执行（常见于职责边界模糊的编排）。**根因**：① 角色职责定义不清；② 没有"任务所有权"概念；③ 终止条件缺失（A 认为该 B 做，B 认为该 A 做，来回转）。

**解法（分层）**：
1. **职责明确（治本）**：每个 Agent 的 description/instruction 明确**能力边界与拒绝条件**（Aether 的 YAML 中每个 Agent 有 `description`，模型依据它判断是否该自己做）；
2. **编排层兜底**：`GraphExecutor` 的 workflow 是**显式定义**的（sequential/parallel 明确谁做谁），不依赖模型协商——**确定性编排防止推诿**；
3. **委派记录与审计**：每次委派落库（`PgAsyncDelegationStore` + `DelegationRecord`），可查"谁把任务给了谁"，定位推诿链；
4. **超时与升级**：委派超时（`aether.delegation.stale-timeout=PT10M`）由 `StaleDelegationScanner` 扫描 → 回收任务 → 升级给主 Agent 或人工；
5. **任务所有权（设计）**：主 Agent 持有任务终态责任，子 Agent 只能"拒绝并说明理由"返回主 Agent，不能无限转交（委派层数/次数上限）；
6. **结果校验**：`ChildResultAggregator` 校验子 Agent 是否真的产出（空结果/敷衍结果视为未完成）。

**常见误区**：依赖模型自觉（模型可能反复转交——要显式编排 + 上限）；无委派审计（推诿无法定位）；子 Agent 无限转交（要层级/次数限制）。

## 118. Tool 的设计原则是什么？哪些能力适合抽象成 Tool？参数 schema 怎么设计？错误码怎么返回给模型？（题单模块三）

【技术点】Tool 设计（腾讯高频）

【答案】

**设计原则**：① **单一职责**（一个工具一件事，如 `list_directory`/`read_file` 分开）；② **可描述**（名字 + description 要让模型知道"何时用、怎么用"——工具发现靠描述）；③ **入参明确**（JSON Schema 定义，少而精）；④ **幂等安全**（写操作幂等，危险操作有审批）；⑤ **失败可解释**（结构化错误）。

**哪些能力适合抽象成 Tool**：**与 LLM 世界隔离的确定性能力**——外部数据获取（搜索/DB 查询）、系统操作（文件/代码执行）、业务动作（下单/审批——需权限）、计算/转换（确定性逻辑）。判断标准：模型不会/不该自己算的（实时数据、系统副作用），就抽成 Tool。

**参数 schema 设计**：JSON Schema——`type`、`properties`（每个参数：type/description/enum/required）、`required` 列表；**描述要写给模型看**（"query：搜索关键词，必填"）；避免过深嵌套（模型难生成）；枚举约束取值（防自由发挥）。Aether 的 MCP 工具定义（Spring AI `@Tool` 注解 + 参数类）自动生成 schema。

**错误码怎么返回给模型（重点）**：**结构化错误对象**而非异常/堆栈——`{"status":"failed","error_type":"Timeout|InvalidParam|NotFound|PermissionDenied|ServerError","error_message":"人类可读描述","retry_after":5,"suggestion":"模型可执行的下一步"}`——让模型**能决策**（重试/换参/换工具/告知用户），且 `error_type` 可枚举便于模型分支处理；Aether 的 `ToolResult` 带 `toolError` 标记。

**常见误区**：工具描述敷衍（模型不知道怎么用，调用率低/乱调）；schema 无枚举无必填（模型乱传参）；错误返回堆栈（模型无法决策）；工具做了太多事（模型难描述调用意图）。

## 119. 模型输出格式不符合要求（比如混入 Markdown）怎么约束？（题单模块三）

【技术点】输出约束

【答案】

**多层约束（组合拳）**：
1. **提示层**：明确输出格式要求（"只输出 JSON，不要 markdown 代码块包裹"）+ few-shot 示例（给标准输出样例）；
2. **结构化输出（最强）**：**Function Calling / Structured Output**（`response_format: {type:"json_object"}` / JSON Schema 约束）——让模型按 schema 生成，从机制上保证结构（Spring AI 的 `ChatModel` 支持结构化输出请求）；
3. **解析层容错**：后端解析 JSON 失败时：① 提取（剥掉 ```json 包裹/截取首个 { 到最后一个 }）；② 失败则**回填模型**（"输出格式不符合要求：期望 JSON，实际为 X，请重新输出"——让模型自己修正）；
4. **校验 + 重试**：`ToolValidation` 校验必填字段，缺则重试（限次，防死循环）；
5. **兜底**：多次失败转人工/降级。

**Aether 实例**：`{outputKey}` 占位符约束跨 Agent 输出（下游 Agent 按结构消费）；工具参数走 JSON Schema 校验；事件序列化（`serializeEvent`）输出固定结构 JSON。

**常见误区**：只靠提示词（模型不可靠——用结构化输出）；解析失败直接抛错（要"回填修正"闭环）；重试无上限（死循环）。

## 120. Memory 有哪些类型，分别怎么实现？（题单模块四，见第 59/81 题）短期记忆和长期记忆在工程中怎么存储、过期、去重？（模块四）

【技术点】记忆工程（见第 59/81 题，补存储/过期/去重）

【答案】

**类型**：① 短期（工作记忆/会话上下文）；② 长期（跨会话画像/知识）；③（进阶）情景记忆（历史事件）、语义记忆（事实知识）、程序记忆（技能）——面试可提但工程主要前两类。

**工程实现（存储/过期/去重）**：
- **短期**：存会话上下文（内存 `ContextManager` + 持久化 `PgSessionRepository`/`RedisSessionRepository`）；**过期** = 会话生命周期（TTL 或软删除 `ARCHIVED`）；**去重** = 相邻重复消息合并；
- **长期**：存 `MemoryStore` + `PgvectorVectorStore`（embedding + 向量检索）；**过期** = **TTL / 时间衰减**（旧记忆权重降低，`MemorySearchResult` 带权重）+ 用户画像更新覆盖；**去重** = 写入时相似度检测（新记忆与旧记忆相似度高 → **合并/覆盖**而非追加，防矛盾记忆并存）；
- **写入筛选**：不是所有话都值得进长期记忆——`SessionMemoryExtractor` 抽取 + 重要性判断（防噪音污染检索）。

**常见误区**：长期记忆全量存（噪音）；无过期机制（旧画像一直生效）；去重只做"完全重复"（语义相似也要合并——相似度阈值）。

## 121. 多 Agent 协作系统怎么设计？通信机制和状态共享怎么实现？编排层怎么设计，中心化还是去中心化？（题单模块五）

【技术点】多 Agent 系统设计（阿里高频）

【答案】

**（结合 Aether 项目作答）**

**整体设计**：**中心化编排**（Aether 选择）——`SubAgentOrchestrator` 作为中央协调者：接收任务 → 拆解 → 委派（`SubAgentDelegationTool`）→ 监控（`OrchestrationController` 查询活跃子 Agent/中断）→ 聚合（`ChildResultAggregator`）→ 交付。

**通信机制**：
- **同进程方法调用（主）**：共享 `AgentRegistry`/`CompletionBus`，子 Agent 结果通过 `ChildResultAggregator`/`DelegationCompletion` 回调返回；`{outputKey}` 占位符（`InstructionResolver`）实现跨 Agent 输出引用；
- **异步委派**：`AsyncDelegationService` + `PgAsyncDelegationStore`（持久化委派任务）+ `LeaseManager`（租约）+ `StaleDelegationScanner`（超时兜底）——任务不丢、可恢复；
- **跨进程（扩展）**：MQ/HTTP；标准化协议 A2A。

**状态共享**：① **会话级状态**：共享会话（`sessionId`）+ 持久化（PG）；② **Agent 级状态**：独立 `AgentState`（隔离，子 Agent 挂起不影响主）；③ **任务级状态**：委派记录（`DelegationRecord`）+ 结果（`DelegationCompletion`）；④ **跨实例**：Redis/PG 共享 + 检查点（`GitShadowCheckpointStore`）。

**中心化 vs 去中心化（重点，字节追问过）**：
- **中心化**（Aether 选择）：一个编排者统一下发/回收——**可控**（任务不丢、可审计、可熔断）、**上下文聚焦**（编排者只持有概要）、实现简单；缺点：编排者单点（要 HA + 降级）；
- **去中心化**：Agent 间直接协商（A2A 式）——**灵活/扩展性好**，但**不可控**（任务归属、终止条件、成本、审计都是难题），生产中极少纯去中心化；
- **结论**：生产选**中心化编排 + 节点自治**（编排者只管"派活 + 收结果 + 兜底"，子 Agent 在节点内自主执行）——Aether 正是此形态。

**常见误区**：吹去中心化（面试官要的是权衡与可控性）；状态共享做成"全共享"（上下文/状态必须隔离）；编排者无兜底（子 Agent 挂了自己不知道——租约 + 超时扫描）。

## 122. 完整介绍 RAG 流程（题单模块六）

【技术点】RAG（必考）

【答案】

**RAG（Retrieval-Augmented Generation，检索增强生成）完整链路**：

**① 离线入库（索引阶段）**：
1. **文档解析**：多种格式（PDF/Word/HTML）→ 纯文本；
2. **分块（Chunking）**：按结构/语义切块（见 123 题）；
3. **向量化（Embedding）**：每块生成向量（embedding 模型）；
4. **入库**：向量 + 原文存向量库（Aether 的 `PgvectorVectorStore`，pgvector 支持 HNSW/IVFFlat 索引）；
5. **（可选）倒排索引**：关键词检索（BM25）备用。

**② 在线检索（查询阶段）**：
1. **查询向量化**：用户问题 → embedding；
2. **向量检索**：相似度 Top-K（`max-results=10`）；
3. **（可选）混合检索**：向量 + 关键词（BM25）融合（权重可配，RRF 融合）；
4. **（可选）Rerank**：对 Top-K 用 Rerank 模型精排（提升相关性）；
5. **重排与过滤**：去重、相关性阈值过滤。

**③ 生成阶段**：把检索片段 + 用户问题拼进 prompt → LLM 生成（**要求模型基于检索内容回答并引用来源**）→ 输出（带引用标注）→（可选）幻觉校验。

**Aether 关联**：记忆召回（`RecallFlow`）本质是轻量 RAG（用户画像/历史语义召回）；`CodeExplorer`/`IdentifierRegistry` 是代码库 RAG（按标识符精准检索）。

**常见误区**：只讲"检索+生成"两段（离线索引也要讲）；忽略 rerank 与混合检索（这是优化点也是高频追问）；检索质量差怪 LLM（先查 chunk/embedding/检索策略）。

## 123. chunk 策略怎么选，为什么？（题单模块六）检索 badcase 长什么样，怎么修的？（模块六）

【技术点】RAG 分块 / 检索优化

【答案】

**chunk 策略（怎么选 + 为什么）**：
1. **固定大小 + 重叠（baseline）**：如 500 token + 50 overlap——实现简单，但会**切断语义单元**（一句话/一个代码函数被拆两半）；
2. **结构感知分块（推荐）**：按 Markdown 标题/段落/代码函数边界切（**Aether 的 skills 文档/文档处理即结构感知**）——块与语义单元对齐，检索命中率更高；
3. **语义分块**：按语义相似度聚类边界（Embedding 判断句子间相似度，低相似处切）——更准但计算开销大；
4. **父子分块**：父块（大上下文）+ 子块（小粒度检索），命中子块后带父块上下文给模型——兼顾精准与上下文完整；
5. **选型依据（为什么）**：chunk 大小影响①召回粒度（太小→上下文不完整，太大→噪音多+token 贵）、②检索精度（语义被切断→向量相似度下降）、③成本（块数×embedding）；**要按文档类型实验调参**（代码 vs 长文 vs 对话记录不同）。

**检索 badcase 与修复（重点，要有真实感）**：
- **badcase 类型**：① 语义切断（答案一半在 A 块一半在 B 块）→ 修复：结构分块 + 重叠/父子块；② 向量召回不准（近义词/实体名不一致）→ 修复：混合检索（BM25 补关键词）+ Rerank；③ 无关片段混入（Top-K 噪音）→ 修复：相关性阈值 + Rerank 精排；④ 查询表述与文档不一致（"怎么部署" vs 文档标题"安装步骤"）→ 修复：查询改写（LLM 扩展同义词）+ 混合检索；⑤ 块顺序错乱 → 修复：保留原文顺序/元数据。
- **修复流程**：badcase 收集（线上失败任务/人工标注）→ 归因（切块？召回？重排？生成？）→ 针对性修复 → 评测集回归。

**常见误区**：chunk 大小拍脑袋（要实验）；只调 chunk 不查 embedding/检索（badcase 归因错）；无 badcase 回流机制（修完不知道有没有效）。

## 124. trace 怎么做？线上出问题怎么定位是哪一跳挂的？（题单模块七，见第 86 题完整版）

【技术点】可观测（见第 86 题）

【答案】**（详见第 86 题）**：MDC traceId（`MdcFilter`）贯穿全链路；结构化 JSON 日志 + RuntimeEvent 事件时间线（`textDelta/toolCall/toolResult/error`）+ 图级 trace 落盘（`aether.graph.trace.persistence`）；按 traceId 串联 → 定位失败跳（模型/工具/上下文/成本/权限）→ 修复 + badcase 沉淀。

## 125. 你项目里最难的一个 case 是什么，怎么解决的？这个方案为什么不用 XX（竞品方案），当时怎么权衡的？（题单模块八）

【技术点】项目深挖 / 方案权衡

【答案】

**（结合 Aether 项目，用"案例 + 权衡"结构答）**

**最难 case：多 Agent 异步委派的任务丢失与状态不一致**
- 现象：子 Agent 异步执行（长时间任务），主 Agent 重启/会话中断后，委派状态丢失或悬挂（子 Agent 在跑但主 Agent 不知道）；
- 方案演进：纯内存委派（丢失）→ `PgAsyncDelegationStore` 持久化委派记录 + `LeaseManager` 租约（写任务时获取租约，过期可回收）+ `StaleDelegationScanner` 定时扫描悬挂委派（`stale-timeout=PT10M`）兜底回收 → 结果 `DelegationCompletion` 持久化 + `CompletionBus` 通知；
- **为什么不用 XX（竞品/替代方案权衡）**：① 为什么不直接 MQ？——MQ 只保证消息投递，但**状态查询/恢复/审计**要额外建（Aether 已有 PG，用 PG 表 + 轮询扫描更简单可靠，且查询委派历史方便）；② 为什么不用分布式任务框架（XXL-Job/Quartz）？——引入重依赖，租约 + 扫描已覆盖需求（简单优先原则）；③ 权衡点：PG 轮询有延迟（秒级）与 DB 压力（批量游标扫描缓解）——换来的是无新组件、状态可审计、可恢复。

**答题要点**：案例要具体（现象 → 分析 → 方案 → 效果）；"为什么不用 XX"要说清**替代方案的真实代价**（不是"不好"而是"在这个约束下不划算"）；体现"简单优先 + 场景驱动选型"。

**常见误区**：编案例（被追问细节穿帮）；只说方案不说权衡（面试官要决策过程）；"因为大家都这么用"（没有自己的判断）。

---
