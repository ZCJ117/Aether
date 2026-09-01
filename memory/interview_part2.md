# 第二部分：影石（Insta360）一面 & 二面凉经

## 一、Java 基础（一面）

### 10. 讲讲 Java：HashMap 是个啥？（1.1）

【技术点】Java 集合 / HashMap

【答案】

**核心概念**：`HashMap` 是基于**哈希表**的 K-V 容器，Java 8 起为"数组 + 链表 + 红黑树"结构：`Node<K,V>[] table`，通过 `key.hashCode()` 高低位扰动后 `(n-1) & hash` 定位桶下标。

**关键原理**：
- **插入**：计算桶位 → 空桶直接放；已有元素用 `equals` 判同（相同则覆盖 value，不同则链尾追加）；链表长度 ≥ 8 且数组长度 ≥ 64 时**树化为红黑树**（长度 < 6 退化为链表）；
- **扩容**：`size > threshold(= capacity * 0.75 loadFactor)` 时扩容为 2 倍，Java 8 优化为"原索引 或 原索引+旧容量"两条链拆分，避免 Java 7 的 `transfer` 头插法（并发下成环死循环）；
- **非线程安全**：并发写会丢数据/成环，需要 `ConcurrentHashMap` 或 `Collections.synchronizedMap`。

**实际应用**：本地缓存、去重、索引映射等一切 K-V 场景。

**常见误区**：认为容量必须手动设很大才好（应设 `initialCapacity = 元素数 / 0.75 + 1` 避免扩容）；以为 `hashCode` 相同就一定会冲突（桶位还取决于扰动和取模，`equals` 相等才必同桶）；把 `HashMap` 用在多线程环境（应改用 `ConcurrentHashMap`）。

### 11. 线程池的参数（1.2）与拒绝策略（1.3）

【技术点】Java 并发 / ThreadPoolExecutor

【答案】

**核心概念**：`ThreadPoolExecutor` 七个核心参数：
1. `corePoolSize` 核心线程数（常驻）；
2. `maximumPoolSize` 最大线程数；
3. `keepAliveTime` + `TimeUnit` 非核心线程空闲存活时间；
4. `workQueue` 任务队列（有界/无界）；
5. `threadFactory` 线程工厂（命名、daemon）；
6. `RejectedExecutionHandler` 拒绝策略。

**执行流程**（必背）：提交任务 → 核心线程数未满 → 创建核心线程执行；核心线程满 → 入队（**先进队列，不是先扩线程**）；队列满 → 创建非核心线程；线程数达 maximumPoolSize 且队列满 → 触发拒绝策略。

**四种拒绝策略（1.3）**：
- `AbortPolicy`（默认）：抛 `RejectedExecutionException`；
- `CallerRunsPolicy`：**调用者线程自己执行**（慢下来，天然背压，适合非核心任务）；
- `DiscardPolicy`：静默丢弃；
- `DiscardOldestPolicy`：丢弃队头最老任务再提交。

**Aether 项目实例**：`ThreadPoolConfig` 配置 `core-pool-size=20 / max-pool-size=50 / keep-alive-time=5000 / block-queue-size=5000 / policy=CallerRunsPolicy`；审计日志独立异步线程池 `auditExecutor`（core=2 / max=5 / queue=100）。选择 CallerRunsPolicy 的考量：Agent 对话任务不能丢，宁可让调用线程执行降速，也不静默丢任务。

**常见误区**：把"队列满才扩线程"记成"线程满才入队"；业务线程池与框架线程池混用导致资源争抢（应给线程池命名，如 Aether 的 `Aether_HikariCP`、`auditExecutor`）；无界队列 + 无限增长线程数导致 OOM；拒绝策略无监控（生产必须接告警指标）。

### 12. 如何创建多个线程？（1.4）Future 和 Callable 有什么区别？（1.5）

【技术点】Java 并发 / 线程创建 / Future

【答案】

**创建线程的几种方式**：
1. 继承 `Thread` 重写 `run()`（不推荐，单继承受限）；
2. 实现 `Runnable`（无返回值，不能抛受检异常）；
3. 实现 `Callable<T>`（**有返回值，可抛异常**）；
4. 线程池 `ExecutorService.submit(callable)`（生产首选，配合 `Future` 拿结果）；
5. `CompletableFuture`（Java 8+，异步编排，拿结果 + 链式回调）。

**Future 与 Callable 的区别（1.5）**：
- `Callable` 是"任务"定义：`V call() throws Exception`，有返回值、可抛异常；`Runnable` 是 `void run()`，无返回值；
- `Future` 是"结果容器"：`submit(Callable)` 返回 `Future<V>`，通过 `future.get()` **阻塞**获取结果，`get(timeout)` 带超时，`cancel()` 取消，`isDone()` 判断完成；
- `Runnable` + `Future` 也能用（`submit(Runnable)` 返回 `Future<?>`，get 返回 null），但只有 `Callable` 能携带返回值。

**常见误区**：`future.get()` 不放超时导致线程永远阻塞；以为 `submit` 后任务立刻执行（只是入队）；用 `Runnable` 传结果要靠共享变量（竞态问题）——该用 `Callable`。

### 13. 如何阻挡多个线程完成任务后一起结束？（1.6）

【技术点】Java 并发 / 多任务聚合

【答案】

**核心概念**：等"所有线程都完成"再继续，是典型的**任务聚合（barrier）**场景，主流方案：
1. `CountDownLatch(1)` / `CountDownLatch(n)`：主线程 `await()`，n 个工作线程各 `countDown()`；
2. `ExecutorService` + `invokeAll(Collection<Callable>)`：批量提交，`invokeAll` 会等**全部完成**并返回 `List<Future>`（推荐，还能收结果）；
3. `CompletableFuture.allOf(...)`：多个 future 组合，全部完成后 `join()`；
4. `CyclicBarrier(n)`：n 个线程互相等齐后同时放行（可重复使用，适合分阶段任务）。

**Aether 项目实例**：`GraphExecutor` 执行 `parallel` 类型工作流（`AgentTypeEnum`）时，多个子 Agent 并行执行后聚合结果，正是用 `CompletableFuture.allOf` / `CountDownLatch` 这类机制等待全部完成；`ChildResultAggregator` 负责聚合子 Agent 结果。

**常见误区**：用 `thread.join()` 逐个等（无法并行收集，B、C 不能并发）；`CountDownLatch` 计数与线程数不一致导致永远 `await`（注意异常分支也要 `countDown`，用 try-finally）。

### 14. 反射是个啥？（1.7）

【技术点】Java 反射

【答案】

**核心概念**：反射（Reflection）允许程序在**运行时**动态获取类的结构（类名、字段、方法、注解、父类/接口），并动态调用方法、访问/修改字段、创建实例——把"编译期可知"变成"运行期可探"。

**关键原理**：类加载后 JVM 在方法区/元空间维护类的 `Class` 对象（`类名.class` / `instance.getClass()` / `Class.forName("...")`），反射就是围绕 `Class` 对象 + `Field`/`Method`/`Constructor` 进行操作；`Method.invoke` 底层经 `MethodAccessor`（本地实现 → 动态生成字节码 `GeneratedMethodAccessor` 提速）。

**应用场景（极广）**：
- Spring IoC/DI：`@Autowired` 按类型找 bean 反射注入字段；
- 框架配置绑定：Spring Boot `@ConfigurationProperties` 反射映射配置到 POJO；
- ORM：MyBatis 把 ResultSet 反射映射为实体；
- 动态代理：JDK 动态代理 `Proxy.newProxyInstance` 本质就是运行时生成代理类（Spring AOP 用它）；
- Aether 项目：`AiAgentAutoConfigProperties` 绑定 `ai.agent.config.*` YAML、`ObjectMapper` 反序列化、策略树节点工厂等大量使用反射与类型推断。

**常见误区**：以为反射性能一定慢（有 JIT 优化，常规调用量可接受，但热路径避免反射）；`getDeclaredField` 访问私有字段需要 `setAccessible(true)`（Java 9+ 模块化下还可能 `InaccessibleObjectException`）；反射破坏封装，滥用会导致代码难维护、难静态分析。

### 15. 单例模式怎么创建？除了双重检查锁和静态内部类，还有什么方法？（1.8/1.9）

【技术点】设计模式 / 单例

【答案】

**核心概念**：保证一个类全局只有一个实例，并提供全局访问点。

**创建方式盘点**：
1. **饿汉式**：`private static final INSTANCE = new Xxx()` 类加载即创建（线程安全，但启动即占资源）；
2. **双重检查锁（DCL）**：`volatile` + 两次判空 + 同步块（线程安全，延迟加载）；
3. **静态内部类**：`private static class Holder { static final INSTANCE = new Xxx(); }`——`Holder` 只在 `getInstance()` 首次访问时加载（JVM 类加载天然线程安全 + 延迟加载，**推荐**）；
4. **枚举单例**（"还有什么方法"的标准答案）：`enum Singleton { INSTANCE }`——**天然防反射、防序列化破坏**（JVM 保证枚举实例唯一），Joshua Bloch 在《Effective Java》中强推；
5. 其他：`synchronized` 方法版（性能差）、`ThreadLocal` 版（每个线程一个实例，不是真正单例）。

**常见误区**：DCL 不写 `volatile`（指令重排导致拿到未初始化完的对象）；序列化/反序列化会创建新实例（需 `readResolve()`）；反射 `setAccessible(true)` + `newInstance()` 可破坏私有构造（枚举天然免疫，或构造器加防反射标志位）。

### 16. 反射能不能反射创建出双重检查锁和 JVM 静态内部类的那个单例？如果可以，那又如何保证你的单例呢？（1.10）

【技术点】Java 反射 / 单例防护

【答案】

**核心概念**：**能**。反射通过 `Class.forName("Xxx").getDeclaredConstructor().setAccessible(true).newInstance()` 可以绕过私有构造器，创建出**第二个实例**，从而破坏 DCL 和静态内部类单例（它们的构造器都是私有的，但反射能强制访问）。

**如何保证单例不被反射破坏**：
1. **枚举单例（根本解法）**：JVM 层面保证枚举实例唯一，`newInstance` 对枚举直接抛 `IllegalArgumentException`，反射无法创建第二个实例；
2. **构造器防御**：私有构造器里加标志位判断——
```java
private static boolean created = false;
private Singleton() {
    if (created) throw new IllegalStateException("Already instantiated");
    created = true;
}
```
（注意这个 flag 本身也可以用反射改，但已足够挡住常规破坏）；
3. **`readResolve()`**：防序列化创建新实例，返回唯一实例。

**常见误区**：以为"私有构造器 = 无法被反射创建"（`setAccessible(true)` 可绕过）；防御只做构造器判断而不做序列化防护；为了防反射把代码写得很绕——面试加分点是说出"枚举是唯一天然免疫反射和序列化的单例方案"。

---

## 二、JVM（一面）

### 17. 如何线上排查进程 OOM？（2.1）

【技术点】JVM 调优 / 线上问题排查

【答案】

**核心概念**：OOM（OutOfMemoryError）是 JVM 堆/元空间/直接内存耗尽抛出的错误。线上排查要"先留证据、再分析、后定位"。

