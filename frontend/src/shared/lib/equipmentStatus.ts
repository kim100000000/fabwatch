/** 설비 상태 (docs/05 equipments.status) — API 값은 그대로 'DOWN' 을 쓴다 */
export type EquipmentStatus = 'RUN' | 'IDLE' | 'DOWN' | 'PM'

/**
 * 화면 표시 라벨의 단일 출처 (docs/03 F-2).
 * DOWN 은 고장 수리(BM, 계획 외 정지) 상태라 화면에서만 'DOWN (BM)' 으로 표기한다.
 * API 값·타입·색상 토큰(--status-down)은 바꾸지 않는다.
 */
export const EQUIPMENT_STATUS_LABEL: Record<EquipmentStatus, string> = {
  RUN: 'RUN',
  IDLE: 'IDLE',
  DOWN: 'DOWN (BM)',
  PM: 'PM',
}

/** 상태 → 화면 라벨 (알 수 없는 값은 원문 그대로) */
export function statusLabel(status: EquipmentStatus): string {
  return EQUIPMENT_STATUS_LABEL[status] ?? status
}
