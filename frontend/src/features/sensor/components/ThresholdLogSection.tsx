import { useState } from 'react'
import { EmptyState, ErrorState, LoadingBlock } from '@/shared/ui'
import { formatKst } from '@/shared/lib/datetime'
import { THRESHOLD_FIELDS } from '../thresholdForm'
import type { ThresholdField } from '../thresholdForm'
import { useThresholdLogs } from '../api/useThresholdLogs'
import type { ThresholdLog } from '../types'
import './sensor.css'

interface ThresholdLogSectionProps {
  equipmentId: number
  sensorId: number
  unit?: string | null
  /** 처음부터 펼칠지 (이력 전용 다이얼로그는 true, 편집 다이얼로그는 접어 둔다) */
  defaultOpen?: boolean
}

/** 로그 한 줄에서 필드별 이전/이후 값을 꺼낸다 */
function pair(log: ThresholdLog, field: ThresholdField): [number | null, number | null] {
  switch (field) {
    case 'critLow':
      return [log.oldCritLow, log.newCritLow]
    case 'warnLow':
      return [log.oldWarnLow, log.newWarnLow]
    case 'warnHigh':
      return [log.oldWarnHigh, log.newWarnHigh]
    case 'critHigh':
      return [log.oldCritHigh, log.newCritHigh]
  }
}

function show(value: number | null): string {
  return value === null ? '미설정' : String(value)
}

/**
 * 임계치 변경 이력 (누가·언제·왜 — docs/03 F-2). 접어 두었다가 펼칠 때만 조회한다.
 * 시각은 KST, 값은 바뀐 항목만 '이전 → 이후' 로 보여준다.
 */
export function ThresholdLogSection({
  equipmentId,
  sensorId,
  unit,
  defaultOpen = false,
}: ThresholdLogSectionProps) {
  const [open, setOpen] = useState(defaultOpen)
  const { logs, totalElements, loading, error, refetch } = useThresholdLogs(equipmentId, sensorId, open)

  return (
    <details
      className="threshold-log"
      open={open}
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary>
        변경 이력{open && !loading && !error ? ` (${totalElements}건)` : ''}
      </summary>

      {open && loading && logs.length === 0 && <LoadingBlock label="변경 이력을 불러오는 중…" />}
      {open && !loading && error && <ErrorState error={error} onRetry={refetch} />}
      {open && !loading && !error && logs.length === 0 && (
        <EmptyState
          title="변경 이력이 없습니다"
          description="임계치를 변경하면 변경자와 사유가 여기에 기록됩니다."
          icon="✎"
        />
      )}

      {open && logs.length > 0 && (
        <>
          <div className="threshold-log-scroll">
            <table className="data-table threshold-log-table">
              <thead>
                <tr>
                  <th scope="col">변경 시각 (KST)</th>
                  <th scope="col">변경자</th>
                  <th scope="col">사유</th>
                  <th scope="col">변경 내용{unit ? ` (${unit})` : ''}</th>
                </tr>
              </thead>
              <tbody>
                {logs.map((log) => {
                  const changed = THRESHOLD_FIELDS.map((field) => ({
                    field,
                    pair: pair(log, field.key),
                  })).filter(({ pair: [before, after] }) => before !== after)
                  return (
                    <tr key={log.id}>
                      <td className="mono">{formatKst(log.changedAt)}</td>
                      <td>{log.changedByName || '-'}</td>
                      <td className="threshold-log-reason">{log.reason}</td>
                      <td>
                        {changed.length === 0 ? (
                          <span className="field-hint">값 변경 없음</span>
                        ) : (
                          <ul className="threshold-diff">
                            {changed.map(({ field, pair: [before, after] }) => (
                              <li key={field.key} data-kind={field.kind}>
                                <span className="diff-label">{field.label}</span>
                                <span className="mono">
                                  {show(before)} → {show(after)}
                                </span>
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          {totalElements > logs.length && (
            <p className="field-hint">최근 {logs.length}건만 표시합니다 (전체 {totalElements}건).</p>
          )}
        </>
      )}
    </details>
  )
}