**标准排查流程**：
1. **启动参数留证据**：`-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/dump/`，OOM 时自动 dump 堆；生产容器加 `-XX:+ExitOnOutOfMemoryError`（OOM 即退出让编排重启，避免半死状态）；
2. **看错误类型**（日志）：`Java heap space`（堆满，对象泄漏/内存不足）、`Metaspace`（类太多，常见于反射/动态代理/热部署）、`GC overhead limit exceeded`（GC 无效循环）、`Direct buffer memory`（堆外）；
3. **分析堆 dump**：`jmap -dump:format=b,file=x.hprof <pid>` 或取自动 dump，用 MAT / JProfiler 分析 **Dominator Tree（支配树）** 找大对象与引用链，定位泄漏对象属于哪个业务线程/类；
4. **排查工具组合**：`jps` 找进程 → `jstat -gcutil <pid> 1000` 看 GC 趋势 → `jmap -histo` 看对象数量 Top（快速定位泄漏类）→ `jstack` 看线程栈（配合找创建者）；
5. **结合监控**：看内存曲线是否持续上涨不回（泄漏）还是周期性冲顶（容量不足），分别走"修 bug"或"加内存/优化"两条路。

**Aether 项目实例**：日志为结构化 JSON（`logstash-logback-encoder` 输出到 `logs/aether-agent.json`），配合 `actuator/prometheus` 的 JVM 指标（`jvm_memory_used_bytes` 等）可先观察堆趋势，再针对性 dump。

**常见误区**：OOM 后直接重启进程（证据丢失，无法定位）；只看"堆内存最大 X"不分析引用链；把内存泄漏当容量问题盲目 `-Xmx` 调大（治标不治本）。

### 18. 堆里面打满了会有 OOM 吗？（2.2）堆会超过设置的大小吗？（2.3）

【技术点】JVM 堆内存

【答案】

**核心概念（2.2）**：堆打满**不一定会**立即抛 OOM——JVM 会先触发 GC（Minor GC → Major/Full GC）尝试回收；只有 **GC 后仍无法分配**（如连续 Full GC 后堆使用率依旧 100%，或 `-XX:+UseG1GC` 下堆满且 Mixed GC 无法满足分配）才抛 `OutOfMemoryError: Java heap space`。极端情况：GC 一直在做但每次都回收不了多少 → `GC overhead limit exceeded`（GC 时间占比 > 98% 且回收 < 2%）。

**核心概念（2.3）**：**堆不会超过 `-Xmx` 设置的大小**（这是 JVM 的硬上限）。`-Xmx` 是堆最大可扩展上限，`-Xms` 是初始大小（生产通常 `-Xms=-Xmx` 避免扩容抖动）；堆在 `-Xms ~ -Xmx` 之间动态伸缩。但注意：
- 堆外内存（Metaspace、直接内存 `DirectByteBuffer`、线程栈、JIT 代码缓存）**不受 `-Xmx` 约束**，OOM 可能来自这些区域；
- 若 `-Xmx` 设得大于容器/物理内存，进程会被 OS OOM Killer 杀掉（容器场景常见）。

**常见误区**：把堆 OOM 和进程被杀混为一谈（容器 OOM 是 cgroup 限制，日志里是 `Killed`，不是 JVM 抛异常）；以为 `-Xmx` 设置多大堆就有多大（要预留 Metaspace、线程栈、堆外）；`-Xms` 与 `-Xmx` 不一致时启动阶段频繁扩容影响性能。

### 19. 讲讲 G1 和 ZGC 的区别（2.4）、分别用在什么场景（2.5）、G1 好处是啥（2.6）

【技术点】JVM 垃圾回收器

【答案】

**核心概念**：
- **G1（Garbage First）**：JDK 9+ 默认。把堆划分为**多个等大 Region**（1-32MB），逻辑上分代（Eden/Survivor/Old 都是 Region 集合，物理上不连续）。回收时维护一个 **"回收价值优先"（Garbage First）** 的集合，优先回收**垃圾最多、停顿最短**的 Region；通过 `-XX:MaxGCPauseMillis`（默认 200ms）软目标控制停顿。混合回收（Mixed GC）同时回收年轻代 + 部分老年代 Region。附带 **RSet（Remembered Set）** 记录跨 Region 引用，减少扫描范围。
- **ZGC（JDK 15+ 正式）**：**不分代**（JDK 21 起支持分代 ZGC），目标是 **TB 级堆下停顿 < 1ms**。核心是**染色指针（Colored Pointer）+ 读屏障（Load Barrier）**：GC 并发标记、并发转移（对象移动与业务线程并行），通过读屏障在对象被访问时"自愈"修正引用，实现**几乎不 STW**；配合多映射内存视图（remap/mark）。

**区别要点**：G1 停顿在几十~几百 ms（与堆大小相关），适合 4GB~64GB 常规堆；ZGC 停顿 < 1ms（与堆大小基本无关），但**牺牲吞吐**（读屏障开销约 10%+），适合超大堆（百 GB~TB）、低延迟场景（金融交易、在线广告、大内存服务）。

**G1 好处（2.6）**：① 可预测停顿（软目标 + Region 局部回收，而不是 CMS 的全堆 Mark-Sweep）；② 吞吐与延迟平衡较好；③ 自动整理内存碎片（复制算法，无 CMS 的内存碎片问题）；④ 单堆大小可到几十 GB 仍可控（CMS 大堆容易长停顿/碎片）。

**Aether 项目实例**：`aether-app` 基于 Spring Boot 3，生产部署（`docker-compose-secure.yml`）建议 `-Xmx` 按 Agent 场景设 2-4G，配 G1（`-XX:+UseG1GC -XX:MaxGCPauseMillis=200`）；若未来承载大批量并发 Agent 会话可评估 ZGC。

**常见误区**：以为 G1 停顿是硬保证（`MaxGCPauseMillis` 只是软目标，Full GC 仍可能长停顿）；以为 ZGC 一定更好（吞吐降低 + 需要大内存才有收益）；把 G1 的 Region 当物理分代连续内存理解。

---

## 三、MySQL（一面）

### 20. 讲讲 B+ 树（3.1）

【技术点】MySQL 索引 / B+ 树

【答案】

**核心概念**：B+ 树是 MySQL InnoDB 存储引擎索引（主键索引/二级索引）的底层数据结构，是多路平衡搜索树：
- **非叶子节点只存键**（不存数据），每节点可容纳更多键 → **树更矮更宽**（3-4 层可支撑千万~亿级数据），减少磁盘 IO（一次 IO 读一个节点/页）；
- **叶子节点存全部数据**（聚簇索引叶子存整行数据，二级索引叶子存主键），且叶子节点**按序双向链表连接** → 范围查询、排序、分组都高效（顺序 IO）；
- 节点大小 = 一页（默认 16KB），充分利用局部性。

**为什么不用其他结构**（常被追问）：
- **二叉树/红黑树**：树高随数据量线性增长（千万级需要 20+ 层），磁盘 IO 次数太多；
- **哈希索引**：O(1) 等值查询快，但**无法范围查询、无法排序、无法最左前缀**，只适合等值场景（InnoDB 的哈希索引是自适应的，仅内存优化）；
- **B 树（非 +）**：非叶子也存数据，节点能存的键少，树更高，且叶子无链表，范围查询要回溯。

**应用场景**：Aether 项目使用 PostgreSQL 16（索引底层也是 B+ 树变体）；表如 `t_user`、`t_refresh_token`、`t_async_delegation` 都依赖主键/索引快速定位。

**常见误区**：说 B+ 树"磁盘 IO 少"是因为"节点大"（本质是矮树 + 顺序 IO）；混淆聚簇索引（叶子=整行）与二级索引（叶子=主键，需回表）。

### 21. 讲讲事务的特性（3.2）

【技术点】MySQL 事务 / ACID

【答案】

**核心概念**：ACID 四大特性：
- **A 原子性（Atomicity）**：事务内操作要么全成功要么全回滚——InnoDB 靠 **undo log** 实现（记录变更前镜像，回滚时反向执行）；
- **C 一致性（Consistency）**：事务前后数据满足完整性约束（业务层面保证，由 AID 支撑）；
- **I 隔离性（Isolation）**：并发事务互不干扰——靠**锁 + MVCC（多版本并发控制）**实现；
- **D 持久性（Durability）**：提交后数据不丢失——靠 **redo log**（WAL：先写日志再落数据页，崩溃恢复时重放）+ binlog。

**常见误区**：把"一致性"理解成数据库自动保证（实际要靠业务约束 + AID 协同）；混淆 undo log（逻辑日志，回滚）与 redo log（物理日志，崩溃恢复）；以为隔离性只靠锁（MVCC 才是主流读方案）。

### 22. 什么是聚簇索引、什么是非聚簇索引？（3.3）id 主键 + 电话号索引的查询分别碰到哪种？（3.4）

【技术点】MySQL 索引 / 回表

【答案】

**核心概念**：
- **聚簇索引（Clustered Index）**：**叶子节点直接存整行数据**。InnoDB 表必须有一个聚簇索引（默认主键，无主键则选唯一非空索引，再无则隐藏 rowid）。数据物理存储顺序与主键逻辑顺序一致；
- **非聚簇索引（二级索引/辅助索引）**：叶子节点存**索引键 + 主键值**，不存完整行。查询二级索引后还需拿主键回聚簇索引取整行 → **回表（回行）**。

**3.4 场景分析**：
- 建了主键 `id`、普通索引 `phone`：
  - `WHERE id = 1`：走**聚簇索引**，直接拿到整行，**无需回表**；
  - `WHERE phone = '138...'`：走**二级索引**（非聚簇），叶子拿到主键 id，再**回表**查整行 → 两次 B+ 树搜索；
  - 若查询列只有 `phone` 和 `id`（覆盖索引），`SELECT phone, id ... WHERE phone=...` 不需回表（**索引覆盖**）。

**常见误区**：以为"二级索引也能直接拿数据"；把"回表"当可选项——只要查询列在二级索引中没有覆盖就必须回表；二级索引的键也可以是多列（联合索引，遵循最左前缀）。

### 23. MySQL 有哪些锁？（3.5）有表锁吗？（3.8）行锁有哪些？（3.9）

【技术点】MySQL 锁机制

【答案】

**锁的分类**：
- **按粒度**：全局锁（`FLUSH TABLES WITH READ LOCK`）、表锁、行锁、间隙锁、临键锁、意向锁；
- **按模式**：共享锁（S，读锁）、排他锁（X，写锁）、意向共享锁（IS）、意向排他锁（IX）；
- **按算法**：记录锁（Record Lock）、间隙锁（Gap Lock）、临键锁（Next-Key Lock）。

**表锁（3.8）——重点纠正**：**InnoDB 有表锁**（`LOCK TABLES` 显式加；DDL 时也需元数据锁 MDL），但日常 DML 并发控制**主要靠行锁 + 意向锁**。面试官问"mysql 有表锁吗？你确认真的有表锁吗"——答：InnoDB 支持表锁（显式 `LOCK TABLES ... WRITE`、`FLUSH TABLES`、DDL 的 MDL 锁），但常规事务 DML 不加表锁，靠**行锁 + 意向锁**配合：执行 DML 前先加意向锁（IS/IX）到表上，意向锁是表级锁，用于表级操作（如 `ALTER TABLE`）快速判断"该表是否有行被锁"而不必逐行扫描。MyISAM 才只有表锁（这也是它被 InnoDB 取代的原因之一）。

**行锁（3.9）**：
- **Record Lock（记录锁）**：锁单条索引记录（对索引项加锁，不是对行）；
- **Gap Lock（间隙锁）**：锁索引记录之间的**间隙**（左开右开），防止幻读，只在 RR 隔离级别存在；
- **Next-Key Lock（临键锁）**：记录锁 + 间隙锁的组合（左开右闭区间），RR 下默认加此锁，既锁记录又锁区间防插入。

