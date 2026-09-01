# Aether P2 Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the four roadmap P2 items: horizontal scaling/state governance, residual refactoring, logging closure, and adaptive multi-turn planning.

**Architecture:** Add Redis-backed shared stores behind optional interfaces while preserving Caffeine/PostgreSQL defaults; extract GraphExecutor orchestration into five strategy beans; separate prod/dev log profiles and provide a trace-to-audit troubleshooting manual; extend PlanActAgent with failure/checkpoint-triggered re-planning and persisted post-task reflection.

**Tech Stack:** Java 17, Spring Boot 3.4, Spring Data Redis StringRedisTemplate, Caffeine, Jackson, RxJava 3, Logback, Maven.

**Spec:** `docs/项目优化路线图.md` sections 1.4, 2.3, 3.4, and 4.4.

## Global Constraints

- P2 acceptance: max class <600 lines; notes package is explicitly experimental; migration chain has V1 baseline.
- Redis features must degrade to existing behavior when Redis is absent; PostgreSQL remains the production session default.
- Do not remove existing GraphExecutor behavior: lifecycle hooks, MDC, tracing, interception, graph-flow DAG, bounded pool.
- New tests use only locally available JUnit 5/Mockito/Testcontainers conventions; unit tests must not require external Redis/Postgres.

---

### Task 1: Horizontal Scaling and Stateful Governance

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/runtime/ModelCallCache.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/runtime/ModelCacheStore.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/runtime/ModelCacheSnapshot.java`
- Create: `aether-infrastructure/src/main/java/cn/zcj/aether/repository/RedisModelCacheStore.java`
- Modify: `aether-infrastructure/src/main/java/cn/zcj/aether/repository/RedisSessionRepository.java`
- Modify: `aether-app/src/main/resources/application.yml`
- Create: `docs/horizontal-scaling-design.md`
- Test: `aether-infrastructure/src/test/java/cn/zcj/aether/repository/RedisSessionRepositoryTest.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/runtime/ModelCallCacheTest.java`

- [x] 1.1 Add `ModelCacheStore` get/put and serializable snapshot boundary.
- [x] 1.2 Wire optional Redis L2 into Caffeine-first `ModelCallCache`, with no L2 when no bean exists.
- [x] 1.3 Implement Redis session value + user/agent/active indexes and JSON persistence.
- [x] 1.4 Document sticky-session versus shared-state deployment and add compose/Nginx path.
- [x] 1.5 Unit-test cache fallback and session index lifecycle.

### Task 2: Residual Refactoring

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/executor/orchestration/*.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutor.java`
- Create: `data/sql/V1__baseline.sql`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/notes/ExternalNotes.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/executor/GraphExecutorOrchestrationTest.java`

- [x] 2.1 Preserve public `execute` while delegating SEQUENTIAL/PARALLEL/LOOP/SUBAGENT/EVENT_DRIVEN to strategies.
- [x] 2.2 Move shared single-node execution and intervention into `OrchestrationSupport`.
- [x] 2.3 Add V1 baseline so Flyway starts with schema creation, followed by V2-V5.
- [x] 2.4 Mark notes as experimental and document persistence/flush boundary.
- [x] 2.5 Assert every production class is below 600 lines and test strategy behavior.

### Task 3: Logging Closure

**Files:**
- Modify: `aether-app/src/main/resources/logback-spring.xml`
- Modify: `aether-app/src/main/resources/application-prod.yml`
- Create: `docs/log-troubleshooting.md`

- [x] 3.1 Restrict prod domain/root logging to INFO while retaining dev DEBUG.
- [x] 3.2 Ensure MDC keys graphExecutionId/sessionId are present in JSON encoder.
- [x] 3.3 Document traceId -> MDC -> audit-log location workflow with concrete commands.

### Task 4: Multi-Turn Planning Capability

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/impl/PlanActAgent.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/DefaultAgentFactory.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewer.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/observability/AgentMetrics.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/impl/PlanActAgentReplanTest.java`
- Test: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/agent/observability/BackgroundReviewerTest.java`

- [x] 4.1 Detect consecutive failed steps and checkpoint/expected-output misses.
- [x] 4.2 Generate a valid replacement plan and restart from the failed step.
- [x] 4.3 Perform synchronous LLM self-reflection and asynchronously submit reviewer output to notes.
- [x] 4.4 Record re-planning and completion metrics.
- [x] 4.5 Unit-test replan trigger, fallback, reflection, and reviewer persistence.
