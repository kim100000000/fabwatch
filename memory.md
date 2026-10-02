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
| AI | Claude API (백엔드 경유만, 키는 env) | 리포트 max_tokens 2000, 일 20건 쿼터 |
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

### 남음
- [ ] 3주차: 점검 이력(F-3) + AI 리포트(F-6)
- [ ] 4주차: KPI + 테스트 + 배포 + 포트폴리오 README 패키징

## 5. 다음 작업 지시 (그대로 실행 가능)

```
3주차 F-3 구현 시 함께 처리 (상태 머신 개선 2026-10-02 후속):
   - DOWN 이탈(DOWN→IDLE/RUN) 시 BM 점검이력 연결을 "권장→강제"로 올리는 건 F-3 이후 단계 (사용자 결정). 지금은 사유만 필수.
   - 알람 센터에서 해제 사유(조치 내용) 표시 여부 확인 필요 (상태 필터로 해제 알람 조회는 가능).
   - 시뮬레이터 DRIFT는 상한이 없어 시나리오를 안 끄면 값이 무한 상승(실측: 수 시간 뒤 온도 1721℃). crit 도달 후 클램프 또는 자동 해제 고려.
   - QA LOW 잔여: DOWN→IDLE이 역할값을 보지 않음(알려진 3역할 선결조건 권장, EquipmentService.validateManualChange), 판정순서(403→400) 테스트·null/미지 역할 테스트 없음, POST /alarms/manual로 TECHNICIAN이 CRITICAL을 올리면 autoDown 발생(기존 설계, docs/03에 명시 권장).

3주차 본작업 (F-3 점검이력 + F-6 AI리포트):
1. inspection/: 점검 이력 등록(PM체크리스트/BM 4M분류), PM스케줄(주기설정→next_due_at계산,
   OVERDUE 경고, 3일초과 MAJOR알람 — alarm 도메인과는 이벤트로 연결), 검색/필터+페이징 (docs/03 F-3)
2. aireport/: 컨텍스트 수집(알람+센서1분집계요약+이력5건+BM3건) → Claude API 호출(백엔드 경유만,
   docs/11 §7) → DRAFT 저장(비동기 202) → 편집→확정 플로우, 일20건 쿼터+토큰기록 (docs/03 F-6, docs/06 §7)
3. frontend features/{inspection,aireport}: S-5 점검등록폼(PM/BM분기), S-4 이력목록, S-7 리포트뷰/에디터
참고: FR-2.3 임계치 편집 화면(2주차 이월분)도 여유 있으면 이번에 같이 붙이기 — API는 이미 완성돼있음.
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