**常见误区**：以为 InnoDB 没有表锁（有，只是 DML 不用）；以为行锁是"锁在数据行上"（实际锁在**索引项**上——无索引的 update 会锁全表所有行，因为走全表扫描；所以"行锁 + 无索引 = 全表锁"）；间隙锁只在 RR 存在（RC 不防幻读不需要）。

### 24. 默认隔离级别是什么？（3.6）不同隔离级别有什么区别？（3.7）

【技术点】MySQL 事务隔离级别

【答案】

**核心概念**：MySQL InnoDB 默认隔离级别是 **REPEATABLE READ（可重复读，RR）**（PostgreSQL 默认 READ COMMITTED）。四种级别由低到高：
1. **READ UNCOMMITTED（读未提交）**：能读到别的事务未提交的数据 → **脏读**；
2. **READ COMMITTED（读已提交，RC）**：只能读已提交 → 无脏读，但**不可重复读**（同一查询两次结果不同，因为别人提交了）；
3. **REPEATABLE READ（可重复读，RR）**：事务内多次读结果一致（靠 MVCC 快照读）→ 无不可重复读，但理论上仍有**幻读**（插入新行）——InnoDB 用 **Next-Key Lock（临键锁）** 在 RR 下基本杜绝了幻读（快照读靠 MVCC，当前读靠临键锁）；
4. **SERIALIZABLE（串行化）**：完全串行，性能最差。

**MVCC 关键原理**：每行记录隐藏 `trx_id`（最近修改事务 id）+ `roll_pointer`（指向 undo log 版本链）；事务开启时生成 **ReadView**（活跃事务列表），快照读根据 ReadView 沿版本链找可见版本。RR 的 ReadView **事务内复用**（所以可重复读），RC 每条语句重新生成（所以不可重复读）。

**常见误区**：以为 RR 下完全无幻读（快照读无幻读，当前读 `SELECT ... FOR UPDATE` 靠临键锁防插入，严格意义上仍可通过特殊路径看到幻行）；把隔离级别与锁完全等同（读路径是 MVCC，写路径是锁，两者配合）；SERIALIZABLE 只是概念（实际几乎不用）。

### 25. 假设我是 select where id=1 精确查询，会上锁吗？会上啥锁？（3.10）update id=1 会上锁吗？（3.11）

【技术点】MySQL 锁 / 快照读 vs 当前读

【答案】

**核心概念（重要区分：快照读 vs 当前读）**：
- 普通 `SELECT ... WHERE id=1` 是**快照读**（MVCC），**不加任何锁**（不加 S 锁也不加 X 锁），直接读版本链中可见版本（除非 `id=1` 记录不存在且无索引等其他情况）；
- `SELECT ... FOR UPDATE` / `SELECT ... LOCK IN SHARE MODE` 是**当前读**：`FOR UPDATE` 加 **X 锁（排他锁）**，`LOCK IN SHARE MODE` 加 **S 锁（共享锁）**；在 RR 下还会加**临键锁**（记录锁 + 间隙锁）防止幻读。

**update（3.11）**：`UPDATE ... WHERE id=1` 是写操作（当前读 + 写），会对 `id=1` 这条记录加 **X 锁（记录锁）**，RR 下锁范围为临键锁区间；其他事务对该行的读（快照读不受影响，MVCC 读旧版本）与写（需等锁）受阻塞。

**常见误区**：以为普通 select 也加锁（不，快照读无锁，这也是 MVCC 高并发读的关键）；混淆 `FOR UPDATE` 与普通 select 的锁行为；忽略"id 无索引"时 update/当前读会全表扫描加锁（升级为全表锁）。

### 26. 我有 UID 3、5、7，我是 update where UID 小于五大于三，会上什么锁？三和五中间会不会上锁？能不能插入？（3.12）

【技术点】MySQL 间隙锁

【答案】

**核心概念**：`UPDATE ... WHERE UID > 3 AND UID < 5`，在 RR 隔离级别下，如果 `UID` 有索引且数据存在 UID=3、5、7：
- 条件区间是 `(3, 5)`，**区间内没有实际记录**，因此 InnoDB 加的是 **间隙锁（Gap Lock）**，锁定 `(3, 5)` 这个区间（不锁记录本身）；
- **3 和 5 之间会上锁（间隙锁）**——这是防幻读的关键：阻止其他事务在这个间隙**插入** UID=4 的记录；
- **能不能插入**：**不能**。在 `(3, 5)` 间隙内插入 UID=4 会**阻塞**，直到该事务提交/回滚释放间隙锁；插入 UID=2、6 等不在间隙内的不受影响；
- 补充：如果条件是 `UID < 5`，则会锁 `(-∞, 5)` 临键/间隙区间；若 `UID` **无索引**，全表扫描 → 全表所有行加锁（间隙锁覆盖整个范围），插入几乎全部被阻塞。

**常见误区**：以为"没有记录就不用锁"（间隙锁恰恰锁的是没有记录的区间）；以为间隙锁只锁等值条件（范围条件同样加）；忽略"无索引导致间隙锁范围失控"这个高频事故点；RC 隔离级别无间隙锁（所以能插入，但也因此有幻读）。

### 27. 你的 CDC 读的是 MySQL 的什么、PostgreSQL 的什么？（3.13）

【技术点】CDC / binlog / WAL

【答案】

**核心概念**：CDC（Change Data Capture，变更数据捕获）通过读取数据库**日志**捕获数据变更，而不是轮询表。
- **MySQL**：读 **binlog**（二进制日志，逻辑日志，记录变更事件，row 格式下记录每行前后镜像）。常用工具：Canal（伪装成 MySQL slave 拉取 binlog）、Debezium（将 binlog 转为 Kafka Connect 消息）；
- **PostgreSQL**：读 **WAL（Write-Ahead Log）** 或基于其逻辑复制的 **replication slot 逻辑解码（logical decoding）**——`pgoutput` / `wal2json` 插件把 WAL 解析为逻辑变更流。Aether 项目使用 PostgreSQL 16，若做数据变更同步可用 Debezium + `pgoutput` 插件订阅 `t_user`、`t_audit_log` 等表。

**关键区别**：MySQL binlog 有三种格式（statement/row/mixed），CDC 必须用 **row 格式**才能拿到完整前后镜像；PG 的逻辑复制只对"发布了发布（PUBLICATION）"的表生效，且需要 `wal_level=logical`。

**常见误区**：以为 CDC 是轮询表（那是 binlog 诞生前的旧方案，性能差）；MySQL CDC 用 statement 格式 binlog（拿不到行级数据）；PG 不开启 `wal_level=logical` 就调逻辑解码（会报错）。

---

## 四、Spring（一面）

### 28. IoC 是啥？（4.1）AOP 是啥？（4.2）AOP 在创建 bean 的哪个阶段创建？（4.3）

【技术点】Spring IoC / AOP

【答案】

**IoC（控制反转）核心概念**：对象创建与依赖关系的控制权从"程序代码手动 new"反转给**容器**（`BeanFactory`/`ApplicationContext`）。对象声明依赖（构造器/字段），容器负责实例化、装配（DI）、管理生命周期。Aether 项目六模块的所有服务（`ChatService`、`ArmoryService`、`AgentRegistry` 等）全部由 Spring 容器管理，模块间通过接口依赖，实现可替换性（Ports & Adapters）。

**AOP（面向切面编程）核心概念**：把横切关注点（日志、事务、安全、审计）从业务代码中抽离为**切面**，在运行时织入。核心术语：`@Aspect` 切面、`@Pointcut` 切点、`@Before/@After/@Around` 通知、JoinPoint 连接点。Aether 项目实例：`@Auditable` + `AuditAspect` 异步记录审计日志（操作类型见 `AuditAction`）到 `t_audit_log`；`@Transactional` 也是 AOP 实现的。

**AOP 在 bean 创建哪个阶段（4.3）**：在 **bean 初始化阶段（实例化之后、初始化前后）**。`AbstractAutowireCapableBeanFactory.doCreateBean` 流程：实例化 → 属性填充（DI）→ **Aware 回调 → `initializeBean`：`BeanPostProcessor` 后置处理器**（其中 `AbstractAutoProxyCreator.postProcessAfterInitialization` 检查切点，匹配则创建代理对象）→ 初始化完成后代理 bean 进入容器。即：**AOP 代理在"初始化完成后"由 BeanPostProcessor 织入**，所以循环依赖场景下三级缓存的"提前暴露"对象是未代理的原始对象（AOP 代理后置创建，这也是三级缓存设计的原因之一）。

**常见误区**：以为 AOP 在类加载时织入（Spring 是运行时 JDK 动态代理/CGLIB，AspectJ 才有编译期织入）；`this` 调用不走代理（自调用 @Transactional 失效，因为 `this` 是原始对象不是代理）；代理对象（JDK 动态代理按接口）类型转换问题（CGLIB 不要求接口）。

### 29. 你说三级缓存，为什么要设计三级缓存，三级缓存有没有问题？（4.4）

【技术点】Spring 循环依赖

【答案】

**核心概念**：Spring 通过**三级缓存**解决"单例 bean 的构造器/字段循环依赖"：
- 一级 `singletonObjects`：**成品**单例池（完全初始化好的 bean）；
- 二级 `earlySingletonObjects`：**提前暴露**的"半成品"（已实例化、未完成属性填充/初始化，可能是原始对象或代理）；
- 三级 `singletonFactories`：**单例工厂**（`ObjectFactory<?>`，延迟创建提前暴露对象的工厂）。

**为什么需要三级（不是两级）**：三级缓存存的是 `ObjectFactory` 而非直接对象，是为了**延迟 AOP 代理的创建**——`getEarlyBeanReference()` 在真正出现循环依赖时才提前生成代理；若没有循环依赖，bean 走正常流程在初始化后由 `BeanPostProcessor` 统一代理，**避免"提前创建代理 + 后来又代理一次"的双代理问题**。换句话说：三级缓存的本质是"**按需提前暴露，且暴露时能正确生成代理**"。

**三级缓存有没有问题（重点）**：
1. **只能解决"字段/Setter 注入"的循环依赖**，**构造器注入的循环依赖无法解决**（实例化阶段就需要对方，此时对方 bean 还没创建，报 `BeanCurrentlyInCreationException`）；
2. **代理时序问题**：提前暴露的对象若是 AOP 代理，`@Autowired` 注入的是代理，但若之后再经过 AOP 链处理可能产生两个代理（Spring 有 `getEarlyBeanReference` 缓存代理机制缓解）；
3. 依赖"提前暴露半成品"违背了"依赖完整 bean"的直觉，**可读性与排查成本高**；
4. 对非单例（prototype）bean 不适用，异步/多线程场景可能拿到半成品。

**常见误区**：以为三级缓存解决"所有"循环依赖（构造器注入不行、prototype 不行）；以为两级缓存够用（会破坏 AOP 代理的正确性）；循环依赖本身是设计缺陷，官方也不推荐依赖它（Spring Boot 2.6+ 默认禁止循环依赖，报错提示改为 true 才放开）。

### 30. @Transactional 的底层原理（4.5）

【技术点】Spring 事务

【答案】

**核心概念**：`@Transactional` 基于 **Spring AOP 代理 + 事务管理器（PlatformTransactionManager）** 实现声明式事务。

**关键原理（执行流程）**：
1. `@Transactional` 方法被调用时，实际执行的是**代理对象**的方法（JDK 动态代理/CGLIB）；
2. `TransactionInterceptor`（AOP 通知）拦截方法：
   - `TransactionAspectSupport.invokeWithinTransaction`：获取事务属性（隔离级别、传播行为、回滚规则）→ 从 `DataSourceTransactionManager` 拿连接、`setAutoCommit(false)`、开启事务；
   - 执行目标方法 → 正常则 **commit**（提交，`redo log`/`undo log` 保证持久性与原子性）；抛异常则 **rollback**（回滚）；
