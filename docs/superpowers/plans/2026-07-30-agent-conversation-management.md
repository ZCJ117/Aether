# Agent 多对话管理 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在左侧栏中为每个 Agent 添加可展开的对话列表和 "+" 新建按钮，后端新增会话列表 API 并修改 createSession 为立即持久化。

**Architecture:** 后端新增 `GET /api/v1/list_sessions` 端点，修改 `createSession` 立即写入存储；前端重构 `SessionSidebar`，用 `AgentAccordionItem` + `ConversationRow` 替代旧的 `AgentSelector` + 扁平会话列表。

**Tech Stack:** Java 17, Spring Boot 3.4.3, PostgreSQL, Vue 3, Pinia, Vite, Tailwind CSS, Lucide Vue Next

---

## 文件结构

| 操作 | 文件 | 职责 |
|------|------|------|
| **Create** | `aether-api/.../dto/SessionItemDTO.java` | 会话列表项 DTO |
| **Modify** | `aether-domain/.../session/SessionRepository.java` | 新增 `listByUserIdAndAgentId` 方法 |
| **Modify** | `aether-infrastructure/.../PgSessionRepository.java` | 实现按 agentId+userId 查询 |
| **Modify** | `aether-infrastructure/.../RedisSessionRepository.java` | 空实现声明 |
| **Modify** | `aether-domain/.../chat/ChatService.java` | createSession 立即持久化 + 新增 listSessions |
| **Modify** | `aether-api/.../IAgentService.java` | 新增 listSessions 接口 |
| **Modify** | `aether-trigger/.../AgentServiceController.java` | 新增 listSessions 端点 |
| **Modify** | `frontend/src/api/types.ts` | 新增 SessionItemDTO 前端类型 |
| **Modify** | `frontend/src/api/session.ts` | 新增 fetchSessions 函数 |
| **Modify** | `frontend/src/stores/session.ts` | 按 agentId 分组 + 后端数据加载 |
| **Modify** | `frontend/src/stores/agent.ts` | 新增 expandedAgentId |
| **Create** | `frontend/src/components/chat/ConversationRow.vue` | 单条会话行 |
| **Create** | `frontend/src/components/chat/AgentAccordionItem.vue` | Agent 折叠条目组件 |
| **Modify** | `frontend/src/components/chat/SessionSidebar.vue` | 重构为 Agent 折叠列表 |
| **Modify** | `frontend/src/views/ChatView.vue` | 适配独立 session 创建流程 |

---

### Task 1: 后端 — SessionItemDTO

**Files:**
- Create: `aether-api/src/main/java/cn/zcj/aether/api/dto/SessionItemDTO.java`
- Create: `aether-api/src/main/java/cn/zcj/aether/api/dto/package-info.java`（检查是否已存在）

- [ ] **Step 1: 创建 DTO**

```java
package cn.zcj.aether.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/**
 * 会话列表项 DTO — 用于 GET /api/v1/list_sessions 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionItemDTO {
    private String sessionId;
    private String agentId;
    private String userId;
    private String title;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
```

- [ ] **Step 2: 确认 package-info.java 存在**

Run: `ls aether-api/src/main/java/cn/zcj/aether/api/dto/package-info.java`
If it doesn't exist, create it with:
```java
package cn.zcj.aether.api.dto;
```

- [ ] **Step 3: Commit**

```bash
git add aether-api/src/main/java/cn/zcj/aether/api/dto/SessionItemDTO.java
git commit -m "新增SessionItemDTO — 会话列表查询响应"
```

---

### Task 2: 后端 — SessionRepository 扩展

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/session/SessionRepository.java`

- [ ] **Step 1: 新增方法签名**

在 `listByUserId` 方法之后添加：

```java
/** 按 userId + agentId 列出活跃会话 */
java.util.List<SessionEntity> listByUserIdAndAgentId(String userId, String agentId);
```

- [ ] **Step 2: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/session/SessionRepository.java
git commit -m "SessionRepository新增listByUserIdAndAgentId方法"
```

---

### Task 3: 后端 — PgSessionRepository 实现新查询

**Files:**
- Modify: `aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgSessionRepository.java`

- [ ] **Step 1: 添加 SQL 和方法实现**

