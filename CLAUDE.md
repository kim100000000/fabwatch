# CLAUDE.md — FabWatch 작업 지침

MES 설비관리 포트폴리오 프로젝트(FabWatch). Spring Boot 3.x(Java 17) + React 18(TS) + MySQL 8.

## 세션 시작 규칙 (필수)

1. **`memory.md`를 가장 먼저 읽는다.** 현재 상태·확정 결정·다음 작업이 전부 거기 있다.
2. 작업 범위에 해당하는 명세만 추가로 읽는다 (전체 문서를 다 읽지 않는다):
   - 기능 개발 → `docs/03_기능명세서.md` 해당 섹션 + `docs/05_DB설계서_ERD.md` 관련 테이블 + `docs/06_API명세서.md` 관련 API
   - UI 작업 → `docs/04_화면설계서.md` 공통 토큰 + 해당 화면
3. **작업 종료 시 반드시 `memory.md` 갱신**: 진행 상태 체크, 새 결정 기록, "다음 작업 지시" 갱신, 작업 로그 1줄 추가. `docs/09_체크리스트.md`의 해당 항목도 체크.

## 프로젝트 구조

```
memory.md        ← 상태 파일 (항상 최신 유지)
CLAUDE.md        ← 본 파일
docs/00~10       ← 명세서 (00 README에 문서 지도, 10 아키텍처 결정 기록)
backend/         ← Spring Boot 모듈러 모놀리스
frontend/        ← Vite + React 18 + TS (FSD 라이트)
```

### 아키텍처: 모듈러 모놀리스 (MSA 아님 — 근거는 docs/10 ADR-1)

**백엔드 — 도메인 우선 패키지** (레이어 우선 금지):
```
com.fabwatch.
├── common/        # config(Security·CORS·Scheduler), exception(GlobalHandler·ErrorCode), util(교대판정 등)
├── auth/          # 각 도메인 내부에 controller/service/repository/entity/dto
├── equipment/     # 설비·라인·공정·상태머신·임계치
├── sensor/        # 센서 데이터·1분 집계·SSE 브로드캐스터
├── inspection/    # 점검 이력·체크리스트·PM 스케줄
├── alarm/
├── aireport/      # Claude 클라이언트 포함 (외부 호출 격리)
└── simulator/     # SensorDataSource 인터페이스 뒤에 구현 ★서비스 분리 1순위
```
- **도메인 간 직접 참조 금지**: 타 도메인의 repository/entity 직접 호출 금지. service 인터페이스 또는 스프링 이벤트로만 통신 (예: 임계치 초과 → ThresholdExceededEvent → alarm 모듈 수신).

**프론트 — FSD 라이트** (컴포넌트 종류별 분류 금지):
```
src/
├── app/           # 라우터, 전역 프로바이더, 레이아웃
├── pages/         # 라우트 단위 조립 (S-0~S-8)
├── features/      # equipment, sensor, inspection, alarm, aireport (백엔드 도메인과 1:1)
└── shared/        # api(axios·토큰 인터셉터), ui(공통 컴포넌트), hooks, lib
```

## 코딩 컨벤션

- 주석·커밋 메시지 한국어. 커밋 형식: `feat: 점검 이력 등록 API 구현`
- 에러 응답 통일: `{ "code": "ERROR_CODE", "message": "...", "timestamp": "..." }` (docs/03 F-7)
- 삭제는 soft delete(deleted_at). 물리 삭제 금지.
- 시각: DB는 UTC, 화면 KST. 교대 판정 D=08~20시 / N=20~08시 (KST).
- 멀티테넌트 전환 지점에 `// TENANT:` 주석 (users, lines, equipments 계열).
- 역할명: `ADMIN` / `ENGINEER` / `TECHNICIAN` — **TECHNICIAN을 MAINTENANCE로 바꾸지 않는다** (역할=사람, Maintenance=업무 용어. 화면 메뉴에서만 "Maintenance(설비보전)" 사용 가능).
- 설비 상태 전환은 docs/03 F-2 상태 머신 규칙 밖의 전환을 절대 허용하지 않는다.

## 보안·데이터 규칙 (위반 금지)

- API 키·비밀번호는 환경변수/.env만. Git 커밋 금지 (.gitignore 확인 후 커밋).
- Claude API는 백엔드 경유만. 프론트에서 직접 호출 금지.
- **실제 LG디스플레이 공정 수치·알람 코드·레시피 사용 금지.** 시드는 전부 가상값 (docs/08 B-3 영업비밀 원칙).

## 우선순위 원칙

- docs/02의 Must(M) 항목이 최우선. Should(S)는 3주차 이후 여유 시에만. 일정 충돌 시 docs/07 버퍼 규칙 적용.
- 막히면 우회 구현으로 진행을 유지한다 (예: SSE 막히면 3초 폴링 폴백 후 기록).
- 핵심 도메인 로직(임계치 판정, PM 일정 계산, KPI 계산, 상태 머신)은 단위 테스트 필수.

## 자주 쓰는 명령 (셋업 후 갱신)

```bash
# backend
cd backend && ./gradlew bootRun        # 실행 (로컬 MySQL: docker compose up -d)
cd backend && ./gradlew test           # 테스트
# frontend
cd frontend && npm run dev             # 개발 서버
cd frontend && npm run build           # 빌드 (tsc 포함)
```

## 하네스: FabWatch 개발

**목표:** 기능 하나를 스펙 확정→백엔드/프론트엔드 병렬 구현→경계면 QA→memory.md 갱신까지 팀으로 자동화.

**트리거:** 기능 구현/셋업/수정 요청 시 `fabwatch-feature-builder` 스킬을 사용하라 (에이전트: backend-developer, frontend-developer, mes-qa). 단순 질문은 직접 응답 가능.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-08-07 | 초기 구성 (3-agent team + 오케스트레이터) | 전체 | 설비관리 모듈 개발 착수 준비. 풀 MES 확장은 보류(추후 별도 확장) |
