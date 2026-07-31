import type { RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Landing',
    component: () => import('@/views/LandingView.vue'),
    meta: { title: 'Aether — Production-grade Multi-Agent AI Runtime', group: 'public' },
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录 — Aether', group: 'public' },
  },
  {
    path: '/app',
    component: () => import('@/components/common/AppLayout.vue'),
    meta: { requiresAuth: true, group: 'app' },
    children: [
      { path: '', redirect: { name: 'Dashboard' } },
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '仪表盘', icon: 'LayoutDashboard', group: 'dashboard' },
      },
      {
        path: 'chat',
        name: 'Chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '对话', icon: 'MessageSquare', group: 'chat' },
      },
      {
        path: 'agents',
        name: 'AgentManagement',
        component: () => import('@/views/AgentManagementView.vue'),
        meta: { title: '智能体管理', icon: 'Bot', group: 'agents' },
      },
      {
        path: 'agents/:id',
        name: 'AgentDetail',
        component: () => import('@/views/AgentDetailView.vue'),
        meta: { title: '智能体详情', group: 'agents' },
      },
      {
        path: 'models',
        name: 'ModelManagement',
        component: () => import('@/views/ModelManagementView.vue'),
        meta: { title: '模型管理', icon: 'Cpu', group: 'models' },
      },
      {
        path: 'skills',
        name: 'SkillManagement',
        component: () => import('@/views/SkillManagementView.vue'),
        meta: { title: '技能管理', icon: 'Wrench', group: 'skills' },
      },
      {
        path: 'memory',
        name: 'MemoryManagement',
        component: () => import('@/views/MemoryManagementView.vue'),
        meta: { title: '记忆管理', icon: 'Brain', group: 'memory' },
      },
      {
        path: 'workflows',
        name: 'WorkflowList',
        component: () => import('@/views/WorkflowListView.vue'),
        meta: { title: '工作流', icon: 'Workflow', group: 'workflows' },
      },
      {
        path: 'workflows/editor/:id?',
        name: 'WorkflowEditor',
        component: () => import('@/views/WorkflowEditorView.vue'),
        meta: { title: '工作流编辑器', group: 'workflows' },
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/SettingsView.vue'),
        meta: { title: '设置', icon: 'Settings', group: 'settings' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '404 — Aether', group: 'public' },
  },
]