在 `LIST_BY_USER_SQL` 常量后添加：

```java
private static final String LIST_BY_USER_AGENT_SQL = """
    SELECT id, session_id, user_id, agent_id, status, state_json, created_at, updated_at
    FROM aether_session WHERE user_id = ? AND agent_id = ? AND status = 'ACTIVE' ORDER BY updated_at DESC
    """;
```

在 `listByUserId` 方法后添加：

```java
@Override
public List<SessionEntity> listByUserIdAndAgentId(String userId, String agentId) {
    return jdbcTemplate.query(LIST_BY_USER_AGENT_SQL, new SessionRowMapper(), userId, agentId);
}
```

- [ ] **Step 2: Commit**

```bash
git add aether-infrastructure/src/main/java/cn/zcj/aether/repository/PgSessionRepository.java
git commit -m "PgSessionRepository实现listByUserIdAndAgentId查询"
```

---

### Task 4: 后端 — RedisSessionRepository 空实现

**Files:**
- Modify: `aether-infrastructure/src/main/java/cn/zcj/aether/repository/RedisSessionRepository.java`

- [ ] **Step 1: 添加空实现方法**

在 `listByUserId` 方法后添加：

```java
@Override
public List<SessionEntity> listByUserIdAndAgentId(String userId, String agentId) {
    return List.of(); // Redis 模式暂不支持按 agentId 过滤
}
```

- [ ] **Step 2: Commit**

```bash
git add aether-infrastructure/src/main/java/cn/zcj/aether/repository/RedisSessionRepository.java
git commit -m "RedisSessionRepository实现listByUserIdAndAgentId空方法"
```

---

### Task 5: 后端 — ChatService 改造

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/chat/ChatService.java`

改动点：① `createSession` 立即持久化 ② 新增 `listSessions` 方法

- [ ] **Step 1: 添加 import**

在 import 区域添加：
```java
import cn.zcj.aether.api.dto.SessionItemDTO;
import cn.zcj.aether.domain.agent.service.session.SessionEntity;
```

- [ ] **Step 2: 修改 createSession 方法（立即持久化）**

替换 `createSession` 方法体 (`ChatService.java:111-121`)：

```java
@Override
public String createSession(String agentId, String userId) {
    AgentGraph graph = agentRegistry.get(agentId);
    if (graph == null) {
        throw new AppException(ResponseCode.E0001.getCode());
    }

    String sessionId = UUID.randomUUID().toString().replace("-", "");
    log.info("创建会话 agentId={} userId={} sessionId={}", agentId, userId, sessionId);

    // 立即持久化空会话，使其出现在列表 API 中
    if (sessionRepository != null) {
        SessionEntity entity = SessionEntity.builder()
                .sessionId(sessionId)
                .userId(userId)
                .agentId(agentId)
                .status("ACTIVE")
                .stateJson(null)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        sessionRepository.save(entity);
    }

    return sessionId;
}
```

- [ ] **Step 3: 添加 Instant import（如未导入）**

检查 `ChatService.java` 是否已导入 `java.time.Instant`。如果没有，在 import 区域添加：
```java
import java.time.Instant;
```

- [ ] **Step 4: 添加 listSessions 方法**

在 `createSession` 方法后添加：

```java
/**
 * 查询用户在某 Agent 下的所有活跃会话。
 * 用于前端 Agent 折叠面板中的对话列表。
 */
public List<SessionItemDTO> listSessions(String agentId, String userId) {
    if (sessionRepository == null) {
        return List.of();
    }
    List<SessionEntity> entities = sessionRepository.listByUserIdAndAgentId(userId, agentId);
    return entities.stream()
            .map(e -> SessionItemDTO.builder()
                    .sessionId(e.getSessionId())
                    .agentId(e.getAgentId())
                    .userId(e.getUserId())
                    .title(extractSessionTitle(e))
                    .status(e.getStatus())
                    .createdAt(e.getCreatedAt())
                    .updatedAt(e.getUpdatedAt())
                    .build())
            .toList();
}

/**
 * 从会话的 AgentState JSON 中提取首条用户消息作为标题。
 * 无消息或 JSON 为空时返回 "新对话"。
 */
