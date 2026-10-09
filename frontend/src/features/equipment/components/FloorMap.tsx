import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useNow } from '@/shared/hooks/useNow'
import { StatusBadge } from '@/shared/ui'
import type { EquipmentSummary } from '../types'
import {
  alarmRing,
  buildFloorLayout,
  isConveyorFlowing,
  isPartAbnormal,
  isSensorStale,
  resolveParts,
  toFloorMapAlarm,
} from '../floorMap'
import type { FloorMapAlarm, FloorMapAlarmSummary, FloorMapSensor } from '../floorMap'
import { FLOOR_MAP_TOOLTIP_ID, FloorMapConveyor, FloorMapUnit } from './FloorMapUnit'
import { FloorMapLegend } from './FloorMapLegend'
import './floormap.css'

export interface FloorMapProps {
  /** 표시할 설비 — 라인 필터·SSE 상태 반영은 호출부(pages)에서 끝낸 값 */
  equipments: EquipmentSummary[]
  /** equipmentId → 센서 최신값 (pages 가 sensor 피처에서 받아 주입) */
  sensorsByEquipment: Record<number, readonly FloorMapSensor[]>
  /** equipmentId → 미해결 알람 집계. null = 집계 전/실패(알 수 없음), 키 없음 = 미해결 알람 없음. 링·건수 뱃지는 PM 지연 알람을 제외하고 센다 */
  alarms: Record<number, FloorMapAlarmSummary> | null
  /** 알람 집계 조회가 실패했는지 — alarms 가 null 일 때 '집계 중'과 '집계 실패'를 구분한다 */
  alarmFailed?: boolean
  /** equipmentId → 브라우저가 새 센서값을 마지막으로 받은 시각(epoch ms) — 센서 끊김 판정 기준 */
  lastReceivedAt?: Record<number, number>
  /** processId → 공정 순서(seq). 없으면 processId 순 */
  processSeq?: Record<number, number>
  onSelect: (equipmentId: number) => void
}

/** 센서 수신 끊김 판정용 시계 갱신 주기 — 스트림이 끊겨 재렌더가 없어도 상태가 바뀌게 한다 */
const CLOCK_TICK_MS = 3_000

/** 박스→툴팁으로 마우스를 옮길 시간 */
const TOOLTIP_HIDE_DELAY_MS = 180

const TOOLTIP_WIDTH = 264
const TOOLTIP_GAP = 8
const TOOLTIP_EST_HEIGHT = 230

interface TooltipTarget {
  equipmentId: number
  /** 위치 재측정용 — 스크롤 중에도 박스를 따라가게 한다 */
  element: HTMLElement
  rect: DOMRect
}

/**
 * 설비 배치도 — 라인별로 공정 순서대로 한 줄 흐름으로 그린다 (좌표는 저장하지 않는 자동 배치).
 * 순수 SVG/CSS 로만 그리고 외부 라이브러리를 쓰지 않는다. 데이터는 전부 props 로 받는다.
 */
