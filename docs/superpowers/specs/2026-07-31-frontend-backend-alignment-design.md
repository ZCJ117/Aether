# Frontend-Backend Alignment — Design Spec

**Date:** 2026-07-31
**Status:** Approved
**Topic:** Align aether backend API responses with aether-frontend-v2 UI rendering, and label mock-only management views as future releases.

---

## 1. Background

The aether backend (`aether-trigger/AgentServiceController.java`) exposes 6 real API endpoints. The aether-frontend-v2 consumes these for chat/conversation flows. However:

- Some backend response fields are unused or incorrectly mapped by the frontend
- One SSE event type (`done`) is not explicitly serialized by the backend
- 6 management views (Dashboard, Agent Management, Model Management, Skills, Memory, Workflows) rely entirely on mock data with no backend endpoints

The decision is **Option B**: keep mock data in management views, label them as future releases, and focus alignment on data that already flows through real APIs.

---

## 2. Scope & Boundaries

### In Scope

**Backend → Frontend data completion (3 items):**

1. Fix `SessionItemDTO` field utilization in `sessionStore.loadSessions()`:
   - `updatedAt` is hardcoded to `Date.now()` — use backend value
   - `agentName` is incorrectly set to `sessionId` — fix the mapping bug
   - `status` field is returned but not displayed — add error indicator in `ConversationRow`

2. Add explicit `done` event serialization in backend `serializeEvent()`

3. Add `internalLlmCall` event field serialization in backend `serializeEvent()`

**Management view "future version" labels (8 views):**

Add a unified info banner to: DashboardView, AgentManagementView, AgentDetailView, ModelManagementView, SkillManagementView, MemoryManagementView, WorkflowListView, WorkflowEditorView.

The banner text: "🚧 此功能为规划中的未来版本，当前展示为示例数据。Agent 配置当前通过 YAML 文件管理。"

For views with interactive CRUD controls (AgentManagementView, ModelManagementView), additionally disable create/edit/delete buttons.

### Out of Scope

- New backend CRUD endpoints for any management module
- Changes to YAML configuration mechanism
- Changes to `/login`, `/` (Landing) routes
- Changes to `ChatView` core conversation flow (already aligned)
- Real authentication backend (local mock login stays)
- i18n integration

---

## 3. Detailed Changes

### 3.1 Backend: `AgentServiceController.serializeEvent()` — add `done` case

**File:** `aether-trigger/.../AgentServiceController.java`
**Location:** `serializeEvent()` method, switch statement

Currently the `done` event type falls through to `default: {}`, producing an empty event:
```
data: {}\n\n
```

Change to explicitly serialize:
```java
case done -> {} // stream close is signaled by emitter.complete(), no payload needed
```

This makes the intent explicit and prevents confusion.

### 3.2 Backend: `AgentServiceController.serializeEvent()` — add `internalLlmCall` case

**File:** `aether-trigger/.../AgentServiceController.java`

Add serialization for `internalLlmCall` events (currently not serialized at all):

```java
case internalLlmCall -> {
    payload.put("source", event.getInternalLlmSource());
    payload.put("model", event.getInternalLlmModel());
    payload.put("durationMs", event.getInternalLlmDurationMs());
    payload.put("success", event.isInternalLlmSuccess());
}
```

### 3.3 Frontend: Fix `sessionStore.loadSessions()` field mapping

**File:** `docs/dev-ops/aether-frontend-v2/src/stores/session.ts`
**Location:** `loadSessions()` method, line 41-49

**Bug 1:** `agentName: item.sessionId` — should use the actual agent name or be omitted.

**Bug 2:** `updatedAt: Date.now()` — should use `new Date(item.updatedAt).getTime()`.

