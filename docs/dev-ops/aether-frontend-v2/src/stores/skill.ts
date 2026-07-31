import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Skill, SkillCategory } from '@/types/skill'

export const useSkillStore = defineStore('skill', () => {
  const skills = ref<Skill[]>([])
  const categories = ref<SkillCategory[]>([])
  const isLoading = ref(false)

  const enabledSkills = computed(() => skills.value.filter((s) => s.enabled))

  async function loadSkills(): Promise<void> {
    isLoading.value = true
    try {
      skills.value = [
        { id: 's1', name: 'web_search', description: '网络搜索工具', category: 'tools', source: 'builtin', type: 'mcp', enabled: true },
        { id: 's2', name: 'weather_query', description: '天气查询工具', category: 'tools', source: 'builtin', type: 'mcp', enabled: true },
        { id: 's3', name: 'file_reader', description: '文件读取技能', category: 'io', source: 'builtin', type: 'resource', enabled: true },
        { id: 's4', name: 'code_review', description: '代码审查技能', category: 'dev', source: 'marketplace', type: 'directory', enabled: false },
      ]
      categories.value = [
        { id: 'tools', name: '工具', count: 2 },
        { id: 'io', name: '输入输出', count: 1 },
        { id: 'dev', name: '开发', count: 1 },
      ]
    } finally { isLoading.value = false }
  }

  function toggleSkill(id: string): void {
    const skill = skills.value.find((s) => s.id === id)
    if (skill) skill.enabled = !skill.enabled
  }

  return {
    skills, categories, isLoading, enabledSkills,
    loadSkills, toggleSkill,
  }
})
