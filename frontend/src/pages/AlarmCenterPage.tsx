import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  AlarmFilterBar,
  AlarmResolveDialog,
  AlarmTable,
  DEFAULT_ALARM_FILTER,
  ManualAlarmDialog,
  ackAlarm,
  alarmFromEvent,
  alarmMatchesFilter,
  inspectionRegisterPath,
  mergeAlarms,
  useAlarmList,
} from '@/features/alarm'
import type { Alarm, AlarmFilterValue } from '@/features/alarm'
import { AlarmReportButton } from '@/features/aireport'
import { useEquipmentList } from '@/features/equipment'
import { useSensorStream } from '@/features/sensor'
import { usePageTitle } from '@/shared/hooks/usePageTitle'
import { EmptyState, ErrorState, LoadingBlock, StreamStatusBadge } from '@/shared/ui'
import { toApiError } from '@/shared/api'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { kstDateToUtcIso } from '@/shared/lib/datetime'

/** 한 번에 불러오는 건수 / 서버 최대 페이지 크기(100) */
const PAGE_STEP = 50
const PAGE_MAX = 100

/**
 * S-6 알람 센터 (/alarms)
 *
 * - GET /alarms (OPEN 우선 정렬은 서버 기본, 화면에서도 compareAlarms 로 유지). 기본 필터는 '미해결(발생+확인)'
 * - 필터: 설비 / 상태 / 심각도 / 기간(KST 날짜 → UTC ISO 변환 후 전송)
 * - PATCH /alarms/{id}/ack, PATCH /alarms/{id}/resolve (확인 상태에서만, 해제 사유 필수)
 * - POST /alarms/manual 수동 고장 보고
 * - SSE /stream/sensors 의 alarm 이벤트로 신규 알람 실시간 추가 (실패 시 3초 폴링 폴백)
 */