private String extractSessionTitle(SessionEntity entity) {
    if (entity.getStateJson() == null || entity.getStateJson().isEmpty()) {
        return "新对话";
    }
    try {
        @SuppressWarnings("unchecked")
        Map<String, Object> state = objectMapper.readValue(entity.getStateJson(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) state.get("messages");
        if (messages != null) {
            for (Map<String, Object> msg : messages) {
                if ("user".equals(msg.get("role"))) {
                    Object content = msg.get("content");
                    if (content instanceof String text && !text.isBlank()) {
                        return text.length() > 30 ? text.substring(0, 30) + "…" : text;
                    }
                }
            }
        }
    } catch (Exception e) {
        log.debug("提取会话标题失败: sessionId={}", entity.getSessionId());
    }
    return "新对话";
}
```

- [ ] **Step 5: 编译验证**

Run: `mvn clean compile -pl aether-domain -am`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/chat/ChatService.java
git commit -m "ChatService: createSession立即持久化 + 新增listSessions方法"
```

---

### Task 6: 后端 — IAgentService 接口

**Files:**
- Modify: `aether-api/src/main/java/cn/zcj/aether/api/IAgentService.java`

- [ ] **Step 1: 新增接口方法**

在 `chatStream` 方法声明前添加：

```java
Response<List<SessionItemDTO>> listSessions(
        @org.springframework.web.bind.annotation.RequestParam("agentId") String agentId,
        @org.springframework.web.bind.annotation.RequestParam("userId") String userId);
```

- [ ] **Step 2: 添加 import**

确保 import 区域包含：
```java
import cn.zcj.aether.api.dto.SessionItemDTO;
```
如果缺少则添加。

- [ ] **Step 3: Commit**

```bash
git add aether-api/src/main/java/cn/zcj/aether/api/IAgentService.java
git commit -m "IAgentService新增listSessions接口方法"
```

---

### Task 7: 后端 — AgentServiceController 端点

**Files:**
- Modify: `aether-trigger/src/main/java/cn/zcj/aether/trigger/http/AgentServiceController.java`

- [ ] **Step 1: 新增 listSessions 端点方法**

在 `chatStream` 方法之前添加：

```java
@RequestMapping(value = "list_sessions", method = RequestMethod.GET)
@Override
public Response<List<SessionItemDTO>> listSessions(
        @RequestParam("agentId") String agentId,
        @RequestParam("userId") String userId) {
    try {
        log.info("查询会话列表 agentId={} userId={}", agentId, userId);
        List<SessionItemDTO> sessions = chatServiceImpl.listSessions(agentId, userId);
        return Response.<List<SessionItemDTO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(sessions)
                .build();
    } catch (Exception e) {
        log.error("查询会话列表失败 agentId={} userId={}", agentId, userId, e);
        return Response.<List<SessionItemDTO>>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(ResponseCode.UN_ERROR.getInfo())
                .build();
    }
}
```

- [ ] **Step 2: 确认 import 存在**

确保 `java.util.List` 已导入（文件已在用）；确认 `SessionItemDTO` 的 import：
```java
import cn.zcj.aether.api.dto.SessionItemDTO;
```

- [ ] **Step 3: 编译验证**

Run: `mvn clean compile -pl aether-trigger -am`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add aether-trigger/src/main/java/cn/zcj/aether/trigger/http/AgentServiceController.java
git commit -m "AgentServiceController新增list_sessions端点"
```

---

### Task 8: 前端 — API 类型扩展

**Files:**
- Modify: `docs/dev-ops/aether-frontend/src/api/types.ts`

- [ ] **Step 1: 添加 SessionItemDTO 类型**

在 `AiAgentConfigDTO` 接口定义后（`agentDesc` 行后，`CreateSessionRequestDTO` 前）添加：

```typescript
/** 会话列表响应 DTO */
export interface SessionItemDTO {
  sessionId: string
  agentId: string
  userId: string
  title: string
  status: string
  createdAt: string   // ISO instant string
  updatedAt: string
}
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend/src/api/types.ts
git commit -m "前端类型新增SessionItemDTO"
```

---

### Task 9: 前端 — API session 模块扩展

**Files:**
- Modify: `docs/dev-ops/aether-frontend/src/api/session.ts`

- [ ] **Step 1: 添加 fetchSessions 函数**

在文件末尾的 `createSessionGet` 函数之后添加：

```typescript
import type { SessionItemDTO } from './types'

/**
 * GET /api/v1/list_sessions — 查询用户在指定 Agent 下的会话列表
 */
export function fetchSessions(
  agentId: string,
  userId: string
): Promise<SessionItemDTO[]> {
  return apiClient.get<unknown, SessionItemDTO[]>('/api/v1/list_sessions', {
    params: { agentId, userId }
  })
}
```

注意：`import type { SessionItemDTO }` 需要合并到文件顶部的 import 行：
```typescript
import type { CreateSessionRequestDTO, CreateSessionResponseDTO, SessionItemDTO } from './types'
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend/src/api/session.ts
git commit -m "前端session API新增fetchSessions函数"
```

---

### Task 10: 前端 — sessionStore 重构

**Files:**
- Modify: `docs/dev-ops/aether-frontend/src/stores/session.ts`

- [ ] **Step 1: 重写 sessionStore**

完整替换为：

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchSessions } from '@/api/session'
import type { SessionItemDTO } from '@/api/types'

export interface SessionInfo {
  sessionId: string
  agentId: string
  agentName: string
  title: string
  createdAt: number
}

const STORAGE_KEY = 'aether_sessions_v2'

export const useSessionStore = defineStore('session', () => {
  /** 按 agentId 分组的会话 */
  const sessionsByAgent = ref<Record<string, SessionInfo[]>>({})
  const currentSessionId = ref<string | null>(null)
  const isLoading = ref(false)

  const currentSession = computed(() => {
    if (!currentSessionId.value) return null
    for (const list of Object.values(sessionsByAgent.value)) {
      const found = list.find(s => s.sessionId === currentSessionId.value)
      if (found) return found
    }
    return null
  })

  /** 获取指定 Agent 的会话列表 */
  function getSessionsForAgent(agentId: string): SessionInfo[] {
    return sessionsByAgent.value[agentId] ?? []
  }

  /** 从后端加载指定 Agent 的会话列表 */
  async function loadSessions(agentId: string, userId: string): Promise<void> {
    isLoading.value = true
    try {
      const dtos: SessionItemDTO[] = await fetchSessions(agentId, userId)
      const infos: SessionInfo[] = dtos.map(dto => ({
        sessionId: dto.sessionId,
        agentId: dto.agentId,
        agentName: '', // will be filled by caller if needed
        title: dto.title || '新对话',
        createdAt: new Date(dto.createdAt).getTime()
      }))
      sessionsByAgent.value[agentId] = infos
      // 也持久化到 localStorage 做离线缓存
      saveToStorage()
    } catch {
      // 后端不可用时回退 localStorage
      loadFromStorage()
    } finally {
      isLoading.value = false
    }
  }

  /** 添加新会话到指定 Agent */
  function addSessionToAgent(session: SessionInfo): void {
    const list = sessionsByAgent.value[session.agentId]
    if (list) {
      list.unshift(session)
    } else {
      sessionsByAgent.value[session.agentId] = [session]
    }
    currentSessionId.value = session.sessionId
    saveToStorage()
  }

  /** 删除会话 */
  function deleteSession(sessionId: string, agentId: string): void {
    const list = sessionsByAgent.value[agentId]
    if (list) {
      sessionsByAgent.value[agentId] = list.filter(s => s.sessionId !== sessionId)
      if (Object.keys(sessionsByAgent.value[agentId]).length === 0) {
        delete sessionsByAgent.value[agentId]
      }
    }
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null
    }
    saveToStorage()
  }

  /** 切换当前会话 */
  function switchSession(sessionId: string): void {
    currentSessionId.value = sessionId
  }

  /** 更新会话标题 */
  function updateSessionTitle(sessionId: string, title: string): void {
    for (const list of Object.values(sessionsByAgent.value)) {
      const session = list.find(s => s.sessionId === sessionId)
      if (session) {
        session.title = title
        saveToStorage()
        return
      }
    }
  }

  function clearAll(): void {
    sessionsByAgent.value = {}
    currentSessionId.value = null
    saveToStorage()
  }

  // -- localStorage persistence (offline cache) --
  function loadFromStorage() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) {
        const data = JSON.parse(raw)
        sessionsByAgent.value = data.sessionsByAgent ?? {}
        currentSessionId.value = data.currentSessionId ?? null
      }
    } catch { /* ignore */ }
  }

  function saveToStorage() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        sessionsByAgent: sessionsByAgent.value,
        currentSessionId: currentSessionId.value
      }))
    } catch { /* ignore */ }
  }

  // Auto-load on store creation
  loadFromStorage()

  return {
    sessionsByAgent,
    currentSessionId,
    currentSession,
    isLoading,
    getSessionsForAgent,
    loadSessions,
    addSessionToAgent,
    deleteSession,
    switchSession,
    updateSessionTitle,
    clearAll
  }
})
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend/src/stores/session.ts
git commit -m "sessionStore重构为按agentId分组 + 后端数据加载"
```

---

### Task 11: 前端 — agentStore 扩展

**Files:**
- Modify: `docs/dev-ops/aether-frontend/src/stores/agent.ts`

- [ ] **Step 1: 添加 expandedAgentId 状态和方法**

在 `const backendError = ref<string | null>(null)` 后添加：

```typescript
/** 当前展开的 Agent ID（用于折叠面板） */
const expandedAgentId = ref<string | null>(null)

function toggleAgent(agentId: string): void {
  expandedAgentId.value = expandedAgentId.value === agentId ? null : agentId
}

function expandAgent(agentId: string): void {
  expandedAgentId.value = agentId
}

function collapseAgent(agentId: string): void {
  if (expandedAgentId.value === agentId) {
    expandedAgentId.value = null
  }
}
```

- [ ] **Step 2: 在 return 对象中添加导出**

在 `return { ... }` 对象中添加：
```typescript
expandedAgentId,
toggleAgent,
expandAgent,
collapseAgent,
```

- [ ] **Step 3: Commit**

```bash
git add docs/dev-ops/aether-frontend/src/stores/agent.ts
git commit -m "agentStore新增expandedAgentId折叠控制"
```

---

### Task 12: 前端 — ConversationRow 组件

**Files:**
- Create: `docs/dev-ops/aether-frontend/src/components/chat/ConversationRow.vue`

- [ ] **Step 1: 创建组件**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import type { SessionInfo } from '@/stores/session'

const props = defineProps<{
  session: SessionInfo
  isActive: boolean
}>()

const emit = defineEmits<{
  select: []
  delete: []
}>()

/** 相对时间文本 */
const timeAgo = computed(() => {
  const diff = Date.now() - props.session.createdAt
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return '刚刚'
  if (mins < 60) return `${mins}分钟前`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}天前`
  return new Date(props.session.createdAt).toLocaleDateString()
})
</script>

<template>
  <button
    @click="emit('select')"
    :class="[
      'w-full text-left px-3 py-2 rounded-lg text-sm group transition-colors duration-150 flex items-center gap-2',
      isActive
        ? 'bg-primary/10 text-primary-100'
        : 'text-primary/50 hover:bg-white/5 hover:text-primary-100'
    ]"
  >
    <!-- 左侧指示条 -->
    <div
      :class="[
        'w-[3px] h-4 rounded-full flex-shrink-0 transition-colors duration-150',
        isActive ? 'bg-primary' : 'bg-transparent'
      ]"
    />
    <!-- 标题 -->
    <span class="truncate flex-1 text-[13px] leading-tight">
      {{ session.title || '新对话' }}
    </span>
    <!-- 时间 + 删除 -->
    <div class="flex items-center gap-1 flex-shrink-0">
      <span class="text-[11px] text-primary/25 tabular-nums">
        {{ timeAgo }}
      </span>
      <button
        @click.stop="emit('delete')"
        class="opacity-0 group-hover:opacity-100 text-primary/30 hover:text-red-400 transition-all p-0.5"
        title="删除会话"
      >
        <Trash2 :size="12" />
      </button>
    </div>
  </button>
</template>
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend/src/components/chat/ConversationRow.vue
git commit -m "新增ConversationRow组件 — 单条会话行"
```

---

### Task 13: 前端 — AgentAccordionItem 组件（Apple Design 规范）

**Files:**
- Create: `docs/dev-ops/aether-frontend/src/components/chat/AgentAccordionItem.vue`

> **DESIGN REQUIREMENT:** 此组件必须严格遵循 apple-design 设计规范。实现前先 invoke `apple-design` skill 获取完整规范。

- [ ] **Step 1: Invoke apple-design skill**

在实现前，调用 `Skill` 工具加载 `apple-design` skill，确保组件实现符合 Apple HIG 规范。

- [ ] **Step 2: 创建组件**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { ChevronRight, Plus, Bot } from 'lucide-vue-next'
import ConversationRow from './ConversationRow.vue'
import type { SessionInfo } from '@/stores/session'

const props = defineProps<{
  agentId: string
  agentName: string
  agentDesc: string
  sessions: SessionInfo[]
  isExpanded: boolean
  isSelected: boolean
  currentSessionId: string | null
}>()

const emit = defineEmits<{
  toggle: []
  createSession: []
  selectSession: [sessionId: string]
  deleteSession: [sessionId: string]
}>()

/** 是否有对话 */
const hasConversations = computed(() => props.sessions.length > 0)
</script>

<template>
  <div class="rounded-xl overflow-hidden">
    <!-- 头部行 -->
    <button
      @click="emit('toggle')"
      :class="[
        'w-full flex items-center gap-2.5 px-3 py-2.5 rounded-xl transition-colors duration-150 group',
        isSelected
          ? 'bg-primary/10'
          : 'hover:bg-white/[0.04]'
      ]"
    >
      <!-- 展开箭头 -->
      <div
        :class="[
          'text-primary/40 transition-transform duration-250',
          'ease-[cubic-bezier(0.4,0,0.2,1)] flex-shrink-0',
          isExpanded ? 'rotate-90' : ''
        ]"
      >
        <ChevronRight :size="14" :stroke-width="2" />
      </div>

      <!-- Agent 信息 -->
      <div class="flex-1 min-w-0 text-left">
        <div
          :class="[
            'text-[13px] font-medium leading-tight truncate transition-colors duration-150',
            isSelected ? 'text-primary-100' : 'text-primary/70 group-hover:text-primary-100'
          ]"
        >
          {{ agentName }}
        </div>
        <div
          v-if="agentDesc"
          class="text-[11px] text-primary/30 mt-0.5 truncate leading-tight"
        >
          {{ agentDesc }}
        </div>
      </div>

      <!-- "+" 圆形按钮 -->
      <button
        @click.stop="emit('createSession')"
        class="w-8 h-8 rounded-full flex items-center justify-center
               bg-primary/10 text-primary/70
               hover:bg-primary/20 hover:text-primary-100 hover:scale-105
               active:scale-95
               transition-all duration-150 flex-shrink-0"
        title="新建对话"
      >
        <Plus :size="16" :stroke-width="2" />
      </button>
    </button>

    <!-- 对话列表（展开/折叠动画 max-height） -->
    <div
      :class="[
        'overflow-hidden transition-all duration-250 ease-[cubic-bezier(0.4,0,0.2,1)]',
        isExpanded ? 'max-h-96 opacity-100' : 'max-h-0 opacity-0'
      ]"
    >
      <div class="pl-7 pr-1 py-1 space-y-0.5">
        <!-- 无对话空状态 -->
        <div
          v-if="!hasConversations"
          class="text-[11px] text-primary/25 py-2 px-3"
        >
          暂无对话
        </div>

        <!-- 对话列表 -->
        <ConversationRow
          v-for="session in sessions"
          :key="session.sessionId"
          :session="session"
          :is-active="session.sessionId === currentSessionId"
          @select="emit('selectSession', session.sessionId)"
          @delete="emit('deleteSession', session.sessionId)"
        />
      </div>
    </div>
  </div>
