# 06. API 명세서 — FabWatch

> 버전 v1.0 / 2026-07-06
> Base: `/api/v1` · 인증: `Authorization: Bearer {accessToken}` (auth 제외 전부)
> 에러 공통: `{ "code": "ERROR_CODE", "message": "...", "timestamp": "..." }`
> 목록 공통: `?page=0&size=20&sort=...` → `{ content: [], totalElements, totalPages, number }`

## 1. 인증 /auth

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| POST | /auth/login | `{email, password}` → `{accessToken, refreshToken, user:{id,name,role}}` | 공개 |
| POST | /auth/refresh | `{refreshToken}` → 새 토큰 쌍 | 공개 |
| POST | /auth/logout | Refresh 무효화 | 로그인 |
| 에러 | | 401 `LOGIN_FAILED` / 429 `ACCOUNT_LOCKED` / 401 `TOKEN_EXPIRED` / 403 `USER_DISABLED` | |

## 2. 설비 /lines, /equipments

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | /lines | 라인+공정+설비 트리 전체 | 전체 |
| GET | /equipments | 목록 (filter: processId, status) | 전체 |
| POST | /equipments | 설비 등록 | ADMIN |
| GET | /equipments/{id} | 상세 (기본정보+센서+PM스케줄+미해결알람수) | 전체 |
| PUT | /equipments/{id} | 수정 | ADMIN |
| PATCH | /equipments/{id}/status | `{toStatus, reason}` 상태 전환. 400 `INVALID_STATUS_TRANSITION`. `DOWN→IDLE`·`DOWN→RUN`은 reason 필수(400 `VALIDATION_ERROR`). **권한: 기본 ENGINEER+, 단 `DOWN→IDLE`은 TECHNICIAN도 가능** (docs/03 F-2) | ENGINEER+ (DOWN→IDLE은 전체) |
| GET | /equipments/{id}/status-logs | 상태 변경 이력 | 전체 |
| GET | /equipments/{id}/kpi | `?period=DAY|WEEK|MONTH` → `{mtbfHours, mttrMin, availability, downCount}` | 전체 |

> 임계치 수정 API(`PUT .../sensors/{sensorId}/thresholds`)는 §3 센서 데이터로 이동(센서 소유 리소스라 sensor 도메인에 구현).
> `GET /equipments/{id}` 상세는 2주차 기준 기본정보만 반환 — 센서/PM스케줄/미해결알람수 통합은 아직 미완료(memory.md 참고).

## 3. 센서 데이터 /sensors

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /equipments/{id}/sensor-data/latest | 센서별 최신값 1건씩(카드용). `PageResponse` 래핑, 항목에 `warnLow/warnHigh/critLow/critHigh` 4값 동봉(기준선용) |
| GET | /equipments/{id}/sensor-data | `?sensorType=&from=&to=` — 1시간 이내: 원본, 초과: 1분 집계 자동 선택. **`PageResponse`가 아니라 단일 객체**: `{equipmentId, granularity:"RAW"|"1M", from, to, series:[{sensorId, sensorType, unit, warnLow~critHigh, points:[{at, value, minValue, maxValue, sampleCount}]}]}`. point shape은 RAW/1M 공통(1M일 때 `at=bucket_at, value=avg_v`, min/max/sampleCount 채워짐) |
| GET | /equipments/{id}/sensors | 설비 센서 목록(임계치 편집 화면 진입용). *(구현 시 추가, 최초 설계엔 없었음)* |
| PUT | /equipments/{id}/sensors/{sensorId}/thresholds | 임계치 수정 `{warnLow,warnHigh,critLow,critHigh,reason}`(reason 필수) → 400 `INVALID_THRESHOLD_RANGE`. ADMIN 전용(ENGINEER 403) |
| GET | /equipments/{id}/sensors/{sensorId}/thresholds/logs | 임계치 변경 이력 `{oldWarn*,oldCrit*,newWarn*,newCrit*,reason,changedBy,changedByName,changedAt}`. *(구현 시 추가)* |
| **SSE** | **GET /stream/sensors?token=&equipmentId=** | `text/event-stream`. 인증은 쿼리파라미터 `token`(SSE 한정, docs/11 §4). event: `sensor` `{sensorId, equipmentId, type, unit, value, measuredAt, level: NORMAL|WARNING|CRITICAL}`(`equipmentId`·`unit`은 카드 매칭/차트축용으로 추가) / event: `alarm` — REST `AlarmResponse`와 다른 shape, **식별자 키가 `id`가 아니라 `alarmId`**, ack/resolve 필드 없음 / event: `status` `{equipmentId, equipmentCode, fromStatus, toStatus, reason, changedBy, changedAt}`. equipmentId 생략 시 전체 라인 구독(메인 대시보드용). 30초 heartbeat(`:heartbeat` 주석) |

