import type { FocusEvent, MouseEvent, ReactNode } from 'react'
import type { EquipmentSummary } from '../types'
import {
  alarmRing,
  describeUnit,
  detectEquipmentKind,
  isPartAbnormal,
  isSensorStale,
  resolveParts,
  statusVisual,
} from '../floorMap'
import type { AlarmRing, EquipmentKind, FloorMapAlarm, FloorMapSensor, PartInfo } from '../floorMap'
import './floormap.css'

/** 설비 1대의 SVG 좌표계 — 컨베이어(FloorMapConveyor)와 세로 기준을 맞추기 위해 같은 viewBox 높이를 쓴다 */
/** 툴팁 요소 id — 열려 있는 설비 박스가 aria-describedby 로 가리킨다 (툴팁은 한 번에 하나만 뜬다) */
export const FLOOR_MAP_TOOLTIP_ID = 'fm-tooltip'

export const UNIT_WIDTH = 152
export const UNIT_VIEW_Y = -12
export const UNIT_VIEW_HEIGHT = 186
/** 본체 세로 중심 — 컨베이어 벨트가 이 높이에 놓인다 */
export const UNIT_BELT_Y = 68

const NAME_MAX_CHARS = 13

/** 센서 끊김 시 본체 면 불투명도 — 0.55 미만이면 DOWN/PM 등 상태 색 구분이 어려워진다 */
const STALE_BODY_OPACITY = 0.7

/** 한글은 넓고 영문/숫자는 좁아서, 뱃지 폭을 글자 수로 어림한다 */
function textWidth(text: string, size: number): number {
  let width = 0
  for (const char of text) width += char.charCodeAt(0) > 0x2e80 ? size : size * 0.62
  return width
}

function truncate(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text
}

/* ------------------------------------------------------------------
 * 아이콘 — 이모지는 폰트에 따라 모양이 달라져 SVG 로 직접 그린다
 * ------------------------------------------------------------------ */

function WarnIcon({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`} className="fm-icon">
      <path d="M0 -5.5 L6 5 H-6 Z" strokeWidth="1.6" strokeLinejoin="round" />
      <path d="M0 -1.5 V1.5" strokeWidth="1.6" strokeLinecap="round" />
      <circle cx="0" cy="3.4" r="0.6" strokeWidth="0.8" />
    </g>
  )
}

function CriticalIcon({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`} className="fm-icon">
      <path d="M-2.4 -5.5 H2.4 L5.5 -2.4 V2.4 L2.4 5.5 H-2.4 L-5.5 2.4 V-2.4 Z" strokeWidth="1.6" strokeLinejoin="round" />
      <path d="M-2.2 -2.2 L2.2 2.2 M2.2 -2.2 L-2.2 2.2" strokeWidth="1.5" strokeLinecap="round" />
    </g>
  )
}

function ClockIcon({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`} className="fm-icon">
      <circle cx="0" cy="0" r="5.2" strokeWidth="1.5" />
      <path d="M0 -2.8 V0 L2.4 1.6" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </g>
  )
}

function QuestionIcon({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`} className="fm-icon">
      <circle cx="0" cy="0" r="5.2" strokeWidth="1.5" />
      <path d="M-1.9 -1.6 a2 2 0 1 1 2.8 1.8 c-0.6 0.4 -0.8 0.8 -0.8 1.4" strokeWidth="1.4" strokeLinecap="round" />
      <circle cx="0.1" cy="3.1" r="0.5" strokeWidth="0.9" />
    </g>
  )
}

/* ------------------------------------------------------------------
 * 외곽 링 (알람) — 본체 색은 건드리지 않는다
 * ------------------------------------------------------------------ */

const RING = { x: 4, y: 4, w: 144, h: 120, r: 13 }

export function RingShape({ ring, rect = RING }: { ring: AlarmRing; rect?: typeof RING }) {
  return (
    <g className="fm-ring-group" data-severity={ring.severity} data-blink={ring.blink}>
      {ring.doubleRing && (
        <rect
          className="fm-ring-outer"
          x={rect.x - 3.2}
          y={rect.y - 3.2}
          width={rect.w + 6.4}
          height={rect.h + 6.4}
          rx={rect.r + 3}
          style={{ stroke: ring.colorVar }}
          strokeWidth={1.4}
        />
      )}
      <rect
        className="fm-ring"
        x={rect.x}
        y={rect.y}
        width={rect.w}
        height={rect.h}
        rx={rect.r}
        style={{ stroke: ring.colorVar }}
        strokeWidth={ring.width}
        strokeDasharray={ring.dasharray ?? undefined}
        strokeLinecap="butt"
      />
    </g>
  )
}