3. **回滚规则**：默认只回滚 **RuntimeException 和 Error**，`@Transactional(rollbackFor = Exception.class)` 才会回滚所有异常（**checked exception 默认不回滚**——经典考点）。

**常见误区（高频踩坑）**：
- **自调用失效**：同类内 `this.method()` 不走代理，事务不生效——需注入自身代理或拆类；
- **私有方法**：`@Transactional` 加在 private 方法上不生效（代理只能拦截 public）；
- **异常被 catch 吞掉**：事务感知不到异常，不回滚；
- **传播行为**：`REQUIRED`（默认，有则加入）、`REQUIRES_NEW`（挂起当前事务开新事务——日志、审计场景常用）、`NESTED`（嵌套保存点）；
- **事务 + 多线程**：事务与线程绑定（ThreadLocal 连接），子线程内新开连接不参与主事务；
- 只读事务 `readOnly=true` 优化（走快照读，PG 下也减少锁开销）。

### 31. 依赖注入有什么方式可以完成？（4.6）如果一个类没有被 Spring 管理，里面的注入还生效吗？有没有办法让他生效？（4.7）

【技术点】Spring DI

【答案】

**依赖注入方式（4.6）**：
1. **构造器注入（官方推荐）**：`@Autowired` 构造器或 Lombok `@RequiredArgsConstructor`——保证 final、不可变、易测试；
2. **Setter 注入**：`@Autowired` 加在 setter 上；
3. **字段注入**：`@Autowired` 直接注入字段（最简洁但不易测试、不推荐，循环依赖可掩盖）。
Aether 项目大量使用构造器注入（如 `ChatService` 注入 `AgentRegistry`、`GraphExecutor`、`MemoryStore`、`SessionRepository` 等）。

**不被 Spring 管理的类注入是否生效（4.7）**：**不生效**——`@Autowired` 依赖 Spring 容器在创建 bean 时处理，`new` 出来的对象没有这个环节，字段为 null。**让它生效的办法**：
1. **`@Configurable` + AspectJ 织入**：让"new 出来的对象"也支持依赖注入（需开启 `spring-aspects`，AOP 织入，较少用）；
2. **Spring 容器静态工具**：通过 `ApplicationContext.getBean()` 手动取（写一个 `SpringContextHolder implements ApplicationContextAware` 静态持有 context）；
3. **构造器手动传入**：把依赖作为构造参数由外部传入（最朴素，测试友好）；
4. 反射注入（不推荐）。
**实际应用场景**：Aether 项目中的 `ReActAgent` 由 `DefaultAgentFactory` 通过**构造函数手动装配**（传入 `ChatModel`、`ToolExecutor`、`ContextManager` 等）而非 `@Autowired`——因为 Agent 实例是按 YAML 配置动态创建的，不属于 Spring 静态 bean 图，这种"手动构造 + 显式传依赖"正是"非 Spring 管理的类如何获得依赖"的标准解法。

**常见误区**：以为 `new` 出来的对象 `@Autowired` 也生效；在 `static` 方法里用注入字段（静态字段不走实例注入）；把 `ApplicationContext` 当全局变量滥用（该用的时候用，别到处 new）。

### 32. 创建一个 bean 有什么办法？（4.8）

【技术点】Spring Bean 创建

【答案】

**核心概念**：Spring 中"创建 bean 定义并交给容器"的方式：
1. **注解扫描**：`@Component` / `@Service` / `@Repository` / `@Controller`（最常用）；
2. **`@Bean` + `@Configuration`**：显式声明（适合第三方库类，如 `ThreadPoolConfig` 里的 `ThreadPoolTaskExecutor`、`RestTemplate`、`PasswordEncoder`）；
3. **`@Import`**：导入 `@Configuration` 类或 `ImportSelector`；
4. **FactoryBean**：实现 `FactoryBean<T>`，容器拿 `getObject()` 结果；
5. **`BeanDefinitionRegistryPostProcessor` / `BeanFactoryPostProcessor`**：编程式注册 `BeanDefinition`（动态创建）；
6. **`@Conditional` 系列**：条件装配（`@ConditionalOnProperty` 等，Spring Boot 大量使用）；
7. **编程式 `ConfigurableApplicationContext.registerBean()`**。

**Aether 项目实例**：
- `AiAgentAutoConfig`（`@Configuration` + `@Bean`）监听 `ApplicationReadyEvent` 把 YAML 配置装配为 Agent 并注册进 `AgentRegistry`；
- `SecurityConfig`、`ThreadPoolConfig`、`AsyncConfig` 用 `@Bean` 声明 SecurityFilterChain、线程池、异步执行器；
- `ModelProviderRegistry` 自动发现 `ModelProvider` SPI bean。

**常见误区**：`@Component` 和 `@Bean` 混用不分场景（`@Bean` 用于显式/外部类，`@Component` 用于自研类）；在 `@Component` 类里写 `@Bean`（可以但不规范，应放 `@Configuration`）；忽略 bean 生命周期钩子（`@PostConstruct`/`@PreDestroy`/`InitializingBean`）。

---

## 五、Redis（一面）

### 33. 你用了 bitmap，bitmap 底层是啥？（5.1）bitmap 的缺点和优点，用在哪里？（5.2）

【技术点】Redis 数据结构

【答案】

**核心概念**：Redis Bitmap 不是独立数据结构，底层就是 **String（SDS 动态字符串）**——按位寻址的二进制数组。`SETBIT key offset 1/0` 把字符串某个 bit 置位，`GETBIT` 读位，`BITCOUNT` 统计 1 的个数，`BITOP` 做位运算。一个 10 亿位（约 1.25 亿字节 ≈ 119MB）的 bitmap 可以标记 10 亿个用户的签到状态（每位 1 个用户），内存极省。

**优点（5.2）**：① 内存极省（1 个用户只占 1 bit，1 亿用户签到 ≈ 12MB）；② 位运算高效（`BITCOUNT`/`BITOP` 由 CPU 位操作完成，聚合快）；③ 天然支持去重统计（同一 offset 只一个位）。

**缺点**：① **稀疏场景浪费**（用户 id 不连续、最大 id 很大时，offset 空间按最大值分配——如只有 1 个用户但 id=10 亿，也要 119MB，可用**多 key 分片 + 增量映射**缓解）；② 只能表达 0/1 布尔（要计数需多个位或换 HyperLogLog）；③ 操作语义与业务 id 强耦合（id → offset 映射要稳定）。

**用在哪里**：签到/打卡统计（Aether 面经背景）、在线状态、**布隆过滤器底层**（多个 bitmap 位）、活跃用户统计、简单黑白名单。

**常见误区**：把 bitmap 当独立数据类型（是 String 的位视图）；以为稀疏场景也省内存（按最大 offset 分配）；用 bitmap 做"计数"（它只存布尔，计数用 `INCR` 或 HLL）。

### 34. 跨表事务：AT 模式是啥？解决什么问题？（6.1）除去 Seata 还有什么方法完成分布式事务？（6.2）

【技术点】分布式事务

【答案】

**AT 模式（6.1）核心概念**：Seata 的 AT（Automatic Transaction）模式是**改进型 2PC（两阶段提交）**，对业务无侵入：
1. **一阶段**：业务 SQL 正常执行（本地事务提交），同时**自动生成 undo log**（记录前镜像/后镜像），注册**全局事务分支**到 TC（事务协调器）——不需要业务写补偿代码；
2. **二阶段提交**：全局成功 → 异步删除 undo log；
3. **二阶段回滚**：全局失败 → TC 通知各分支，用 undo log **反向补偿**（把数据改回前镜像），并校验后镜像是否被其他事务修改（脏写检测）。

**解决的问题**：跨库/跨服务（微服务）的数据一致性——保证"要么全成功要么全回滚"，解决本地消息表/手动补偿带来的"实现复杂、时效差"问题。Aether 项目若涉及多服务事务，可评估 Seata AT；目前以本地事务 + 消息队列 + 幂等为主。

**其他分布式事务方案（6.2）**：
1. **2PC（XA 两阶段）**：数据库原生支持（`xa start/end/prepare/commit`），强一致但**阻塞**（prepare 后资源被锁），性能差、协调者单点；
2. **TCC（Try-Confirm-Cancel）**：业务显式实现三阶段（预留资源 → 确认 → 取消），无锁等待、性能好，但**侵入性强**（每个操作要写 try/confirm/cancel 三套逻辑）；
3. **本地消息表 / 事务消息（RocketMQ）**：最终一致性，异步解耦；
4. **Saga**：长事务编排（正向补偿链），适合业务流程长、允许中间状态可见的场景；
5. **对账 + 定时补偿**：最朴素可靠的兜底（数据不一致靠对账任务发现并修复）。

**选型原则**：强一致且短事务 → XA/AT；短事务高性能 → TCC；异步最终一致 → 事务消息/本地消息表；长流程 → Saga。Aether 项目中子 Agent 委派采用"异步委派 + 租约管理 + 过时扫描补偿"（`AsyncDelegationService` + `LeaseManager` + `StaleDelegationScanner`）——本质就是最终一致性 + 定时补偿的思路。

**常见误区**：所有场景都上 Seata（本地事务能解决的就别引分布式事务，成本高）；把 AT 当无侵入万能（仍有脏写检测限制、undo log 开销）；事务消息 ≠ 普通 MQ 发消息（普通 MQ 没有"本地事务与消息同生共死"保证）。

### 35. 为什么用 bitmap 防止缓存穿透？（6.3）布隆过滤器的优缺点（6.4）怎么缓解布隆过滤器碰撞？（6.5）

【技术点】缓存穿透 / 布隆过滤器

【答案】

**核心概念**：**缓存穿透** = 查询一个**不存在**的数据（如 id=-1），缓存没有 → 每次都打到数据库，恶意攻击可打垮 DB。**布隆过滤器（Bloom Filter）** 是解决穿透的经典方案：在缓存前加一层"存在性过滤器"，`不存在` 直接返回，`可能存在` 才放行到缓存/DB。

**为什么用 bitmap 相关方案（6.3）**：布隆过滤器底层就是 **bitmap（位数组）+ 多个哈希函数**：插入时把元素经 k 个哈希函数映射到 k 个 bit 置 1；查询时检查 k 个 bit 是否全为 1——不全为 1 一定不存在（**零漏报**），全为 1 可能误判（**有误报率**）。bitmap 让"亿级 key 的存在性判断"只需几百 MB 内存。Redis 可直接用 bitmap 实现简单布隆，或用 Redisson 的 `RBloomFilter`。

**优缺点（6.4）**：
- 优点：空间极省（1 个元素 k 个 bit）；查询 O(k)；**不存在判定绝对准确（不漏报）**——正好挡住穿透；
- 缺点：**误报**（存在性判断可能"假阳性"：把不存在的说成存在，导致少量穿透漏到 DB——需要 DB 兜底）；**不支持删除**（bit 置 1 无法安全清除，会误伤其他元素，除非用计数布隆）；元素多了误报率上升；**无法枚举元素**。

