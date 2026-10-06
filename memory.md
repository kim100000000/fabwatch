# memory.md — FabWatch 작업 상태 파일

> **새 대화를 시작하면 이 파일부터 읽는다.** 여기에 현재 상태·결정 사항·다음 할 일이 전부 있다.
> **모든 작업 종료 시 이 파일을 갱신한다** (완료 항목 체크, 결정 추가, "다음 할 일" 갱신, 하단 로그 1줄 추가).

---

## 1. 프로젝트 개요 (불변)

- **프로젝트**: FabWatch — 설비 점검 이력 관리 + 센서 실시간 대시보드 + AI 고장 리포트 (MES 설비관리 모듈)
- **목적**: ① 취업 포트폴리오 (제조IT/스마트팩토리/MES 직무) ② 수익화는 설계만 (docs/08)
- **차별점**: LG디스플레이 생산 테크니션 3년 현장 경험을 기능에 반영 (PM/BM, 4M, 교대조, 인터락, MTBF/MTTR, 알람 중복 억제 등)
- **사용자**: 김성태 (kst980510@gmail.com)

## 2. 확정된 기술 결정 (변경 시 사유와 함께 여기 갱신)

| 항목 | 결정 | 비고 |
|---|---|---|
| 백엔드 | Spring Boot 3.x, Java 17, JPA, MySQL 8 | 국내 제조IT 채용 주류 스택 |
| 프론트 | React 18 + TypeScript + Vite, Recharts | |
| 실시간 | SSE (WebSocket 아님) | 단방향 푸시라 SSE로 충분. 막히면 3초 폴링 폴백 |
| 센서 데이터 | 시뮬레이터 직접 제작 (외부 데이터셋 X) | 백엔드 내장 @Scheduled 2초 주기. DRIFT/SPIKE/STEP 시나리오 |
| AI | Claude API (백엔드 경유만, 키는 env). 모델 `claude-sonnet-5-5`, `AI_PROVIDER=claude|mock`, effort low, 타임아웃 60초 | 리포트 max_tokens 4096(2000→상향: Sonnet 5.5는 thinking 기본 ON이라 사고 토큰이 max_tokens를 먹음), 일 20건 쿼터(ai_call_logs, KST 일자 전체 합산) |
| 인증 | JWT Access 30분 + Refresh 14일(DB 저장) | 역할: ADMIN/ENGINEER/TECHNICIAN |
| 배포 | Railway(백+DB) + Vercel(프론트) | 4주차 |
| 데이터 정책 | 센서 원본 7일 + 1분 집계 영구, 나머지 soft delete | |
| 수익화 대비 | tenant_id 추가 지점에 `// TENANT:` 주석, 센서 없이도 점검 기능 독립 동작 | 실구현은 안 함 |
| 시각 | DB는 UTC, 화면 KST. 교대 D=08~20시, N=20~08시 | |
| 일정 | 1개월 집중 (2026-07-06 ~ 08-02) | 주차별 계획은 docs/07 |
| 역할 명칭 | TECHNICIAN 유지 (MAINTENANCE로 변경 안 함) | 역할=사람(테크니션), Maintenance=업무 용어로만 사용 (메뉴/이력서) |
| 아키텍처 | 모듈러 모놀리스 (MSA 안 함) | 도메인 우선 패키지 + 도메인 간 이벤트 통신. 분리 1순위: simulator, aireport. 근거·면접답변 docs/10 |
| 폴더 구조 | 백엔드: 도메인별 수직 분할 / 프론트: FSD 라이트(app·pages·features·shared) | 상세는 CLAUDE.md 프로젝트 구조 섹션 |
| 테스트 DB | H2(MySQL 모드) 인메모리 | Docker 없어도 `./gradlew test`로 전체 검증 가능. 로컬 실행은 여전히 docker-compose MySQL8 |
| refresh_token 저장 | docs/05 `users.refresh_token` 컬럼 방식 채택 (docs/11이 언급한 별도 refresh_tokens 테이블 대신) + `refresh_token_expires_at`/`failed_login_count`/`locked_until` 3컬럼 추가 | docs/11 §2(14일 만료·5회/15분 잠금) 저장할 자리가 docs/05에 없어서 보완 |
| `lines` 테이블명 | 백틱 인용 매핑 | MySQL 예약어 충돌 |
| 도메인 간 FK | JPA 연관관계 대신 Long ID만 보관, 이름 조회는 서비스 인터페이스로 | "도메인 간 직접 참조 금지" 원칙을 FK 레벨까지 적용 |

## 3. 문서 지도