</template>
```

- [ ] **Step 3: Commit**

```bash
git add docs/dev-ops/aether-frontend/src/components/chat/AgentAccordionItem.vue
git commit -m "新增AgentAccordionItem组件 — Agent折叠条目含对话列表和+按钮"
```

---

### Task 14: 前端 — SessionSidebar 重构

**Files:**
- Modify: `docs/dev-ops/aether-frontend/src/components/chat/SessionSidebar.vue`

- [ ] **Step 1: 重写 SessionSidebar**

完整替换为：

```vue
<script setup lang="ts">
import { watch, onMounted } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useChatStore } from '@/stores/chat'
import { useAgentStore } from '@/stores/agent'
import { useAuthStore } from '@/stores/auth'
import { createSession } from '@/api/session'
import { RefreshCw } from 'lucide-vue-next'
import AgentAccordionItem from './AgentAccordionItem.vue'

const sessionStore = useSessionStore()
const chatStore = useChatStore()
const agentStore = useAgentStore()
const authStore = useAuthStore()

// 加载 Agent 列表
onMounted(() => {
  if (!agentStore.hasAgents && !agentStore.isLoading) {
    agentStore.loadAgents()
  }
})

// 当选中 Agent 变化时，加载其会话列表
watch(() => agentStore.selectedAgentId, async (newId) => {
  if (newId && authStore.userId) {
    await sessionStore.loadSessions(newId, authStore.userId)
  }
})