export function AlarmCenterPage() {
  usePageTitle('알람 센터')
  const navigate = useNavigate()
  const [filter, setFilter] = useState<AlarmFilterValue>(DEFAULT_ALARM_FILTER)
  const [pageSize, setPageSize] = useState(PAGE_STEP)
  const [reloadKey, setReloadKey] = useState(0)
  const [liveAlarms, setLiveAlarms] = useState<Alarm[]>([])
  const [busyAlarmId, setBusyAlarmId] = useState<number | null>(null)
  const [resolveTarget, setResolveTarget] = useState<Alarm | null>(null)
  const [manualOpen, setManualOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const { equipments } = useEquipmentList({ size: 100 })

  // 필터가 바뀌면 이전 필터 기준으로 쌓인 실시간 알람과 '더 보기' 상태를 버린다
  useEffect(() => {
    setLiveAlarms([])
    setPageSize(PAGE_STEP)
  }, [filter])

  const listFilter = useMemo(
    () => ({
      equipmentId: filter.equipmentId,
      status: filter.status,
      severity: filter.severity,
      from: kstDateToUtcIso(filter.fromDate, 'start') ?? null,
      to: kstDateToUtcIso(filter.toDate, 'end') ?? null,
      size: pageSize,
    }),
    [filter, pageSize],
  )

  const { alarms, totalElements, loading, error } = useAlarmList(listFilter, reloadKey)

  const refresh = useCallback(() => setReloadKey((key) => key + 1), [])

  /** 알람이 현재 필터에 맞는지 — 안 맞으면 목록에 끼워 넣지 않는다 */
  const matchesFilter = useCallback(
    (alarm: Alarm): boolean => alarmMatchesFilter(alarm, filter, kstDateToUtcIso),
    [filter],
  )

  const stream = useSensorStream(filter.equipmentId ?? null, {
    onAlarm: (payload) => {
      // SSE 페이로드는 alarmId 를 쓰므로 반드시 변환해서 목록 shape 로 맞춘다
      const alarm = alarmFromEvent(payload)
      if (!matchesFilter(alarm)) return
      setLiveAlarms((previous) => [alarm, ...previous.filter((item) => item.id !== alarm.id)])
    },
    onPoll: async () => {
      // 폴백 폴링(3초): 목록 재조회로 신규 알람을 따라잡는다
      setReloadKey((key) => key + 1)
    },
  })

  // 서버 값과 실시간·로컬 값을 합치고(서버 우선 / 더 진행된 처리 단계 우선), 표시 직전에 필터를 다시 적용한다
  const mergedAlarms = useMemo(
    () => mergeAlarms(alarms, liveAlarms).filter(matchesFilter),
    [alarms, liveAlarms, matchesFilter],
  )

  /** 서버 응답으로 로컬 상태를 갱신 (확인/해제 직후 즉시 반영) */
  const applyUpdated = useCallback((updated: Alarm) => {
    setLiveAlarms((previous) => [updated, ...previous.filter((item) => item.id !== updated.id)])
  }, [])

  const handleAck = async (alarm: Alarm) => {
    setBusyAlarmId(alarm.id)
    setActionError(null)
    try {
      const updated = await ackAlarm(alarm.id)
      applyUpdated(updated)
      setNotice(`알람 #${alarm.id} 을(를) 확인 처리했습니다.`)
      refresh()
    } catch (cause) {
      setActionError(toUserMessage(toApiError(cause)))
    } finally {
      setBusyAlarmId(null)
    }
  }

  const hasMore = totalElements > mergedAlarms.length

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">알람 센터</h1>
          <p className="page-subtitle">
            발생 → 확인 → 해제 순서로만 처리됩니다 (확인 없이 해제할 수 없습니다).
          </p>
        </div>
        <div className="equipment-toolbar" style={{ margin: 0 }}>
          <StreamStatusBadge state={stream} />
          <button type="button" className="btn" onClick={() => setManualOpen(true)}>
            수동 고장 보고
          </button>
          <button type="button" className="btn btn-ghost" onClick={refresh}>
            새로고침
          </button>
        </div>
      </div>

      {notice && (
        <p className="form-notice" role="status">
          {notice}
          <button type="button" onClick={() => setNotice(null)} aria-label="알림 닫기">
            ✕
          </button>
        </p>
      )}

      {actionError && (
        <p className="form-error" role="alert">
          {actionError}
        </p>
      )}

      <AlarmFilterBar value={filter} onChange={setFilter} equipments={equipments} />

      {loading && mergedAlarms.length === 0 && <LoadingBlock label="알람을 불러오는 중…" />}

      {!loading && error && mergedAlarms.length === 0 && (
        <ErrorState error={error} onRetry={refresh} />
      )}

      {!loading && !error && mergedAlarms.length === 0 && (
        <EmptyState
          title={filter.status === 'UNRESOLVED' ? '처리할 알람이 없습니다' : '조건에 맞는 알람이 없습니다'}
          description={
            filter.status === 'UNRESOLVED'
              ? '미해결 알람이 모두 처리되었습니다. 지난 알람은 상태를 "전체"로 바꿔 확인하세요.'
              : '필터를 완화하거나 기간을 넓혀 보세요.'
          }
          icon="⚠"
        />
      )}

      {mergedAlarms.length > 0 && (
        <>
          <p className="field-hint alarm-count-note" role="status">
            총 <span className="mono">{totalElements}</span>건 중{' '}
            <span className="mono">{mergedAlarms.length}</span>건 표시
            {hasMore && pageSize >= PAGE_MAX && ' — 설비·기간·심각도 조건으로 범위를 좁혀 주세요.'}
          </p>
          <AlarmTable
            alarms={mergedAlarms}
            busyAlarmId={busyAlarmId}
            onAck={(alarm) => void handleAck(alarm)}
            onResolveRequest={setResolveTarget}
            onSelectEquipment={(equipmentId) => navigate(`/equipment/${equipmentId}`)}
            onOpenInspection={(inspectionId) => navigate(`/inspections?id=${inspectionId}`)}
            onRegisterInspection={(alarm) => navigate(inspectionRegisterPath(alarm))}
            // AI 리포트는 센서/수동 알람의 미해결 건에만 — PM 지연·해제 완료 알람에는 숨긴다
            renderExtraActions={(alarm) =>
              alarm.alarmType !== 'PM_OVERDUE' && alarm.status !== 'RESOLVED' ? (
                <AlarmReportButton alarmId={alarm.id} />
              ) : null
            }
          />
          {hasMore && pageSize < PAGE_MAX && (
            <div className="alarm-more">
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => setPageSize((size) => Math.min(size + PAGE_STEP, PAGE_MAX))}
              >
                더 보기
              </button>
            </div>
          )}
        </>
      )}

      {resolveTarget && (
        <AlarmResolveDialog
          alarm={resolveTarget}
          onClose={() => setResolveTarget(null)}
          onResolved={(updated) => {
            applyUpdated(updated)
            setResolveTarget(null)
            setNotice(`알람 #${updated.id} 을(를) 해제 처리했습니다.`)
            refresh()
          }}
        />
      )}

      {manualOpen && (
        <ManualAlarmDialog
          equipments={equipments}
          defaultEquipmentId={filter.equipmentId}
          onClose={() => setManualOpen(false)}
          onCreated={(created) => {
            setManualOpen(false)
            setLiveAlarms((previous) => [created, ...previous])
            setNotice(`수동 고장 보고를 등록했습니다 (알람 #${created.id}).`)
            refresh()
          }}
        />
      )}
    </div>
  )
}
