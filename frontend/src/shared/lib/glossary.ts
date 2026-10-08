/**
 * 현장 용어 풀이 — 단일 출처 (docs/14_용어사전.md 와 같은 의미를 유지한다).
 * 화면에서는 `<Term term="MTBF" />` 또는 배지 title 로 노출한다. 문구를 바꿀 때는 docs/14 도 함께 확인한다.
 */
export type GlossaryKey =
  | 'PM'
  | 'BM'
  | 'MTBF'
  | 'MTTR'
  | 'AVAILABILITY'
  | 'ACK'
  | 'RESOLVE'
  | 'OVERDUE'
  | 'FOUR_M'
  | 'NG'
  | 'DOWN'
  | 'SEVERITY'
  | 'WARNING'
  | 'MAJOR'
  | 'CRITICAL'

export interface GlossaryEntry {
  /** 화면에 보이는 기본 표기 */
  label: string
  /** 한 줄 풀이 */
  description: string
}

export const GLOSSARY: Record<GlossaryKey, GlossaryEntry> = {
  PM: {
    label: 'PM',
    description: '예방정비(Preventive Maintenance). 고장 나기 전에 주기적으로 미리 하는 계획된 정비입니다.',
  },
  BM: {
    label: 'BM',
    description: '돌발정비(Breakdown Maintenance). 고장이 난 뒤 수행하는 수리로, 고장 원인(4M) 분류가 필수입니다.',
  },
  MTBF: {
    label: 'MTBF',
    description: '평균 고장 간격. 가동 시간의 합 ÷ 고장(DOWN 전환) 횟수이며, 길수록 설비가 안정적입니다. 고장이 없으면 "고장 없음"으로 표시합니다.',
  },
  MTTR: {
    label: 'MTTR',
    description: '평균 수리 시간. 고장(DOWN) 진입부터 이탈까지 걸린 시간의 평균이며, 짧을수록 복구가 빠릅니다. 아직 수리 중인 건은 제외합니다.',
  },
  AVAILABILITY: {
    label: '가동률',
    description: 'RUN 시간 ÷ (전체 시간 − PM 시간). 계획된 정비(PM) 시간은 분모에서 뺍니다.',
  },
  ACK: {
    label: '확인(ACK)',
    description: '알람을 확인했다는 표시이며 확인한 담당자가 기록됩니다. 확인을 거쳐야만 해제할 수 있습니다.',
  },
  RESOLVE: {
    label: '해제(RESOLVE)',
    description: '알람의 원인 조치를 끝내고 닫는 처리입니다. 확인(ACK)된 알람만 해제할 수 있고 해제 사유가 필수입니다.',
  },
  OVERDUE: {
    label: 'PM 지연',
    description: 'PM 예정일이 지난 상태(OVERDUE)입니다. 3일을 넘기면 주요(MAJOR) 알람이 발생합니다.',
  },
  FOUR_M: {
    label: '4M',
    description: '고장 원인 분류 체계. 사람(Man) · 설비(Machine) · 자재(Material) · 방법(Method) 중 하나를 고릅니다.',
  },
  NG: {
    label: 'NG',
    description: '체크리스트 항목 판정 "불량". NG가 1건이라도 있으면 점검 이력에 NG 표시가 붙습니다. (OK 정상 / NG 불량 / N/A 해당 없음)',
  },
  DOWN: {
    label: 'DOWN (BM)',
    description: '고장으로 멈춘 계획 외 정지 상태입니다. 위험(CRITICAL) 알람이 나면 자동으로 전환됩니다.',
  },
  SEVERITY: {
    label: '심각도',
    description: '경고(WARNING) < 주요(MAJOR) < 위험(CRITICAL) 순으로 높습니다. 위험은 설비를 자동으로 DOWN 처리합니다.',
  },
  WARNING: {
    label: 'WARNING',
    description: '경고. 센서값이 경고 임계치를 벗어났습니다. 주의해서 지켜보세요.',
  },
  MAJOR: {
    label: 'MAJOR',
    description: '주요. PM 지연 등 시스템 규칙으로 발생하는 중간 심각도 알람입니다.',
  },
  CRITICAL: {
    label: 'CRITICAL',
    description: '위험. 센서값이 위험 임계치를 벗어났습니다. 설비가 자동으로 DOWN 처리됩니다.',
  },
}

/** 설비 상태/심각도 값 → 풀이 키 (배지 title 용). 풀이가 없는 값은 undefined */
export function glossaryOf(key: string): GlossaryEntry | undefined {
  return (GLOSSARY as Record<string, GlossaryEntry | undefined>)[key]
}
