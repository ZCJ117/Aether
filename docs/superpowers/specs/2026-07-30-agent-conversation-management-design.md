# Agent 多对话管理 — 设计文档

**日期**: 2026-07-30
**状态**: 已确认
**分支**: master

---

## 1. 背景与目标

### 现状
- 前端左侧栏包含 Agent 选择器 + 全局扁平会话列表，会话不按 Agent 分组
- 创建会话绑定在"发送第一条消息"时触发，无法预先创建空白会话
- 后端无会话列表 API，前端仅靠 localStorage 维护会话元数据
- 会话数据无法跨设备同步、刷新页面后历史消息丢失

### 目标
1. 每个 Agent 条目右侧增加 "+" 圆形按钮，点击即创建空白会话
2. 每个 Agent 条目可展开/折叠，展示该 Agent 下的所有会话
3. 创建首个会话时自动展开列表、自动选中新会话、进入聊天界面
4. 会话数据从后端 API 拉取，替代纯前端 localStorage 维护
5. 前端遵循 Apple Design 设计规范

---

## 2. 设计决策记录

| 决策 | 选项 | 理由 |
|------|------|------|
| 功能位置 | 替换整个左侧栏 | 统一入口，避免新旧两套 UI 并存 |
| 会话数据来源 | 新增后端 API | 可靠、跨设备同步、支持会话恢复 |
| 空会话命名 | "新对话" | 简洁，与现 fallback 一致，首条消息后自动更新 |
| 创建后行为 | 展开列表 + 自动选中进入聊天 | 减少点击步骤，流畅体验 |
| 动画时长 | 0.25s | 符合 Apple HIG 微交互建议 |

---

## 3. 后端改动

### 3.1 新增 API: 查询会话列表

```
GET /api/v1/list_sessions?agentId={agentId}&userId={userId}
```

**响应体:**
```json
{
  "code": "0000",
  "info": "成功",
  "data": [
    {
      "sessionId": "abc123def456",
      "agentId": "agent-1",
      "userId": "admin",
      "title": "帮我分析这段代码",
      "status": "ACTIVE",
      "createdAt": "2026-07-30T10:30:00Z",
      "updatedAt": "2026-07-30T10:35:00Z"
    }
  ]
}
```

**实现路径:**
```
AgentServiceController.listSessions()
  → ChatService.listSessions(agentId, userId)
    → SessionRepository.listByUserIdAndAgentId(userId, agentId)
      → PgSessionRepository: SELECT * WHERE user_id=? AND agent_id=? AND status='ACTIVE' ORDER BY updated_at DESC
      → RedisSessionRepository: 暂返回空列表（Redis 实现不在此次范围）
```

### 3.2 改动: 创建会话时立即持久化

**文件:** `ChatService.createSession()`

**现状:** 仅生成 UUID 返回，会话在首次 Agent 执行后才由 `SessionPersistenceHook` 落库。

**改动:** 创建 sessionId 后立即调用 `sessionRepository.save()` 写入一条 `status=ACTIVE`、`stateJson=null` 的记录。这样新会话立刻出现在列表 API 的返回结果中。

**注意:** `save()` 是 `CompletableFuture<Void>`，调用时不阻塞返回。前端在 session 列表 API 中可能看不到刚创建的会话（异步写入延迟），通过前端创建后立即追加到本地列表来补偿。

### 3.3 新增文件清单

| 文件 | 说明 |
|------|------|
| `aether-api/.../dto/SessionItemDTO.java` | 会话列表项 DTO |
| `aether-domain/.../session/SessionRepository.java` | 新增 `listByUserIdAndAgentId` 方法 |
| `aether-infrastructure/.../PgSessionRepository.java` | 实现新查询方法 |
| `aether-infrastructure/.../RedisSessionRepository.java` | 空实现（返回空列表） |

### 3.4 修改文件清单

| 文件 | 改动 |
|------|------|
| `ChatService.java` | `createSession()` 立即持久化；新增 `listSessions()` |
| `AgentServiceController.java` | 新增 `listSessions` 端点 |
| `IAgentService.java` | 新增 `listSessions` 接口方法 |

---

## 4. 前端改动

### 4.1 组件树（重构后）

```
ChatView.vue
  └── ChatLayout.vue
        ├── SessionSidebar.vue (重构)
        │   └── AgentAccordionItem.vue (新组件, v-for agents)
        │       ├── 头部行
        │       │   ├── ChevronRight 图标 (展开/折叠, 0.25s 旋转)
        │       │   ├── Agent 名称 + 描述
        │       │   └── [+] 圆形按钮 (32×32, Plus 图标)
        │       └── 对话列表 (Transition, max-height 动画)
        │           └── ConversationRow.vue (新组件, v-for sessions)
        │               ├── 会话标题 (truncate)
        │               ├── 相对时间 (如 "3分钟前")
        │               └── 删除按钮 (Trash2, hover 出现)
        └── 聊天主区域 (不变)
```

### 4.2 新增组件

#### AgentAccordionItem.vue

**Props:**
- `agent: AiAgentConfigDTO`
- `sessions: SessionInfo[]`
- `isExpanded: boolean`
- `isSelected: boolean` — 当前活跃会话是否属于此 Agent

**Emits:**
- `toggle` — 展开/折叠
- `create-session` — 点击 "+" 按钮
- `select-session(sessionId)` — 选中某个会话
- `delete-session(sessionId)` — 删除某个会话

