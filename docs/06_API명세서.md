# 06. API 명세서 — FabWatch

> 버전 v1.0 / 2026-07-06
> Base: `/api/v1` · 인증: `Authorization: Bearer {accessToken}` (auth 제외 전부)
> 에러 공통: `{ "code": "ERROR_CODE", "message": "...", "timestamp": "..." }` — 클라이언트 실수는 500이 아니라 해당 4xx로 내려간다: 400 `VALIDATION_ERROR`(형식·필수값·파싱 실패), 401 `UNAUTHORIZED`, 403 `FORBIDDEN`, 404 `NOT_FOUND`, 405 `METHOD_NOT_ALLOWED`, 406 `NOT_ACCEPTABLE`, 415 `UNSUPPORTED_MEDIA_TYPE`. (참고: 입력 검증이 권한 검사보다 먼저 평가되어, 권한 없는 사용자가 잘못된 본문을 보내면 403이 아니라 400이 나올 수 있다 — 본문에는 공개된 DTO 제약 메시지만 담긴다.)
> 목록 공통: `?page=0&size=20&sort=...` → `{ content: [], totalElements, totalPages, number }`

## 1. 인증 /auth

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| POST | /auth/login | `{email, password}` → `{accessToken, refreshToken, user:{id,name,role}}` | 공개 |
| POST | /auth/refresh | `{refreshToken}` → 새 토큰 쌍 | 공개 |
| POST | /auth/logout | Refresh 무효화 | 로그인 |
| GET | /users/lookup | 활성 사용자 경량 조회(점검 이력 작업자 필터용). **단순 배열**(PageResponse 아님), 이름순 → `[{id,name,role}]`. 이메일·비밀번호·토큰·잠금 상태는 응답에 없음. 비활성(`enabled=false`)·soft delete 사용자 제외. 현재 규모에선 전체 반환(`// SCALE:`). 토큰 없음 401 | 로그인(전체 역할) |
| 에러 | | 401 `LOGIN_FAILED` / 429 `ACCOUNT_LOCKED` / 401 `TOKEN_EXPIRED` / 403 `USER_DISABLED` | |

## 2. 설비 /lines, /equipments

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | /lines | 라인+공정+설비 트리 전체 | 전체 |
| GET | /equipments | 목록 (filter: processId, status) | 전체 |
| POST | /equipments | 설비 등록 | ADMIN |
| GET | /equipments/{id} | 상세 (기본정보만). 센서·미해결 알람·다음 PM 예정은 서버가 합치지 않고 프론트가 기존 API(`/equipments/{id}/sensors`, `/alarms?equipmentId=&status=`, `/pm-schedules?equipmentId=`)를 조합한다 | 전체 |
| PUT | /equipments/{id} | 수정 | ADMIN |
| PATCH | /equipments/{id}/status | `{toStatus, reason}` 상태 전환. 400 `INVALID_STATUS_TRANSITION`. `DOWN→IDLE`·`DOWN→RUN`은 reason 필수(400 `VALIDATION_ERROR`). **권한: 기본 ENGINEER+, 단 `DOWN→IDLE`은 TECHNICIAN도 가능** (docs/03 F-2) | ENGINEER+ (DOWN→IDLE은 전체) |
| GET | /equipments/{id}/status-logs | 상태 변경 이력 | 전체 |
| GET | /equipments/{id}/kpi | `?period=DAY|WEEK|MONTH`(기본 DAY, 그 외 값 400 `VALIDATION_ERROR`, 없는 설비 404) → `{equipmentId, period, periodStart, periodEnd, mtbfHours, mttrMin, availability, downCount}`. periodStart/End는 UTC ISO(경계는 KST 00:00/월요일/1일). `mtbfHours`(시간, DOWN 진입 0회면 null)·`mttrMin`(분, 완료된 DOWN 0건이면 null)·`availability`(0~1 비율, 분모 0이면 null)는 **null 가능**(키는 항상 존재), `downCount`는 기간 내 DOWN 진입 횟수. 계산 규칙은 docs/03 F-5.4 | 전체 |
| GET | /equipments/kpi | `?period=&lineId=` → `{period, periodStart, periodEnd, summary:{mtbfHours, mttrMin, availability, downCount}, equipments:[{equipmentId, equipmentCode, equipmentName, mtbfHours, mttrMin, availability, downCount}]}`. `summary`는 **합산 후 재계산**(비율 평균 아님), 삭제된 설비 제외, 설비는 코드순. 없는 lineId 404. 리터럴 경로라 `/equipments/{id}`보다 우선 매칭 | 전체 |

