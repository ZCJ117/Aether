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