```
memory.md            ← 본 파일 (항상 최신)
CLAUDE.md            ← Claude 작업 지침 (세션 규칙, 컨벤션, 금지사항)
docs/00_README_문서활용가이드.md   ← AI 협업 원칙, 프롬프트 템플릿
docs/01_서비스기획서.md            ← 차별점(현장경험→기능 매핑 표), 시뮬레이터 컨셉
docs/02_요구사항명세서.md          ← FR/NFR 전체 (Must/Should/Could)
docs/03_기능명세서.md              ← 기능별 규칙·예외 ★개발 시 해당 섹션 투입
docs/04_화면설계서.md              ← 디자인 토큰(다크 산업용), 화면 8종
docs/05_DB설계서_ERD.md            ← 테이블 16개(2주차에 sensor_threshold_logs 추가), 시드 데이터 정의
docs/06_API명세서.md               ← REST + SSE 전체 엔드포인트
docs/07_개발로드맵.md              ← 4주 계획 + 버퍼 규칙 + 취업 병행 일정
docs/08_수익화전략.md              ← SaaS/템플릿/콘텐츠 3트랙 (실행은 취업 후)
docs/09_체크리스트.md              ← 완성형 체크리스트 ★작업 완료 시마다 체크
docs/10_아키텍처결정기록_면접대비.md ← ADR 10건 + 면접 답변집 (새 결정 시 ADR 추가)
docs/11_보안명세서.md              ← 인증/인가/OWASP/시크릿/AI 보안
docs/12_테스트계획서.md            ← 단위/통합/E2E 전략, 4대 핵심 도메인 테스트 케이스
docs/13_배포운영명세서.md          ← Railway/Vercel 배포, 환경변수, 스케줄러, 장애 대응
docs/14_용어사전.md                ← MES 도메인·시스템 용어, 에러코드 색인 (용어 단일 출처)
docs/15_성능부하명세서.md          ← SLO, 병목 예측, 인덱스/다운샘플링, SSE 성능
```

## 4. 진행 상태

### 완료
- [x] 2026-07-06: 기획·명세 문서 00~08 전체 작성 완료 (v1.0)
- [x] 2026-07-06: CLAUDE.md + docs/09_체크리스트.md 작성, 역할 명칭 TECHNICIAN 확정
- [x] 2026-07-06: 아키텍처 확정(모듈러 모놀리스) + 폴더 구조 확정 + docs/10 ADR 작성
- [x] 2026-07-07: docs/11 보안명세서 작성 + docs/12~15 신규 명세서 4종 작성 (테스트·배포운영·용어사전·성능부하)
- [x] 2026-08-07: 비판적 리스크 검토 후 docs 5종 수정 (01/03/07/08/11) + 하네스(.claude/agents,skills) 구성
- [x] 2026-08-07: **1주차 셋업 완료** — git init, docker-compose(MySQL8), .gitignore, .env.example
- [x] 2026-08-07: **백엔드** Spring Boot 프로젝트 골격 + JPA 엔티티 15개(도메인별 배치, 미구현 도메인은 common/entity에 임시 배치) + 시드(CommandLineRunner) + F-1 인증(JWT 로그인/리프레시/로그아웃, 5회 잠금) + F-2 설비마스터(라인/공정/설비 CRUD, 상태머신 4x4 전이, 상태로그) — `./gradlew build/test` 49개 전부 통과
- [x] 2026-08-07: **프론트엔드** Vite+React18+TS, FSD라이트(app/pages/features{auth,equipment}/shared), 8개 라우트(S-0~S-8, 실구현은 S-0/S-2/S-3+레이아웃, 나머지 스텁), axios 인터셉터(401→refresh 자동), 디자인 토큰 전량 적용 — `npm run build` 통과
- [x] 2026-08-07: **QA 1라운드** — 경계면 검증 21항목 통과, 5건 발견·수정(`/lines` 페이지래핑 unwrap 안 하면 헤더 크래시였음, refresh user 필드 미사용, logout 불필요 바디, 목록 DTO modelName/maker 누락, 에러코드 매핑 불일치). 재검증 후 백엔드 49/프론트 build 전부 그린. 리포트: `.claude/_workspace/qa/f1-f2-인증설비마스터_20260807.md`

### 1주차 알려진 미완료 (2주차에 정리됨)
- [x] 2026-08-09: S-2 설비 등록/수정 폼 연결 완료 (EquipmentFormDialog, ADMIN 전용, code/status는 읽기전용)
- [x] 2026-08-09: S-3 "상태 변경" 버튼 연결 완료 (EquipmentStatusDialog, ADMIN·ENGINEER, 허용 전이만 후보 노출) + 상태 이력 탭 실구현(GET status-logs 소비) — **주간 게이트 UI로 완주 가능해짐**
- [x] 2026-08-10: 임계치 설정 API(FR-2.3) 백엔드 구현 완료 — 프론트 편집 화면은 아래 "2주차 알려진 미완료" 참고