> 임계치 수정 API(`PUT .../sensors/{sensorId}/thresholds`)는 §3 센서 데이터로 이동(센서 소유 리소스라 sensor 도메인에 구현).
> `GET /equipments/{id}` 상세는 기본정보만 반환한다. **센서/미해결 알람/다음 PM 예정은 프론트에서 기존 API를 조합한다(도메인 경계 원칙)** — equipment 도메인이 sensor·alarm·inspection에 직접 의존하면 "도메인 간 직접 참조 금지"(CLAUDE.md, docs/10 ADR-1) 위반이라 서버에서 합치지 않기로 결정했다. 조합 위치는 `EquipmentDetailPage`(pages).

## 3. 센서 데이터 /sensors

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /equipments/{id}/sensor-data/latest | 센서별 최신값 1건씩(카드용). `PageResponse` 래핑, 항목에 `warnLow/warnHigh/critLow/critHigh` 4값 동봉(기준선용) |
| GET | /equipments/{id}/sensor-data | `?sensorType=&from=&to=` — 1시간 이내: 원본, 초과: 1분 집계 자동 선택. **`PageResponse`가 아니라 단일 객체**: `{equipmentId, granularity:"RAW"|"1M", from, to, series:[{sensorId, sensorType, unit, warnLow~critHigh, points:[{at, value, minValue, maxValue, sampleCount}]}]}`. point shape은 RAW/1M 공통(1M일 때 `at=bucket_at, value=avg_v`, min/max/sampleCount 채워짐) |
| GET | /equipments/{id}/sensors | 설비 센서 목록(임계치 편집 화면 진입용). *(구현 시 추가, 최초 설계엔 없었음)* |
| PUT | /equipments/{id}/sensors/{sensorId}/thresholds | 임계치 수정 `{warnLow,warnHigh,critLow,critHigh,reason}`(reason 필수·trim·300자). 4값은 각각 null 가능(해당 방향 미사용)하나 **전부 null이거나 키를 빠뜨리면 400 `INVALID_THRESHOLD_RANGE`**(센서 감시가 꺼지므로). 순서 위반도 `INVALID_THRESHOLD_RANGE`, 소수 3자리 이상·정수 8자리 초과는 400 `VALIDATION_ERROR`(DB decimal(10,2)). ADMIN 전용(ENGINEER 403), 변경 이력은 누가·언제·왜·이전→이후로 보존 |
| GET | /equipments/{id}/sensors/{sensorId}/thresholds/logs | 임계치 변경 이력 `{oldWarn*,oldCrit*,newWarn*,newCrit*,reason,changedBy,changedByName,changedAt}`. *(구현 시 추가)* |
| **SSE** | **GET /stream/sensors?token=&equipmentId=** | `text/event-stream`. 인증은 쿼리파라미터 `token`(SSE 한정, docs/11 §4). event: `sensor` `{sensorId, equipmentId, type, unit, value, measuredAt, level: NORMAL|WARNING|CRITICAL}`(`equipmentId`·`unit`은 카드 매칭/차트축용으로 추가) / event: `alarm` — REST `AlarmResponse`와 다른 shape, **식별자 키가 `id`가 아니라 `alarmId`**, ack/resolve 필드 없음 / event: `status` `{equipmentId, equipmentCode, fromStatus, toStatus, reason, changedBy, changedAt}`. equipmentId 생략 시 전체 라인 구독(메인 대시보드용). 30초 heartbeat(`:heartbeat` 주석) |