**缓解碰撞/误报（6.5）**：
1. **调参**：按公式控制误报率——`位数组大小 m = -n·ln(p)/(ln2)²`，哈希函数数 `k = (m/n)·ln2`；元素 n 越大，m 要越大，否则误报率飙升（Redis 布隆的 `BF.RESERVE` 就是按 error_rate 预分配）；
2. **增加哈希函数数量**：k 越大冲突越少，但插入/查询开销越大（平衡）；
3. **多层布隆（分级过滤）**：第一层粗筛、第二层细分（如按业务前缀分桶）；
4. **计数布隆过滤器（Counting Bloom）**：每个 bit 换成计数器，支持删除；
5. **与缓存/DB 双层兜底**：误判放行后由缓存判空 + DB 兜底，命中"不存在"可回写**空值缓存（带短 TTL）**，把穿透损失降到最低；
6. **定期重建**：数据量大后重建过滤器（离线重建 + 双写切换）。

**常见误区**：以为布隆过滤器"不存在"也可能误判（**不会**，误判只发生在"存在"方向上）；不支持删除还硬删（应重建或计数布隆）；m、k 拍脑袋设（要按误报率公式算，否则内存浪费或误报失控）。

---

## 六、项目题（一面）

### 36. 讲讲你这个项目的难点（7.1）；给你一个原有的模块，你会怎么对它进行升级（7.2）

【技术点】项目深挖 / 架构演进

【答案】

**7.1 项目难点（结合 Aether 项目，挑最有说服力的 3 个）**：

**难点一：模型调用不可靠（超时/限流/失败）与成本失控**
- 现象：Agent 高频调模型 API，单点模型服务超时、限流，key 配额耗尽，且 token 成本随对话轮次线性增长；
- 方案：`ResilientChatModelExecutor` 按 `DefaultModelErrorClassifier` 分类错误（可重试/不可重试），`RetryBackoff` 指数退避 + fallback 链（OpenAI → Anthropic → DashScope 跨 Provider 兜底）；`RotatingCredentialPool` 实现 key 轮换；`ModelPricingRegistry` + `TokenBudget` 按 `maxCostUsd` 熔断；
- 效果：单点故障不再导致 Agent 卡死，成本有硬上限。

**难点二：上下文窗口溢出与长对话质量**
- 现象：多轮对话 + 工具结果累积，prompt 超模型窗口，截断后信息丢失；
- 方案：`ContextManager` 分层压缩——微压缩（裁剪低价值消息）、自动压缩（`AutoCompactResult` 触发摘要）、`CompactionPipeline` 多步压缩管道；压缩边界以 `compactBoundary` 事件通知前端展示"上下文已压缩"；
- 效果：长会话可用，用户可感知压缩行为（透明性）。

**难点三：Agent 自主执行的安全边界**
- 现象：模型可能调用危险工具（删文件、访问内网）或被注入攻击（prompt injection 诱导调工具）；
- 方案：`PermissionEngine` + 规则集（`DangerousToolRule` 危险操作、`InjectionGuardRule` 注入检测、`SensitiveArgMaskRule` 敏感参数脱敏、`ToolAllowlistRule` 白名单）；命中规则的工具调用**挂起**（`AgentStatus.PAUSED`）→ 发 `permissionAsking` 事件 → 用户 `/api/v1/confirm` 批准/拒绝 → 恢复执行；`SsrfSafeInterceptor` 防 SSRF（`aether.ssrf.allow-private-urls=false` 生产禁内网）；
- 效果：Human-in-the-loop，安全规则可插拔扩展。

**7.2 如何升级一个原有模块（答题框架 + Aether 实例）**：
用"**现状评估 → 瓶颈定位 → 方案对比 → 落地步骤 → 验证**"框架回答。以 Aether 中"记忆模块升级"为例：
1. **现状**：`DefaultMemoryFacade` 的 `remember`/`recall` 是同步 + 异步混合（`CompletableFuture`），语义召回直接查 `PgvectorVectorStore`，无缓存；
2. **瓶颈**：高并发下 pgvector 查询 QPS 瓶颈；写入阻塞（embedding 生成慢）；召回无相关性过滤；
3. **升级方案**：① 召回加 **Caffeine 本地缓存 + Redis 二级缓存**（热点记忆秒回）；② 写入异步化（`EncodingFlow` 后台生成 embedding + 批量入库，配合 `MemoryEmbeddingBackfillRunner` 存量回填）；③ `RecallFlow` 加 **Rerank + 时间衰减权重**（`aether.memory.recall.max-results` 可配）；④ 升级为多路召回（向量 + 关键词 BM25 混合）；
4. **落地顺序**：先加缓存（收益最大、改动最小）→ 异步化 → 混合召回 → 压测验证（QPS、P99、召回率）；
5. **验证**：指标化（召回率、响应 P99、写入吞吐），A/B 对比旧实现。

**常见误区**：难点讲成"我用了 xx 中间件"（要讲"问题 → 权衡 → 方案 → 效果"）；升级模块不做影响面分析（先列接口兼容性、存量数据迁移、灰度开关）；没有量化指标（升级要有前后对比数据）。

---

## 七、二面（个人背景与实习经历）

### 37. 你刚刚提到 A、B 表数据不一致的问题，这个问题产生的原因是什么？有没有对原因做过深入分析？（二面 6）

【技术点】数据一致性 / 问题排查思路

【答案】

**核心概念**：跨表数据不一致的典型成因（按频率排查）：
1. **事务边界错误**：两步写不在同一事务（或自调用 `@Transactional` 失效），中途异常导致一表提交一表未提交；
2. **MQ 消费幂等缺失**：消息重复消费（at-least-once）导致重复写；或消费失败未重试、无死信；
3. **并发写冲突**：无锁/无版本号，ABA 问题（先读后写覆盖他人更新）；
4. **缓存与 DB 双写不一致**：先删缓存后写库/先写库后删缓存顺序问题，无消息/延迟双删；
5. **异步补偿缺失**：本地消息表投递失败后无对账任务。

**深入分析的方法**（面试加分）：① 先看**监控告警/日志时间线**（哪张表、哪个时间点开始不一致，用 MDC traceId 串全链路，Aether 项目 `MdcFilter` 正是为此）；② 定位**写入路径**（谁写 A、谁写 B、是否同一服务）；③ 复现（回放流量/构造场景）；④ 用**对账脚本**（按业务键 join 比对）量化不一致比例与分布，反向定位发生时段 → 对应代码变更/发布窗口；⑤ 修复 + 补数据 + 加监控（不一致率指标 + 告警）。

**常见误区**：只修数据不修根因（要找到"为什么会产生"）；不一致处理只靠"人工跑脚本"（要有对账平台 + 补偿机制）；忽略幂等（所有异步写都要幂等键）。

### 38. Consul 出故障了，它具体是怎么出故障的？（二面 7）

【技术点】注册中心 / 高可用 / 故障排查

【答案】

**核心概念**：Consul 作为注册中心/配置中心，典型故障形态与根因：
1. **Leader 选举失败 / Raft 集群脑裂**：多数节点不可达 → 无法选举新 leader → 注册/发现全部不可用（Consul 基于 Raft 强一致，**可用性依赖多数派**）；
2. **磁盘/内存耗尽**：Consul 是内存型 KV，大量 KV（服务实例注册多、TTL 没清理）导致内存涨 → OOM/响应慢；
3. **网络分区**：机房网络抖动，客户端连不上 → 服务发现失败（但客户端本地缓存可兜底读旧数据）；
4. **健康检查风暴**：服务实例异常多时 Consul 反复检查，CPU 打满；
5. **版本/配置错误**：升级不兼容、ACL token 失效。

**排查思路**：看 Consul 自身指标（`serfHealth`、leader 状态、raft 提交延迟、内存）→ 看客户端报错（`No known leader`、`connection refused`）→ 分网络层/存储层/选举层定位。**加固**：Consul 集群 ≥ 3 节点（最好 5 节点，容忍 2 节点故障）；客户端**本地缓存 + 降级**（发现失败用缓存的服务列表）；对 Consul 做监控告警（leader 变更、心跳超时）。

**常见误区**：单节点 Consul 上生产（单点即故障源）；把"Consul 挂了服务就全挂"当必然（客户端应有缓存与重试降级，服务间也应保留静态兜底配置）；只重启不查根因。

### 39. 为什么两个进程能够同时抢到锁？这个问题你有深入了解过吗？（二面 8）

【技术点】分布式锁

【答案】

**核心概念**：两个进程同时抢到锁 = 分布式锁失效，典型根因：
1. **过期时间太短（最常见）**：业务执行超过锁 TTL，锁自动过期释放，另一进程拿到锁 → 两进程"同时"持锁。解法：**续期**（Redisson WatchDog 默认 30s，每 1/3 时间自动续期，业务结束才释放）；
2. **非原子操作**：`GET` 判断 → `SETNX` 加锁分两步（判断与加锁不原子），并发窗口都能加锁——必须用 **`SET key value NX PX 30000`** 单命令原子完成；
3. **释放错锁**：A 拿到锁，执行超时锁过期，B 拿到锁，A 完成后 `DEL` 把 B 的锁删了 → 必须**先比对 value（唯一标识，如 UUID）再删**（Lua 脚本保证原子）；
4. **Redis 主从切换**：主节点加锁成功但未同步到从节点，主挂了从节点顶上——锁丢了（RedLock 或最小化该场景）；
5. **时钟跳跃**：依赖 `PX` 相对时间一般没问题，但若用"过期时刻"判断可能受服务器时钟回拨影响。

**深入理解（加分）**：锁的本质是"对共享资源写权限的互斥令牌"，要满足**互斥性、可重入、防死锁（过期）、防误删、高可用**。Redis 分布式锁是 **AP 思路（最终一致）**，极端情况（主从切换）会失效；`Redisson` 封装了 `RLock`（`tryLock(leaseTime, timeUnit)`）+ WatchDog 续期 + 公平锁/读写锁，是生产标准答案。ZooKeeper/Nacos 用 **CP 强一致**（临时顺序节点）实现，无过期问题但性能低、实现复杂。

**Aether 项目实例**：`RotatingCredentialPool` 分配模型 key 时若多实例并发轮换，需用分布式锁保护"当前游标推进"，可采用 Redisson `RLock`（`tryLock` 带超时 + 看门狗续期），避免两个实例拿到同一个 key。

**常见误区**：`SETNX` 裸用不带过期时间（死锁风险）；`DEL` 不校验 value（误删他人锁）；以为 Redis 分布式锁绝对安全（主从切换窗口仍可能双锁）；业务时长超过锁 TTL 不做续期（这是"两进程同时持锁"第一大根因）。

### 40. 如果让你自己实现一个分布式锁，你会怎么避免多个进程同时获取到锁的情况？（二面 9）你会选择什么方案或组件来实现分布式锁？（二面 10）

【技术点】分布式锁设计

【答案】

**自己实现的核心设计（9）**：
1. **原子加锁**：`SET key <unique_value> NX PX <ttl>`——单命令，NX 保证互斥，PX 保证自动过期防死锁，unique_value（UUID/雪花）保证释放时能校验归属；
2. **锁续期**：后台任务在 TTL 的 1/3 处续期（避免长任务锁过期）；
3. **安全释放**：`if (GET(key) == myValue) DEL(key)`——用 **Lua 脚本**保证"比对 + 删除"原子（防止把别人的锁删了）；
4. **重入**：value 里带线程标识 + 计数器（可重入）；
5. **公平性（可选）**：排队等待（Redisson fair lock 基于 ZSet 排队）；
6. **容错**：加锁失败重试（带退避）；锁获取加超时上限。