/** 点击 Agent 条目切换展开/折叠 */
function handleToggleAgent(agentId: string) {
  agentStore.toggleAgent(agentId)
}

/** 点击 "+" 按钮 — 创建新会话 */
async function handleCreateSession(agentId: string) {
  if (!authStore.userId) return
  try {
    const res = await createSession(agentId, authStore.userId)
    const agent = agentStore.agents.find(a => a.agentId === agentId)
    sessionStore.addSessionToAgent({
      sessionId: res.sessionId,
      agentId,
      agentName: agent?.agentName ?? '',
      title: '新对话',
      createdAt: Date.now()
    })
    // 自动展开
    agentStore.expandAgent(agentId)
    // 清空聊天区域，准备新对话
    chatStore.clearMessages()
  } catch (err) {
    // toast handled by interceptor
  }
}

/** 选中某个会话 */
function handleSelectSession(sessionId: string) {
  sessionStore.switchSession(sessionId)
  chatStore.clearMessages()
}

/** 删除某个会话 */
function handleDeleteSession(sessionId: string, agentId: string) {
  sessionStore.deleteSession(sessionId, agentId)
  if (sessionStore.currentSessionId === null) {
    chatStore.clearMessages()
  }
}
</script>

<template>
  <div class="h-full flex flex-col bg-[#0a0a0a]">
    <!-- 标题 -->
    <div class="px-3 pt-3 pb-2 flex items-center justify-between">
      <span class="text-xs text-primary/50 uppercase tracking-wider">智能体</span>
      <!-- 刷新按钮 -->
      <button
        v-if="agentStore.hasAgents"
        @click="agentStore.loadAgents()"
        class="text-primary/40 hover:text-primary-100 transition-colors p-0.5"
        title="刷新智能体列表"
      >
        <RefreshCw
          :size="13"
          :class="agentStore.isLoading ? 'animate-spin' : ''"
        />
      </button>
    </div>

    <!-- Agent 列表区域 -->
    <div class="flex-1 overflow-y-auto px-3 pb-3 space-y-1">
      <!-- 加载中 -->
      <div v-if="agentStore.isLoading" class="text-xs text-primary/40 py-4 text-center">
        加载中…
      </div>

      <!-- 加载失败 -->
      <div v-else-if="agentStore.backendDown" class="py-4 text-center">
        <p class="text-xs text-primary/40 mb-2">{{ agentStore.backendError || '无法连接后端' }}</p>
        <button
          @click="agentStore.loadAgents()"
          class="flex items-center gap-1 text-xs text-primary/60 hover:text-primary-100 transition-colors mx-auto"
        >
          <RefreshCw :size="12" />
          <span>重试</span>
        </button>
      </div>

      <!-- Agent 列表 -->
      <template v-else-if="agentStore.hasAgents">
        <AgentAccordionItem
          v-for="agent in agentStore.agents"
          :key="agent.agentId"
          :agent-id="agent.agentId"
          :agent-name="agent.agentName"
          :agent-desc="agent.agentDesc"
          :sessions="sessionStore.getSessionsForAgent(agent.agentId)"
          :is-expanded="agentStore.expandedAgentId === agent.agentId"
          :is-selected="agentStore.selectedAgentId === agent.agentId"
          :current-session-id="sessionStore.currentSessionId"
          @toggle="handleToggleAgent(agent.agentId)"
          @create-session="handleCreateSession(agent.agentId)"
          @select-session="handleSelectSession"
          @delete-session="(sessionId: string) => handleDeleteSession(sessionId, agent.agentId)"
        />
      </template>

      <!-- 无 Agent -->
      <div v-else class="text-xs text-primary/40 py-4 text-center">
        暂无可用智能体
      </div>
    </div>
  </div>