> 임계치 API는 설비가 아니라 센서 소유이므로 실제 구현은 `sensor` 도메인 패키지에 위치(equipment가 Sensor 엔티티를 직접 참조하지 않기 위함). 경로·권한은 위 표와 동일.

## 4. 점검 이력 /inspections

> 구현 완료(3주차). 시각은 전부 ISO-8601 UTC 문자열. 목록은 `{content,totalElements,totalPages,number}`, 정렬은 `startedAt desc` 고정(sort 파라미터 무시), `size` 기본 20.

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | /inspections | filter: equipmentId, type(PM/BM), shift(D/N), workerId, hasNg, from, to(`startedAt` 기준 from 이상 / to 미만), page, size → `PageResponse<InspectionResponse>` | 전체 |
| POST | /inspections | 등록 → **201** `InspectionDetailResponse`. workerId는 받지 않음(로그인 사용자) | 전체 |
| GET | /inspections/{id} | 상세 `InspectionDetailResponse` (404 `NOT_FOUND`) | 전체 |
| PUT | /inspections/{id} | 수정(작성자 본인 or ENGINEER+, 아니면 403 `FORBIDDEN`) → `InspectionDetailResponse` | 조건부 |
| PATCH | /inspections/{id}/review | 엔지니어 승인. 이미 승인된 건은 멱등(기존 승인자·시각 그대로 반환) → `InspectionDetailResponse` | ENGINEER+ |
| GET | /equipments/{id}/checklist | PM 체크리스트 템플릿. **단순 배열**(PageResponse 아님), active=true만, seq 순 → `[{id,itemName,criteria,seq,active}]` | 전체 |
| POST | /equipments/{id}/checklist | `{itemName, criteria?, seq?}` → 201 단건. seq 생략 시 맨 뒤(최대 seq+1) | ENGINEER+ |
| PUT | /equipments/{id}/checklist/{itemId} | `{itemName, criteria, seq, active}` → 단건. **삭제는 `active=false`**(물리 삭제 없음). seq가 null이면 순서 유지 | ENGINEER+ |

**InspectionResponse (목록 행)**
```json
{
  "id": 1, "equipmentId": 1, "equipmentCode": "LAMI-01", "equipmentName": "합착기 1호",
  "type": "PM|BM", "shift": "D|N", "workerId": 3, "workerName": "이테크니션",
  "startedAt": "2026-07-06T13:00:00Z", "endedAt": "2026-07-06T14:30:00Z", "durationMin": 90,
  "content": "...", "actionTaken": "...|null",
  "cause4m": "MAN|MACHINE|MATERIAL|METHOD|null", "causeDetail": "...|null",
  "alarmId": 45, "hasNg": false,
  "reviewedBy": 2, "reviewedByName": "박엔지니어", "reviewedAt": "...", "createdAt": "..."
}
```
**InspectionDetailResponse** = InspectionResponse 전 필드 + `checkResults: [{checklistItemId, itemName, criteria, result:"OK|NG|NA", note}]` (결과 없으면 빈 배열).

**POST /inspections 요청**
```json
{
  "equipmentId": 1, "type": "BM", "shift": "N",
  "startedAt": "2026-07-06T22:10:00Z", "endedAt": "2026-07-07T00:30:00Z",
  "content": "합착 롤러 진동 이상으로 정지", "actionTaken": "베어링 교체 후 시운전",
  "cause4m": "MACHINE", "causeDetail": "베어링 마모", "alarmId": 45,
  "checkResults": [{ "checklistItemId": 1, "result": "OK", "note": null }]
}
```
필수: equipmentId, type, startedAt, endedAt, content. 나머지 선택.

