<script setup>
import BrandMark from '@/components/common/BrandMark.vue'
import UserPill from './UserPill.vue'
import AppButton from '@/components/common/AppButton.vue'

defineProps({
  username: { type: String, default: '' },
  loginMeta: { type: String, default: '' }
})

defineEmits(['logout'])
</script>

<template>
  <header class="topbar">
    <div class="topbar-inner">
      <BrandMark size="sm" />
      <div class="topbar-slot">
        <slot name="selector" />
      </div>
      <div class="spacer" />
      <UserPill :username="username" :meta="loginMeta" />
      <AppButton variant="outline" @click="$emit('logout')">退出</AppButton>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  background: linear-gradient(180deg, rgba(245, 245, 247, 0.97), rgba(245, 245, 247, 0.82));
  backdrop-filter: blur(18px) saturate(180%);
  -webkit-backdrop-filter: blur(18px) saturate(180%);
  border-bottom: 1px solid var(--border-light);
  transition: border-color 0.3s ease;
}

.topbar-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 12px 24px;
  display: flex;
  align-items: center;
  gap: 16px;
}

.topbar-slot {
  flex: 1 1 auto;
  max-width: 320px;
  position: relative;
}

.spacer {
  flex: 1 1 auto;
}

@media (max-width: 767px) {
  .topbar-inner {
    flex-wrap: wrap;
    gap: 10px;
    padding: 10px 16px;
  }

  .topbar-slot {
    max-width: none;
    order: 3;
    flex: 1 1 100%;
  }
}
</style>
