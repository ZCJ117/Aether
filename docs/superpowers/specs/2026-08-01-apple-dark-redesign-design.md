# Apple Dark 风格前端重设计 — 设计规格

**日期:** 2026-08-01
**范围:** 登录页 + 6 个登录后页面（仪表盘/对话/智能体/模型/技能/设置）
**不变:** 初始引导页（LandingView）完全保留

---

## 1. 设计目标

将 aether-frontend-v2 的 6 个核心页面统一为 Apple HIG 暗色风格：
- 毛玻璃材质侧边栏和顶栏
- 统一的暗色色彩体系（无蓝紫色深色感）
- 浅蓝 `#5AC8FA` 作为主交互强调色
- 微软雅黑优先的中文字体渲染
- 8pt 网格间距 + 连续圆角标度
- 0.5px 细线分隔替代粗边框

---

## 2. 色彩体系

### 2.1 背景层级

| 角色 | 色值 | 用途 |
|---|---|---|
| Base | `#000000` | 内容区底色 |
| Elevated | `#1C1C1E` | 侧边栏/顶栏底色 |
| Grouped | `#2C2C2E` | 卡片/分组背景 |
| Separator | `#3A3A3C` | 分隔线 |

### 2.2 文字色阶

| 角色 | 色值 | 用途 |
|---|---|---|
| Primary | `#F5F5F7` | 标题、正文 |
| Secondary | `#98989D` | 副标题、次要信息 |
| Tertiary | `#636366` | 辅助文字、占位符 |
| Quaternary | `#48484A` | 禁用文字 |

### 2.3 语义色彩

| 角色 | 色值 | 用途 |
|---|---|---|
| 主强调（浅蓝） | `#5AC8FA` | 按钮、链接、选中态、高亮 |
| 成功/正常 | `#30D158` | 活跃状态、连接成功 |
| 警告 | `#FFD60A` | 警告提示 |
| 提醒 | `#FF9F0A` | 暂停状态、中等告警 |
| 危险/删除 | `#FF453A` | 错误、删除、退出登录 |

### 2.4 色彩使用规则

- 主强调色仅用于交互元素（按钮、链接、选中态边框、统计数字高亮）
- 页面大面积不使用蓝色/紫色作背景
- 语义色仅用于状态指示，不作装饰

---

## 3. 字体

### 3.1 字体栈

```css
font-family: 'Microsoft YaHei', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Helvetica Neue', sans-serif;
```

### 3.2 字号层级

| 层级 | 大小 | 字重 | 用途 |
|---|---|---|---|
| Large Title | 28px | Bold 700 | 页面标题 |
| Title | 20px | Semibold 600 | 区块标题 |
| Headline | 16px | Semibold 600 | 卡片标题、列表主文字 |
| Body | 14px | Regular 400 | 正文、描述、表格内容 |
| Footnote | 12px | Regular 400 | 辅助说明、时间戳 |
| Caption | 11px | Medium 500 | 标签、分类、状态徽章 |

---

## 4. 毛玻璃参数

| 位置 | blur | saturate | 背景透明度 |
|---|---|---|---|
| 侧边栏 | 20px | 180% | `rgba(28,28,30,0.85)` |
| 顶栏 | 20px | 180% | `rgba(28,28,30,0.80)` |
| 登录卡片 | 30px | 200% | `rgba(28,28,30,0.85)` |
| 弹窗 | 30px | 200% | `rgba(44,44,46,0.65)` |

---

## 5. 圆角 & 间距

### 5.1 圆角标度

| 元素 | 圆角 |
|---|---|
| 小按钮/标签 | 6px |
| 输入框/分组按钮 | 10px |
| 卡片/列表项 | 14px |
| 模态/登录卡片 | 20px |
| 全圆标签/头像 | 999px |

### 5.2 间距（8pt 网格）

| 场景 | 值 |
|---|---|
| 页面外边距 | 20px |
| 卡片内边距 | 16px |
| 区块间距 | 20px |
| 同组元素间距 | 8px / 12px |
| 侧边栏（展开/折叠） | 260px / 64px |
| 顶栏高度 | 44px |

---

## 6. 各页面规格

### 6.1 登录页 (`LoginView.vue`)

- 全屏 `#000` 背景
- 居中毛玻璃卡片：`max-width: 360px`, `border-radius: 20px`, `blur(30px)`
- 品牌区域：圆角方块 + "A" 字母 + "Aether" 标题 + "Agent Platform" 副标题
- 输入框：暗色半透明 `rgba(44,44,46,0.6)`, `border: 0.5px solid rgba(255,255,255,0.1)`, 聚焦态浅蓝边框
- 登录按钮：浅蓝实心 `#5AC8FA`, 全宽, `border-radius: 10px`, 黑色文字
- 错误提示：红色浅底 `rgba(255,69,58,0.08)` + 红色文字
- 状态：默认 / 加载中（按钮内 spinner） / 错误

### 6.2 仪表盘 (`DashboardView.vue`)

- 页面标题 28px Bold
- 时间段切换：pill 组，选中态浅蓝底 `rgba(90,200,250,0.12)`
- 4 列统计卡片：14px 圆角半透明底，28px Bold 大数字 + 彩色趋势小字
- 图表区 2:1 分栏：左柱状图（浅蓝柱），右系统健康（彩色进度条）
- 底部双栏：智能体状态 + 最近会话
- 状态：正常 / 加载中 / 空数据

