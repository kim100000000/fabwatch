import { useCallback, useEffect, useState } from 'react'
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
import { DEFAULT_SCENARIO_PARAM, SCENARIO_TYPES, SCENARIO_TYPE_HINT } from '../types'
import type { Scenario, ScenarioType } from '../types'
import './simulator.css'

interface DemoControlPanelProps {
  equipments: EquipmentSummary[]
  /** equipmentId → 센서 목록 (센서 선택용) */
  sensorsByEquipment: Record<number, SensorLatest[]>
}

/**
 * 데모 컨트롤 패널 (docs/04 §3 S-1 우하단 플로팅, ENGINEER+ / docs/06 §8).
 * 시연 중 DRIFT/SPIKE/STEP 을 주입해 임계치 → 알람 → 자동 DOWN 흐름을 보여준다.
 * ※ 가상 센서값 생성 로직만 바꾼다 — 실설비 제어가 아니다 (docs/11 §10).
 */
export function DemoControlPanel({ equipments, sensorsByEquipment }: DemoControlPanelProps) {
  const [open, setOpen] = useState(false)
  const [scenarios, setScenarios] = useState<Scenario[]>([])
  const [equipmentId, setEquipmentId] = useState<number | null>(equipments[0]?.id ?? null)
  const [sensorId, setSensorId] = useState<number | null>(null)
  const [type, setType] = useState<ScenarioType>('DRIFT')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

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

  // 설비를 바꾸면 선택 중인 센서가 목록에 없어진다 — 그때는 첫 센서로 자동 대체 (별도 effect 불필요)
  const activeSensorId =
    sensorId !== null && sensors.some((sensor) => sensor.sensorId === sensorId)
      ? sensorId
      : (sensors[0]?.sensorId ?? null)

  const inject = async () => {
    if (activeSensorId === null) return
    setBusy(true)
    setError(null)
    try {
      await createScenario({ sensorId: activeSensorId, type, param: DEFAULT_SCENARIO_PARAM[type] })
      await reload()
    } catch (cause) {
      setError(toUserMessage(toApiError(cause)))
    } finally {
      setBusy(false)
    }
  }

  const release = async (scenarioId: number) => {
    setBusy(true)
    setError(null)
    try {
      await deleteScenario(scenarioId)
      await reload()
    } catch (cause) {
      setError(toUserMessage(toApiError(cause)))
    } finally {
      setBusy(false)
    }
  }

  const runDemo = async () => {
    setBusy(true)
    setError(null)
    try {
      await startDemoScenario()
      await reload()
    } catch (cause) {
      setError(toUserMessage(toApiError(cause)))
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <button type="button" className="demo-fab" onClick={() => setOpen(true)}>
        ⚗ 데모 제어
      </button>
    )
  }

  return (
    <aside className="demo-panel" aria-label="시뮬레이터 데모 제어">
      <div className="demo-panel-head">
        <strong>데모 제어 (시뮬레이터)</strong>
        <button type="button" className="modal-close" onClick={() => setOpen(false)} aria-label="닫기">
          ✕
        </button>
      </div>

      <div className="demo-panel-body">
        <div className="field">
          <label htmlFor="demo-equipment">설비</label>
          <select
            id="demo-equipment"
            value={equipmentId ?? ''}
            onChange={(event) =>
              setEquipmentId(event.target.value ? Number(event.target.value) : null)
            }
          >
            {equipments.map((equipment) => (
              <option key={equipment.id} value={equipment.id}>
                {equipment.code}
              </option>
            ))}
          </select>
        </div>

        <div className="field">
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

        <div className="field">
          <label htmlFor="demo-type">시나리오</label>
          <select
            id="demo-type"
            value={type}
            onChange={(event) => setType(event.target.value as ScenarioType)}
          >
            {SCENARIO_TYPES.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
          <span className="field-hint">{SCENARIO_TYPE_HINT[type]}</span>
        </div>

        <div className="demo-actions">
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={() => void inject()}
            disabled={busy || activeSensorId === null}
          >
            {busy ? <Spinner /> : null}
            주입
          </button>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => void runDemo()}
            disabled={busy}
          >
            데모 자동 시작
          </button>
        </div>

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        <div className="demo-scenario-list">
          <span className="field-hint">활성 시나리오 {scenarios.length}건</span>
          {scenarios.map((scenario) => (
            <div key={scenario.id} className="demo-scenario">
              <span className="mono">
                {scenario.equipmentCode ?? `#${scenario.equipmentId ?? '-'}`} ·{' '}
                {scenario.sensorType ?? `sensor ${scenario.sensorId}`} · {scenario.type}
              </span>
              <button
                type="button"
                className="btn btn-sm btn-ghost"
                onClick={() => void release(scenario.id)}
                disabled={busy}
              >
                해제
              </button>
            </div>
          ))}
        </div>
      </div>
    </aside>
  )
}