/* ------------------------------------------------------------------
 * 종류별 외형 (가상 일반형 4종 + 범용 박스) — 지붕 장식 + 본체 상단 스트립
 * ------------------------------------------------------------------ */

/** 지붕 장식(본체 뒤)과 본체 상단 스트립(본체 앞) — 그리는 순서가 달라 둘로 나눠 돌려준다 */
function kindArt(kind: EquipmentKind): { roof: ReactNode; strip: ReactNode } {
  switch (kind) {
    case 'LAMI': // 합착기: 상·하 가압 롤러 + 판 사이 시트
      return {
        roof: (
          <g className="fm-roof">
            <rect x="34" y="16" width="84" height="6" rx="2" />
            <circle cx="52" cy="13" r="6" />
            <circle cx="100" cy="13" r="6" />
          </g>
        ),
        strip: (
          <g className="fm-strip">
            <rect x="86" y="28" width="46" height="4" rx="1" />
            <path d="M84 35.5 H134" />
            <rect x="86" y="39" width="46" height="4" rx="1" />
          </g>
        ),
      }
    case 'OVEN': // 경화로: 굴뚝 + 열 파형
      return {
        roof: (
          <g className="fm-roof">
            <rect x="34" y="8" width="14" height="14" rx="2" />
            <rect x="104" y="8" width="14" height="14" rx="2" />
            <rect x="30" y="18" width="92" height="4" rx="1" />
          </g>
        ),
        strip: (
          <g className="fm-strip">
            <path d="M92 43 q-3.5 -4 0 -7.5 q3.5 -4 0 -7.5" />
            <path d="M108 43 q-3.5 -4 0 -7.5 q3.5 -4 0 -7.5" />
            <path d="M124 43 q-3.5 -4 0 -7.5 q3.5 -4 0 -7.5" />
          </g>
        ),
      }
    case 'SCRB': // 스크라이버: 갠트리 레일 + 커팅 헤드
      return {
        roof: (
          <g className="fm-roof">
            <rect x="18" y="14" width="116" height="4" rx="1.5" />
            <rect x="68" y="14" width="16" height="9" rx="2" />
          </g>
        ),
        strip: (
          <g className="fm-strip">
            <path d="M84 41 H134" />
            <path d="M109 27 L103.5 38 H114.5 Z" />
          </g>
        ),
      }
    case 'AOI': // 검사기: 카메라 돔 + 스캔 빔
      return {
        roof: (
          <g className="fm-roof">
            <path d="M56 22 a20 13 0 0 1 40 0 Z" />
            <circle cx="76" cy="15" r="3.5" />
          </g>
        ),
        strip: (
          <g className="fm-strip">
            <path d="M109 27 L90 41 M109 27 L128 41 M109 27 V41" />
            <path d="M84 42 H134" />
          </g>
        ),
      }
    default: // 범용 박스
      return {
        roof: (
          <g className="fm-roof">
            <rect x="30" y="15" width="92" height="7" rx="2" />
          </g>
        ),
        strip: (
          <g className="fm-strip">
            <rect x="90" y="30" width="9" height="9" rx="1.5" />
            <rect x="104" y="30" width="9" height="9" rx="1.5" />
            <rect x="118" y="30" width="9" height="9" rx="1.5" />
          </g>
        ),
      }
  }
}

/* ------------------------------------------------------------------
 * 파트 4슬롯 — 센서 4종 대응 (좌상 히터/챔버, 우상 구동 모터, 좌하 진공/에어, 우하 전장 제어)
 * ------------------------------------------------------------------ */

const CELL_W = 54
const CELL_H = 26
const CELL_POS: Record<PartInfo['sensorType'], { x: number; y: number }> = {
  TEMP: { x: 20, y: 49 },
  VIBRATION: { x: 78, y: 49 },
  PRESSURE: { x: 20, y: 79 },
  CURRENT: { x: 78, y: 79 },
}

