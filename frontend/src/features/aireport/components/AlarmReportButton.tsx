import { useAuth } from '@/app/providers/useAuth'
import { Spinner } from '@/shared/ui'
import { useReportLauncher } from '../api/useReportLauncher'
import { canManageReport } from '../types'
import './report.css'

interface AlarmReportButtonProps {
  alarmId: number
}

/**
 * 알람 행 'AI 리포트' 버튼 (알람 센터 / 설비 알람 탭). ENGINEER+ 에게만 보인다.
 * 이미 이 알람의 리포트가 있으면 새로 만들지 않고 해당 리포트로 이동한다.
 */
export function AlarmReportButton({ alarmId }: AlarmReportButtonProps) {
  const { user } = useAuth()
  const launcher = useReportLauncher()
  if (!canManageReport(user?.role)) return null

  return (
    <>
      <button
        type="button"
        className="btn btn-sm"
        disabled={launcher.busy}
        aria-label={`알람 #${alarmId} AI 리포트`}
        onClick={() => void launcher.launch({ alarmId })}
      >
        {launcher.busy && <Spinner />}
        AI 리포트
      </button>
      {launcher.error && (
        <span className="report-inline-error" role="alert">
          {launcher.error}
        </span>
      )}
    </>
  )
}