### 2주차 완료 — 2026-08-10
- [x] **백엔드**: simulator(SensorDataSource 인터페이스+구현체, DRIFT/SPIKE/STEP), sensor(SSE브로드캐스터 3종+30초heartbeat, 1분집계, 7일삭제, 임계치API), alarm(임계치판정→중복억제→알람생성, CRITICAL자동DOWN은 이벤트로만) — 엔티티 common/entity에서 각 도메인으로 이동 완료. 도메인간 참조는 서비스 인터페이스/이벤트뿐(grep 재검증, 0건 위반). `gradlew test` 114개 전부 통과(1주차 49→114)
- [x] **프론트**: SSE훅(+3초폴링폴백), S-1 라인현황 대시보드, S-3 센서탭(실시간+이력차트, Recharts), S-6 알람센터(ACK/RESOLVE/수동보고). Playwright로 SSE/폴백/게이팅 실측 검증까지 완료(목서버 기준). `npm run build`/lint 통과
- [x] **QA 3라운드**: 신규 에러코드4종 매핑 확인(2개 추가, 2개는 의도적 미매핑+주석), 도메인경계 재검증(0건), 프론트 optional필드 크래시위험 없음 확인, 중복억제/자동DOWN 로직 재대조, 실결함 3건 발견·수정("0" 오도표시, 허위 보안주석 등). 리포트: `.claude/_workspace/qa/f4-f5-시뮬레이터센서알람_20260810.md`
- [x] **문서 동기화**: docs/05(sensor_threshold_logs 테이블 신설 반영, 16개 테이블), docs/06(SSE payload 실제 shape, 엔드포인트 2개 추가, sensor-data 응답이 PageResponse 아님을 반영)

### 2주차 알려진 미완료
- [ ] 임계치 편집 화면(프론트) 없음 — API는 완성, UI는 3주차 이후로 이월 (데모 핵심 플로우엔 없어도 지장 없음)
- [ ] S-1 KPI 스트립이 문서 스펙(누적 가동률)이 아니라 현재 상태 스냅샷으로 구현됨 — 누적 KPI는 4주차 FR-5.7과 함께 처리
- [x] 2026-10-01: 실MySQL 기동 + 시연 완료 (DRIFT: 경고→임계→자동DOWN 확인, STEP: 경고 알람 확인). 실기동 중 발견·수정: `@Lob` 컬럼 length 미지정 시 tinytext 생성(AiReport/Inspection), MySQL 호스트 포트 3306→3307(docker-compose/.env.example)

### 3주차 F-3 점검 이력 완료 — 2026-10-02
- [x] **백엔드**: inspection 도메인 신규(엔티티 4개 common/entity→inspection/ 이동), 점검 이력 등록/조회/수정/승인, PM 체크리스트 조회·관리 API, PM 스케줄(주기 설정, PmScheduleCalculator 순수함수, 매시 :10 UTC 스케줄러가 3일 초과 시 PmOverdueEvent → alarm이 MAJOR 'PM_OVERDUE' 생성), BM+alarmId → alarm 인터페이스(AlarmCommandService)로 자동 ACK+RESOLVE(작성자 명의). BM 등록은 설비 상태를 자동 변경하지 않음. 테스트 156→235 통과
- [x] **프론트**: S-4 목록/검색(필터 6종+페이징+상세모달+승인/수정), S-5 등록(PM 체크리스트 OK/NG/NA, BM 4M+연계알람, 소요시간·교대 자동), BM 저장 후 DOWN이면 IDLE 전환 확인 다이얼로그(자동 전환 금지), 설비상세 점검 탭+PM 스케줄 카드, 대시보드 PM OVERDUE 배지, 알람 센터 해제 사유·처리자·처리시각 표시(조치한 알람 이력 가시화)
- [x] **QA**: 경계면 검증 HIGH 0 / MED 2(PM 체크리스트 로딩 중 제출 우회, PM 지연 롤백 통합테스트 부재) 수정, 실MySQL 라이브 확인(BM+알람 자동해제·설비 DOWN 유지, PM 등록→스케줄 갱신, 스케줄러가 PM_OVERDUE 알람 5건 생성). 리포트: `.claude/_workspace/qa/f3-점검이력_20261002.md`
- [x] **라이브에서 발견·수정**: PM 등록해도 기존 OPEN PM_OVERDUE 알람이 남던 결함 → PM 등록 시 같은 설비의 미해결 PM_OVERDUE 알람 자동 해소 (스케줄 갱신 시에만)