**등록/수정 규칙 (에러는 공통 포맷 `{code,message,timestamp}`)**
- `endedAt > startedAt` 아니면 400 `VALIDATION_ERROR`. 미래 시각 불가(종료 시각이 현재+1분 초과) 400 `VALIDATION_ERROR`. `durationMin`은 자동 계산(분, 내림). 24시간 초과는 허용(서버 에러 아님, 프론트 경고).
- `shift` 생략 시 `startedAt`(KST)으로 자동 판정(D=08~20, N=20~08). PUT에서 shift 생략 시 startedAt이 바뀌었으면 재판정, 아니면 기존 값 유지.
- BM은 `cause4m` 필수 → 400 `CAUSE_4M_REQUIRED`. PM의 cause4m/causeDetail은 무시(null 저장).
- `checkResults`는 **PM에서만** 허용(BM에 넣으면 400), 항목 중복 불가, 해당 설비의 템플릿 항목이어야 함(아니면 400). NG가 1건이라도 있으면 `hasNg=true`.
- `alarmId`는 **BM에서만** 허용(PM이면 400). 없는 알람 404 `NOT_FOUND`, **다른 설비 알람이면 400 `VALIDATION_ERROR`**. 연계 알람 처리: OPEN이면 작성자 명의로 자동 ACK 후 RESOLVED, ACK면 RESOLVED, 이미 RESOLVED면 그대로(에러 없음). `resolveNote`는 `BM 점검 이력 #{id}로 조치 완료`. 점검 저장과 알람 해제는 한 트랜잭션.
- **설비 상태는 자동 변경하지 않는다.** BM 등록 후 DOWN→IDLE은 프론트가 확인 다이얼로그 후 `PATCH /equipments/{id}/status` 호출.
- PM 등록 시 해당 설비 PM 스케줄의 `last_done_at`=종료 시각, `next_due_at` 재계산, `overdue_alarm_sent=false` 초기화. (기존 last_done보다 이전 시각의 소급 등록은 스케줄을 되돌리지 않음. 스케줄 없는 설비는 건너뜀.)
- PM 등록으로 스케줄이 실제 갱신된 경우, 해당 설비의 미해결(OPEN/ACK) `PM_OVERDUE` 알람은 같은 트랜잭션에서 작성자 명의로 자동 ACK→RESOLVED 된다(`resolveNote`=`PM 점검 이력 #{id}로 수행 완료`, 알람이 없거나 이미 RESOLVED면 아무 일도 없음, 소급 등록·스케줄 없는 설비는 해소하지 않음). BM·타 설비 알람은 영향 없음.
- PUT: 수정 가능 필드는 `shift?, startedAt, endedAt, content, actionTaken, cause4m, causeDetail, checkResults?`. 설비·유형·작성자·알람 연계는 불변. `checkResults`가 null이면 기존 결과 유지, 배열이면(빈 배열 포함) 기존 결과를 soft delete 후 교체하고 hasNg 재계산. 판정 순서: 404 → 403 → 400.
- 에러 코드: 400 `VALIDATION_ERROR` / 400 `CAUSE_4M_REQUIRED` / 403 `FORBIDDEN` / 404 `NOT_FOUND`.

## 5. PM 스케줄 /pm-schedules

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | /pm-schedules | `?overdueOnly=true\|false(기본)&equipmentId=` → **단순 배열**(PageResponse 아님) `[PmScheduleResponse]`, `nextDueAt` 빠른 순 | 전체 |
| PUT | /equipments/{id}/pm-schedule | `{cycleType, cycleValue}` 설정/변경(없으면 생성) → 200 `PmScheduleResponse`. nextDueAt 재계산(기준: lastDoneAt, 없으면 현재), overdue 알람 플래그 초기화 | ENGINEER+ |

