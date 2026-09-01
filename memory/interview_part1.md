# 大厂面试题整理与答案详解（完整版）

> 整理说明：本文档按原始面经顺序，将各公司面试题逐条整理为统一格式，并为每道题补全答案（核心概念定义、关键原理说明、代码示例或实际应用场景、常见误区）。凡涉及"项目"或 Agent 架构的题目，均结合本地 **Aether 项目**（Java 17 + Spring Boot 3 + Spring AI 的 DDD 六模块多 Agent 后端）的真实代码与配置作答。

---

# 第一部分：京东科技 - 一面凉经（30min）

## 1. 选择一个比较熟悉的项目，在项目过程中遇到哪些困难，主要做了哪些功能，哪些模块，每个功能模块的业务流程是怎么样的？

【技术点】项目深挖 / 架构设计 / 业务流程表达

【答案】

**（结合 Aether 项目作答）**

**项目概况**：Aether 是一个生产级多 Agent AI 运行时后端，采用 DDD 六边形（Ports & Adapters）分层 + 模块化单体架构，六模块 Maven 工程（`groupId=cn.zcj.aether`，入口 `cn.zcj.aether.Application`，端口 8091）。核心设计思想是"配置即装配"：通过 `agent/agents.yml` 等 YAML 声明智能体的模型、工具、提示词与工作流，应用启动时由 `AiAgentAutoConfig` 监听 `ApplicationReadyEvent`，将配置翻译为可运行的 Agent 实例并注册到 `AgentRegistry`，运行时按 `agentId` 路由执行。

**六个模块及其业务流程**：
1. **aether-types（基础类型层）**：枚举、常量、异常（`ResponseCode`、`UserRole`、`AppException`）。无内部依赖。
2. **aether-api（端口接口 + DTO）**：`IAgentService` 端口接口、`Response<T>` 统一响应（`code/info/data`）。
3. **aether-domain（领域层，核心）**：Agent 引擎、模型 SPI、记忆、工具、会话、子 Agent 编排。核心类如 `ReActAgent`（标准 ReAct 循环，`MAX_TURNS=100`）、`PlanActAgent`、`GraphExecutor`（loop/parallel/sequential 三种工作流）、`ChatService`、`ArmoryService`、`ContextManager`、`DefaultMemoryFacade`。
4. **aether-infrastructure（适配器层）**：PostgreSQL/Redis 持久化（`PgSessionRepository`/`RedisSessionRepository`）、pgvector 向量存储（`PgvectorVectorStore`）、凭据轮换（`RotatingCredentialPool`）、检查点 Git 影子仓（`GitShadowCheckpointStore`）。
5. **aether-trigger（HTTP 触发层）**：`AuthController`、`AgentServiceController`、`OrchestrationController`、`McpRefreshController`，以及 `JwtAuthFilter`、`RateLimitFilter`、`MdcFilter`、`SecurityHeadersFilter` 等 Filter。
6. **aether-app（应用装配层，可执行）**：Spring Security 配置、`ThreadPoolConfig`、`DataInitializer`、`application-dev.yml` 等，含 `agent/*.yml` 智能体配置。

**核心业务流程**：用户登录（`/api/v1/auth/login` 签发 JWT）→ 创建会话（`/api/v1/create_session`）→ 发起对话（`/api/v1/chat` 同步 / `/api/v1/chat_stream` 流式 SSE）→ `ChatService.handleMessageStream` 根据 `agentId` 从 `AgentRegistry` 取 Agent → `ReActAgent.execute` 进入思考-行动循环，通过 `ModelInvoker` 调模型，通过 `ToolExecutor` 执行工具（MCP/技能书）→ 事件以 `Flowable<RuntimeEvent>` 流式输出（`textDelta`/`toolCall`/`toolResult`/`permissionAsking`/`done` 等）→ 每 N 轮（`checkpointInterval` 默认 5）自动保存检查点 → 会话消息持久化到 PostgreSQL。

