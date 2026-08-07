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

## 3. 문서 지도

```
memory.md            ← 본 파일 (항상 최신)
CLAUDE.md            ← Claude 작업 지침 (세션 규칙, 컨벤션, 금지사항)
docs/00_README_문서활용가이드.md   ← AI 협업 원칙, 프롬프트 템플릿
docs/01_서비스기획서.md            ← 차별점(현장경험→기능 매핑 표), 시뮬레이터 컨셉
docs/02_요구사항명세서.md          ← FR/NFR 전체 (Must/Should/Could)
docs/03_기능명세서.md              ← 기능별 규칙·예외 ★개발 시 해당 섹션 투입
docs/04_화면설계서.md              ← 디자인 토큰(다크 산업용), 화면 8종
docs/05_DB설계서_ERD.md            ← 테이블 15개, 시드 데이터 정의
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

### 미착수 (코드는 아직 한 줄도 없음)
- [ ] 1주차: 프로젝트 셋업 (Spring Boot + React + MySQL + Git) ← **다음 작업**
- [ ] 1주차: DB 스키마 + 시드, 인증(F-1), 설비 마스터(F-2)
- [ ] 2주차: 시뮬레이터(F-4) + SSE + 알람 + 대시보드(F-5)
- [ ] 3주차: 점검 이력(F-3) + AI 리포트(F-6)
- [ ] 4주차: KPI + 테스트 + 배포 + 포트폴리오 README 패키징

## 5. 다음 작업 지시 (그대로 실행 가능)

```
1. backend/ (Spring Boot 3.x, Gradle, Java 17): 패키지 구조
   com.fabwatch.{auth, equipment, sensor, inspection, alarm, aireport, simulator, common}
2. frontend/ (Vite + React 18 + TS): 라우팅 8개 화면 골격 (docs/04 §2)
3. docker-compose.yml (MySQL 8 로컬용)
4. docs/05 기준 JPA 엔티티 전체 + 시드 (CommandLineRunner)
5. F-1 인증 구현 (docs/03 F-1 + docs/06 §1)
참고: 이전 프로젝트 taedibear-studio(../claude/taedibear-studio)와 같은 스택이므로
      설정(gradle, security config) 재사용 가능.
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
| 2026-08-07 | 비판적 투자자 관점 리스크 검토 후 docs 5종 수정 | 01(차별점=서사지 moat 아님 명시), 03 F-2(DOWN전환은 DB필드일 뿐 물리제어 아님 명시), 07(실PLC연동 시 안전경고 추가), 08(§0 리스크 섹션·원가추정·A-5 게이트 3항목 추가), 11(§10 실설비제어 안전게이트 신설). 하네스 backend-developer/mes-qa에도 "실제 설비 쓰기 제어 금지" 원칙 반영 |
| 2026-07-07 | docs/12~15 명세서 4종 신규 작성 | 테스트계획(핵심4종 단위테스트)·배포운영(Railway+Vercel)·용어사전(용어 단일출처)·성능부하(SLO+병목예측). 보안(11)은 기존 유지 |
| 2026-07-06 | 아키텍처·폴더 구조 확정, docs/10 ADR+면접대비 작성 | 모듈러 모놀리스, 도메인 우선 패키지, FSD 라이트. MSA 미채택 근거 문서화 |
| 2026-07-06 | CLAUDE.md + 09_체크리스트 추가 | 역할명 TECHNICIAN 유지 확정 (Maintenance는 업무 용어로만) |
| 2026-07-06 | 프로젝트 킥오프, 문서 00~08 + memory.md 작성 | 스택·범위·일정 확정. 다음: 1주차 셋업 |
