import { apiClient, unwrapList } from '@/shared/api'
import type { PageResponse } from '@/shared/api'
import type {
  SensorDefinition,
  SensorLatest,
  SensorSeriesQuery,
  SensorSeriesResponse,
  ThresholdLog,
  ThresholdUpdateRequest,
} from '../types'

/**
 * GET /equipments/{id}/sensor-data/latest — 센서별 최신값 1건씩 (카드용, docs/06 §3)
 * 백엔드는 목록 공통 래핑({content,...})으로 응답한다 — unwrapList 로 배열만 꺼낸다.
 */
export async function fetchLatestSensorData(
  equipmentId: number,
  signal?: AbortSignal,
): Promise<SensorLatest[]> {
  const { data } = await apiClient.get<PageResponse<SensorLatest> | SensorLatest[]>(
    `/equipments/${equipmentId}/sensor-data/latest`,
    { signal },
  )
  // 전체 라인 대시보드에서 sensorId → equipmentId 색인을 만들 수 있게 equipmentId 를 보강한다.
  return unwrapList(data, `GET /equipments/${equipmentId}/sensor-data/latest`).map((item) => ({
    ...item,
    equipmentId: item.equipmentId ?? equipmentId,
  }))
}

/**
 * GET /equipments/{id}/sensor-data — 기간 이력 (docs/06 §3)
 * 1시간 이내는 원본(RAW), 초과는 1분 집계(1M)를 서버가 선택하고 granularity 로 알려준다.
 * 프론트는 기간으로 추측하지 않고 응답의 granularity 를 그대로 표시한다.
 * sensorType 을 주면 해당 센서 1개, 생략하면 설비의 전체 센서 series 가 온다.
 */
export async function fetchSensorSeries(
  equipmentId: number,
  query: SensorSeriesQuery,
  signal?: AbortSignal,
): Promise<SensorSeriesResponse> {
  const { data } = await apiClient.get<SensorSeriesResponse>(
    `/equipments/${equipmentId}/sensor-data`,
    {
      params: { sensorType: query.sensorType, from: query.from, to: query.to },
      signal,
    },
  )
  return { ...data, series: data.series ?? [] }
}

/**
 * GET /equipments/{id}/sensors — 설비 센서 정의 목록 (임계치 편집 진입용, 전체 로그인 사용자).
 * 목록 공통 래핑({content,...}) 응답.
 */
export async function fetchEquipmentSensors(
  equipmentId: number,
  signal?: AbortSignal,
): Promise<SensorDefinition[]> {
  const { data } = await apiClient.get<PageResponse<SensorDefinition> | SensorDefinition[]>(
    `/equipments/${equipmentId}/sensors`,
    { signal },
  )
  return unwrapList(data, `GET /equipments/${equipmentId}/sensors`)
}

/**
 * PUT /equipments/{id}/sensors/{sensorId}/thresholds — 임계치 수정 (ADMIN 전용).
 * 400 INVALID_THRESHOLD_RANGE / 403 FORBIDDEN(ENGINEER·TECHNICIAN). 갱신된 센서 정의를 돌려받는다.
 */
export async function updateSensorThresholds(
  equipmentId: number,
  sensorId: number,
  request: ThresholdUpdateRequest,
): Promise<SensorDefinition> {
  const { data } = await apiClient.put<SensorDefinition>(
    `/equipments/${equipmentId}/sensors/${sensorId}/thresholds`,
    request,
  )
  return data
}

/** GET /equipments/{id}/sensors/{sensorId}/thresholds/logs — 임계치 변경 이력 (최신순, 전체 로그인 사용자) */
export async function fetchThresholdLogs(
  equipmentId: number,
  sensorId: number,
  size: number,
  signal?: AbortSignal,
): Promise<PageResponse<ThresholdLog>> {
  const { data } = await apiClient.get<PageResponse<ThresholdLog>>(
    `/equipments/${equipmentId}/sensors/${sensorId}/thresholds/logs`,
    { params: { page: 0, size }, signal },
  )
  return data
}