**遇到的困难**（可挑 2-3 个讲）：
- **模型容错与凭据轮换**：单一模型 API 易超时/限流，且 key 有配额。方案：`ResilientChatModelExecutor`（错误分类 + 退避重试 + fallback 链）+ `RotatingCredentialPool` + `DefaultModelErrorClassifier` + `TokenBudget`（`maxCostUsd` 成本熔断）。
- **上下文爆窗**：长对话 token 超限。方案：`ContextManager` 提供微压缩、`CompactionPipeline` 多步压缩管道，产出 `compactBoundary` 事件。
- **危险工具调用**：模型可能触发危险操作。方案：`PermissionEngine` + 规则集（`DangerousToolRule`、`InjectionGuardRule`、`SensitiveArgMaskRule`），工具调用挂起为 `PAUSED` 状态并发出 `permissionAsking` 事件，等用户通过 `/api/v1/confirm` 回执后恢复。

【常见误区】把"用了什么中间件"当项目亮点，讲不出业务闭环；建议按"业务场景 → 模块 → 类 → 流程 → 难点 → 方案 → 效果"的结构讲，每个决策都要能回答"为什么"。

## 2. 你提到用了本地事务表来做分布式事务，本地事务表如果说消息比较多、交易量比较大，你该怎么处理呢？

【技术点】分布式事务 / 本地消息表 / 高并发削峰

【答案】

**核心概念**：本地消息表（Local Message Table）是"最终一致性"方案：本地事务中同时写业务表 + 消息表，通过异步任务扫描消息表把消息可靠投递到 MQ/下游，投递成功后标记消息为已发送。核心是利用"本地事务保证业务与消息同生共死"。

**关键原理**：
- 写库（业务表、消息表）在同一本地事务 → 要么都成功要么都失败；
- 后台线程/定时任务轮询未发送消息（状态 `pending`）→ 发 MQ → 收到 ack 后把状态改为 `sent`；
- 下游消费成功后回调查询/对账，最终一致。

**交易量大的处理（重点）**：
1. **分库分表**：消息表按 `user_id` 或 `order_id` 分片，避免单表膨胀与锁竞争；
2. **批量扫描 + 游标分页**：轮询不要 `LIMIT 100 OFFSET n`（深分页慢），用 `id > last_id ORDER BY id LIMIT 100` 游标方式；
3. **多消费者并发扫描**：多个 worker 按分片号各扫各的，避免重复投递（投递带幂等键）；
4. **MQ 削峰**：投递到 MQ 后由消费方按自身能力消费，下游不需要同步等待；
5. **对账 + 定时补偿**：加"超时未确认"的任务（如每 5 分钟扫一次 `create_time < now - 5min AND status=pending`）重发，配合死信队列处理一直失败的记录；
6. **消息去重**：消息表主键即业务幂等键（如 `order_id`），消费者也按幂等键去重。

**常见误区**：把"本地消息表"等同于"分布式事务的万能解"。它只保证最终一致性，不保证实时一致；且重试要幂等（消息可能重复投递）。若对一致性要求高（强一致），需改用 2PC/Seata AT 或 TCC。另外，轮询扫描在高并发下会成为数据库压力点，必须用游标 + 批量 + 分片，而不是简单全表扫。

## 3. 你知道 RabbitMQ 的集群部署原理吗？

【技术点】RabbitMQ 集群 / 高可用

【答案】

**核心概念**：RabbitMQ 集群把多个节点组成一个逻辑 Broker，共享元数据（交换机、队列元数据、绑定关系、权限、vhost），但**队列内容（消息）默认只在声明它的节点上**（单副本），其他节点只持有队列元数据。

**关键原理**：
- **普通集群（无镜像）**：消息路由时若生产者连的是 A 节点而队列在 B 节点，A 会转发给 B，但消息只有一份；若 B 宕机，B 上的队列及其消息丢失（元数据在，可恢复声明，消息没了）。适合测试或对消息不敏感场景。
- **镜像队列（Mirrored Queue）**：把主队列镜像到其他节点，写操作同步到所有镜像（`ha-mode: all` 或按 `ha-policy`），主节点宕机后镜像自动提升为主。牺牲部分写入性能换高可用。老版本方案，现在推荐 **Quorum Queue**（基于 Raft 的仲裁队列，数据多副本强一致，官方主推）。
- **联邦（Federation）/ 铲子（Shovel）**：跨机房跨集群的消息复制方案。