**组件选型（10）**：
- **Redis + Redisson（最常用，推荐）**：`RLock` 封装 NX+TTL+续期+重入+公平锁，`tryLock(waitTime, leaseTime, TimeUnit)`，看门狗自动续期；
- **ZooKeeper**：临时顺序节点 + Watcher，锁删除即释放（无过期问题），CP 强一致，性能低于 Redis；
- **Nacos/Etcd**：类似 ZK（Etcd 基于 Raft + lease，支持 `CreateSession` 分布式锁 API 等价物）；Aether 项目若用 Nacos（Spring Cloud Alibaba）可用其分布式锁/分布式配置能力；
- **数据库唯一索引**：`INSERT ... ON DUPLICATE KEY` 拿唯一索引当锁（简单、可靠但性能差，只适合低频）；
- **选型权衡**：对一致性要求极高（金融扣款）选 ZK/Etcd；追求性能与简单选 Redis；绝不能选"进程内锁"（synchronized/ReentrantLock 只在本进程有效）。

**常见误区**：用 `synchronized` 解决分布式场景（进程内锁无效）；自己造轮子忽略续期/误删；把"用组件"与"理解原理"对立（面试官要的是你既会用 Redisson 也能说出它底层做了什么）。

### 41. 你刚刚说的 Redis 分布式锁，是使用 SET NX / SETNX 的方式吗？为什么不直接使用 Redisson？（二面 11/12）

【技术点】分布式锁 / Redisson

【答案】

**SETNX 方式（11）**：是的，基础做法是 `SETNX`，但**裸 `SETNX` 有缺陷**，生产标准是 `SET key value NX PX ttl`（`SETNX` 已不推荐单独用：不支持过期、无值校验）。Redis 官方也建议用 `SET ... NX PX` 替代 `SETNX`。

**为什么不直接用 Redisson（12）**（这是个"反追问"题，考的是技术选型权衡而非堆组件）：
1. **依赖与体积**：Redisson 引入较重依赖，若项目 Redis 用量很小（仅缓存），为一把锁引整个客户端库不划算；
2. **控制力/教学场景**：手写 `SET NX PX + Lua` 逻辑简单清晰、无黑盒，方便自己掌控行为与排查；
3. **特殊需求**：自研锁可定制（如 Aether 的 `RotatingCredentialPool` 轮换场景可自定义重试/超时语义，或用本地分配 + 分布式锁兜底）；
4. **性能**：Redisson 的 WatchDog 续期是额外后台任务（虽有收益也有开销）；极端低锁频率下自实现更轻；
5. **但也要承认**：Redisson 在"续期、可重入、公平锁、红锁"等完整能力上是成熟方案——**如果需求复杂或团队要标准能力，直接用 Redisson 更稳妥**。答题要体现"场景驱动选型"而不是"为了不用而不用"。

**常见误区**：答"Redisson 太重所以不用"显得不会权衡（要承认成熟组件的价值）；反之无脑"直接用 Redisson"没展示对 SET NX PX 原理的理解；忽略 Redis 客户端版本兼容性。

### 42. 你刚刚还提到了 ZooKeeper、Nacos 之类的方案，你知道它们的分布式锁是怎么实现的吗？（二面 13）

【技术点】ZooKeeper / Nacos 分布式锁

【答案】

**ZooKeeper 实现**（CP 强一致，基于 ZAB 协议）：
1. **临时顺序节点（EPHEMERAL_SEQUENTIAL）**：客户端在锁目录下创建临时顺序节点 `/locks/lock_000000001`；
2. 客户端 `getChildren` 看**自己是否是最小序号**——是则**获得锁**；
3. 不是则**监听前一个节点**（Watcher），前一个节点删除（锁释放）时被唤醒重新判断；
4. **释放**：主动删除节点，或客户端**会话超时**时临时节点被自动删除（防死锁）——这就是 ZK 锁"无过期时间问题"的原因（会话即租约）。

**Nacos 实现**（Nacos 2.x）：
- Nacos 的分布式锁并非官方核心功能（它主要做注册/配置），但可通过**配置/临时实例 + 回调**实现类似效果；更常用的是 **Spring Cloud Alibaba 生态配合 Seata** 做分布式事务，锁场景一般用 ZK/Redis/Etcd。

**对比要点**：ZK 锁**无 TTL 概念**（用会话，客户端挂会话断锁自动释放，比 Redis 更安全），但**羊群效应**（大量客户端监听同一节点唤醒风暴，可优化为监听前驱）；写性能低（ZAB 全量广播）；**Redlock 与 ZK 的取舍**是经典面试点：Redis 快但 AP（主从切换丢锁），ZK 稳但慢（CP，锁不丢）。

**常见误区**：以为 ZK 锁也有"过期时间"问题（没有，会话租约天然防死锁）；忽略 Watcher 的羊群效应；把 Nacos 当锁组件用（要清楚 Nacos 的定位是注册配置中心，锁是衍生能力）。

### 43. 你解决数据不一致问题的方案，听起来更多是业务上的重构，而不是通过技术手段规避问题，我这样理解准确吗？（二面 14）在这个方案里面，你们有没有使用什么技术手段来解决这个问题？（二面 15）

【技术点】架构思维 / 数据一致性

【答案】

**14 题的理解（要敢于承认 + 补充技术层）**：面试官在试探你**是否真的从架构层面思考过**。诚实的回答结构：
1. **承认业务重构是根因治理**：数据不一致的根源往往是业务建模/流程设计问题（如两步写本质上是"一个业务动作被拆成两个无原子边界"），业务重构（合并写入路径、收敛写入入口）是**消除不一致的根**——技术手段只能兜底；
2. **但技术手段是必要的第二道防线**（见 15 题）；
3. **表达取舍**：业务重构成本高、周期长，所以分两阶段：短期技术兜底止血，中期业务重构根治——这是工程上"先止血后根治"的标准节奏。

**15 题技术手段（具体、可落地）**：
1. **本地消息表 + 定时对账**：业务与消息同事务写库，异步投递，投递失败重试；加对账任务扫描不一致数据；
2. **分布式事务**（Seata AT / TCC）：对强一致核心链路；
3. **幂等设计**：所有写入带业务幂等键（`order_id`），消费端去重；
4. **版本号/乐观锁**：`UPDATE ... SET x=? WHERE id=? AND version=?` 防并发覆盖（ABA）；
5. **MQ 事务消息**（RocketMQ）：本地事务与消息发送同生共死；
6. **监控 + 对账报表 + 告警**：不一致率指标化，异常自动发现。

**Aether 项目实例**：子 Agent 委派数据一致性采用"**异步委派持久化（`PgAsyncDelegationStore`）+ 租约（`LeaseManager`）+ 过时扫描补偿（`StaleDelegationScanner`，`aether.delegation.stale-timeout=PT10M`）**"——即：记录先落库（本地事务），后台补偿扫描兜底，最终一致。这正是"技术手段做兜底"的活例子。

**常见误区**：被面试官带节奏认为"业务重构 = 没有技术含量"（要强调根因治理与兜底机制是互补的）；技术手段只提概念不提落地细节（幂等键怎么设计、对账怎么跑、补偿怎么触发）。

### 44. 你们项目使用的是 PostgreSQL，对吗？你知道 PostgreSQL 的底层机制是怎样的吗？（二面 16/17）你没有深入探索过 PostgreSQL 的底层实现吗？（二面 18）

【技术点】PostgreSQL 架构

【答案】

**核心概念（PG 底层机制）**：
1. **进程模型**：PG 是**多进程 + 共享内存**（postmaster 主进程 + 每连接一个 backend 进程 + 后台进程：checkpointer、bgwriter、walwriter、autovacuum、logical replication launcher），与 MySQL 多线程模型不同；
2. **存储结构**：表/索引存放在**表空间**，数据按 **Page（8KB）** 组织，Page 内 Tuple 用 **ctid（页号+行号）** 定位；MVCC 用**多版本**——每个 Tuple 带 `xmin/xmax` 事务标识，旧版本留在页内（**没有 undo log**，靠 **vacuum** 清理死元组——这是 PG 与 MySQL 最大差异之一）；
3. **WAL（预写日志）**：任何修改先写 WAL（16MB 段文件）再落数据页，崩溃恢复重放 WAL；`wal_level=logical` 时支持**逻辑复制（CDC）**；
4. **索引**：默认 B-tree（也支持 hash、GiST、SP-GiST、GIN、BRIN）；**pgvector 扩展**提供向量索引（HNSW/IVFFlat）——Aether 的记忆向量检索依赖它；
5. **事务**：MVCC + 锁（行锁/表锁/咨询锁 Advisory Lock）+ 快照（ReadView 类似）；
6. **查询执行**：Parser → Analyzer → Rewriter → Planner（基于代价的优化器，可 `EXPLAIN ANALYZE`）→ Executor（火山模型）。

**为什么用 PG（结合 Aether）**：Aether 需要 **pgvector 向量存储**（`PgvectorVectorStore` 做记忆语义召回）、JSONB、强约束（外键/检查约束）、逻辑复制（CDC 对账）。pgvector/pgvector:pg16 Docker 镜像直接部署。

**常见误区**：把 PG 的 MVCC 与 MySQL InnoDB 混为一谈（PG 无 undo log、靠 vacuum；MySQL 靠 undo log）；以为 PG 死元组不清理会一直膨胀（autovacuum 自动处理，但配置不当会膨胀）；忽略 PG 是进程模型（连接数受内存限制，高并发要用连接池——Aether 用 HikariCP `Aether_HikariCP`，max 25）。

### 45. 你对 MySQL 了解吗？（二面 19）你为什么会说 MySQL 以前主要使用 MyISAM？（二面 20）你能大概讲一下 InnoDB 的索引是怎么设计的吗？为什么要这样设计？（二面 21）

【技术点】MySQL 存储引擎

【答案】

**MySQL 存储引擎演进（19/20）**：MySQL 5.5 之前**默认引擎是 MyISAM**，5.5 之后**默认改为 InnoDB**。MyISAM 的问题：① **不支持事务**（无 redo/undo）；② **只支持表级锁**（DML 并发写性能差）；③ **崩溃恢复能力弱**（数据文件与索引文件分离，断电可能损坏）；④ 无外键。InnoDB 是**事务型引擎**：支持 ACID（redo/undo）、**行级锁 + MVCC**、聚簇索引、崩溃恢复（doublewrite + redo）。现在生产基本只用 InnoDB，MyISAM 仅存于历史/只读场景（全文索引被 InnoDB 也支持后更无优势）。

**InnoDB 索引设计（21）——重点展开**：
1. **聚簇索引（主键索引）**：B+ 树叶子节点**直接存整行记录**，数据按主键顺序物理组织——查询主键一次 B+ 树定位即拿整行；
2. **二级索引**：叶子节点存**索引列 + 主键值**，查二级索引后需**回表**（用主键回聚簇索引取整行）；
3. **联合索引**：多列组成 B+ 树键，**最左前缀**原则（`(a,b,c)` 可命中 `a`、`a,b`、`a,b,c`，不能跳过 a 用 b）；
4. **自适应哈希索引**：内存中对热点索引页建哈希加速等值查询；
5. **索引下推（ICP）**：联合索引查询时把 WHERE 条件下推到存储引擎层过滤，减少回表。

**为什么这样设计**：① B+ 树矮宽 → 磁盘 IO 少（一个节点一个页 16KB，3-4 层支撑千万级）；② 叶子有序链表 → 范围查询/排序高效；③ 聚簇索引让主键查询零回表 → **主键设计尽量短小有序**（自增 vs UUID——UUID 无序导致页分裂、索引膨胀）；④ 二级索引存主键而非行地址 → 数据移动（页分裂/重组）不影响二级索引，且主键尽量短以减小二级索引体积。

