import { useCallback, useEffect, useState } from 'react'
import axios from 'axios'
import { toApiError } from '@/shared/api'
import type { ApiError } from '@/shared/api'
import { fetchAiReport } from './aiReportApi'
import type { AiReport } from '../types'

/** GENERATING 폴링 간격 / 최대 횟수 (2초 × 75 = 약 150초 — Claude 읽기 60초 + 재시도 1회 포함 최대 약 2분) */
const POLL_INTERVAL_MS = 2000
const MAX_POLL_TICKS = 75
/** 폴링 중 연속 실패 허용 횟수 — 일시적 네트워크 오류는 넘기고 계속 시도한다 */
const MAX_POLL_ERRORS = 3

export interface AiReportDetailResult {
  report: AiReport | null
  loading: boolean
  /** 최초 조회 실패 또는 폴링 연속 실패 */
  error: ApiError | null
  /** GENERATING 이 약 150초 넘게 끝나지 않아 폴링을 멈춘 상태 */
  pollTimedOut: boolean
  /** 서버 응답(저장/확정 결과)으로 즉시 갱신 */
  setReport: (report: AiReport) => void
  /** 다시 조회 (GENERATING 이면 폴링 재개) */
  refetch: () => void
}

/**
 * GET /ai-reports/{id} 상세 훅 + GENERATING 자동 폴링.
 * - GENERATING 인 동안만 2초 간격 폴링, 끝나면(DRAFT/FAILED/CONFIRMED) 즉시 중단
 * - 약 150초 후 pollTimedOut — 호출부가 '새로고침해 주세요' 안내
 * - 탭이 숨겨진 동안은 요청을 보내지 않고(횟수도 세지 않음), 다시 보이면 즉시 1회 조회
 * - 언마운트/상태 변경 시 interval·진행 중 요청을 모두 정리 (메모리 누수 방지)
 */
export function useAiReportDetail(reportId: number | null): AiReportDetailResult {
  const [report, setReportState] = useState<AiReport | null>(null)
  const [loading, setLoading] = useState<boolean>(reportId !== null)
  const [error, setError] = useState<ApiError | null>(null)
  const [pollTimedOut, setPollTimedOut] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)

  const setReport = useCallback((next: AiReport) => {
    setReportState(next)
    setError(null)
  }, [])

  const refetch = useCallback(() => {
    setPollTimedOut(false)
    setReloadKey((key) => key + 1)
  }, [])

  // 1) 최초/수동 조회
  useEffect(() => {
    if (reportId === null) {
      setLoading(false)
      return
    }
    const controller = new AbortController()
    let alive = true
    setLoading(true)
    setError(null)

    fetchAiReport(reportId, controller.signal)
      .then((result) => {
        if (!alive) return
        setReportState(result)
        setLoading(false)
      })
      .catch((cause: unknown) => {
        if (!alive || axios.isCancel(cause)) return
        setError(toApiError(cause))
        setLoading(false)
      })

    return () => {
      alive = false
      controller.abort()
    }
  }, [reportId, reloadKey])

  // 2) GENERATING 폴링
  const generating = report?.status === 'GENERATING'
  useEffect(() => {
    if (reportId === null || !generating) return

    const controller = new AbortController()
    let alive = true
    let inFlight = false
    let ticks = 0
    let errors = 0
    let timer: ReturnType<typeof setInterval> | null = null

    const stop = () => {
      if (timer !== null) {
        clearInterval(timer)
        timer = null
      }
    }

    const tick = async () => {
      if (!alive || inFlight || document.hidden) return
      inFlight = true
      ticks += 1
      try {
        const next = await fetchAiReport(reportId, controller.signal)
        if (!alive) return
        errors = 0
        setReportState(next)
        if (next.status !== 'GENERATING') stop()
        else if (ticks >= MAX_POLL_TICKS) {
          stop()
          setPollTimedOut(true)
        }
      } catch (cause) {
        if (!alive || axios.isCancel(cause)) return
        errors += 1
        if (errors >= MAX_POLL_ERRORS) {
          stop()
          setError(toApiError(cause))
        } else if (ticks >= MAX_POLL_TICKS) {
          stop()
          setPollTimedOut(true)
        }
      } finally {
        inFlight = false
      }
    }

    // 탭이 다시 보이면 기다리지 않고 바로 한 번 조회
    const onVisible = () => {
      if (!document.hidden) void tick()
    }

    timer = setInterval(() => void tick(), POLL_INTERVAL_MS)
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      alive = false
      stop()
      controller.abort()
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [reportId, generating, reloadKey])

  return { report, loading, error, pollTimedOut, setReport, refetch }
}