### 3주차 F-6 AI 리포트 완료 — 2026-10-03 (mock 기준, 실 Claude 호출은 미검증)
- [x] **백엔드**: aireport 도메인 신규(AiReport 이동, ai_call_logs 신규). 컨텍스트 수집(알람·센서 1분집계 추이·최근 점검 5·BM 3·4M)은 각 도메인 *QueryService 인터페이스로만 접근. PromptBuilder(순수함수, [[DATA]] 구분자+NFKC 정규화로 인젝션 방어, 개인정보 미포함). ClaudeClient 인터페이스+RestClient 구현(재시도 5xx/타임아웃 1회·429/401/400 무재시도, 키 마스킹). 비동기 202+전용 워커(스레드2·큐10)+재시작 복구(5분). 쿼터 일20건(403 AI_QUOTA_EXCEEDED), 409 ALREADY_GENERATING·INVALID_REPORT_STATE. 확정본 불변(PESSIMISTIC_WRITE 직렬화). draft_content 어떤 경로로도 수정 불가. 테스트 235→365 통과(3회 연속)
- [x] **프론트**: S-7 목록/상세/편집(좌 에디터·우 미리보기), 2초 폴링(숨김탭 중지·150초 상한), FAILED 재시도/수동 작성, 원본 초안 보기 토글, 확정 확인 다이얼로그, 마크다운 안전 렌더(raw HTML·javascript:·이미지 차단), 설비 리포트 탭, 알람/점검 상세의 생성 진입점(ENGINEER+), 저장 안 한 변경 이탈 가드(라우터를 createBrowserRouter로 교체)
- [x] **QA**: HIGH 1(thinking 토큰 vs max_tokens) / MED 5 / LOW 다수 → H-1·M-1~M-5·L-1·L-2·L-4~L-6 수정. 리포트: `.claude/_workspace/qa/f6-ai리포트_20261003.md`. 간헐 로그인 401의 원인(테스트 컨텍스트들이 같은 H2 인메모리 DB 공유+create-drop)을 찾아 컨텍스트별 고유 DB로 해결
- [x] **실MySQL+브라우저 라이브 확인(AI_PROVIDER=mock)**: 생성 202→3초 후 DRAFT, TECHNICIAN 403, 중복 409, 편집→저장→확정, 확정 후 PUT/재확정 409, AI 원본 보존, 악성 마크다운(script/onerror/javascript:/이미지) 제거 확인, ai_call_logs 기록

### 4주차 진행 — 2026-10-03
- [x] **KPI(FR-5.7)**: KpiCalculator 순수함수(가동률=RUN/(전체−PM), MTBF=ΣRUN/DOWN진입, MTTR=DOWN 진입~이탈 평균·진행중 제외, KST 기간 절삭, 합산 재계산) + EquipmentKpiService(로그 2쿼리) + `GET /equipments/{id}/kpi`·`GET /equipments/kpi`. 프론트: 설비 상세 KPI 카드, 대시보드 라인 KPI(+설비별 표). 단위테스트 22+, 손계산 3건이 실MySQL 로그와 일치(QA)
- [x] **시뮬레이터**: DRIFT 상한(plateau) 도입(무한 상승 해결), 목표값=crit+노이즈 1σ(crit 정확히면 plateau에서 CRITICAL 확률 ~49%/샘플이라), 데모 자동 시작 2분(`SIMULATOR_DEMO_DURATION_MIN`). **라이브 실측: 경고 ~52초 → 임계+자동 DOWN 100~109초**
- [x] **QA**: HIGH 0 / MED 3 수정(KPI 첫 로딩 영구 로딩 버그, DRIFT 목표값, MTTR 힌트 보강). 리포트 `.claude/_workspace/qa/f5-kpi시뮬레이터_20261003.md`. 테스트 365→411, 프론트 build/lint 통과
- [x] **README.md 작성** + 스크린샷 5장(`docs/screenshots/`): 현장 경험→기능 매핑, 아키텍처(mermaid), 실행 방법, 3분 시연, 트러블슈팅 6건, 한계. 배포 URL은 아직 없음(배포 전)
- [x] 라이브 데모 환경 정리(로컬 DB): 미해결 알람 해제·설비 RUN 복귀·시나리오 해제 → 바로 시연 가능한 상태
- [x] **개발 마무리 3건 (2026-10-06)**: ① 설비 상세 헤더(센서/미해결 알람/다음 PM)를 프론트에서 기존 API 조합으로 채움(equipment가 sensor/alarm/inspection에 의존하지 않도록 서버 합산 대신 조합 — docs/06 기록) ② 임계치 편집 화면(설비 상세 센서 탭, ADMIN 전용·사유 필수·변경 이력·클라이언트 검증=서버 규칙) ③ 점검 이력 작업자 필터(`GET /users/lookup`: id·이름·역할만, 이메일 미노출)
- [x] **최종 점검(체크리스트)**: 에러 응답 포맷 전 경로 통일 확인 — 405/415가 500으로 나오던 결함 수정(406 포함, JSON 강제), 비밀키 히스토리 점검 clean, `// TENANT:` 확인, NFR-1 실측(SSE 지연 중앙값 32ms)
- [x] **QA(임계치·헤더·필터)**: HIGH 0 / MED 4 수정 — 서버가 소수 3자리·정수 8자리 초과를 안 막던 것(@Digits), 4값 전부 비우면 센서 감시가 꺼지던 것(서버+프론트 차단), Modal 포커스 트랩·제출 중 닫기 방지, docs/14 에러코드 색인. **범위 밖 발견 X-1**: application.yml이 AiProperties 기본값(4096/60초)을 2000/30초로 덮어써 F-6 QA H-1 수정이 무효였음 → yml 정정 + yml 바인딩 검증 테스트 추가(기본값만 보던 테스트가 못 잡은 것). 테스트 411→434. 리포트 `.claude/_workspace/qa/f2f3-임계치헤더작업자필터_20261006.md`

