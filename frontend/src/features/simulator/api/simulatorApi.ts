import { apiClient, unwrapList } from '@/shared/api'
import type { PageResponse } from '@/shared/api'
import type { Scenario, ScenarioCreateRequest } from '../types'

/** GET /simulator/scenarios — 활성 시나리오 목록 (ENGINEER+) */
export async function fetchScenarios(signal?: AbortSignal): Promise<Scenario[]> {
  const { data } = await apiClient.get<PageResponse<Scenario> | Scenario[]>(
    '/simulator/scenarios',
    { signal },
  )
  return unwrapList(data, 'GET /simulator/scenarios')
}

/** POST /simulator/scenarios — 시나리오 주입 (ENGINEER+) */
export async function createScenario(request: ScenarioCreateRequest): Promise<Scenario> {
  const { data } = await apiClient.post<Scenario>('/simulator/scenarios', request)
  return data
}

/** DELETE /simulator/scenarios/{id} — 해제(정상 복귀) */
export async function deleteScenario(scenarioId: number): Promise<void> {
  await apiClient.delete(`/simulator/scenarios/${scenarioId}`)
}

/** POST /simulator/demo — 데모 자동 시나리오 시작 (FR-4.5) */
export async function startDemoScenario(): Promise<void> {
  await apiClient.post('/simulator/demo')
}
