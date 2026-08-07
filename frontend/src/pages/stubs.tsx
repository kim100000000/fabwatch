import { StubPage } from './StubPage'

/**
 * docs/04 §2 화면 목록 중 이번 라운드 미구현 화면의 라우팅 스텁.
 * 각 화면 구현 시 이 파일에서 꺼내 전용 페이지 파일로 승격한다.
 */

/** S-1 라인 현황 대시보드 (/) */
export function DashboardPage() {
  return (
    <StubPage
      screen="S-1"
      title="라인 현황 대시보드"
      plan="2주차: KPI 스트립(가동률/금일 알람/PM지연/DOWN 수) + 설비 카드 그리드(SSE 2초 갱신) + 실시간 알람 스트림"
    />
  )
}

/** S-4 점검 이력 목록/검색 (/inspections) */
export function InspectionListPage() {
  return (
    <StubPage
      screen="S-4"
      title="점검 이력"
      plan="3주차: 점검 이력 목록/검색 (filter: 설비·유형·교대·작업자·NG 여부·기간)"
    />
  )
}

/** S-5 점검 이력 등록 (/inspections/new) */
export function InspectionCreatePage() {
  return (
    <StubPage
      screen="S-5"
      title="점검 이력 등록"
      plan="3주차: PM/BM 토글 분기 폼 — PM은 체크리스트 OK/NG/NA, BM은 4M 분류 + 연계 알람. 소요시간 자동 계산, 교대조 자동 판정"
    />
  )
}

/** S-6 알람 센터 (/alarms) */
export function AlarmCenterPage() {
  return (
    <StubPage
      screen="S-6"
      title="알람 센터"
      plan="2주차: 알람 목록(OPEN 우선 정렬) + ACK/RESOLVE 처리 + SSE 신규 알람 수신"
    />
  )
}

/** S-7 AI 리포트 (/reports) */
export function ReportListPage() {
  return (
    <StubPage
      screen="S-7"
      title="AI 리포트"
      plan="3주차: 리포트 목록/상세/편집 (DRAFT·CONFIRMED 배지, 마크다운 렌더링 — raw HTML 비활성)"
    />
  )
}

/** S-8 관리 (/admin) */
export function AdminPage() {
  return (
    <StubPage
      screen="S-8"
      title="관리"
      plan="2~4주차: 사용자 관리 · 임계치 설정 · PM 스케줄 · 시뮬레이터 시나리오 제어 (ADMIN / 일부 ENGINEER)"
    />
  )
}