> 임계치 API는 설비가 아니라 센서 소유이므로 실제 구현은 `sensor` 도메인 패키지에 위치(equipment가 Sensor 엔티티를 직접 참조하지 않기 위함). 경로·권한은 위 표와 동일.

## 4. 점검 이력 /inspections

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | /inspections | filter: equipmentId, type, shift, workerId, hasNg, from, to | 전체 |
| POST | /inspections | 등록. BM은 cause4m 필수(400 `CAUSE_4M_REQUIRED`), PM은 checkResults 배열 포함 가능. alarmId 있으면 해당 알람 자동 RESOLVED | 전체 |
| GET | /inspections/{id} | 상세 (체크리스트 결과 포함) | 전체 |
| PUT | /inspections/{id} | 수정 (작성자 본인 or ENGINEER+) | 조건부 |
| PATCH | /inspections/{id}/review | 엔지니어 승인 | ENGINEER+ |
| GET | /equipments/{id}/checklist | PM 체크리스트 템플릿 | 전체 |
| POST/PUT | /equipments/{id}/checklist | 템플릿 관리 | ENGINEER+ |

POST /inspections 요청 예:
```json
{
  "equipmentId": 1, "type": "BM", "shift": "N",
  "startedAt": "2026-07-06T22:10:00Z", "endedAt": "2026-07-07T00:30:00Z",
  "content": "합착 롤러 진동 이상으로 정지", "actionTaken": "베어링 교체 후 시운전",
  "cause4m": "MACHINE", "causeDetail": "베어링 마모", "alarmId": 45
}
```

## 5. PM 스케줄 /pm-schedules

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | /pm-schedules | 전체 (filter: overdueOnly=true) | 전체 |
| PUT | /equipments/{id}/pm-schedule | `{cycleType, cycleValue}` 설정/변경 → nextDueAt 재계산 | ENGINEER+ |

## 6. 알람 /alarms

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | /alarms | filter: equipmentId, status, severity, from, to. 기본 OPEN 우선 정렬 | 전체 |
| PATCH | /alarms/{id}/ack | 확인 처리 (본인 기록) | 전체 |
| PATCH | /alarms/{id}/resolve | `{resolveNote}` 필수. ACK 상태에서만 가능 (400 `ACK_REQUIRED_FIRST`) | 전체 |
| POST | /alarms/manual | 수동 고장 보고 `{equipmentId, severity, message}` | 전체 |

## 7. AI 리포트 /ai-reports

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| POST | /ai-reports | `{alarmId? 또는 inspectionId?}` → 202 Accepted `{reportId, status:"GENERATING"}` (비동기 생성) | ENGINEER+ |
| GET | /ai-reports/{id} | 상세. 폴링용 (GENERATING→DRAFT/FAILED) | 전체 |
| GET | /ai-reports | 목록 (filter: equipmentId, status) | 전체 |
| PUT | /ai-reports/{id} | `{finalContent}` 수정 저장 (DRAFT만) | ENGINEER+ |
| PATCH | /ai-reports/{id}/confirm | 확정 | ENGINEER+ |
| POST | /ai-reports/{id}/retry | FAILED 재시도 | ENGINEER+ |
| POST | /ai-reports/shift-summary | `{date, shift}` 교대 인수인계 요약 (Should) | ENGINEER+ |
| 에러 | | 403 `AI_QUOTA_EXCEEDED` / 409 `ALREADY_GENERATING` | |

## 8. 시뮬레이터 /simulator (데모 제어)

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | /simulator/scenarios | 활성 시나리오 목록 | ENGINEER+ |
| POST | /simulator/scenarios | `{sensorId, type: DRIFT|SPIKE|STEP, param: {...}}` 주입 | ENGINEER+ |
| DELETE | /simulator/scenarios/{id} | 해제 (정상 복귀) | ENGINEER+ |
| POST | /simulator/demo | 데모 자동 시나리오 시작 (FR-4.5) | ENGINEER+ |

param 기본값: DRIFT `{durationMin: 10}` (10분에 crit 도달 기울기 자동 계산) / SPIKE `{probability: 0.1, multiplier: 1.8}` / STEP `{offsetRatio: 0.15}`

## 9. 관리 /admin

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET/POST | /admin/users | 사용자 목록/등록 | ADMIN |
| PATCH | /admin/users/{id}/enabled | 활성/비활성 | ADMIN |
| GET | /admin/ai-usage | AI 호출 로그·토큰 사용량 (NFR-4) | ADMIN |

## 10. 외부 API — Claude (백엔드 내부 호출)

- `POST https://api.anthropic.com/v1/messages`, 모델 `claude-sonnet-*`(비용 균형), `max_tokens: 2000`.
- 키는 환경변수 `ANTHROPIC_API_KEY`. **프론트에서 직접 호출 절대 금지.**
- 타임아웃 30s, 재시도 1회(5xx만). 사용량 `ai_reports.prompt_tokens/completion_tokens` 기록.