### 남음
- [ ] **실 Claude API 호출 검증** (ANTHROPIC_API_KEY 필요, `AI_PROVIDER=claude`): 응답 형식·토큰·지연(60초 내)·effort low 품질 확인. (yml 기본값 정정 후 처음 실제 값 4096/60이 적용되는 상태)
- [ ] **배포(Railway 백+DB, Vercel 프론트)** — 사용자 계정/외부 공개 필요. 저장소 준비(Dockerfile/설정/환경변수/CORS/application-prod) → 사용자 확인 후 실배포 → 스모크
- [ ] 포트폴리오 마무리: 이력서용 3줄 설명, 시연 GIF/영상(선택), 배포 URL을 README에 반영, 지인 1명 3분 테스트, 태블릿 폭 실확인(코드 기준 확인만 됨)
- [ ] 여유분(Should): 사용자 등록/비활성화(FR-1.3), 교대 인수인계 요약(FR-6.5)
- [ ] **로컬 DB 정리 필요**: `sensor_threshold_logs`에 UI 검증용 테스트 이력 4건(id 1~4, 사유 "UI 검증용…")이 남아 있음 — 삭제 시도가 권한 규칙에 막혀 못 지움. 시연 전에 사용자가 직접 삭제: `docker exec fabwatch-mysql mysql -ufabwatch -pfabwatch_local fabwatch -e "delete from sensor_threshold_logs where id in (1,2,3,4)"`

## 5. 다음 작업 지시 (그대로 실행 가능)

