import { Modal } from '@/shared/ui'
import { SENSOR_TYPE_LABEL } from '../types'
import type { SensorLatest } from '../types'
import { ThresholdValuesSummary } from './ThresholdValuesSummary'
import { ThresholdLogSection } from './ThresholdLogSection'
import './sensor.css'

interface ThresholdHistoryDialogProps {
  equipmentId: number
  sensor: SensorLatest
  onClose: () => void
}

/**
 * 임계치 변경 이력 조회 전용 다이얼로그 — ADMIN 이 아닌 역할(ENGINEER/TECHNICIAN)용.
 * 이력 조회는 서버가 전체 로그인 사용자에게 열어 두었다. 편집 컨트롤은 아예 렌더링하지 않는다.
 */
export function ThresholdHistoryDialog({ equipmentId, sensor, onClose }: ThresholdHistoryDialogProps) {
  const label = SENSOR_TYPE_LABEL[sensor.sensorType] ?? sensor.sensorType
  return (
    <Modal
      title={`${label} 임계치 변경 이력`}
      wide
      onClose={onClose}
      footer={
        <button type="button" className="btn" onClick={onClose}>
          닫기
        </button>
      }
    >
      <div className="threshold-dialog">
        <ThresholdValuesSummary sensor={sensor} />
        <p className="field-hint">임계치 변경은 관리자(ADMIN)만 할 수 있습니다.</p>
        <ThresholdLogSection equipmentId={equipmentId} sensorId={sensor.sensorId} unit={sensor.unit} defaultOpen />
      </div>
    </Modal>
  )
}