/** 파트 글리프 — 셀 로컬 좌표(54x26) */
function PartGlyph({ type }: { type: PartInfo['sensorType'] }) {
  switch (type) {
    case 'TEMP': // 히터 코일
      return <path d="M7 13 H13 L16 5 L21 21 L26 5 L31 21 L34 13 H47" />
    case 'VIBRATION': // 구동 모터 + 축
      return (
        <>
          <circle cx="17" cy="13" r="8" />
          <circle cx="17" cy="13" r="2.5" />
          <path d="M25 13 H46 M42 8 V18" />
        </>
      )
    case 'PRESSURE': // 진공/에어 배관 + 게이지
      return (
        <>
          <path d="M6 13 H31" />
          <circle cx="39" cy="13" r="8" />
          <path d="M39 13 L43 8.5" />
        </>
      )
    case 'CURRENT': // 전장 제어 (번개)
      return (
        <>
          <path d="M7 13 H17 M37 13 H47" />
          <path d="M30 3.5 L21 15 H27.5 L25 22.5 L34 11 H27.5 Z" />
        </>
      )
  }
}

function PartCell({ part }: { part: PartInfo }) {
  const { x, y } = CELL_POS[part.sensorType]
  const abnormal = isPartAbnormal(part.state)
  return (
    <g transform={`translate(${x} ${y})`} className="fm-part" data-state={part.state} data-part={part.sensorType}>
      <rect className="fm-part-bg" width={CELL_W} height={CELL_H} rx="3" />
      {part.state !== 'NONE' && (
        <g className="fm-glyph">
          <PartGlyph type={part.sensorType} />
        </g>
      )}
      {abnormal && (
        // 파트 이상: 색(주황/빨강) + 라벨 글자(+ 경고/위험)로 함께 표시
        <text className="fm-part-label" x={CELL_W / 2} y={CELL_H / 2 + 3.6} textAnchor="middle">
          {part.shortLabel} {part.state === 'CRITICAL' ? '위험' : '경고'}
        </text>
      )}
    </g>
  )
}

/* ------------------------------------------------------------------
 * 설비 박스 1대
 * ------------------------------------------------------------------ */

export interface FloorMapUnitProps {
  equipment: EquipmentSummary
  /** undefined = 센서 목록 아직 못 받음 */
  sensors: readonly FloorMapSensor[] | undefined
  /** null = 알람 집계 전/실패(알 수 없음), undefined = 이 설비는 미해결 알람 없음 */
  alarm: FloorMapAlarm | null | undefined
  alarmUnknown: boolean
  /** 알람 집계를 못 가져온 경우 (집계 중과 구분해 '집계 실패'로 표기) */
  alarmFailed: boolean
  /** 브라우저가 이 설비의 새 센서값을 마지막으로 받은 시각(epoch ms) — 끊김 판정 기준 */
  lastReceivedAt: number | undefined
  /** 이 설비의 툴팁이 열려 있는지 (aria-describedby 연결용) */
  tooltipOpen: boolean
  nowMs: number
  onSelect: (equipmentId: number) => void
  onTooltipShow: (equipmentId: number, target: HTMLElement) => void
  onTooltipHide: () => void
}