```json
PmScheduleResponse = {
  "id": 1, "equipmentId": 2, "equipmentCode": "LAMI-02", "equipmentName": "합착기 2호",
  "cycleType": "DAILY|WEEKLY|MONTHLY", "cycleValue": 4,
  "lastDoneAt": "...|null", "nextDueAt": "...",
  "overdue": true, "overdueDays": 2
}
```
- `overdue` = 조회 시각 > nextDueAt. `overdueDays` = 경과 24시간 단위 내림(경과 전이면 0; 지연 직후 5시간이면 overdue=true, overdueDays=0).
- `cycleValue` 검증(위반 400 `VALIDATION_ERROR`): WEEKLY 1~7(1=월 … 7=일), MONTHLY 1~28, DAILY는 null.
- **next_due_at 계산(KST 날짜, last_done의 KST 시각 유지)**: DAILY=+1일 / WEEKLY=수행일 이후 첫 해당 요일(같은 요일이면 다음 주) / MONTHLY=수행일 이후 첫 해당 일자(그 달 일자가 남았으면 이번 달, 아니면 다음 달).
- **PM 지연 알람**: 매시 :10(UTC) 스케줄러가 `next_due_at + 3일 < now` & `overdue_alarm_sent=false`인 스케줄에 `PmOverdueEvent`를 발행 → alarm 도메인이 MAJOR/`PM_OVERDUE` 알람 생성(메시지 `{설비코드} PM 예정일 경과 ({n}일)`, 설비당 미해결 PM_OVERDUE 1건이면 중복 생성 안 함) → `overdue_alarm_sent=true`. PM 이력 등록 시 플래그 리셋.

## 6. 알람 /alarms

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | /alarms | filter: equipmentId, status, severity, from, to. 기본 OPEN 우선 정렬 | 전체 |
| PATCH | /alarms/{id}/ack | 확인 처리 (본인 기록) | 전체 |
| PATCH | /alarms/{id}/resolve | `{resolveNote}` 필수. ACK 상태에서만 가능 (400 `ACK_REQUIRED_FIRST`) | 전체 |
| POST | /alarms/manual | 수동 고장 보고 `{equipmentId, severity, message}` | 전체 |

## 7. AI 리포트 /ai-reports

> 2026-10-03 실제 구현 shape으로 갱신 (F-6). 시각은 ISO-8601 UTC, 목록은 공통 `PageResponse {content,totalElements,totalPages,number}`(page/size, 기본 20, 최신순 고정).

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| POST | /ai-reports | body `{alarmId?, inspectionId?}` → **202** `{reportId, status:"GENERATING"}` (비동기 생성) | ENGINEER+ (ADMIN 포함) |
| GET | /ai-reports/{id} | 상세 `AiReportResponse`. 폴링용 (GENERATING→DRAFT/FAILED) | 전체 |
| GET | /ai-reports | 목록 `PageResponse<AiReportSummary>` (filter: equipmentId, status, alarmId, inspectionId, page, size) | 전체 |
| PUT | /ai-reports/{id} | `{finalContent}` 수정 저장 — **DRAFT 또는 FAILED**(수동 작성)만 | ENGINEER+ |
| PATCH | /ai-reports/{id}/confirm | 확정 → CONFIRMED | ENGINEER+ |
| POST | /ai-reports/{id}/retry | FAILED 재시도 → **202** `{reportId, status:"GENERATING"}` (같은 reportId 재사용) | ENGINEER+ |
| POST | /ai-reports/shift-summary | `{date, shift}` 교대 인수인계 요약 (Should, 미구현) | ENGINEER+ |

