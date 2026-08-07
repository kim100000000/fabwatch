import { Route, Routes } from 'react-router-dom'
import { AppLayout } from './layouts/AppLayout'
import { ProtectedRoute } from './ProtectedRoute'
import {
  AdminPage,
  AlarmCenterPage,
  DashboardPage,
  EquipmentDetailPage,
  EquipmentListPage,
  InspectionCreatePage,
  InspectionListPage,
  LoginPage,
  NotFoundPage,
  ReportListPage,
} from '@/pages'

/**
 * 라우팅 (docs/04 §2 화면 목록 S-0 ~ S-8)
 * S-0 만 공개, 나머지는 로그인 필요.
 */
export function AppRoutes() {
  return (
    <Routes>
      {/* S-0 로그인 */}
      <Route path="/login" element={<LoginPage />} />

      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          {/* S-1 라인 현황 대시보드 */}
          <Route index element={<DashboardPage />} />
          {/* S-2 설비 목록 */}
          <Route path="equipment" element={<EquipmentListPage />} />
          {/* S-3 설비 상세 */}
          <Route path="equipment/:id" element={<EquipmentDetailPage />} />
          {/* S-4 점검 이력 목록 */}
          <Route path="inspections" element={<InspectionListPage />} />
          {/* S-5 점검 이력 등록 */}
          <Route path="inspections/new" element={<InspectionCreatePage />} />
          {/* S-6 알람 센터 */}
          <Route path="alarms" element={<AlarmCenterPage />} />
          {/* S-7 AI 리포트 */}
          <Route path="reports" element={<ReportListPage />} />
          {/* S-8 관리 */}
          <Route path="admin" element={<AdminPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>
    </Routes>
  )
}
