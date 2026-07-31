# Frontend-Backend Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix backend SSE event serialization bugs, correct frontend session field mapping, add error status indicators, and label 8 mock-only management views as future releases.

**Architecture:** Minimal surgical changes — 1 backend file (add two switch cases), 12 frontend files (1 new component + 11 modifications). No new dependencies, no architectural changes.

**Tech Stack:** Java 17 (backend), Vue 3 + TypeScript + Pinia (frontend)

---

## Task 1: Backend — Add `done` and `internalLlmCall` cases to `serializeEvent()`

**Files:**
- Modify: `aether-trigger/src/main/java/cn/zcj/aether/trigger/http/AgentServiceController.java:389`

- [ ] **Step 1: Replace the `default` case with explicit `done` and `internalLlmCall` cases**

The current code at line 389 falls through to `default -> {}` for `done`, `internalLlmCall`, and `maxTurnsReached`. Replace `default -> {}` with:

```java
                case internalLlmCall -> {
                    payload.put("source", event.getInternalLlmSource());
                    payload.put("model", event.getInternalLlmModel());
                    payload.put("durationMs", event.getInternalLlmDurationMs());
                    payload.put("success", event.isInternalLlmSuccess());
                }
                case done -> {} // stream close is signaled by emitter.complete(), no payload needed
                default -> {}   // maxTurnsReached and any future types — no extra payload
```

The exact edit: find `default -> {}` (line 389) and replace with the three-case block above.

- [ ] **Step 2: Build backend to verify compilation**

```bash
cd D:/code/Agents-framework/aether && mvn clean compile -pl aether-trigger -am -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add aether-trigger/src/main/java/cn/zcj/aether/trigger/http/AgentServiceController.java
git commit -m "后端 SSE 序列化补全 done 和 internalLlmCall 事件"
```

---

## Task 2: Frontend — Fix `sessionStore.loadSessions()` field mapping

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/stores/session.ts:41-49`

- [ ] **Step 1: Fix the two bugs in `loadSessions()`**

The current code (lines 41-49):
```typescript
sessionsByAgent.value[agentId] = data.map((item) => ({
  sessionId: item.sessionId,
  agentId: item.agentId,
  agentName: item.sessionId,   // BUG: should be empty string
  title: item.title || '新对话',
  status: item.status,
  createdAt: new Date(item.createdAt).getTime(),
  updatedAt: Date.now(),        // BUG: should use backend value
}))
```

Replace with:
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

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/stores/session.ts
git commit -m "修复 sessionStore 字段映射 bug: agentName 和 updatedAt"
```

---

## Task 3: Frontend — Add error status indicator to `ConversationRow.vue`

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/components/chat/ConversationRow.vue`

- [ ] **Step 1: Add red error dot next to session title**

Between the title `<span>` (line 28) and the time `<span>` (line 29), add a conditional red dot:

```vue
<span class="flex-1 truncate">{{ session.title }}</span>
<!-- Error status indicator -->
<span
  v-if="session.status === 'ERROR'"
  class="flex-shrink-0 w-1.5 h-1.5 rounded-full bg-red-500"
  title="会话异常"
></span>
<span class="flex-shrink-0 text-xs text-[#DEDBC8]/30">{{ relativeTime(session.createdAt) }}</span>
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/components/chat/ConversationRow.vue
git commit -m "ConversationRow 增加 ERROR 状态红色指示点"
```

---

## Task 4: Frontend — Create `FutureVersionBanner.vue` component

**Files:**
- Create: `docs/dev-ops/aether-frontend-v2/src/components/common/FutureVersionBanner.vue`

- [ ] **Step 1: Create the reusable banner component**

```vue
<script setup lang="ts">
defineProps<{
  message?: string
}>()
</script>

<template>
  <div class="future-version-banner">
    <span class="banner-icon">🚧</span>
    <span class="banner-text">
      {{ message || '此功能为规划中的未来版本，当前展示为示例数据。Agent 配置当前通过 YAML 文件管理。' }}
    </span>
  </div>
</template>

<style scoped>
.future-version-banner {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 1rem;
  margin-bottom: 1rem;
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.2);
  border-radius: 8px;
}

.banner-icon {
  font-size: 0.875rem;
  flex-shrink: 0;
}

.banner-text {
  font-size: 0.8125rem;
  color: #f59e0b;
  line-height: 1.4;
}
</style>
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/components/common/FutureVersionBanner.vue
git commit -m "新增 FutureVersionBanner 组件 — 未来版本提示横幅"
```

---

## Task 5: Frontend — Add FutureVersionBanner to DashboardView

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/views/DashboardView.vue`

- [ ] **Step 1: Import and place the banner below the page header**

Add import after existing imports (line 3):
```typescript
import FutureVersionBanner from '@/components/common/FutureVersionBanner.vue'
```

Insert the banner between `<div class="dashboard-header">` (closing at line 33) and `<!-- Stat Cards -->` (line 36):
```vue
    </div>

    <FutureVersionBanner />

    <!-- Stat Cards -->
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/views/DashboardView.vue
git commit -m "DashboardView 添加未来版本提示横幅"
```

---

## Task 6: Frontend — Add FutureVersionBanner to AgentManagementView + disable create button

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/views/AgentManagementView.vue`

- [ ] **Step 1: Import banner and add it, disable create button**

Add import after line 3:
```typescript
import FutureVersionBanner from '@/components/common/FutureVersionBanner.vue'
```

Insert banner after `</div>` closing the page-header (line 29):
```vue
    </div>

    <FutureVersionBanner />

    <div v-if="agentStore.managementLoading" class="loading">加载中...</div>
