import { useAuth } from '@/app/providers/useAuth'
import { Spinner } from '@/shared/ui'
import { useReportLauncher } from '../api/useReportLauncher'
import { canManageReport } from '../types'
import './report.css'

interface InspectionReportButtonProps {
  inspectionId: number
  /** 점검에 연계된 알람 (BM) — 알람 경로로 만든 기존 리포트 중복 방지용 */
  alarmId?: number | null
  /** 점검 유형 — BM 에서만 노출 (고장 수리 이력 기반 리포트) */
  type: 'PM' | 'BM'
}

/** 점검 이력 상세 모달의 'AI 리포트 생성' 버튼 (BM 만, ENGINEER+). 기존 리포트가 있으면 그쪽으로 이동. */
export function InspectionReportButton({ inspectionId, alarmId, type }: InspectionReportButtonProps) {
  const { user } = useAuth()
  const launcher = useReportLauncher()
  if (type !== 'BM' || !canManageReport(user?.role)) return null

  return (
    <>
      {launcher.error && (
        <span className="report-inline-error" role="alert">
          {launcher.error}
        </span>
      )}
      <button
        type="button"
        className="btn"
        disabled={launcher.busy}
        onClick={() => void launcher.launch({ inspectionId, linkedAlarmId: alarmId })}
      >
        {launcher.busy && <Spinner />}
        AI 리포트 생성
      </button>
    </>
  )
}