**常见误区**：主键用随机 UUID（页分裂 + 二级索引膨胀，长 UUID 每张二级索引都要带一份）；创建大量冗余索引（写放大，联合索引可覆盖多个查询）；以为索引一定加速（低选择性列如 `sex` 建索引不如全表扫）。

---

## 八、二面（Agent 项目）

### 46. 你们设计主 Agent 和子 Agent 的主要目的是什么？（二面 22）子 Agent 主要负责做哪些事情？（二面 23）

【技术点】多 Agent 架构

【答案】

**（结合 Aether 项目作答）**

**主 Agent + 子 Agent 设计目的（22）**：
1. **关注点分离**：主 Agent（编排者）负责**理解任务、拆解规划、调度子 Agent、聚合结果**；子 Agent 负责**单点专业执行**（代码审查、文档生成、检索等）。Aether 的 `SubAgentOrchestrator` + `SubAgentDelegationTool` 正是这个职责划分的落地；
2. **上下文隔离**：每个子 Agent 有独立上下文（独立 `ContextManager`），避免主 Agent 上下文被子任务细节污染、爆窗；
3. **并发与效率**：可并行委派（`parallel` 工作流 / `CompletableFuture` 并发），多个子 Agent 并行执行独立子任务；
4. **专业分工**：不同子 Agent 配不同模型/工具/提示词（如代码审查 Agent 用代码工具，检索 Agent 用搜索 MCP）；
5. **可维护性**：子 Agent 可独立迭代、独立评测。

**子 Agent 负责的事情（23）**（Aether 实例）：
- 执行**分解后的具体任务**（如 `test-agent.yml` 的 `CodeWriterAgent → CodeReviewerAgent → CodeRefactorerAgent` sequential 流水线：写代码 → 审查 → 重构，通过 `{generated_code}`/`{review_comments}` 占位符传输出）；
- 并行研究多个子主题（`parallel_research_app.yml`）；
- 使用**专属工具集**（搜索、代码、文档处理）；
- 返回结构化结果给主 Agent，由 `ChildResultAggregator` 聚合；
- 支持**异步委派**（`AsyncDelegationService` + `PgAsyncDelegationStore` 持久化委派任务，`LeaseManager` 租约管理，`StaleDelegationScanner` 扫描超时子 Agent 兜底）。

**常见误区**：多 Agent = 越多越好（每个 Agent 都有模型调用成本与协调开销，能单 Agent 解决的别拆）；子 Agent 结果不校验直接合并（要有结果校验/聚合策略，如 `ResultRefiner`）；主 Agent 无调度边界（要防死循环与互相推诿——见后文"多 Agent 互相推诿"题）。

### 47. 你有没有了解过 Agent 之间的通信方式和部署方式？（二面 24）

【技术点】多 Agent 通信 / 部署

【答案】

**（结合 Aether 项目作答）**

**通信方式**：
1. **进程内方法调用（最常见）**：Aether 的主子 Agent 在**同一 JVM 进程**内通信——通过共享 `AgentRegistry`、`GraphExecutor` 编排、`CompletionBus`/`ChildResultAggregator` 传结果，`{outputKey}` 占位符实现跨 Agent 输出引用（`InstructionResolver` 解析）；
2. **消息队列**：跨进程/跨服务时用 MQ（任务下发、结果回调），Aether 的异步委派（`AsyncDelegationService`）若跨实例可用 MQ 解耦；
3. **HTTP/gRPC**：跨服务调用；
4. **标准化协议**：**A2A（Agent-to-Agent，Google 2025 发布）** 与 **MCP**——MCP 是"Agent ↔ 工具"的标准（Aether 通过 `SSEToolMcpCreateService` 等接入 MCP 工具），A2A 是"Agent ↔ Agent"的标准（Aether 目前未接入，可作扩展点讲）。

**部署方式**：
- **单进程多 Agent（Aether 当前形态）**：全部 Agent 在一个 Spring Boot 应用内，模块化单体部署，简单、共享缓存/DB，成本低；
- **多实例负载均衡**：水平扩容时需注意 Agent 状态与会话的**分布式存储**（Aether 会话已支持 `PgSessionRepository`/`RedisSessionRepository`，检查点 `GitShadowCheckpointStore` 可跨实例恢复）；
- **独立服务化**：重量级子 Agent 拆独立服务（专用 GPU/模型），通过 MQ/HTTP 通信；
- **K8s 编排**：容器化部署 + HPA 按 QPS 扩缩容。

**常见误区**：答不出通信协议层面的标准化（MCP/A2A 是现在的高频考点）；以为多 Agent 必须跨进程（同进程方法调用即可，跨进程有成本）；部署多实例时不考虑会话/检查点共享（会导致用户会话漂移）。

### 48. 主 Agent 和子 Agent 之间有没有做隔离？（二面 25）主 Agent 更多是一个通用型 Agent 吗？（二面 26）主 Agent 自己能不能直接完成具体任务，比如生成文档之类的？（二面 27）

【技术点】Agent 隔离 / 角色边界

【答案】

**（结合 Aether 项目作答）**

**隔离（25）——三层隔离**：
1. **上下文隔离**：每个 Agent 独立 `ContextManager`/`TokenBudget`，子 Agent 的对话上下文不回流污染主 Agent（结果只以结构化数据聚合）；
2. **状态隔离**：`AgentState` 独立，子 Agent 可 `PAUSED`/中断不影响主 Agent（`OrchestrationController` 支持 interrupt）；
3. **资源/权限隔离**：子 Agent 工具集按 YAML 独立配置（`tool-mcp-list` 按 Agent 声明）；权限审批规则（`PermissionEngine`）全局兜底；`SubAgentDenyApprovalRule` 可限制子 Agent 的敏感操作；Python 工具走独立沙箱（`execute_code` 在安全沙箱执行）。

**主 Agent 是否通用型（26）**：**是**。Aether 中主 Agent 是"**编排型通用 Agent**"：不绑定具体领域技能，核心能力是规划、委派（`SubAgentDelegationTool`）、聚合。通用型的好处：入口统一、可扩展任意子 Agent。但也因此**本身不适合做重活**（见 27）。

**主 Agent 能否直接完成具体任务（27）**：**能但设计上不鼓励**。Aether 的主 Agent 本身是一个可执行 ReAct 循环的 `ReActAgent`（有模型 + 工具），技术上可以自己写文档/检索；但设计原则是：
1. **能委派就委派**：具体任务（生成文档、写代码）交给专门子 Agent，主 Agent 专注规划调度，避免上下文被细节占满、token 成本失控（`TokenBudget` 限制）；
2. **兜底能力**：简单任务主 Agent 直接完成（减少一次委派开销）；
3. **回答要体现设计权衡**：这是"编排 vs 执行"的职责边界问题——完全让主 Agent 干所有活 = 单 Agent，失去多 Agent 意义；完全不让 = 过度设计。

**常见误区**：把主 Agent 设计成"全知全能"（上下文爆炸、token 爆炸）；子 Agent 无隔离地共享主 Agent 上下文（互相污染）；隔离只谈权限不谈上下文/状态。

### 49. 你们这个 Agent 是部署在云端还是本地？（二面 28）云端的主 Agent 和子 Agent，它们的运行环境有没有做隔离？（二面 29）Agent 既要给产品使用，又能够读取代码，这种代码权限、环境隔离和安全性问题，你们有没有考虑过？（二面 30）你们的安全部门会允许把公司内部代码直接暴露给大模型吗？（二面 31）

【技术点】Agent 部署安全 / 代码权限 / 环境隔离

【答案】

**（结合 Aether 项目作答）**

**部署形态（28）**：Aether 支持云端部署（`docker-compose-secure.yml` 一键部署 aether + pgvector），也支持本地开发运行（dev profile 直连本地 PG）。生产形态为云端容器化（Docker + K8s 可选）。

**环境隔离（29）**：
- **进程内隔离**：同 JVM 内通过独立 `AgentState`/`ContextManager` 隔离逻辑环境；
- **沙箱隔离（重活）**：代码执行/文档处理的**重操作放入独立沙箱**——Aether 的 `PythonTools`（`PythonServicePort`/`PythonToolCallbackAdapter`）调用**独立的 Python 服务**（`aether-python-services`），代码执行在隔离沙箱（`execute_code`），Agent 本身只持有工具调用权限；
- **部署级隔离**：生产可把主 Agent 与代码类子 Agent 拆到不同容器/命名空间，网络策略最小化（只开放必要端口）。

**代码权限与安全（30）——这是多 Agent 产品最核心的安全命题**：
1. **权限审批（Human-in-the-loop）**：`PermissionEngine` 规则集——`DangerousToolRule`（危险命令）、`InjectionGuardRule`（提示注入检测）、`SensitiveArgMaskRule`（敏感参数脱敏，如密钥不传给模型）、`ToolAllowlistRule`（工具白名单）；命中规则 → `PAUSED` + `permissionAsking` → 用户审批；
2. **最小权限**：Agent 只获得任务需要的工具（代码 Agent 才挂代码工具）；`SsrfSafeInterceptor` 防 SSRF（`aether.ssrf.allow-private-urls=false` 禁止内网访问）；
3. **脱敏与审计**：`SensitiveArgMaskRule` 对参数打码；`@Auditable` + `AuditAspect` 全量审计（谁、何时、调了什么工具、结果如何 → `t_audit_log`）；
4. **读代码不执行**：代码浏览类（`CodeExplorer`/`IdentifierRegistry`）只读索引，执行类走沙箱，两者分离。

**公司代码暴露给大模型（31）——关键表态题**：
- **不是"能不能"而是"怎么防"**：现代企业允许 AI 读代码，但必须满足：① **私有化/网关**（模型 API 走内网代理，数据不出内网，或直接用私有化模型）；② **脱敏**（敏感信息（密钥、客户数据）在进入 prompt 前过滤——`SensitiveArgMaskRule`）；③ **最小化投喂**（RAG 按需检索片段，而不是把整个仓库塞进上下文——`CodeExplorer` 按标识符/文件定位精准检索）；④ **审计追溯**；⑤ **合规评审**（安全部门评审数据流向）。答这个题要体现"安全是分层设计，不是非黑即白"。

**常见误区**：说"绝对不给模型看代码"（否认产品形态）；或"直接全量喂"（无安全设计）；忽略**审计**与**脱敏**这两个关键机制（安全评审必问）；把 prompt injection 当成小概率事件（工具调用场景下注入是真实攻击面，`InjectionGuardRule` 就是为此设计）。

---

## 九、二面（网络安全 / HTTP / 微服务 / 并发 / Redis）

### 50. 网络安全里的七层防御和四层防御有什么区别？（二面 32）SQL 注入属于七层攻击还是四层攻击？（二面 33）为什么 SQL 注入属于这一层？（二面 34）

【技术点】网络分层 / Web 安全

【答案】

**核心概念（32）**：基于 **OSI 七层模型**与 **TCP/IP 四层模型**的防御：
- **四层防御（传输层）**：IP 层 + TCP/UDP 层——防火墙（包过滤）、DDoS 防护（SYN Flood 等）、ACL、负载均衡（L4）。**不感知应用内容**（只认 IP/端口/协议）；
- **七层防御（应用层）**：Web 应用防火墙（WAF）、反向代理、内容过滤、Bot 防护、API 网关——**能解析 HTTP 语义**（URL、Header、Body、Cookie），识别 SQL 注入、XSS、SSRF、文件上传、爬虫等应用级攻击。
- **区别本质**：四层看"包"（网络可达性），七层看"内容"（业务语义）；七层防御能做的精细化检测四层做不到。