**Apple Design 要点:**
- 圆角 12px 容器，微妙背景色区分选中态
- 展开箭头使用 `ChevronRight` 图标，旋转 90° 表示展开，`transition-transform duration-250`
- "+" 按钮: 32×32 纯圆形，SF-style 加号图标（线宽 2px），hover 时微缩放(1.05) + 背景加深
- 对话列表使用 Vue `<Transition>` + `max-height` 动画，`duration-250 ease-[cubic-bezier(0.4,0,0.2,1)]`
- 选中会话行：左侧 3px 宽圆角指示条，浅蓝背景

#### ConversationRow.vue

**Props:**
- `session: SessionInfo`
- `isActive: boolean`

**Emits:**
- `select`
- `delete`

### 4.3 Store 改动

#### sessionStore

```typescript
// 从扁平列表 → 按 agentId 分组
const sessionsByAgent = ref<Record<string, SessionInfo[]>>({})

// 新增方法
async function loadSessions(agentId: string, userId: string): Promise<void>
function getSessionsForAgent(agentId: string): SessionInfo[]
function addSessionToAgent(session: SessionInfo): void
function removeSessionFromAgent(sessionId: string, agentId: string): void
```

保留 `currentSessionId` 和 `switchSession()` 逻辑不变。

#### agentStore

```typescript
// 新增
const expandedAgentId = ref<string | null>(null)

function toggleAgent(agentId: string): void
function expandAgent(agentId: string): void  // 强制展开（用于创建首个会话后）
function collapseAgent(agentId: string): void
```

### 4.4 数据流

```
页面加载
  │
  ├─ agentStore.loadAgents() → GET /api/v1/query_ai_agent_config_list
  │
  └─ 默认展开上次选中的 Agent, 加载其会话列表
       └─ sessionStore.loadSessions(agentId, userId)
            → GET /api/v1/list_sessions?agentId={agentId}&userId={userId}

点击 [+]:
  │
  ├─ POST /api/v1/create_session { agentId, userId }
  │     → { sessionId }
  │
  ├─ sessionStore.addSessionToAgent({ sessionId, agentId, title: "新对话", ... })
  │
  ├─ agentStore.expandAgent(agentId)   // 自动展开
  │
  └─ sessionStore.switchSession(sessionId)  // 自动选中 → 清空聊天区域

点击会话行:
  ├─ sessionStore.switchSession(sessionId)
  └─ chatStore.clearMessages()  // 切换到空白上下文
       (注: 消息恢复能力后续迭代再做)
```

### 4.5 删除项

- `AgentSelector.vue` — Agent 选择逻辑融入 `AgentAccordionItem`
- 全局 "新建对话" 按钮 — 改为每个 Agent 独立的 "+" 按钮
- `sessionStore` 的 `sessions` 扁平数组 — 改为 `sessionsByAgent` 分组记录

### 4.6 新增文件清单

| 文件 | 说明 |
|------|------|
| `src/components/chat/AgentAccordionItem.vue` | Agent 折叠条目（头部 + 对话列表） |
| `src/components/chat/ConversationRow.vue` | 单条会话行 |
| `src/api/session.ts` | 新增 `fetchSessions(agentId, userId)` |
| `src/api/types.ts` | 新增 `SessionItemDTO` 类型 |

### 4.7 修改文件清单

| 文件 | 改动 |
|------|------|
| `SessionSidebar.vue` | 重构：用 AgentAccordionItem 替换 AgentSelector + 扁平会话列表 |
| `stores/session.ts` | 按 agentId 分组存储，新增 loadSessions 方法 |
| `stores/agent.ts` | 新增 expandedAgentId 状态和折叠控制方法 |
| `ChatView.vue` | 适配新的 session 创建流程 |

---

## 5. Apple Design 规范清单

| 规范 | 应用 |
|------|------|
| **圆角与间距** | 容器 `rounded-xl`(12px)，内部 padding 12-16px，元素间距 8px |
| **SF-style 图标** | 使用 Lucide 图标库，线宽 `stroke-width: 2`，尺寸 16-20px |
| **动画** | 展开/折叠 `duration-250` + `cubic-bezier(0.4,0,0.2,1)`；"+"" 按钮 hover `scale(1.05)` |
| **字体** | 标题 `font-medium`、描述 `text-xs text-secondary`、时间 `text-[11px] text-tertiary` |
| **颜色** | 选中态浅蓝 `bg-primary/10`，指示条 `bg-primary`，分隔线 `border-white/5` |
| **触控友好** | 最小点击区域 32×32px（"+"" 按钮），行高 ≥ 40px |
| **阴影/层级** | 左侧栏使用 subtle 右侧内阴影分隔，不浮于内容之上 |
| **触觉反馈** | 无（Web 限制），但动画曲线模拟物理感觉 |

---

## 6. 测试策略

### 后端
- **单元测试:** `ChatServiceTest` — 验证 `createSession` 立即持久化、`listSessions` 过滤正确
- **集成测试:** `AgentServiceControllerTest` — 验证新端点返回正确 JSON 结构

### 前端
- **组件测试:** `AgentAccordionItem` 展开/折叠、"+"" 按钮触发事件
- **E2E:** Playwright — 点击 "+" → 会话出现在列表 → 自动展开 → 自动选中

---

## 7. 风险与限制

| 风险 | 缓解 |
|------|------|
| `createSession` 立即持久化但异步写入，列表 API 可能暂时看不到新会话 | 前端创建后立即追加到本地列表，不等待列表 API 刷新 |
| `RedisSessionRepository.listByUserId` 返回空列表 | 标注为已知限制，Redis 模式下列表为空 |
| 切换会话时消息不恢复（需要额外后端支持） | 本次不实现消息恢复，后续迭代处理 |
| 旧 `SessionStore` 与 `SessionRepository` 两套实现并存 | 本次不改 `SessionStore`，仅基于 `SessionRepository` 扩展 |