**实际部署形态**：通常 3 节点集群 + Keepalived 或负载均衡器（HAProxy）对外；客户端用 AMQP 连接多个节点做故障转移。

**常见误区**：以为"RabbitMQ 集群 = 消息自动多副本"。默认普通集群消息单副本，节点挂了消息就丢；要 HA 必须配镜像队列或仲裁队列。另外镜像队列所有节点都存全量消息，集群越大复制开销越大，不要盲目堆节点。

## 4. 谈谈对 ThreadLocal 的理解。4.1 Key 是弱引用、Value 是强引用，怎么说 Value 被强引用引用不能被回收？

【技术点】Java 并发 / ThreadLocal / 内存泄漏

【答案】

**核心概念**：`ThreadLocal` 为每个线程维护一份独立的变量副本，实现线程隔离。其内部结构：每个 `Thread` 持有一个 `ThreadLocalMap`，key 是 `ThreadLocal` 实例（**弱引用**），value 是用户存入的对象（**强引用**）。

**关键原理（4.1 为什么 Value 不能被回收）**：`ThreadLocalMap.Entry` 继承 `WeakReference<ThreadLocal>`，key 是弱引用——当外部不再引用 `ThreadLocal` 对象时，GC 会回收 key（置为 `null`）。但 **value 是强引用，被 Entry 直接持有**；只要线程存活（比如线程池中的线程），`ThreadLocalMap` 及其 Entry 就存活，value 就永远被强引用链（Thread → ThreadLocalMap → Entry → value）引用，无法被回收 → **内存泄漏**。这就是"Key 是弱引用，Value 是强引用"导致的问题：key 没了，value 却还在。

**解决方式**：使用后必须 `remove()`；`ThreadLocalMap` 在 `set/get` 时也会顺带清理 key 为 null 的脏 Entry（启发式清理），但不能保证及时。

**实际应用场景**：
- 传递 traceId/MDC（Aether 项目 `MdcFilter` 用 MDC，原理相同）；
- `SimpleDateFormat` 线程安全替代；
- Spring 的 `RequestContextHolder`、`TransactionSynchronizationManager` 都是 ThreadLocal 实现。

**常见误区**：
1. 以为"弱引用 = 内存安全"，其实 Value 强引用才是泄漏根源；
2. 在**线程池**里用 ThreadLocal 而不 remove：线程复用导致"脏数据"串到下一个任务（如登录用户信息串号），同时造成泄漏；
3. 用 `ThreadLocal` 传参当全局变量用，难排查难维护。

## 5. 如何做到用户下次登录免登录？5.1 RefreshToken 是怎么续期的？5.2 你的 token 保存在 Redis 里面，大量用户的 token 同时过期，导致系统问题，怎么处理？

【技术点】JWT / 双 Token 无感刷新 / Redis 缓存击穿

【答案】

**核心概念**：免登录 = 前端持有长期有效的凭证，到期前"无感刷新"。主流方案是**双 Token**：Access Token（短效，15 分钟级）+ Refresh Token（长效，7 天级）。Aether 项目即采用此设计（`aether.security.jwt.access-token-expiration=900000`，`refresh-token-expiration=604800000`，jjwt 0.12.5 HMAC-SHA256，无状态会话）。

**关键原理**：
- 登录成功后同时签发 Access Token 与 Refresh Token，Access Token 随请求头携带；
- 前端拦截 401（或主动在过期前）→ 调 `/api/v1/auth/refresh` 用 Refresh Token 换新 Access Token；
- **5.1 续期方式**：Aether 中 Refresh Token 存库（`t_refresh_token` 表，`RefreshTokenRepository`），刷新时**轮转**：校验 Refresh Token 有效性（未过期、未吊销）→ 吊销旧 Refresh Token（标记 used/revoked）→ 签发新的 Access Token + 新的 Refresh Token。轮转的好处是 Refresh Token 被窃取时攻击面小、可检测重放。

