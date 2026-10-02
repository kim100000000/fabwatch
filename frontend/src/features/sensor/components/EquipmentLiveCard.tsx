import type { EquipmentSummary } from '@/features/equipment'
import { StatusBadge } from '@/shared/ui'
import { SENSOR_TYPE_LABEL } from '../types'
import type { SensorLatest } from '../types'
import './sensor.css'

interface EquipmentLiveCardProps {
  equipment: EquipmentSummary
  /** 센서별 최신값 (SSE 로 2초마다 갱신) */
  sensors: SensorLatest[]
  /** 미조치(OPEN/ACK) 알람 수 */
  openAlarmCount: number
  /** 방금 갱신된 센서 id 집합 — 임계치 초과 시 1회 깜빡임 */
  flashSensorIds?: ReadonlySet<number>
  onSelect: (equipmentId: number) => void
}

/**
 * S-1 설비 실시간 카드 (docs/04 §3, docs/03 F-5.1).
 * 상태 좌측 보더 4색 + 현재 센서 4값 + 미조치 알람 수 배지 + PM OVERDUE 배지.
 */
export function EquipmentLiveCard({
  equipment,
  sensors,
  openAlarmCount,
  flashSensorIds,
  onSelect,
}: EquipmentLiveCardProps) {
  return (
    <button
      type="button"
      className="live-card"
      data-status={equipment.status}
      onClick={() => onSelect(equipment.id)}
    >
      <div className="live-card-top">
        <span className="live-card-code">{equipment.code}</span>
        <StatusBadge status={equipment.status} />
      </div>
      <span className="live-card-name">{equipment.name}</span>

      {sensors.length > 0 ? (
        <div className="live-sensor-grid">
          {sensors.map((sensor) => (
            <span key={sensor.sensorId} className="live-sensor" data-level={sensor.level ?? 'NORMAL'}>
              <span className="label">{SENSOR_TYPE_LABEL[sensor.sensorType] ?? sensor.sensorType}</span>
              <span className="value" data-flash={flashSensorIds?.has(sensor.sensorId) ? 'true' : undefined}>
                {sensor.value === null || sensor.value === undefined
                  ? '-'
                  : sensor.value.toFixed(1)}
                {sensor.unit ? ` ${sensor.unit}` : ''}
              </span>
            </span>
          ))}
        </div>
      ) : (
        <span className="live-card-name">센서 데이터 없음</span>
      )}

      <div className="live-card-foot">
        <span className="live-alarm-count" data-zero={openAlarmCount === 0}>
          ⚠ 미조치 {openAlarmCount}
        </span>
        {/* PM OVERDUE 배지 — 대시보드가 GET /pm-schedules?overdueOnly=true 결과를 pmOverdue 로 합쳐 내려준다 (docs/04 §3 S-1) */}
        {equipment.pmOverdue && <span className="pm-overdue-badge">PM OVERDUE</span>}
      </div>
    </button>
  )
}