```
다음: 4주차 남은 것 — (1) 배포 준비·실배포(사용자 확인 필요: Railway/Vercel 계정, 공개 URL), (2) 이력서 3줄·시연 GIF, (3) 실 Claude 호출 검증(키 준비 시 최우선).
   배포 준비는 저장소 쪽만 먼저 가능: backend Dockerfile, application-prod.yml(환경변수 주입, ddl-auto 정책, CORS 허용 오리진), vercel.json(SPA 라우팅·API 베이스 URL), docs/13 절차 점검.
   배포 환경 주의: JWT_SECRET 필수 고정(미설정 시 재시작마다 로그인 풀림), SSE 프록시/타임아웃, AI 쿼터·키는 환경변수, 시드 계정 비밀번호는 데모용임을 README에 명시, MySQL @Lob 길이 이슈는 해결됨.

임계치/헤더/필터 후속(QA LOW 이월):
   - 권한 없는 사용자가 잘못된 본문을 보내면 403 대신 400이 먼저 나옴(@Valid가 @PreAuthorize보다 먼저). 보안 위험 낮음(공개 제약 메시지뿐). 완화: 변경성 엔드포인트를 SecurityConfig URL 규칙으로도 차단 + 최저 권한 역할 403 테스트
   - 임계치 편집 다이얼로그가 오래된 값으로 초기화될 수 있고 서버에 동시 편집 방어 없음(last-write-wins). 진행 중 DRIFT는 주입 당시 목표값 유지, 열린 알람은 임계치를 완화해도 자동 해소되지 않음(문서화 필요)
   - 405 응답에 Allow 헤더 없음, 필수 파라미터 누락 400 핸들러는 도달 불가·무테스트, ConstraintViolationException 메시지 원문 노출, 설비 전환 시 헤더 값 잔존, 센서 목록 중복 호출, equipment↔sensor/alarm/inspection/aireport 순환(EquipmentDetailTabs, 기존 구조), docs/04 S-8이 임계치를 /admin에 두는 서술과 실제(S-3 센서 탭) 불일치
   - 백엔드가 같은 build 폴더를 동시에 쓰면 `Could not write XML test results`로 테스트가 실패함 — 에이전트 병렬 작업 시 gradle 테스트는 한 번에 하나만

KPI 후속(QA LOW/MED 이월):
   - K-1: 기간 시작 전 진입해 계속 DOWN인 설비는 'DOWN 0회·MTBF 고장 없음·가동률 0%'로 표시됨(스펙대로이나 모순으로 읽힘). ongoingDown/completedDownCount 필드 추가 검토
   - LOW: BM을 PM 상태로 기록하면 가동률 과대, 반올림으로 0.0 표시, 가동률 0.99996→'100.0%', 대시보드 KPI에 선택 라인명 미표시, 헤더 라인 필터가 설비 카드 목록엔 미적용(KPI에만), 프론트 단위 테스트 없음, 대상 설비가 이미 DOWN이면 CRITICAL이 나도 자동 DOWN 생략(정상)
   - 테스트 누락(QA T-1~T-7): 기간 이전부터 끝까지 DOWN 지속, 동일 changed_at 쿼리 통합, 노이즈 경로 plateau CRITICAL 판정, SimulatorProperties 바인딩

F-6 후속:
   - 코드 방어 못 한 항목: 복구 5분 기준 vs 큐 최악 대기(약 10분) — 워커 시작 시점에 updated_at 갱신하면 해소. 기동 시 info 로그로 경고만 함
   - 점검 경로 리포트는 신규부터 alarmId 저장(과거 리포트는 null)
   - GET /admin/ai-usage(호출 로그 조회) 미구현, 번들 890kB 코드 스플리팅 미적용, ai_call_logs MySQL 실경합 테스트 미검증(H2만)

F-3 이월/후속 (우선순위 순):
   - DOWN 이탈(DOWN→IDLE/RUN) 시 BM 점검이력 연결을 "권장→강제"로 올림 (사용자 결정: F-3 이후 단계 — 이제 착수 가능)
   - 점검 이력 작업자 필터: 일반 역할이 쓸 사용자 목록 API가 없어 '내 점검만'으로 대체함. 필요하면 사용자 목록(이름만) 조회 API 추가
   - PM 체크리스트 템플릿 관리 UI 없음(API만 있음, ENGINEER+). 시드 템플릿으로 동작
   - 시드 PM 스케줄이 8월 기준이라 5대 전부 OVERDUE(실DB는 시드 건너뜀). 데모 전에 스케줄 재설정 또는 DB 시드 재생성 필요
   - 서버 재시작마다 JWT 시크릿이 바뀌어 로그인이 풀림(JWT_SECRET 미설정). .env에 고정값 두면 개발 편의 개선
   - QA LOW 잔여: DOWN→IDLE이 역할값을 보지 않음(알려진 3역할 선결조건 권장, EquipmentService.validateManualChange), 판정순서(403→400)·null/미지 역할 테스트 없음,
     POST /alarms/manual로 TECHNICIAN이 CRITICAL을 올리면 autoDown 발생(기존 설계, docs/03 명시 권장), PM 이력 수정 시 endedAt 변경이 스케줄에 미반영,
     한 스케줄 실패가 PM 지연 배치 전체 롤백, PM 스케줄 최초 설정 동시요청 UNIQUE 위반 500, ?id= 딥링크 마운트 1회만 반영, 서버 null 가능 필드를 프론트가 non-null로 선언
```

## 6. 주의사항 (매 작업 공통)

- 코드 주석 한국어. 멀티테넌트 지점 `// TENANT:` 주석.
- 실제 LG디스플레이 공정 수치·알람 코드 사용 금지 — 시드는 전부 가상값 (docs/08 B-3).
- API 키·비밀번호는 .env / 환경변수. Git에 올리지 않는다 (.gitignore 확인).
- Must(M) 우선. Should는 3주차 이후 여유 시에만.
- 에러 응답 포맷, 상태 머신 전환 규칙은 docs/03 F-7, F-2 준수.

## 7. 작업 로그 (최신이 위)