Fix:
```typescript
sessionsByAgent.value[agentId] = data.map((item) => ({
  sessionId: item.sessionId,
  agentId: item.agentId,
  agentName: '', // agent name comes from agentStore, not session data
  title: item.title || '新对话',
  status: item.status,
  createdAt: new Date(item.createdAt).getTime(),
  updatedAt: new Date(item.updatedAt).getTime(),
}))
```

### 3.4 Frontend: `ConversationRow.vue` — add error status indicator

**File:** `docs/dev-ops/aether-frontend-v2/src/components/chat/ConversationRow.vue`

Display a small red dot (6×6px) next to the session title when `status === 'ERROR'`.

### 3.5 Frontend: Management view banners (8 views)

Add a reusable `FutureVersionBanner.vue` component:

```vue
<template>
  <div class="future-version-banner">
    <span class="banner-icon">🚧</span>
    <span class="banner-text">
      此功能为规划中的未来版本，当前展示为示例数据。Agent 配置当前通过 YAML 文件管理。
    </span>
  </div>
</template>
```

Place at the top of each management view's main content area (below the page header, above the main content).

For **AgentManagementView** and **ModelManagementView**: additionally disable "新建"/"创建" buttons via `:disabled="true"` with `title="未来版本中开放"` tooltip.

### 3.6 Frontend: Remove unused `POST /api/v1/chat` export

**File:** `docs/dev-ops/aether-frontend-v2/src/api/chat.ts`

The `sendMessage()` function (blocking chat, line 9) is never called by any store or component. Add a comment noting it's kept for potential non-streaming use cases but currently unused.

---

## 4. Files Changed

### Backend

| File | Change |
|---|---|
| `aether-trigger/.../AgentServiceController.java` | Add `done` and `internalLlmCall` cases to `serializeEvent()` |

### Frontend

| File | Change |
|---|---|
| `src/stores/session.ts` | Fix `agentName` bug, use real `updatedAt` |
| `src/components/chat/ConversationRow.vue` | Add error status dot |
| `src/components/common/FutureVersionBanner.vue` | **New** — reusable banner component |
| `src/views/DashboardView.vue` | Add FutureVersionBanner |
| `src/views/AgentManagementView.vue` | Add FutureVersionBanner + disable create button |
| `src/views/AgentDetailView.vue` | Add FutureVersionBanner |
| `src/views/ModelManagementView.vue` | Add FutureVersionBanner + disable CRUD buttons |
| `src/views/SkillManagementView.vue` | Add FutureVersionBanner |
| `src/views/MemoryManagementView.vue` | Add FutureVersionBanner |
| `src/views/WorkflowListView.vue` | Add FutureVersionBanner |
| `src/views/WorkflowEditorView.vue` | Add FutureVersionBanner |
| `src/api/chat.ts` | Add comment on unused `sendMessage()` |

---

## 5. Verification

### Backend
- `done` events no longer produce `data: {}\n\n` — either clean empty line or no event at all
- `internalLlmCall` events carry `source`, `model`, `durationMs`, `success` fields

### Frontend
- Session list shows correct `updatedAt` timestamps (not `Date.now()`)
- `agentName` is no longer incorrectly set to `sessionId`
- Error-status sessions show red dot indicator in ConversationRow
- All 8 management views show the FutureVersionBanner at the top
- AgentManagementView and ModelManagementView create buttons are disabled

---

## 6. Excluded Items (with rationale)

| Item | Reason |
|---|---|
| Backend CRUD for Agent management | Future release — current YAML-driven config suffices |
| Backend CRUD for Model management | Future release — models configured via YAML |
| Backend dashboard stats API | Future release — telemetry not yet implemented |
| Backend skill CRUD | Future release — skills come from MCP/Skills YAML config |
| Backend memory CRUD | Future release — memory is file-based (`.claude/memory/`) |
| Backend workflow CRUD | Future release — workflows defined in YAML |
| Real auth backend | Future release — local mock login acceptable for dev |
| `sendMessage()` (blocking chat) removal | Kept as fallback; marked as currently unused |
