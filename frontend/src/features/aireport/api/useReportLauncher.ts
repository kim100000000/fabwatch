import { useCallback, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toApiError } from '@/shared/api'
import { errorCodeOf, toUserMessage } from '@/shared/lib/errorMessage'
import { createAiReport, fetchAiReports } from './aiReportApi'
import type { ReportTarget } from '../types'

export interface ReportLauncher {
  /** 진행 중 — 버튼 disabled 용 (이중 제출 방지) */
  busy: boolean
  /** 마지막 실패의 사용자 메시지 (code 기준 매핑) */
  error: string | null
  clearError: () => void
  /** 기존 리포트가 있으면 그쪽으로, 없으면 생성(202) 후 /reports/{id} 로 이동. 성공 여부를 돌려준다. */
  launch: (target: ReportTarget) => Promise<boolean>
}

/**
 * 대상(알람/점검)의 기존 리포트 id 조회 — 최신순 1건.
 * 점검 대상이고 연계 알람이 있으면 ?alarmId= 도 조회한다 (서버는 점검 경유 리포트에도 alarmId 를 저장하지만,
 * 이전에 알람 경로로 만들어진 리포트와의 중복도 막기 위해 방어적으로 한 번 더 찾는다 — QA M-4).
 */
async function findExistingReportId(target: ReportTarget): Promise<number | null> {
  const query = async (filter: { alarmId?: number; inspectionId?: number }) => {
    const page = await fetchAiReports({ ...filter, page: 0, size: 1 })
    return page.content[0]?.id ?? null
  }
  if (target.alarmId !== undefined) return query({ alarmId: target.alarmId })

  const byInspection = await query({ inspectionId: target.inspectionId })
  if (byInspection !== null) return byInspection
  if (target.linkedAlarmId != null) return query({ alarmId: target.linkedAlarmId })
  return null
}

/**
 * 생성 진입점 공통 흐름 (S-3 헤더/탭, 알람 행, 점검 이력 상세).
 * 1) 같은 알람/점검의 리포트가 이미 있으면 새로 만들지 않고 보기로 이동
 * 2) 없으면 POST /ai-reports → 202 → 즉시 /reports/{reportId} (GENERATING 화면에서 폴링)
 * 3) 409 ALREADY_GENERATING 이면 메시지를 보여 주고, 기존 리포트를 다시 조회해 있으면 그쪽으로 이동
 * 4) 에러는 code 기준 한국어 매핑 (AI_QUOTA_EXCEEDED / ALREADY_GENERATING 등)
 */
export function useReportLauncher(): ReportLauncher {
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // state 는 비동기라 더블클릭 사이를 못 막는다 — ref 로 즉시 잠근다
  const lockRef = useRef(false)

  const launch = useCallback(
    async (target: ReportTarget): Promise<boolean> => {
      if (lockRef.current) return false
      lockRef.current = true
      setBusy(true)
      setError(null)
      try {
        const existingId = await findExistingReportId(target)
        if (existingId !== null) {
          navigate(`/reports/${existingId}`)
          return true
        }
        try {
          const accepted = await createAiReport(
            target.alarmId !== undefined ? { alarmId: target.alarmId } : { inspectionId: target.inspectionId },
          )
          navigate(`/reports/${accepted.reportId}`)
          return true
        } catch (cause) {
          const apiError = toApiError(cause)
          if (errorCodeOf(apiError) === 'ALREADY_GENERATING') {
            // 다른 곳에서 먼저 생성이 시작됨 — 안내 후 그 리포트로 이동을 시도한다
            setError(toUserMessage(apiError))
            try {
              const generatingId = await findExistingReportId(target)
              if (generatingId !== null) {
                navigate(`/reports/${generatingId}`)
                return true
              }
            } catch {
              // 재조회 실패는 위 안내 메시지만 남기고 넘어간다
            }
            return false
          }
          throw cause
        }
      } catch (cause) {
        setError(toUserMessage(toApiError(cause)))
        return false
      } finally {
        lockRef.current = false
        setBusy(false)
      }
    },
    [navigate],
  )

  const clearError = useCallback(() => setError(null), [])

  return { busy, error, clearError, launch }
}