**POST 규칙**
- alarmId·inspectionId 둘 다 없으면 400 `VALIDATION_ERROR`, 존재하지 않으면 404 `NOT_FOUND`.
- 둘 다 있으면 둘 다 저장하되 서로 다른 설비면 400 `VALIDATION_ERROR`.
- 점검만 지정하면 그 점검의 설비 기준으로 컨텍스트를 수집하고, 점검에 연계된 알람(있으면)도 컨텍스트에 포함한다(점검만 지정했는데 그 점검에 연계 알람이 있으면 컬럼 alarm_id에도 그 알람 id를 저장한다 — `?alarmId=` 필터와 409 `ALREADY_GENERATING` 판정이 알람 경로·점검 경로 양쪽에서 같은 사건으로 일치하도록. 요청에 alarmId가 있으면 요청값 우선).
- `title`은 서버가 생성: `고장 리포트 — {설비코드} {발생일시 KST yyyy-MM-dd HH:mm}` (발생일시 = 알람 발생 시각, 알람이 없으면 점검 시작 시각).
- 같은 알람/점검에 GENERATING 리포트가 이미 있으면 409 `ALREADY_GENERATING`. 오늘(KST) 호출 시도 수가 `AI_DAILY_QUOTA` 이상이면 403 `AI_QUOTA_EXCEEDED` (생성·재시도 공통, mock 호출도 카운트).

**AiReportResponse** (GET /{id}, PUT, PATCH confirm)
```json
{ "id":1, "equipmentId":2, "equipmentCode":"LAMI-01", "equipmentName":"합착기 1호",
  "alarmId":10, "inspectionId":null, "title":"고장 리포트 — LAMI-01 2026-10-02 23:03",
  "status":"GENERATING|DRAFT|CONFIRMED|FAILED",
  "draftContent":"…AI 원본 마크다운…", "finalContent":"…편집/확정본…", "failReason":null,
  "model":"claude-sonnet-5-5", "promptTokens":1200, "completionTokens":650,
  "createdBy":3, "createdByName":"…", "confirmedBy":null, "confirmedByName":null,
  "createdAt":"2026-10-02T14:05:00Z", "confirmedAt":null }
```
- GENERATING 중에는 `draftContent`/`finalContent`가 null. mock provider면 `model:"mock"`, 토큰 0, 본문 맨 위에 `> [MOCK] 실제 AI가 생성한 리포트가 아닙니다`.
- `draftContent`(AI 원본)는 어떤 API로도 수정되지 않는다. 편집은 `finalContent`만.

**AiReportSummary** (목록 행, 본문 제외)
`{ id, equipmentId, equipmentCode, equipmentName, alarmId, inspectionId, title, status, createdByName, createdAt, confirmedAt }`

**상태 전이 규칙**
| 동작 | GENERATING | DRAFT | FAILED | CONFIRMED |
|---|---|---|---|---|
| PUT (finalContent) | 409 | 200 | 200 (수동 작성) | 409 |
| PATCH confirm | 409 | 200 (final이 비면 draft를 final로 복사) | 200 (final 있어야 함, 없으면 400 `VALIDATION_ERROR`) | 409 |
| POST retry | 409 | 409 | 202 | 409 |

위 409는 전부 `INVALID_REPORT_STATE`. CONFIRMED 이후에는 수정·재확정·재시도 불가. PUT의 `finalContent`가 공백이면 400 `VALIDATION_ERROR`.

**에러 코드**: 403 `AI_QUOTA_EXCEEDED` / 409 `ALREADY_GENERATING` / 409 `INVALID_REPORT_STATE` / 400 `VALIDATION_ERROR` / 404 `NOT_FOUND` / 403 `FORBIDDEN`(TECHNICIAN의 POST·PUT·PATCH·retry)

## 8. 시뮬레이터 /simulator (데모 제어)

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | /simulator/scenarios | 활성 시나리오 목록 | ENGINEER+ |
| POST | /simulator/scenarios | `{sensorId, type: DRIFT|SPIKE|STEP, param: {...}}` 주입 | ENGINEER+ |
| DELETE | /simulator/scenarios/{id} | 해제 (정상 복귀) | ENGINEER+ |
| POST | /simulator/demo | 데모 자동 시나리오 시작 (FR-4.5). 진동 센서에 DRIFT를 주입하며 durationMin은 설정 `fabwatch.simulator.demo-duration-min`(기본 **2분**, 환경변수 `SIMULATOR_DEMO_DURATION_MIN`) — 약 2~3분 안에 정상 → 드리프트 → WARNING → CRITICAL → 자동 DOWN. 이미 주입돼 있으면 그대로 두고 활성 목록 반환 | ENGINEER+ |