</template>
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend/src/components/chat/SessionSidebar.vue
git commit -m "重构SessionSidebar为Agent折叠列表 + 独立新建会话"
```

---

### Task 15: 前端 — ChatView 适配

**Files:**
- Modify: `docs/dev-ops/aether-frontend/src/views/ChatView.vue`

- [ ] **Step 1: 修改 sendMessage 后的 session 存储逻辑**

在 `ChatView.vue` 的 `handleSend` 方法中，将原来的直接 `sessionStore.addSession(...)` 改为适配新的 API：

替换 `handleSend` 方法（`ChatView.vue:54-83`）：

```typescript
async function handleSend(message: string) {
  if (!agentStore.selectedAgentId) {
    toast.warning('请先选择一个智能体')
    return
  }
  if (!authStore.userId) {
    toast.error('未登录，请重新登录')
    router.push('/login')
    return
  }

  const hadSession = !!chatStore.sessionId

  try {
    await chatStore.sendMessage(message, agentStore.selectedAgentId, authStore.userId)

    // 首条消息后更新会话列表和标题
    if (!hadSession && chatStore.sessionId && agentStore.selectedAgent) {
      const newSession: SessionInfo = {
        sessionId: chatStore.sessionId,
        agentId: agentStore.selectedAgentId,
        agentName: agentStore.selectedAgent.agentName,
        title: message.slice(0, 30) + (message.length > 30 ? '…' : ''),
        createdAt: Date.now()
      }
      sessionStore.addSessionToAgent(newSession)
      // 更新后端会话标题（通过后续自动持久化生效；此处更新本地）
      sessionStore.updateSessionTitle(chatStore.sessionId, newSession.title)
    }
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '发送失败')
  }
}
```

- [ ] **Step 2: 确认 import**

确保 `SessionInfo` 类型已导入：
```typescript
import { useSessionStore, type SessionInfo } from '@/stores/session'
```

（如果 `SessionInfo` 未在 `useSessionStore` 同文件 export type，则在 `session.ts` 中确认已 export）

- [ ] **Step 3: Commit**

```bash
git add docs/dev-ops/aether-frontend/src/views/ChatView.vue
git commit -m "ChatView适配新的sessionStore API"
```

---

### Task 16: 端到端验证

- [ ] **Step 1: 启动后端**

```bash
cd aether-app && mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
Expected: 后端在 8091 端口启动，日志正常

- [ ] **Step 2: 启动前端**

```bash
cd docs/dev-ops/aether-frontend && npm run dev
```
Expected: 前端在 5173 端口启动

- [ ] **Step 3: 验证 "查询 Agent 列表"**

前端刷新 → 左侧栏显示 Agent 列表（加载自后端 API），应无 "网络错误"

- [ ] **Step 4: 验证 "点击 + 创建会话"**

点击 Agent 的 "+" 按钮 → 列表展开 → 显示 "新对话" → 聊天区域清空准备输入

- [ ] **Step 5: 验证 "展开/折叠动画"**

点击 Agent 条目 → 0.25s 平滑展开/折叠，箭头旋转 90°

- [ ] **Step 6: 验证 "后端 API — list_sessions"**

```bash
curl "http://localhost:8091/api/v1/list_sessions?agentId=YOUR_AGENT_ID&userId=admin"
```
Expected: 返回 JSON 数组，包含刚创建的会话

- [ ] **Step 7: 验证 "删除会话"**

hover 会话行 → 点击垃圾桶图标 → 会话从列表消失

- [ ] **Step 8: 验证 "发送消息后标题更新"**

选中会话 → 发送消息 → 刷新页面 → 会话标题变为首条消息截取