**5.2 大量 token 同时过期的处理**：
1. **合理设计过期时间**：Access Token 与 Refresh Token 的失效时间错开，并加随机抖动（`exp = base + random(0, 60s)`），避免"同时刻雪崩"；
2. **主动续期代替到期续期**：滑动过期——用户活跃时每次请求自动续期（sliding expiration），而不是固定 7 天一刀切；
3. **Redis 存储做懒加载 + 预热**：如果 Access Token 的校验依赖 Redis（黑名单/白名单），热点 token 会瞬间打到 Redis——用本地缓存（Caffeine）缓存校验结果 + 布隆过滤器拦掉不存在的 token；
4. **降级策略**：校验 Redis 故障时降级为 JWT 签名自校验（Aether 的 JWT 本身就是无状态验签，不强依赖 Redis）；
5. **批量续期**：定时任务在高峰期前批量刷新快过期的 token，或者服务端在请求时自动判断"剩余有效期 < 阈值"就顺带下发新 token。

**常见误区**：把 Access Token 有效期设很长（如 7 天）来"省事"——泄露风险大且无法及时吊销；续期接口无限次使用旧 Refresh Token——必须轮转或加使用次数限制；token 校验完全依赖 Redis 且不做缓存——Redis 抖动/雪崩时全站 401。

## 6. 现在有 ABCD 四个线程，如何让 A 线程先打印，然后 BC 打印，D 再打印？

【技术点】Java 并发 / 线程协作 / 顺序控制

【答案】

**核心概念**：多线程按指定顺序执行，本质是"依赖控制"：B/C 依赖 A 完成，D 依赖 B/C 完成。常用四种手段：

**方案一：`CountDownLatch`（推荐，B/C 并发，D 聚合）**
```java
CountDownLatch aDone = new CountDownLatch(1);   // 等 A
CountDownLatch bcDone = new CountDownLatch(2);  // 等 B 和 C
// A: 打印后 aDone.countDown();
// B/C: aDone.await(); 打印后 bcDone.countDown();
// D: bcDone.await(); 打印
```

**方案二：`CompletableFuture` 编排（最优雅）**
```java
CompletableFuture<Void> a = CompletableFuture.runAsync(() -> print("A"));
CompletableFuture<Void> bc = a.thenAccept(x -> {
    List<CompletableFuture<Void>> list = List.of(
        CompletableFuture.runAsync(() -> print("B")),
        CompletableFuture.runAsync(() -> print("C")));
    CompletableFuture.allOf(list.toArray(new CompletableFuture[0])).join();
});
bc.thenRun(() -> print("D"));
```

**方案三：`CyclicBarrier` / 锁 + 条件变量**（用 `ReentrantLock` + `Condition`，通过状态变量控制，适合严格交替多次的场景）；**方案四：`join()`**（`b.join(); c.join(); d.start()`，简单但串行化程度高，B、C 无法真正并行）。

**常见误区**：用 `sleep` 控制顺序（不可靠）；用 `volatile` 自旋忙等（浪费 CPU）；B、C 用 `join` 串行等待（失去并行性）。

## 7. 有没有去了解一些技术栈的原理？

【技术点】技术深广度 / 原理探究

【答案】

**答题思路**（这类开放题考的是"是否有源码级/原理级思考"，结合 Aether 项目讲最稳）：

1. **讲"看过源码/读过的原理"**：如 Spring 循环依赖三级缓存（`DefaultSingletonBeanRegistry` 的 `singletonFactories`/`earlySingletonObjects`）、Spring AI 的 `ChatModel` 抽象与 `ToolCallback` 机制、RxJava 的背压 `BackpressureStrategy`；
2. **讲"自己实现过底层"**：Aether 中自研了 `ResilientChatModelExecutor`（容错重试）而非直接依赖框架；`RotatingCredentialPool` 实现 key 轮换；`GraphExecutor` 实现三种工作流编排——这些都可以说"我研究过 xx 的原理，并自己实现/验证过类似机制"；
3. **讲"原理如何指导实践"**：比如因为理解 JWT 是无状态验签，所以 token 校验不依赖 Redis，避免中间件抖动导致全站失效；
4. 如果确实没深入过的技术，诚实说"这个我目前只停留在使用层面，但我可以讲讲我的理解"，然后从设计思想角度展开（内推人那条经验也印证了这一点）。