export function FloorMapUnit({
  equipment,
  sensors,
  alarm,
  alarmUnknown,
  alarmFailed,
  lastReceivedAt,
  tooltipOpen,
  nowMs,
  onSelect,
  onTooltipShow,
  onTooltipHide,
}: FloorMapUnitProps) {
  const kind = detectEquipmentKind(equipment.code)
  const visual = statusVisual(equipment.status)
  const ring = alarmRing(alarm)
  const parts = resolveParts(sensors)
  const art = kindArt(kind)
  const stale = isSensorStale(sensors, lastReceivedAt, nowMs)
  const pmOverdue = !!equipment.pmOverdue

  const ariaLabel = describeUnit({
    code: equipment.code,
    name: equipment.name,
    status: equipment.status,
    kind,
    ring,
    alarmUnknown,
    alarmFailed,
    pmOverdue,
    stale,
    parts,
  })

  // 알람 뱃지 폭 — '경고 2' 같은 글자 폭에 맞춘다
  const alarmText = ring ? `${ring.label} ${ring.count}` : ''
  const alarmPillW = ring ? 24 + textWidth(alarmText, 10) : 0
  const pmPillW = 24 + textWidth('PM 지연', 10)

  return (
    <button
      type="button"
      className="fm-unit"
      data-status={equipment.status}
      data-kind={kind}
      data-stale={stale ? 'true' : undefined}
      aria-label={ariaLabel}
      aria-describedby={tooltipOpen ? FLOOR_MAP_TOOLTIP_ID : undefined}
      onClick={() => onSelect(equipment.id)}
      onMouseEnter={(event: MouseEvent<HTMLElement>) => onTooltipShow(equipment.id, event.currentTarget)}
      onMouseLeave={onTooltipHide}
      onFocus={(event: FocusEvent<HTMLElement>) => onTooltipShow(equipment.id, event.currentTarget)}
      onBlur={onTooltipHide}
    >
      <svg
        className="fm-svg"
        width={UNIT_WIDTH}
        height={UNIT_VIEW_HEIGHT}
        viewBox={`0 ${UNIT_VIEW_Y} ${UNIT_WIDTH} ${UNIT_VIEW_HEIGHT}`}
        aria-hidden="true"
        focusable="false"
      >
        {ring && <RingShape ring={ring} />}

        {/* 장비 본체 — 끊김(stale)이면 면만 흐리게(최소 0.55). 상태 라벨은 이 그룹 밖이라 흐려지지 않는다 */}
        <g className="fm-machine" opacity={stale ? STALE_BODY_OPACITY : 1}>
          {art.roof}
          <rect className="fm-feet" x="24" y="114" width="24" height="5" rx="1.5" />
          <rect className="fm-feet" x="104" y="114" width="24" height="5" rx="1.5" />
          <rect className="fm-body" x="10" y="22" width="132" height="92" rx="8" style={{ fill: visual.colorVar }} />
          {art.strip}
          <rect className="fm-panel" x="17" y="46" width="118" height="62" rx="5" />
          {parts.map((part) => (
            <PartCell key={part.sensorType} part={part} />
          ))}
        </g>
        {/* 끊김일 때는 어두운 테두리(halo)를 둘러 흐려진 본체 위에서도 라벨이 읽히게 한다 */}
        <text className="fm-status-text" data-stale={stale ? 'true' : undefined} x="18" y="40">
          {visual.label}
        </text>

        {stale && (
          <g className="fm-stale-mark">
            <circle cx="76" cy="77" r="14" />
            <text x="76" y="84" textAnchor="middle">
              ?
            </text>
          </g>
        )}

        {/* 모서리 뱃지: 좌상=PM 지연, 우상=미해결 알람 (아이콘 + 글자) */}
        {pmOverdue && (
          <g className="fm-pill fm-pill-pm" transform="translate(2 -10)">
            <rect width={pmPillW} height="17" rx="8.5" />
            <ClockIcon x={11} y={8.5} />
            <text x={20} y={12.2}>
              PM 지연
            </text>
          </g>
        )}
        {ring && (
          <g
            className="fm-pill fm-pill-alarm"
            data-severity={ring.severity}
            data-unacked={ring.unacked ? 'true' : 'false'}
            transform={`translate(${UNIT_WIDTH - 2 - alarmPillW} -10)`}
          >
            <rect width={alarmPillW} height="17" rx="8.5" />
            {ring.severity === 'CRITICAL' ? <CriticalIcon x={11} y={8.5} /> : <WarnIcon x={11} y={8.5} />}
            <text x={21} y={12.2}>
              {alarmText}
            </text>
          </g>
        )}

        {/* 캡션 */}
        <text className="fm-code" x={UNIT_WIDTH / 2} y="139" textAnchor="middle">
          {equipment.code}
        </text>
        <text className="fm-name" x={UNIT_WIDTH / 2} y="154" textAnchor="middle">
          {truncate(equipment.name, NAME_MAX_CHARS)}
        </text>
        {stale && (
          <g className="fm-stale-caption">
            <QuestionIcon x={UNIT_WIDTH / 2 - 26} y={166} />
            <text x={UNIT_WIDTH / 2 - 17} y={170}>
              수신 끊김
            </text>
          </g>
        )}
      </svg>
    </button>
  )
}

/* ------------------------------------------------------------------
 * 컨베이어 — 설비 사이 화살표. 양쪽이 RUN 일 때만 흐름 애니메이션
 * ------------------------------------------------------------------ */

export const CONVEYOR_WIDTH = 48

export function FloorMapConveyor({ flowing }: { flowing: boolean }) {
  return (
    <svg
      className="fm-conveyor"
      data-flowing={flowing ? 'true' : 'false'}
      width={CONVEYOR_WIDTH}
      height={UNIT_VIEW_HEIGHT}
      viewBox={`0 ${UNIT_VIEW_Y} ${CONVEYOR_WIDTH} ${UNIT_VIEW_HEIGHT}`}
      aria-hidden="true"
      focusable="false"
    >
      <rect className="fm-belt" x="0" y={UNIT_BELT_Y - 5} width="36" height="10" rx="2" />
      <path className="fm-belt-flow" d={`M3 ${UNIT_BELT_Y} H34`} />
      <path className="fm-belt-arrow" d={`M34 ${UNIT_BELT_Y - 9} L47 ${UNIT_BELT_Y} L34 ${UNIT_BELT_Y + 9} Z`} />
    </svg>
  )
}
