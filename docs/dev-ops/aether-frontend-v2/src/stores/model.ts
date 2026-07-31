import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchModels, createModel, updateModel, deleteModel, testModelConnection } from '@/api/model'
import type { ModelConfig, ModelTestResult } from '@/types/model'

export const useModelStore = defineStore('model', () => {
  const models = ref<ModelConfig[]>([])
  const isLoading = ref(false)
  const testResults = ref<Record<string, ModelTestResult>>({})

  const activeModels = computed(() => models.value.filter((m) => m.status === 'ACTIVE'))

  async function loadModels(): Promise<void> {
    isLoading.value = true
    try { models.value = await fetchModels() } finally { isLoading.value = false }
  }

  async function addModel(config: ModelConfig): Promise<boolean> {
    try { await createModel(config); models.value.push(config); return true } catch { return false }
  }

  async function editModel(id: string, data: Partial<ModelConfig>): Promise<boolean> {
    try { await updateModel(id, data); const idx = models.value.findIndex((m) => m.id === id); if (idx >= 0) models.value[idx] = { ...models.value[idx], ...data }; return true } catch { return false }
  }

  async function removeModel(id: string): Promise<boolean> {
    try { await deleteModel(id); models.value = models.value.filter((m) => m.id !== id); return true } catch { return false }
  }

  async function testConnection(id: string): Promise<ModelTestResult> {
    const result = await testModelConnection(id)
    testResults.value[id] = result
    return result
  }

  return {
    models, isLoading, testResults, activeModels,
    loadModels, addModel, editModel, removeModel, testConnection,
  }
})
