import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent, PointerEvent } from 'react'
import type { EquipmentSummary } from '@/features/equipment'
import type { SensorLatest } from '@/features/sensor'
import { SENSOR_TYPE_LABEL } from '@/features/sensor'
import { Spinner } from '@/shared/ui'
import { toApiError } from '@/shared/api'
import { toUserMessage } from '@/shared/lib/errorMessage'
import {
  createScenario,
  deleteScenario,
  fetchScenarios,
  startDemoScenario,
} from '../api/simulatorApi'
import {
  DEFAULT_DRIFT_DURATION_MIN,
  DRIFT_DURATION_OPTIONS,
  buildScenarioParam,
  clampPosition,
  isEquipmentSelectable,
  keyDelta,
  parseStoredPosition,
  scenarioHint,
} from '../demoPanelModel'
import type { Point } from '../demoPanelModel'
import { SCENARIO_TYPES } from '../types'
import type { Scenario, ScenarioType } from '../types'
import './simulator.css'

/** 패널 위치 저장 키 (사용자 편의용 — 저장 실패해도 동작에는 영향 없음) */
const POSITION_STORAGE_KEY = 'fabwatch.demoPanel.position'

function readStoredPosition(): Point | null {
  try {
    return parseStoredPosition(window.localStorage.getItem(POSITION_STORAGE_KEY))
  } catch {
    return null
  }
}

function writeStoredPosition(position: Point | null): void {
  try {
    if (position) window.localStorage.setItem(POSITION_STORAGE_KEY, JSON.stringify(position))
    else window.localStorage.removeItem(POSITION_STORAGE_KEY)
  } catch {
    // 저장 불가 환경에서는 이번 세션 위치만 유지한다
  }
}

interface DemoControlPanelProps {
  equipments: EquipmentSummary[]
  /** equipmentId → 센서 목록 (센서 선택용 — 해당 설비에 실제로 있는 센서만 들어 있다) */
  sensorsByEquipment: Record<number, SensorLatest[]>
}

/**
 * 데모 컨트롤 패널 (docs/04 §3 S-1 우하단 플로팅, ENGINEER+ / docs/06 §8).
 * 기본은 접힌 알약 버튼. 펼치면 컴팩트 패널 — 헤더를 드래그(또는 방향키)로 옮길 수 있고 위치는 기억한다.
 * 주 동작은 '데모 자동 시작'(약 2분), 센서/시나리오 수동 주입은 접이식 고급 섹션이다.
 * ※ 가상 센서값 생성 로직만 바꾼다 — 실설비 제어가 아니다 (docs/11 §10).
 */