```

Disable the create button (line 28) by adding `disabled` and `title`:
```vue
<button class="btn-create" disabled title="未来版本中开放">+ 创建智能体</button>
```

Add disabled styling in `<style scoped>`:
```css
.btn-create:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
```

Also disable the edit/delete buttons in each row (lines 59-60):
```vue
<button class="btn-edit" disabled title="未来版本中开放">编辑</button>
<button class="btn-delete" disabled title="未来版本中开放">删除</button>
```

Add disabled styling:
```css
.btn-edit:disabled, .btn-delete:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/views/AgentManagementView.vue
git commit -m "AgentManagementView 添加未来版本提示 + 禁用操作按钮"
```

---

## Task 7: Frontend — Add FutureVersionBanner to AgentDetailView

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/views/AgentDetailView.vue`

- [ ] **Step 1: Import and place banner**

Add import after line 3:
```typescript
import FutureVersionBanner from '@/components/common/FutureVersionBanner.vue'
```

Insert banner after `</div>` closing page-header (line 33):
```vue
    </div>

    <FutureVersionBanner />

    <div v-if="!agent" class="loading">加载中...</div>
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/views/AgentDetailView.vue
git commit -m "AgentDetailView 添加未来版本提示横幅"
```

---

## Task 8: Frontend — Add FutureVersionBanner to ModelManagementView + disable CRUD buttons

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/views/ModelManagementView.vue`

- [ ] **Step 1: Import banner, add it, disable buttons**

Add import after line 3:
```typescript
import FutureVersionBanner from '@/components/common/FutureVersionBanner.vue'
```

Insert banner after `</div>` closing page-header (line 17):
```vue
    </div>

    <FutureVersionBanner />

    <div v-if="modelStore.isLoading" class="loading">加载中...</div>
```

Disable the create button (line 16):
```vue
<button class="btn-create" disabled title="未来版本中开放">+ 添加模型</button>
```

Disable the test button in each card (line 42):
```vue
<button class="btn-test" disabled title="未来版本中开放">测试连接</button>
```

Add disabled styling:
```css
.btn-create:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.btn-test:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/views/ModelManagementView.vue
git commit -m "ModelManagementView 添加未来版本提示 + 禁用操作按钮"
```

---

## Task 9: Frontend — Add FutureVersionBanner to SkillManagementView

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/views/SkillManagementView.vue`

- [ ] **Step 1: Import and place banner**

Add import after line 3:
```typescript
import FutureVersionBanner from '@/components/common/FutureVersionBanner.vue'
```

Insert banner after `</div>` closing page-header (line 16):
```vue
    </div>

    <FutureVersionBanner />

    <div v-if="skillStore.isLoading" class="loading">加载中...</div>
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/views/SkillManagementView.vue
git commit -m "SkillManagementView 添加未来版本提示横幅"
```

---

## Task 10: Frontend — Add FutureVersionBanner to MemoryManagementView

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/views/MemoryManagementView.vue`

- [ ] **Step 1: Import and place banner**

Add import after line 3:
```typescript
import FutureVersionBanner from '@/components/common/FutureVersionBanner.vue'
```

Insert banner after `</div>` closing page-header (line 16):
```vue
    </div>

    <FutureVersionBanner />

    <div v-if="memoryStore.isLoading" class="loading">加载中...</div>
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/views/MemoryManagementView.vue
git commit -m "MemoryManagementView 添加未来版本提示横幅"
```

---

## Task 11: Frontend — Add FutureVersionBanner to WorkflowListView

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/views/WorkflowListView.vue`

- [ ] **Step 1: Import and place banner**

Add import after line 3:
```typescript
import FutureVersionBanner from '@/components/common/FutureVersionBanner.vue'
```

Insert banner after `</div>` closing page-header (line 32):
```vue
    </div>

    <FutureVersionBanner />

    <div v-if="workflowStore.workflows.length === 0" class="empty">
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/views/WorkflowListView.vue
git commit -m "WorkflowListView 添加未来版本提示横幅"
```

---

## Task 12: Frontend — Add FutureVersionBanner to WorkflowEditorView

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/views/WorkflowEditorView.vue`

- [ ] **Step 1: Import and place banner**

Add import after line 3:
```typescript
import FutureVersionBanner from '@/components/common/FutureVersionBanner.vue'
```

Insert banner after `</div>` closing editor-header (line 44):
```vue
    </div>

    <FutureVersionBanner />

    <div class="editor-canvas">
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/views/WorkflowEditorView.vue
git commit -m "WorkflowEditorView 添加未来版本提示横幅"
```

---

## Task 13: Frontend — Add comment on unused `sendMessage()` in chat.ts

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/api/chat.ts:8-15`

- [ ] **Step 1: Add JSDoc comment noting the function is currently unused**

Insert before the `sendMessage` function (line 8):
```typescript
/**
 * 阻塞式对话（当前未使用，前端统一使用 SSE 流式对话）。
 * 保留作为未来非流式场景的备用接口。
 */
export function sendMessage(
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev-ops/aether-frontend-v2/src/api/chat.ts
git commit -m "chat.ts 标注 sendMessage 为当前未使用的备用接口"
```

---

## Task 14: Build Verification

- [ ] **Step 1: Verify backend compiles**

```bash
cd D:/code/Agents-framework/aether && mvn clean compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Verify frontend compiles (if Node.js available)**

```bash
cd D:/code/Agents-framework/aether/docs/dev-ops/aether-frontend-v2 && npx vue-tsc --noEmit 2>&1 | head -20
```

Note: Vue SFC type-checking may produce pre-existing warnings unrelated to these changes. Focus only on new errors in the files we modified.

- [ ] **Step 3: Commit any remaining changes**

```bash
git status
```

If clean, no action needed. If any uncommitted changes remain, review and commit.
