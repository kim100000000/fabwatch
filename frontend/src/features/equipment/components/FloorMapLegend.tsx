import { EQUIPMENT_STATUSES } from '../types'
import { alarmRing, statusVisual } from '../floorMap'
import type { FloorMapAlarmSeverity } from '../floorMap'
import { RingShape } from './FloorMapUnit'
import './floormap.css'

const SEVERITIES: FloorMapAlarmSeverity[] = ['WARNING', 'MAJOR', 'CRITICAL']

/** 범례용 작은 링 견본 — 지도에서 쓰는 RingShape 를 그대로 써서 모양이 어긋나지 않게 한다 */
function RingSwatch({ severity, unacked }: { severity: FloorMapAlarmSeverity; unacked: boolean }) {
  const ring = alarmRing({ count: 1, openCount: unacked ? 1 : 0, maxSeverity: severity })
  if (!ring) return null
  return (
    <svg className="fm-legend-ring" width="46" height="30" viewBox="0 0 46 30" aria-hidden="true" focusable="false">
      <RingShape ring={ring} rect={{ x: 6, y: 5, w: 34, h: 20, r: 5 }} />
      <rect className="fm-legend-ring-body" x="13" y="10" width="20" height="10" rx="2" />
    </svg>
  )
}

/** 배치도 범례 — 상태 4색 / 알람 링 3단계 / 뱃지·파트 의미 */
export function FloorMapLegend() {
  return (
    <div className="fm-legend" role="group" aria-label="배치도 범례">
      <div className="fm-legend-block">
        <span className="fm-legend-title">설비 상태 (본체 색)</span>
        <ul className="fm-legend-list">
          {EQUIPMENT_STATUSES.map((status) => {
            const visual = statusVisual(status)
            return (
              <li key={status}>
                <span className="fm-swatch" style={{ background: visual.colorVar }} aria-hidden="true" />
                {visual.label}
              </li>
            )
          })}
        </ul>
      </div>

      <div className="fm-legend-block">
        <span className="fm-legend-title">미해결 알람 (외곽 링 — 본체 색은 그대로)</span>
        <ul className="fm-legend-list">
          {SEVERITIES.map((severity) => {
            const ring = alarmRing({ count: 1, openCount: 1, maxSeverity: severity })
            return (
              <li key={severity}>
                <RingSwatch severity={severity} unacked />
                {ring?.label}
                <span className="fm-legend-note">
                  {severity === 'WARNING' ? '점선' : severity === 'MAJOR' ? '실선 두 겹' : '굵은 두 겹'}
                </span>
              </li>
            )
          })}
          <li>
            <RingSwatch severity="CRITICAL" unacked={false} />
            확인됨(ACK)
            <span className="fm-legend-note">깜빡임 없음 · 미확인(OPEN)은 깜빡임</span>
          </li>
        </ul>
      </div>

      <div className="fm-legend-block">
        <span className="fm-legend-title">뱃지 · 파트</span>
        <ul className="fm-legend-list">
          <li>
            <span className="fm-chip fm-chip-alarm" data-unacked="true">
              경고 2
            </span>
            알람 건수 (채움=미확인, 윤곽=확인됨)
          </li>
          <li>
            <span className="fm-chip fm-chip-pm">PM 지연</span>
            PM 예정일 초과
          </li>
          <li>
            <span className="fm-chip fm-chip-stale">? 수신 끊김</span>
            센서 수신 끊김 (설비가 흐려짐)
          </li>
          <li>
            <span className="fm-chip fm-chip-part" data-state="WARNING">
              히터 경고
            </span>
            <span className="fm-chip fm-chip-part" data-state="CRITICAL">
              모터 위험
            </span>
            센서 이상 파트만 색 변경
          </li>
          <li>
            <span className="fm-legend-flow" aria-hidden="true" />
            RUN 설비 사이만 흐름 애니메이션
          </li>
        </ul>
      </div>
      <p className="fm-legend-footnote">
        알람 링·건수는 <b>PM 지연 알람을 제외</b>한 센서/수동 알람 기준입니다. PM 지연은 별도 뱃지로 표시합니다.
      </p>
    </div>
  )
}