export function FloorMap({
  equipments,
  sensorsByEquipment,
  alarms,
  alarmFailed = false,
  lastReceivedAt,
  processSeq,
  onSelect,
}: FloorMapProps) {
  const layout = useMemo(() => buildFloorLayout(equipments, processSeq), [equipments, processSeq])
  const nowMs = useNow(CLOCK_TICK_MS)
  const [tip, setTip] = useState<TooltipTarget | null>(null)

  // 마우스가 설비 박스에서 툴팁으로 건너가는 짧은 순간에 닫히지 않도록 닫기를 잠깐 미룬다 (툴팁 위에 마우스를 올려 둘 수 있다)
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const cancelHide = useCallback(() => {
    if (hideTimerRef.current) clearTimeout(hideTimerRef.current)
    hideTimerRef.current = null
  }, [])
  const showTip = useCallback(
    (equipmentId: number, target: HTMLElement) => {
      cancelHide()
      setTip({ equipmentId, element: target, rect: target.getBoundingClientRect() })
    },
    [cancelHide],
  )
  const hideTip = useCallback(() => {
    cancelHide()
    hideTimerRef.current = setTimeout(() => setTip(null), TOOLTIP_HIDE_DELAY_MS)
  }, [cancelHide])
  useEffect(() => cancelHide, [cancelHide])

  // 스크롤·리사이즈로 박스 위치가 바뀌면 툴팁이 따라가게 다시 측정한다
  // (키보드 포커스는 박스를 화면 안으로 스크롤시키므로, 닫아 버리면 Tab 이동 때 툴팁이 안 보인다). Esc 로 닫을 수 있다.
  const tipElement = tip?.element
  useEffect(() => {
    if (!tipElement) return
    const remeasure = () =>
      setTip((previous) =>
        previous && previous.element.isConnected
          ? { ...previous, rect: previous.element.getBoundingClientRect() }
          : null,
      )
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        cancelHide()
        setTip(null)
      }
    }
    window.addEventListener('scroll', remeasure, true)
    window.addEventListener('resize', remeasure)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('scroll', remeasure, true)
      window.removeEventListener('resize', remeasure)
      window.removeEventListener('keydown', onKey)
    }
  }, [tipElement, cancelHide])

  const tipEquipment = tip ? equipments.find((equipment) => equipment.id === tip.equipmentId) : undefined

  return (
    <section className="fm" aria-label="설비 배치도">
      {layout.map((line) => {
        const lineCount = line.groups.reduce((sum, group) => sum + group.equipments.length, 0)
        return (
          <div className="fm-line" key={line.lineId ?? 'none'}>
            <div className="fm-line-head">
              <h3 className="fm-line-name">{line.lineName}</h3>
              <span className="fm-line-meta">
                설비 {lineCount}대 · 공정 {line.groups.length}개 · 좌→우 흐름
              </span>
            </div>
            <ScrollHint>
              <div className="fm-row">
                {line.groups.map((group, groupIndex) => {
                  const nextGroup = line.groups[groupIndex + 1]
                  const lastOfGroup = group.equipments[group.equipments.length - 1]
                  const firstOfNext = nextGroup?.equipments[0]
                  return (
                    <Fragment key={group.processId ?? 'none'}>
                      <div className="fm-group">
                        <div className="fm-group-head">
                          <span className="fm-step" aria-hidden="true">
                            {groupIndex + 1}
                          </span>
                          <span className="fm-group-name">{group.processName}</span>
                          <span className="fm-group-count">{group.equipments.length}대</span>
                        </div>
                        <div className="fm-units">
                          {group.equipments.map((equipment, index) => {
                            const next = group.equipments[index + 1]
                            return (
                              <Fragment key={equipment.id}>
                                <FloorMapUnit
                                  equipment={equipment}
                                  sensors={sensorsByEquipment[equipment.id]}
                                  alarm={alarms ? toFloorMapAlarm(alarms[equipment.id]) : null}
                                  alarmUnknown={alarms === null}
                                  alarmFailed={alarmFailed}
                                  lastReceivedAt={lastReceivedAt?.[equipment.id]}
                                  tooltipOpen={tip?.equipmentId === equipment.id}
                                  nowMs={nowMs}
                                  onSelect={onSelect}
                                  onTooltipShow={showTip}
                                  onTooltipHide={hideTip}
                                />
                                {next && (
                                  <FloorMapConveyor flowing={isConveyorFlowing(equipment.status, next.status)} />
                                )}
                              </Fragment>
                            )
                          })}
                        </div>
                      </div>
                      {/* 공정 경계에서도 컨베이어가 이어진다 */}
                      {lastOfGroup && firstOfNext && (
                        <div className="fm-between">
                          <FloorMapConveyor flowing={isConveyorFlowing(lastOfGroup.status, firstOfNext.status)} />
                        </div>
                      )}
                    </Fragment>
                  )
                })}
              </div>
            </ScrollHint>
          </div>
        )
      })}

      <FloorMapLegend />

      {tip && tipEquipment && (
        <FloorMapTooltip
          equipment={tipEquipment}
          sensors={sensorsByEquipment[tipEquipment.id]}
          alarm={alarms ? toFloorMapAlarm(alarms[tipEquipment.id]) : null}
          alarmUnknown={alarms === null}
          alarmFailed={alarmFailed}
          lastReceivedAt={lastReceivedAt?.[tipEquipment.id]}
          nowMs={nowMs}
          rect={tip.rect}
          onEnter={cancelHide}
          onLeave={hideTip}
        />
      )}
    </section>
  )
}

/* ------------------------------------------------------------------
 * 툴팁 — 마우스 호버/키보드 포커스 시 고정 위치(fixed)로 띄운다 (스크롤 컨테이너에 잘리지 않게)
 * ------------------------------------------------------------------ */

function formatValue(value: number | null, unit: string | null): string {
  if (value === null || value === undefined) return '-'
  return `${value.toFixed(1)}${unit ? ` ${unit}` : ''}`
}

interface FloorMapTooltipProps {
  equipment: EquipmentSummary
  sensors: readonly FloorMapSensor[] | undefined
  alarm: FloorMapAlarm | null | undefined
  alarmUnknown: boolean
  alarmFailed: boolean
  lastReceivedAt: number | undefined
  nowMs: number
  rect: DOMRect
  onEnter: () => void
  onLeave: () => void
}