export function DemoControlPanel({ equipments, sensorsByEquipment }: DemoControlPanelProps) {
  const [open, setOpen] = useState(false)
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [scenarios, setScenarios] = useState<Scenario[]>([])
  // 사용자가 고른 설비 id. 비어 있거나 목록에 없으면(목록이 늦게 도착하는 경우 포함) 첫 설비로 파생한다.
  const [selectedEquipmentId, setSelectedEquipmentId] = useState<number | null>(null)
  const [sensorId, setSensorId] = useState<number | null>(null)
  const [type, setType] = useState<ScenarioType>('DRIFT')
  const [driftMin, setDriftMin] = useState<number>(DEFAULT_DRIFT_DURATION_MIN)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 패널 위치 — null 이면 CSS 기본(우하단). 드래그/방향키로 옮기면 좌상단 기준 px 로 고정한다.
  const [position, setPosition] = useState<Point | null>(readStoredPosition)
  const [dragging, setDragging] = useState(false)
  const panelRef = useRef<HTMLElement>(null)
  // 드래그가 끝나는 시점의 최신 위치를 저장하기 위한 참조
  const positionRef = useRef<Point | null>(position)
  useEffect(() => {
    positionRef.current = position
  }, [position])
  const dragRef = useRef<{ offsetX: number; offsetY: number; pointerId: number } | null>(null)

  /** 선택 가능한 첫 설비(센서 없는 설비는 건너뛴다) */
  const selectableEquipments = equipments.filter((equipment) =>
    isEquipmentSelectable(sensorsByEquipment[equipment.id]),
  )
  const equipmentId =
    selectedEquipmentId !== null && selectableEquipments.some((equipment) => equipment.id === selectedEquipmentId)
      ? selectedEquipmentId
      : (selectableEquipments[0]?.id ?? null)

  const sensors = equipmentId !== null ? (sensorsByEquipment[equipmentId] ?? []) : []

  const reload = useCallback(async (signal?: AbortSignal) => {
    try {
      setScenarios(await fetchScenarios(signal))
      setError(null)
    } catch (cause) {
      // 백엔드 시뮬레이터 API 미기동일 수 있으므로 패널만 조용히 비운다
      setScenarios([])
      setError(toUserMessage(toApiError(cause)))
    }
  }, [])

  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    void reload(controller.signal)
    return () => controller.abort()
  }, [open, reload])

  // Esc 로 패널 닫기
  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open])

  /** 현재 패널 크기/뷰포트 기준으로 위치를 뷰포트 안으로 제한 */
  const clampToViewport = useCallback((point: Point): Point => {
    const rect = panelRef.current?.getBoundingClientRect()
    return clampPosition(
      point,
      { w: rect?.width ?? 280, h: rect?.height ?? 200 },
      { w: window.innerWidth, h: window.innerHeight },
    )
  }, [])

  // 열릴 때, 윈도 리사이즈 때, 패널 높이가 바뀔 때(고급 섹션 펼침 등) 위치를 다시 제한한다
  useLayoutEffect(() => {
    if (!open) return
    const refit = () =>
      setPosition((previous) => {
        if (!previous) return previous
        const next = clampToViewport(previous)
        return next.x === previous.x && next.y === previous.y ? previous : next
      })
    refit()
    window.addEventListener('resize', refit)
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(refit)
    if (panelRef.current) observer?.observe(panelRef.current)
    return () => {
      window.removeEventListener('resize', refit)
      observer?.disconnect()
    }
  }, [open, clampToViewport])

  // 드래그 중에는 페이지 텍스트가 선택되지 않게 한다
  useEffect(() => {
    if (!dragging) return
    const previous = document.body.style.userSelect
    document.body.style.userSelect = 'none'
    return () => {
      document.body.style.userSelect = previous
    }
  }, [dragging])

  const onHeaderPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    // 헤더 안 버튼(초기화/닫기)을 누를 때는 드래그를 시작하지 않는다. 마우스는 왼쪽 버튼만.
    if ((event.target as HTMLElement).closest('button')) return
    if (event.pointerType === 'mouse' && event.button !== 0) return
    const rect = panelRef.current?.getBoundingClientRect()
    if (!rect) return
    dragRef.current = { offsetX: event.clientX - rect.left, offsetY: event.clientY - rect.top, pointerId: event.pointerId }
    event.currentTarget.setPointerCapture(event.pointerId)
    setDragging(true)
  }

  const onHeaderPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    setPosition(clampToViewport({ x: event.clientX - drag.offsetX, y: event.clientY - drag.offsetY }))
  }

  const endDrag = (event: PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    dragRef.current = null
    setDragging(false)
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
    writeStoredPosition(positionRef.current)
  }

  const resetPosition = () => {
    setPosition(null)
    writeStoredPosition(null)
  }

  // 키보드 이동: 헤더에 포커스 후 방향키(Shift=크게), Home=위치 초기화
  const onHeaderKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.target !== event.currentTarget) return // 헤더 안 버튼의 키 입력은 건드리지 않는다
    if (event.key === 'Home') {
      event.preventDefault()
      resetPosition()
      return
    }
    const delta = keyDelta(event.key, event.shiftKey)
    if (!delta) return
    event.preventDefault()
    const rect = panelRef.current?.getBoundingClientRect()
    const base = position ?? { x: rect?.left ?? 0, y: rect?.top ?? 0 }
    const next = clampToViewport({ x: base.x + delta.x, y: base.y + delta.y })
    setPosition(next)
    writeStoredPosition(next)
  }

  // 설비를 바꾸면 선택 중인 센서가 목록에 없어진다 — 그때는 첫 센서로 자동 대체 (별도 effect 불필요)
  const activeSensorId =
    sensorId !== null && sensors.some((sensor) => sensor.sensorId === sensorId)
      ? sensorId
      : (sensors[0]?.sensorId ?? null)

  /** 공통 실행 래퍼 — busy/오류 처리 후 활성 시나리오 목록을 다시 읽는다 */
  const run = async (action: () => Promise<unknown>) => {
    setBusy(true)
    setError(null)
    try {
      await action()
      await reload()
    } catch (cause) {
      setError(toUserMessage(toApiError(cause)))
    } finally {
      setBusy(false)
    }
  }

  const inject = () => {
    if (activeSensorId === null) return
    return run(() =>
      createScenario({ sensorId: activeSensorId, type, param: buildScenarioParam(type, driftMin) }),
    )
  }

  if (!open) {
    return (
      <button type="button" className="demo-fab" onClick={() => setOpen(true)}>
        ⚗ 데모 제어
      </button>
    )
  }

  const activeScenarios = scenarios.filter((scenario) => scenario.active !== false)

  return (
    <aside
      ref={panelRef}
      className="demo-panel"
      data-dragging={dragging ? 'true' : undefined}
      style={position ? { left: position.x, top: position.y, right: 'auto', bottom: 'auto' } : undefined}
      aria-label="시뮬레이터 데모 제어"
    >
      <div
        className="demo-panel-head"
        tabIndex={0}
        role="group"
        aria-label="데모 제어 패널 이동 — 방향키로 이동, Shift+방향키는 크게, Home은 위치 초기화"
        title="드래그해서 옮길 수 있습니다 (방향키도 가능)"
        onPointerDown={onHeaderPointerDown}
        onPointerMove={onHeaderPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onKeyDown={onHeaderKeyDown}
      >
        <span className="demo-grip" aria-hidden="true">
          ⠿
        </span>
        <strong className="demo-title">데모 제어</strong>
        <button
          type="button"
          className="demo-icon-btn"
          onClick={resetPosition}
          aria-label="패널 위치 초기화"
          title="위치 초기화 (우하단)"
          disabled={position === null}
        >
          ↺
        </button>
        <button type="button" className="demo-icon-btn" onClick={() => setOpen(false)} aria-label="닫기" title="닫기 (Esc)">
          ✕
        </button>
      </div>

      <div className="demo-panel-body">
        {/* 주 동작: 데모 자동 시작 */}
        <button
          type="button"
          className="btn btn-primary demo-primary"
          onClick={() => void run(startDemoScenario)}
          disabled={busy}
        >
          {busy ? <Spinner /> : null}
          데모 자동 시작
        </button>
        <span className="demo-hint">약 2분 · 진동 드리프트로 경고 → 위험 → 자동 DOWN (BM)</span>

        {error && (
          <p className="form-error demo-error" role="alert">
            {error}
          </p>
        )}

        <div className="demo-scenario-list">
          <span className="demo-hint">활성 시나리오 {activeScenarios.length}건</span>
          {activeScenarios.map((scenario) => (
            <div key={scenario.id} className="demo-scenario">
              <span className="mono">
                {scenario.equipmentCode ?? `#${scenario.equipmentId ?? '-'}`} ·{' '}
                {SENSOR_TYPE_LABEL[scenario.sensorType as keyof typeof SENSOR_TYPE_LABEL] ??
                  scenario.sensorType ??
                  `sensor ${scenario.sensorId}`}{' '}
                · {scenario.type}
              </span>
              <button type="button" className="demo-link-btn" onClick={() => void run(() => deleteScenario(scenario.id))} disabled={busy}>
                해제
              </button>
            </div>
          ))}
        </div>

        {/* 수동 주입(고급) — 접이식 */}
        <button
          type="button"
          className="demo-advanced-toggle"
          aria-expanded={advancedOpen}
          aria-controls="demo-advanced"
          onClick={() => setAdvancedOpen((value) => !value)}
        >
          <span aria-hidden="true">{advancedOpen ? '▾' : '▸'}</span> 수동 주입 (고급)
        </button>

        {advancedOpen && (
          <div id="demo-advanced" className="demo-advanced">
            <div className="demo-field">
              <label htmlFor="demo-equipment">설비</label>
              <select
                id="demo-equipment"
                value={equipmentId ?? ''}
                onChange={(event) => setSelectedEquipmentId(event.target.value ? Number(event.target.value) : null)}
              >
                {equipments.map((equipment) => (
                  <option
                    key={equipment.id}
                    value={equipment.id}
                    disabled={!isEquipmentSelectable(sensorsByEquipment[equipment.id])}
                  >
                    {equipment.code}
                    {isEquipmentSelectable(sensorsByEquipment[equipment.id]) ? '' : ' (센서 없음)'}
                  </option>
                ))}
              </select>
            </div>

            <div className="demo-field">
              <label htmlFor="demo-sensor">센서</label>
              <select
                id="demo-sensor"
                value={activeSensorId ?? ''}
                onChange={(event) => setSensorId(event.target.value ? Number(event.target.value) : null)}
              >
                {sensors.length === 0 && <option value="">센서 없음</option>}
                {sensors.map((sensor) => (
                  <option key={sensor.sensorId} value={sensor.sensorId}>
                    {SENSOR_TYPE_LABEL[sensor.sensorType] ?? sensor.sensorType}
                  </option>
                ))}
              </select>
            </div>

            <div className="demo-field">
              <label htmlFor="demo-type">시나리오</label>
              <select id="demo-type" value={type} onChange={(event) => setType(event.target.value as ScenarioType)}>
                {SCENARIO_TYPES.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
            </div>

            {type === 'DRIFT' && (
              <div className="demo-field">
                <label htmlFor="demo-drift-min">지속시간</label>
                <select id="demo-drift-min" value={driftMin} onChange={(event) => setDriftMin(Number(event.target.value))}>
                  {DRIFT_DURATION_OPTIONS.map((minutes) => (
                    <option key={minutes} value={minutes}>
                      {minutes}분{minutes === DEFAULT_DRIFT_DURATION_MIN ? ' (데모와 동일)' : ''}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <span className="demo-hint">{scenarioHint(type, driftMin)}</span>

            <button type="button" className="btn demo-inject" onClick={() => void inject()} disabled={busy || activeSensorId === null}>
              주입
            </button>
          </div>
        )}
      </div>
    </aside>
  )
}