### 6.3 对话页 (`ChatView.vue`)

- 左侧栏 220px：
  - 顶部 Agent 下拉选择器（圆角按钮 + ▼ 指示）
  - 按 Agent 分组的会话列表，每个 Agent 组带 [+] 新建按钮
  - 选中会话：浅蓝底色 `rgba(90,200,250,0.08)`
- 消息区：
  - 用户消息：浅蓝实心气泡 `#5AC8FA` 右对齐，`border-radius: 14px 14px 4px 14px`
  - Agent 消息：暗灰半透明气泡 `rgba(44,44,46,0.6)` 左对齐，`border-radius: 14px 14px 14px 4px`，带 Agent 头像标识
  - 代码块：内嵌高亮 `rgba(90,200,250,0.1)` 底色
- 底部输入栏：暗色半透明输入框 + 浅蓝圆形发送按钮
- 状态：未选 Agent / 空会话 / 有消息 / 发送中

### 6.4 智能体管理 (`AgentManagementView.vue`)

- 页面标题 28px Bold
- 表格布局：`grid-template-columns: 2fr 1fr 1fr 1fr`
- 每行：双行文字（名称 Bold + 描述 Caption）+ 类型 + 模型 + 状态色点
- 状态：绿点 `#30D158` = 活跃，橙点 `#FF9F0A` = 暂停
- 14px 圆角整体卡片容器

### 6.5 模型管理 (`ModelManagementView.vue`)

- 页面标题 28px Bold
- 3 列卡片网格：`auto-fill, minmax(280px, 1fr)`
- 每张卡片：Provider 标签（CAPTION）+ 模型名（Headline）+ Context 大小 + 连接状态 + "测试连接" 链接
- 状态：绿 = 已连接，红 = 未连接

### 6.6 技能管理 (`SkillManagementView.vue`)

- 页面标题 28px Bold
- 按分类分组，每组有 CAPTION 标注
- 技能行：名称 Bold + 描述 Footnote + Apple 风格 Toggle 开关
- Toggle：40px × 22px，绿色 `#30D158`（开）/ 灰色 `rgba(255,255,255,0.1)`（关）

### 6.7 设置页 (`SettingsView.vue`)

- 页面标题 28px Bold
- iOS grouped list 风格：每节 CAPTION 标注 + 圆角卡片容器
- API Token 节：当前 Token（遮罩显示）+ 新 Token 输入 + 连接地址
- 长期记忆节：标注当前绑定的 Agent 名，记忆摘要 + 时间戳 + 编辑/添加链接
- 账户节：退出登录（红色 `#FF453A` 居中文字按钮）
- 切换 Agent 时长期记忆自动刷新

---

## 7. 侧边栏 & 顶栏（AppShell）

### 7.1 侧边栏
- 毛玻璃 `blur(20px) saturate(180%)`, 背景 `rgba(28,28,30,0.85)`
- 展开 260px / 折叠 64px
- 导航项：选中态浅蓝底 `rgba(90,200,250,0.1)` + 左边条 `3px solid #5AC8FA`
- 非选中态：灰色 `#98989D` 半透明
- 底部固定"设置"项
- 项间距 3px，圆角 10px
- 无需分隔边框，用颜色区分即可

### 7.2 顶栏
- 毛玻璃 `blur(20px) saturate(180%)`, 背景 `rgba(28,28,30,0.80)`
- 高度 44px
- 仅保留：侧边栏折叠按钮 + 面包屑导航
- 底部 `0.5px solid rgba(255,255,255,0.06)` 分隔线
- 删除：搜索框、通知铃铛、主题切换、语言切换

---

## 8. 交互 & 动画

### 8.1 过渡动画
- 页面路由切换：`opacity 0.2s ease`
- 侧边栏折叠：`transition-all duration-300`
- 按钮 hover：背景色微变 `transition: background-color 0.15s`
- 输入框聚焦：边框色变为浅蓝，无 glow 扩散
- Toggle 开关：`transition: background-color 0.25s`
- 不使用 spring 动画（CSS 能力有限），使用 `ease` 缓动

### 8.2 滚动条
- 保持当前细滚动条样式（6px 宽，半透明）
- 颜色适配新色彩体系

---

## 9. 实现策略

### 9.1 修改顺序
1. **全局基础** — `style.css` CSS 变量、`tailwind.config` 颜色扩展、字体栈
2. **AppShell** — `AppLayout.vue`, `AppSidebar.vue`, `AppHeader.vue`
3. **登录页** — `LoginView.vue`
4. **仪表盘** — `DashboardView.vue`
5. **对话页** — `ChatView.vue`, `ChatLayout.vue`
6. **列表页** — `AgentManagementView.vue`, `ModelManagementView.vue`, `SkillManagementView.vue`
7. **设置页** — `SettingsView.vue`
8. **验证** — 全量 type-check + build

### 9.2 不修改的文件
- `LandingView.vue` 及所有 `src/components/landing/` 文件
- 后端任何代码
- 路由定义
- Store 逻辑
- API 层

### 9.3 可删除的文件
- `ThemeToggle.vue`, `LocaleToggle.vue` — 已无引用
- `SearchInput.vue` — 已无引用

---

## 10. 约束

- 不引入新的 npm 依赖
- 不修改 Vue Router 路由结构
- 不修改 Pinia store 逻辑
- 保持现有 Lucide 图标库（已足够接近 SF Symbols 风格）
- 保持现有功能逻辑不变（仅改样式）