**SQL 注入属于七层（33/34）**：SQL 注入发生在**应用层**——攻击者把恶意 SQL 片段放进 HTTP 请求参数（Query String/Body/Header），应用拼接 SQL 时被注入。这是**应用逻辑漏洞**，四层防火墙只看到 TCP 连接与数据包，无法理解"这条 HTTP 请求里藏着 SQL 关键字"；只有七层 WAF 能解析请求内容做模式匹配（关键字、语法特征），且根本解法在**应用编码**（参数化查询/预编译 + 输入校验 + 最小权限 DB 账户）。

**Aether 项目实例**：`SsrfSafeInterceptor`（SSRF 防内网）、`RateLimitFilter`（限流）、`SecurityHeadersFilter`（安全响应头）——这些都是七层防御组件；`InjectionGuardRule` 防的是"向模型/工具注入恶意指令"（本质同 SQL 注入的"输入不可信"问题）。

**常见误区**：把 SQL 注入归到四层（它是应用层漏洞）；认为 WAF 能彻底解决注入（WAF 是缓解，参数化查询才是根治）；混淆 DDoS（四层可防）与注入（需七层 + 代码）。

### 51. HTTP 和 HTTPS 有什么区别？（二面 35）HTTPS 为什么更加安全？它主要能够防范哪些安全问题？（二面 36）

【技术点】HTTP / HTTPS / TLS

【答案】

**核心概念（35）**：
- **HTTP**：明文传输——内容可被窃听（抓包看明文）、篡改（中间人改内容）、无法验证身份（伪站点）；
- **HTTPS** = **HTTP + TLS/SSL**：在 TCP 之上加 TLS 层，提供**机密性（加密）、完整性（防篡改）、身份认证（证书）**三重保护。

**HTTPS 为什么安全、防什么（36）**：
1. **防窃听**：对称加密（会话密钥）加密应用数据——解决"明文可读"；
2. **防篡改**：MAC/HMAC 或 AEAD（如 AES-GCM）保证数据完整性——解决"中间人改包"；
3. **防冒充**：数字证书（CA 签发）+ 非对称签名验签——解决"伪站点/中间人冒充"（客户端验证服务端证书链与域名）。
组合起来：**非对称加密协商密钥，对称加密传输数据**（性能），**证书防中间人**。

**常见误区**：以为 HTTPS 只是"加密"（还有完整性、认证）；以为 HTTPS 能防所有攻击（防不了业务层漏洞如 XSS/SQL 注入；加密的 SQL 注入照样注入）；忽略证书链验证（浏览器信任锚点被篡改则 HTTPS 形同虚设）。

### 52. HTTPS 中的公钥和私钥是怎么一步一步完成传输和密钥协商的？（二面 37）HTTPS/TLS 的过程中涉及哪些加密算法？（二面 38）

【技术点】TLS 握手 / 密钥协商

【答案】

**TLS 1.2 握手流程（37，简化版）**：
1. **ClientHello**：客户端发送支持的 TLS 版本、加密套件列表、随机数 `client_random`；
2. **ServerHello**：服务端选加密套件，返回 `server_random` + **数字证书**（含服务端公钥，CA 签名）；
3. **证书校验**：客户端验证证书链（CA 公钥验签、域名、有效期）；
4. **密钥交换**（经典 RSA 方式）：客户端生成 **pre-master secret（预主密钥）**，用服务端**公钥加密**后发给服务端；服务端用**私钥解密**拿到 pre-master；
   （现代推荐 **ECDHE**：双方用各自临时密钥做 ECDH，交换公钥分量，各自算出**相同的共享密钥**，无需传输 pre-master，具备**前向保密**）
5. **生成会话密钥**：双方用 `client_random + server_random + pre-master/共享密钥` 通过 PRF 派生**对称会话密钥**；
6. **Finished**：双方用会话密钥加密"握手消息摘要"互验，握手完成，之后**全程对称加密**通信。

**涉及算法（38）**：
- **非对称/密钥交换**：RSA（老）、ECDHE（推荐，前向保密）、DHE；
- **对称加密**：AES-GCM（主流）、AES-CBC、CHACHA20（移动端）；
- **哈希/完整性**：SHA-256/384、HMAC；
- **签名**：RSA、ECDSA（证书签名）；
- **密钥派生**：PRF/HKDF（TLS 1.3）。
**TLS 1.3 简化**：握手 1-RTT（0-RTT 支持恢复），移除 RSA 密钥交换与旧弱套件，默认 AEAD。

**常见误区**：把"非对称加密传输所有数据"（性能差，实际只加密密钥交换阶段，数据用对称加密）；RSA 密钥交换无前向保密（私钥泄露可解密历史流量——ECDHE 解决）；混淆"公钥加密/私钥解密"（加密）与"私钥签名/公钥验签"（认证）两组操作。

### 53. 你使用过 Spring Cloud Alibaba，对吗？你能讲一下服务注册与服务发现的完整过程吗？（二面 39/40）Nacos 在 CAP 理论中属于哪一种？（二面 41）

【技术点】微服务 / Nacos / 注册发现

【答案】

**注册发现完整过程（40）**：
1. **启动注册**：服务启动时向注册中心（Nacos）发送注册请求（服务名、IP、端口、元数据、健康检查方式），Nacos 记录并维护**服务实例列表**；
2. **心跳续约**：服务定期发心跳（Nacos 默认 5s，`spring.cloud.nacos.discovery.heart-beat-interval`），超时（15s）标记不健康，超长（30s）摘除；
3. **订阅发现**：消费方启动时从 Nacos **拉取**目标服务实例列表（并建立长轮询订阅 `subscribe`），Nacos 实例变更（注册/下线/摘除）时**推送**更新；
4. **负载均衡**：消费方用 Ribbon/Spring Cloud LoadBalancer 从实例列表选一个发起调用；
5. **下线/优雅停止**：服务停止时反注册（deregister），并支持**优雅下线**（先摘除再停，避免流量打到已停实例）。

**CAP 归属（41）**：Nacos **默认 AP（注册中心 AP 优先）**——保证可用性，允许服务列表短暂不一致（临时实例用 AP 模式，基于 Distro 协议最终一致）；同时 Nacos **支持 CP 模式**（持久实例走 Raft，用于配置中心强一致场景）。一句话：**Nacos 注册中心默认 AP，配置中心走 CP（Raft 持久化）**。对比：ZooKeeper/Etcd 是 CP（强一致、可用性受影响），Eureka 是纯 AP。这也是"注册中心该选 AP 还是 CP"的经典讨论：服务发现场景**AP 更合适**（宁可读到旧列表也不让调用失败），配置下发场景**CP 更合适**（配置错了影响大）。

**常见误区**：说 Nacos 是纯 CP 或纯 AP（分模式）；把注册中心当 CP 强一致（服务发现 AP 是主流选择）；忽略"订阅推送 + 本地缓存兜底"（Nacos 客户端有本地快照，注册中心不可用时可用缓存继续调用）。

### 54. Java 中 synchronized 和 ReentrantLock 有什么区别？（二面 42）你了解 ReentrantLock 实现公平锁的原理吗？它具体是怎么实现公平锁的？（二面 43）

【技术点】Java 并发锁

【答案】

**synchronized vs ReentrantLock（42）**：
| 维度 | synchronized | ReentrantLock |
|---|---|---|
| 实现 | JVM 内置（monitor），自动加解锁 | JDK 类（AQS），需手动 lock/unlock（finally 释放） |
| 公平性 | 非公平 | 可公平可非公平（构造参数） |
| 可中断 | 不可中断（等待锁时不能响应 interrupt） | `lockInterruptibly()` 可中断 |
| 超时 | 无 | `tryLock(timeout)` |
| 条件变量 | `wait/notify`（一个条件队列） | 多个 `Condition`（精准唤醒） |
| 性能 | 锁升级（偏向→轻量→重量），现代 JVM 与 ReentrantLock 接近 | — |
| 使用 | 简单场景优先 | 需要公平/超时/多条件时 |

**公平锁原理（43）**：`ReentrantLock(true)` 走 **AQS（AbstractQueuedSynchronizer）**：
- AQS 维护一个 **CLH 变体双向等待队列**（`Node` 链），`state` 表示锁持有数；
- **非公平**：`lock()` 先 `compareAndSetState(0→1)` **直接抢**（插队），失败才入队；
- **公平**：`lock()` 先判断 `hasQueuedPredecessors()`——**队列中已有等待者则当前线程不得抢先**（CAS 前先看队列），只能**排到队尾**（`addWaiter`）等待；唤醒时队列**头节点**的下一个节点被唤醒（`unparkSuccessor`），严格 FIFO；
- **实现关键**：`hasQueuedPredecessors()` 返回"队列非空且头节点后继不是当前线程"，配合 AQS 的 `tryAcquire` 钩子在**获取锁前**检查，保证**先到先得**。

**常见误区**：以为 ReentrantLock 一定比 synchronized 快（现代 JVM 两者相近）；忘记 finally 释放锁（死锁）；公平锁吞吐更低（排队唤醒开销，非公平在低竞争下更快）；把 AQS 的 state 当普通 int（是 volatile + CAS 的同步状态）。

### 55. 为什么 Redis 是一个高性能的框架？它的高性能主要体现在哪些方面？（二面 44）你了解 I/O 多路复用的实现原理吗？（二面 45）

【技术点】Redis 高性能 / I/O 多路复用

【答案】

**Redis 高性能来源（44）**：
1. **纯内存操作**：数据全在内存，读写 ns 级（这是根本）；
2. **单线程模型（6.0 前）**：单线程避免**锁竞争与上下文切换**，所有命令串行执行，天然线程安全、无死锁（6.0 后网络 IO 多线程，命令执行仍单线程）；
3. **I/O 多路复用**：单线程同时监听大量连接（epoll），事件驱动，无阻塞等待（见 45）；
4. **高效数据结构**：SDS（动态字符串，O(1) 长度）、跳表（ZSet）、压缩列表/quicklist、字典渐进式 rehash；
5. **零拷贝/减少拷贝**：协议解析与写回复优化；`unlink` 异步删大 key；
6. **响应式事件循环**：`aeEventLoop` 统一处理文件事件（socket 读写）与时间事件（定时任务）。

**I/O 多路复用原理（45）**：**一个线程同时监控多个 fd（文件描述符）**，当任一 fd 就绪（可读/可写）时通知处理：
- **select**：轮询 fd 集合（1024 上限，O(n)）；
- **poll**：链表无上限，仍 O(n)；
- **epoll（Linux 主流，Redis 使用）**：**事件驱动**——`epoll_create` 建红黑树 + 就绪链表；`epoll_ctl` 注册 fd 与关注事件；`epoll_wait` 阻塞，**内核只返回就绪的 fd**（O(1) 取就绪队列）；配合 **LT/ET 触发模式**，避免每次全量扫描；性能与连接数无关（百万连接可行）；
- Redis 的 `ae_epoll.c` 封装 epoll，事件循环：`aeMain` → `aeProcessEvents` → `epoll_wait` 拿到就绪事件 → 回调 `readQueryFromClient` 等处理器。
- **与多线程对比**：多线程模型每连接一线程（C10K 瓶颈）；IO 多路复用 + 事件循环 = **C10M 的基础**（Netty、Nginx 同理）。

**常见误区**：说"Redis 快是因为单线程"（单线程是结果，内存 + 多路复用 + 数据结构才是根因）；把 epoll 说成轮询（它是事件驱动，内核主动通知）；混淆 select/poll/epoll 的复杂度与适用规模；忽略 6.0 后 IO 多线程（命令执行仍是单线程）。

---
