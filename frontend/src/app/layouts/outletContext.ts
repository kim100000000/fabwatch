/** AppLayout 이 Outlet context 로 내려주는 값 (헤더에서 선택한 라인) */
export interface AppOutletContext {
  /** 선택된 라인 ID (전체 라인은 null) */
  lineId: number | null
}