**常见误区**：背概念清单（"我会 Spring、Redis、MQ……"）而没有深度；或者只报技术名不给依据。面试官要的是"你在什么场景、基于什么原理、做了什么决策"。

## 8. TCP 粘包拆包问题

【技术点】网络编程 / Netty / TCP

【答案】

**核心概念**：TCP 是**面向字节流**的协议，没有"消息边界"。发送方多次 `write` 的数据可能被合并成一次发送（**粘包**），一次 `write` 的数据也可能被拆成多次发送（**拆包**）。根源是 Nagle 算法合并、接收缓冲区大小、MTU 限制。

**关键原理与解决方案**（四种主流方案）：
1. **固定长度**：每个消息定长（如 4 字节），不足补齐。简单但浪费带宽；
2. **分隔符**：消息末尾加 `\n` / `\r\n`（Netty 的 `LineBasedFrameDecoder`、`DelimiterBasedFrameDecoder`）。适合文本协议（如 Redis RESP、HTTP 老版本）；缺点：内容不能含分隔符，需转义；
3. **长度字段 + 消息体（主流）**：消息头固定 N 字节存消息体长度（Netty `LengthFieldBasedFrameDecoder`）。如：2 字节长度 + payload；
4. **HTTP/2、Protobuf 变长编码**等更复杂的帧协议。

**应用场景**：Aether 的流式对话是 SSE（Server-Sent Events），每个事件以 `data: {...}\n\n` 空行分隔——本质就是用"分隔符"协议解决帧边界问题（`AgentServiceController.serializeEvent` 输出 `data:` 前缀 JSON）；MCP 的 Stdio 传输底层也是用类似帧协议（JSON-RPC + Content-Length 头）来界定消息边界。

**常见误区**：把粘包当成 TCP 的 bug（TCP 本来就无边界，是应用层协议的设计问题）；收到半包/多包时直接 `new String(bytes)` 解析（必须用解码器缓冲处理）；自定义协议不处理"读到了半个包"的残余数据。

## 9. 在一个 2G 内存 2 核的服务器上，有 100G 的文本，里面是一堆字符，找出里面所有的 helloworld，并且统计它们的次数

【技术点】海量数据处理 / 内存受限 / 流式处理

【答案】

**核心概念**：数据规模（100G）远超内存（2G），不能一次性 `readAllBytes` 或用 `HashMap` 装下全部 token；必须**流式读取 + 有限内存统计 + 分治**。

**思路**（重点讲清楚"内存约束下的工程取舍"）：
1. **流式读取**：`BufferedReader` / 内存映射（`FileChannel.map` 按 1GB 分块映射），逐块处理，单块进内存；
2. **滑动窗口匹配**：对每个块用"滑窗"（窗口大小 = 目标串长度 10）逐字符匹配 `helloworld`，注意**块与块交界处**——保留上一个块末尾 `len-1` 个字符作为缓冲区重叠，避免跨块漏匹配；
3. **统计**：由于只统计一种模式 `helloworld`，根本不需要 HashMap——只需一个 `long count` 计数器即可，100G 文本也不需要额外存储；
4. **更通用版本（统计所有单词次数）**：HashMap 存不下 → 分治：把文本按哈希（如 `hash(word) % 1000`）拆到 1000 个小文件，每份 100MB，分别统计后合并；
5. **多核利用**：2 核可以"生产者-消费者"：一个线程读块，两个线程并行匹配计数，最后合并。

**代码骨架**（滑动窗口统计单模式）：
```java
long count = 0;
byte[] pattern = "helloworld".getBytes();
int keep = pattern.length - 1;  // 跨块重叠
byte[] prevTail = new byte[0];
try (FileInputStream fis = new FileInputStream(path)) {
    byte[] buf = new byte[1 << 20]; // 1MB 块
    // 每块 = prevTail + 新读的 buf；匹配完保留末尾 keep 字节
}
```

**常见误区**：一上来就说"用 HashMap 统计"（内存爆）；不处理跨块边界导致漏计；直接 `readLine` 假设文本按行组织（题干只说"一堆字符"，可能没有换行，必须按字节滑窗）；把 100G 全 load 进内存（显然不可行）。

---