function FloorMapTooltip({
  equipment,
  sensors,
  alarm,
  alarmUnknown,
  alarmFailed,
  lastReceivedAt,
  nowMs,
  rect,
  onEnter,
  onLeave,
}: FloorMapTooltipProps) {
  const ring = alarmRing(alarm)
  const parts = resolveParts(sensors)
  const stale = isSensorStale(sensors, lastReceivedAt, nowMs)

  const viewportW = typeof window === 'undefined' ? 1280 : window.innerWidth
  const viewportH = typeof window === 'undefined' ? 800 : window.innerHeight
  const left = Math.max(8, Math.min(rect.left + rect.width / 2 - TOOLTIP_WIDTH / 2, viewportW - TOOLTIP_WIDTH - 8))
  // 아래 공간이 모자라면 박스 위쪽에 띄운다
  const placeAbove = rect.bottom + TOOLTIP_GAP + TOOLTIP_EST_HEIGHT > viewportH && rect.top > TOOLTIP_EST_HEIGHT
  const style = placeAbove
    ? { left, top: rect.top - TOOLTIP_GAP, transform: 'translateY(-100%)' }
    : { left, top: rect.bottom + TOOLTIP_GAP }

  return (
    <div
      id={FLOOR_MAP_TOOLTIP_ID}
      className="fm-tooltip"
      style={style}
      role="tooltip"
      onMouseEnter={onEnter}
      onMouseLeave={onLeave}
    >
      <div className="fm-tip-head">
        <div>
          <div className="fm-tip-code">{equipment.code}</div>
          <div className="fm-tip-name">{equipment.name}</div>
        </div>
        <StatusBadge status={equipment.status} />
      </div>

      {stale && <div className="fm-tip-note">? 센서 수신 끊김 — 표시된 값은 마지막 수신값입니다</div>}
      {(equipment.pmOverdue || (alarm?.pmOverdueCount ?? 0) > 0) && (
        <div className="fm-tip-note fm-tip-note-pm">
          PM 지연 — PM 예정일이 지났습니다
          {(alarm?.pmOverdueCount ?? 0) > 0 && ` (PM 지연 알람 ${alarm?.pmOverdueCount}건, 링·건수에는 미포함)`}
        </div>
      )}

      <table className="fm-tip-sensors">
        <tbody>
          {parts.map((part) => (
            <tr key={part.sensorType} data-state={part.state}>
              <th scope="row">
                {part.sensorLabel}
                <span className="fm-tip-part">{part.fullLabel}</span>
              </th>
              <td className="mono">{part.state === 'NONE' ? '센서 없음' : formatValue(part.value, part.unit)}</td>
              <td className="fm-tip-level">
                {isPartAbnormal(part.state) ? (part.state === 'CRITICAL' ? '위험' : '경고') : ''}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="fm-tip-alarm" data-severity={ring?.severity}>
        {ring
          ? `미해결 알람 ${ring.count}건 · 최고 ${ring.label}${
              ring.unacked ? ` · 미확인 ${ring.openCount}건` : ' · 모두 확인됨'
            }`
          : alarmUnknown
            ? alarmFailed
              ? '미해결 알람 집계 실패 — 잠시 후 자동으로 다시 시도합니다'
              : '미해결 알람 집계 중…'
            : '미해결 알람 없음'}
      </div>
      <div className="fm-tip-hint">클릭 또는 Enter: 설비 상세</div>
    </div>
  )
}

/* ------------------------------------------------------------------
 * 가로 스크롤 컨테이너 — 넘치는 쪽 가장자리에 그라데이션 힌트를 보여 준다
 * ------------------------------------------------------------------ */

function ScrollHint({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  const [edges, setEdges] = useState({ left: false, right: false })

  const measure = useCallback(() => {
    const element = ref.current
    if (!element) return
    const left = element.scrollLeft > 4
    const right = element.scrollLeft + element.clientWidth < element.scrollWidth - 4
    setEdges((previous) => (previous.left === left && previous.right === right ? previous : { left, right }))
  }, [])

  useEffect(() => {
    measure()
    const element = ref.current
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    if (element.firstElementChild) observer.observe(element.firstElementChild)
    return () => observer.disconnect()
  }, [measure])

  return (
    <div className="fm-scroll-wrap" data-left={edges.left} data-right={edges.right}>
      <div className="fm-scroll" ref={ref} onScroll={measure}>
        {children}
      </div>
    </div>
  )
}
