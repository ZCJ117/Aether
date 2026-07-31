import { api } from './client'
import type { DashboardStatsDTO } from '@/types/dashboard'

const BASE = '/api/v1'

export function fetchDashboardStats(): Promise<DashboardStatsDTO> {
  return api.get<DashboardStatsDTO>(`${BASE}/dashboard_stats`)
}
