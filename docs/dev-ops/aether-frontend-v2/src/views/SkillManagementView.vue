<script setup lang="ts">
import { onMounted } from 'vue'
import { useSkillStore } from '@/stores/skill'

const skillStore = useSkillStore()

onMounted(() => {
  skillStore.loadSkills()
})
</script>

<template>
  <div class="skill-management">
    <div class="page-header">
      <h1>技能管理</h1>
    </div>

    <div v-if="skillStore.isLoading" class="loading">加载中...</div>

    <div v-else-if="skillStore.skills.length === 0" class="empty">
      <p>暂无技能</p>
    </div>

    <div v-else class="skills-list">
      <!-- Categories -->
      <div v-for="cat in skillStore.categories" :key="cat.id" class="category-section">
        <h3>{{ cat.name }} <span class="cat-count">{{ cat.count }}</span></h3>
        <div class="skill-items">
          <div
            v-for="skill in skillStore.skills.filter((s) => s.category === cat.id)"
            :key="skill.id"
            class="skill-item"
          >
            <div class="skill-info">
              <span class="skill-name">{{ skill.name }}</span>
              <span class="skill-desc">{{ skill.description }}</span>
              <span class="skill-source">{{ skill.source }} / {{ skill.type }}</span>
            </div>
            <label class="toggle">
              <input
                type="checkbox"
                :checked="skill.enabled"
                @change="skillStore.toggleSkill(skill.id)"
              />
              <span class="toggle-slider"></span>
            </label>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.skill-management {
  padding: 1.5rem;
}

.page-header {
  margin-bottom: 1.5rem;
}

.page-header h1 {
  font-size: 1.5rem;
  margin: 0;
  color: var(--text-primary, #eee);
}

.loading, .empty {
  text-align: center;
  padding: 3rem;
  color: var(--text-secondary, #888);
}

.skills-list {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.category-section h3 {
  margin: 0 0 0.75rem;
  font-size: 0.9375rem;
  color: var(--text-primary, #eee);
}

.cat-count {
  font-size: 0.75rem;
  color: var(--text-secondary, #888);
  font-weight: 400;
}

.skill-items {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.skill-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.875rem 1rem;
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 8px;
}

.skill-info {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.skill-name {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text-primary, #eee);
  font-family: monospace;
}

.skill-desc {
  font-size: 0.75rem;
  color: var(--text-secondary, #aaa);
}

.skill-source {
  font-size: 0.6875rem;
  color: var(--text-secondary, #666);
}

/* Toggle */
.toggle {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 22px;
  cursor: pointer;
}

.toggle input {
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-slider {
  position: absolute;
  inset: 0;
  background: var(--border-color, #2a2a4a);
  border-radius: 22px;
  transition: background 0.2s;
}

.toggle-slider::before {
  content: '';
  position: absolute;
  width: 16px;
  height: 16px;
  left: 3px;
  bottom: 3px;
  background: #fff;
  border-radius: 50%;
  transition: transform 0.2s;
}

.toggle input:checked + .toggle-slider {
  background: var(--accent-color, #6366f1);
}

.toggle input:checked + .toggle-slider::before {
  transform: translateX(18px);
}
</style>
