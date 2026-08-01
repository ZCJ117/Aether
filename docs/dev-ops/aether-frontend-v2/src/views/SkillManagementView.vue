<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useSkillStore } from '@/stores/skill'

const skillStore = useSkillStore()

onMounted(() => {
  skillStore.loadSkills()
})

function toggleSkill(id: string) {
  skillStore.toggleSkill(id)
}

const groupedSkills = computed(() => {
  const groups: Record<string, typeof skillStore.skills> = {}
  for (const skill of skillStore.skills) {
    const cat = skill.category || '未分类'
    if (!groups[cat]) groups[cat] = []
    groups[cat].push(skill)
  }
  return groups
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">技能</h1>

    <div v-if="skillStore.isLoading" class="state-msg">加载中...</div>

    <template v-else>
      <template v-for="(group, cat) in groupedSkills" :key="cat">
        <div class="cat-label">{{ cat }}</div>
        <div class="skill-list">
          <div
            v-for="skill in group"
            :key="skill.id"
            class="skill-row"
          >
            <div class="skill-info">
              <div class="skill-name">{{ skill.name }}</div>
              <div class="skill-desc">{{ skill.description }}</div>
            </div>
            <button
              :class="['toggle', { active: skill.enabled }]"
              @click="toggleSkill(skill.id)"
            >
              <span class="toggle-knob" />
            </button>
          </div>
        </div>
      </template>
      <div v-if="!skillStore.skills.length" class="state-msg">暂无技能</div>
    </template>
  </div>
</template>

<style scoped>
.page { padding: 20px; }
.page-title { font-size: 28px; font-weight: 700; color: #F5F5F7; margin: 0 0 18px; }

.cat-label {
  font-size: 11px;
  color: #636366;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 8px 0 4px;
}

.skill-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

.skill-row {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 12px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.skill-name { font-size: 14px; font-weight: 600; color: #F5F5F7; }
.skill-desc { font-size: 11px; color: #98989D; margin-top: 2px; }

.toggle {
  width: 40px;
  height: 22px;
  border-radius: 11px;
  border: none;
  background: rgba(255, 255, 255, 0.08);
  position: relative;
  cursor: pointer;
  transition: background-color 0.25s;
  flex-shrink: 0;
}

.toggle.active {
  background: #30D158;
}

.toggle-knob {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  position: absolute;
  top: 2px;
  left: 2px;
  transition: transform 0.25s;
}

.toggle.active .toggle-knob {
  transform: translateX(18px);
  background: #fff;
}

.toggle:not(.active) .toggle-knob {
  background: #636366;
}

.state-msg {
  text-align: center;
  padding: 48px 0;
  font-size: 14px;
  color: #98989D;
}
</style>