| 날짜 | 작업 | 결과/결정 |
|---|---|---|
| 2026-10-06 | **개발 마무리: 설비 상세 헤더·임계치 편집 화면·작업자 필터 + 최종 점검** (하네스: backend/frontend 병렬 → mes-qa → 수정 → 라이브 검증) | FR-2.3/2.4/3.5 완료(체크리스트 Must 미완 항목 해소), /users/lookup, 405·415·406 핸들러, 임계치 자릿수·전부 비우기 차단, Modal 접근성. 최종 점검: 에러 포맷 전 경로 통일, 비밀키 clean, NFR-1 실측 32ms. QA에서 범위 밖 X-1 발견(yml이 AI 기본값 덮어씀 → F-6 수정 무효였음)→정정·바인딩 테스트. 테스트 411→434. 에이전트가 만든 임계치 테스트 이력 4건은 권한 규칙으로 삭제 못 해 남아 있음(사용자 정리 필요) |
| 2026-10-03 | **4주차 KPI + 시뮬레이터 개선 + README/스크린샷** (하네스: backend/frontend 병렬 → mes-qa → 수정 → 라이브 검증) | KPI(가동률/MTBF/MTTR, KST 절삭·합산 재계산)·설비 상세/대시보드 위젯, DRIFT plateau(crit+1σ)+데모 2분(실측 경고 52초→자동 DOWN 100~109초). 테스트 365→411. QA HIGH 0, MED 3 수정, 손계산 3건 실DB 일치. 라이브에서 확인: 숨김 탭은 폴링 중지(설계), 세션은 curl 로그인이 refresh token을 교체해 브라우저 세션을 끊을 수 있음(사용자당 1개 저장). README.md+스크린샷 5장 작성. 데모 환경 정리 후 커밋. 배포·이력서 3줄·실Claude 호출 검증은 남음 |
| 2026-10-03 | **3주차 F-6 AI 리포트** (하네스: backend/frontend 병렬 → mes-qa → 수정 2개 병렬 → 라이브 검증) | aireport 도메인(비동기 202, 쿼터, 호출 로그, 키 마스킹, 인젝션 방어, 확정본 불변), S-7+설비 리포트 탭+생성 진입점. 테스트 235→365, 프론트 build/lint 통과. QA에서 HIGH 1(Sonnet 5.5가 thinking 기본 ON이라 max_tokens 2000으론 본문 빈/잘림 위험 — claude-api 스킬로 사양 확인 후 effort low+max_tokens 4096+타임아웃 60초로 수정), MED 5(키 개행 시 로그 노출 경로, 간헐 401=테스트 H2 공유, 확정 레이스, 알람/점검 경로 중복 생성, docs/11 권한표) 수정. 실MySQL+브라우저 라이브 확인은 AI_PROVIDER=mock 기준. **실 Claude 호출은 API 키 없어 미검증**. 리포트: `.claude/_workspace/qa/f6-ai리포트_20261003.md` |
| 2026-10-02 | **3주차 F-3 점검 이력 + PM 스케줄 + 알람 이력 가시화** (하네스: backend-dev/frontend-dev 병렬 → mes-qa → 라이브 검증·수정) | 점검 이력 CRUD·승인·체크리스트·PM 스케줄·PM 지연 알람(이벤트)·BM→알람 자동 해제, S-4/S-5/점검탭/OVERDUE 배지/알람 해제사유 표시. 테스트 156→235, 프론트 build/lint 통과. QA HIGH 0, MED 2 수정. 실MySQL 라이브 확인 중 PM 등록 후 PM_OVERDUE 알람 잔존 결함 발견→자동 해소로 수정. 이월: BM 연결 강제, 작업자 필터, 템플릿 관리 UI, 시드 PM 스케줄 재설정. 리포트: `.claude/_workspace/qa/f3-점검이력_20261002.md` |
| 2026-10-02 | **상태 머신 개선(F-2)** (하네스: backend-dev/frontend-dev 병렬 → mes-qa) | DOWN=BM(계획 외 정지) 취급, 라벨 "DOWN (BM)". 신규 DOWN→IDLE(사유 필수, TECHNICIAN 포함 전 역할), DOWN→RUN(사유 필수, ENGINEER+), DOWN→PM 유지, PM→RUN 계속 불가. 권한 판정은 EquipmentService.validateManualChange 한 곳(404→400허용표→403권한→400사유). MTTR 정의 DOWN 진입~이탈로 변경(구현은 4주차). 별도 컬럼 없이 로그의 DOWN→RUN이 시운전 생략 표시. 문서 6종(03/06/11/12/14+memory) 동기화. 테스트 114→156 통과, 프론트 build/lint 통과. QA: HIGH 0 / MED 3(설비수정폼 라벨 누락, docs/11 권한표 stale, 시드가 DOWN→PM 경로) 모두 수정. 실서버 확인: 브라우저 DOWN→IDLE 직행 성공, TECHNICIAN API 403/400/403 확인. 리포트: `.claude/_workspace/qa/f2-상태머신개선_20261002.md`. 실DB는 기존 시드 로그 유지(시드는 빈 DB에서만 실행) |
| 2026-10-01 | 실MySQL 기동 + 3분 시연 (2주 연속 미룬 항목 해소) | docker MySQL(3307)+bootRun+vite, 브라우저로 DRIFT→경고→임계→자동DOWN, STEP→경고 확인. 실기동에서만 드러난 결함 2종 수정(@Lob tinytext, 포트 충돌). 상태 머신 DOWN→PM 강제 경로가 현장 흐름과 안 맞는다는 지적 → 개선안은 결정 대기 |
| 2026-08-10 | **2주차 완료** (하네스 3-agent: backend-dev/frontend-dev/mes-qa) | 시뮬레이터(F-4)+SSE(sensor/alarm/status 3종)+알람(임계치판정+중복억제+CRITICAL자동DOWN)+임계치API(FR-2.3 이월분) 백엔드, 대시보드(S-1)+센서탭(S-3)+알람센터(S-6) 프론트(Playwright 실측검증 포함). QA에서 실결함 3건 발견·수정(에러코드 미매핑으로 DB컬럼명 노출, 미해결알람 0으로 오도표시, 존재안하는 클래스 언급하는 허위 보안주석). QA가 이번엔 "리포트 먼저 저장" 순서를 지켜서 중단 리스크 없이 완료. docs/05·06 드리프트 3건 리더가 직접 동기화. 미완료: 임계치 편집화면(UI), 실MySQL 3분시연(2주 연속 미룸 — 3주차 착수 전 필수) |
| 2026-08-09 | S-2/S-3 프론트 연결 (1주차 잔여 마무리) + QA 2라운드 | 설비 등록/수정 폼, 상태변경 다이얼로그(허용전이만 후보), 상태이력 탭 구현. QA 서브에이전트가 검증 도중 워치독 타임아웃으로 중단돼, 리더가 직접 핵심 위험 4項(전이표/에러코드/응답shape/권한매트릭스) 재대조 — 전부 일치, 결함 없음. 리포트: `.claude/_workspace/qa/f2-설비상태변경UI연결_20260809.md`. GitHub `kim100000000/fabwatch`(public) 생성, develop을 기본 브랜치로 전환 |
| 2026-08-07 | **1주차 개발 완료** (하네스 3-agent 병렬: backend-dev/frontend-dev/mes-qa) | git init+docker-compose+.gitignore, 백엔드(F-1 인증+F-2 설비마스터+엔티티15개+시드, 49테스트 통과), 프론트(FSD라이트+로그인+설비목록/상세+레이아웃, build통과), QA에서 실버그 3건 발견·수정(`/lines` 크래시급 shape불일치 포함). 미완료: S-2/S-3 프론트 버튼 미연결(백엔드는 완성), MySQL 실기동 미검증(Docker꺼짐, H2로 대체검증) |
| 2026-08-07 | 비판적 투자자 관점 리스크 검토 후 docs 5종 수정 | 01(차별점=서사지 moat 아님 명시), 03 F-2(DOWN전환은 DB필드일 뿐 물리제어 아님 명시), 07(실PLC연동 시 안전경고 추가), 08(§0 리스크 섹션·원가추정·A-5 게이트 3항목 추가), 11(§10 실설비제어 안전게이트 신설). 하네스 backend-developer/mes-qa에도 "실제 설비 쓰기 제어 금지" 원칙 반영 |
| 2026-07-07 | docs/12~15 명세서 4종 신규 작성 | 테스트계획(핵심4종 단위테스트)·배포운영(Railway+Vercel)·용어사전(용어 단일출처)·성능부하(SLO+병목예측). 보안(11)은 기존 유지 |
| 2026-07-06 | 아키텍처·폴더 구조 확정, docs/10 ADR+면접대비 작성 | 모듈러 모놀리스, 도메인 우선 패키지, FSD 라이트. MSA 미채택 근거 문서화 |
| 2026-07-06 | CLAUDE.md + 09_체크리스트 추가 | 역할명 TECHNICIAN 유지 확정 (Maintenance는 업무 용어로만) |
| 2026-07-06 | 프로젝트 킥오프, 문서 00~08 + memory.md 작성 | 스택·범위·일정 확정. 다음: 1주차 셋업 |