param 기본값: DRIFT `{durationMin: 10}` (durationMin분에 crit 도달하는 기울기 자동 계산, 응답 param에 `slopePerSec`·`maxElapsedSec`·`targetValue` 포함. **상한 도달 후엔 목표값에서 고정**되어 해제하지 않아도 무한 상승하지 않음) / SPIKE `{probability: 0.1, multiplier: 1.8}` / STEP `{offsetRatio: 0.15}`

## 9. 관리 /admin

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET/POST | /admin/users | 사용자 목록/등록 | ADMIN |
| PATCH | /admin/users/{id}/enabled | 활성/비활성 | ADMIN |
| GET | /admin/ai-usage | AI 호출 로그·토큰 사용량 (NFR-4) | ADMIN |

## 10. 외부 API — Claude (백엔드 내부 호출)

- `POST https://api.anthropic.com/v1/messages` (헤더 `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`), body `{model, max_tokens, output_config:{effort}, system, messages:[{role:"user", content}]}`. `thinking` 필드는 보내지 않는다(생략=adaptive thinking, `effort: "low"`로 사고 토큰 최소화). `temperature`/`top_p`/`top_k`는 보내지 않는다(비기본값은 400). 응답의 `text` 블록을 이어붙이고 `usage.input_tokens/output_tokens`를 기록한다. 모델 기본값 `claude-sonnet-5-5`(`AI_MODEL`), `max_tokens: 4096`(`AI_MAX_TOKENS`, 사고 토큰 포함 상한), `effort: low`(`AI_EFFORT`, low/medium/high).
- 키는 환경변수 `ANTHROPIC_API_KEY`. **프론트에서 직접 호출 절대 금지.** 키는 로그·에러 메시지·응답·DB(fail_reason)에 노출하지 않는다.
- `AI_PROVIDER=claude|mock`(기본 claude). 키가 비어 있는 claude는 호출 없이 FAILED(`ANTHROPIC_API_KEY가 설정되지 않았습니다`) — 키가 없다고 자동 mock으로 넘어가지 않는다. mock은 명시 설정했을 때만 동작.
- 읽기 타임아웃 60s(`AI_TIMEOUT_SECONDS`, connect 10s). 타임아웃/5xx는 1회 재시도, 429는 재시도 없이 FAILED(사유 "잠시 후 다시 시도해 주세요"), 그 외 4xx(401/400 등)도 재시도 없이 FAILED(정제된 사유). 사용량은 `ai_reports.prompt_tokens/completion_tokens` + `ai_call_logs`에 기록. 본문이 비거나 잘린 경우는 고정 코드로 구분한다: `max_tokens`로 text가 비면 FAILED `[MAX_TOKENS_TRUNCATED] ...`, 그 외 빈 본문은 FAILED `[EMPTY_CONTENT] ...`(fail_reason·`ai_call_logs.error_summary` 동일), 본문이 일부 있고 `max_tokens`로 잘렸으면 DRAFT(잘림 안내 문구 부착) + `ai_call_logs.error_summary="[MAX_TOKENS_TRUNCATED] ..."`(success=true).
- API 키는 앞뒤 공백을 제거하고 내부에 개행·공백·제어문자가 있으면 "미설정/유효하지 않음"으로 취급해 호출하지 않는다(`NOT_CONFIGURED`). 호출 중 예외는 키·원문 없는 고정 문구로만 변환하고, 생성 워커의 서버 로그에도 예외 객체/메시지 대신 클래스명+reportId만 남긴다.
- 구현 위치: `com.fabwatch.aireport` (`ClaudeClient` 인터페이스 + `AnthropicHttpClaudeClient`(Spring RestClient)). 별도 SDK 의존성 없음.
