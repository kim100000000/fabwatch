---
name: fabwatch-feature-builder
description: "FabWatch(MES 설비관리 포트폴리오, Spring Boot+React) 개발을 조율하는 오케스트레이터. '설비관리 시스템 개발/구현', 'FabWatch 기능 만들어줘', '점검이력/센서시뮬레이터/알람/대시보드/AI리포트 구현', '1주차 셋업', '다음 작업 진행해줘' 요청 시 사용. 후속 작업: 특정 기능 수정/보완, 이전 구현 개선, 체크리스트 갱신 요청 시에도 반드시 이 스킬을 사용."
---

# FabWatch Feature Builder — 오케스트레이터

FabWatch(MES 설비관리 포트폴리오) 프로젝트의 백엔드-프론트엔드 팀을 조율해 기능 하나를 스펙부터 구현·검증·문서 갱신까지 완결한다. 프로젝트 자체 컨벤션(`CLAUDE.md`)의 "한 대화 = 한 기능, 세션 종료 시 memory.md 갱신" 규칙을 팀 작업으로 자동화한 것이다.

## 실행 모드: 에이전트 팀

## 에이전트 구성

| 팀원 | 에이전트 타입 | 역할 | 스킬 | 출력 |
|------|-------------|------|------|------|
| backend-dev | 커스텀 (backend-developer) | Spring Boot 도메인 구현 | spring-boot-domain-impl | `backend/` 코드 |
| frontend-dev | 커스텀 (frontend-developer) | React/TS 화면 구현 | react-fsd-impl | `frontend/` 코드 |
| qa | 커스텀 (mes-qa), general-purpose 기반 | 경계면·컨벤션 검증 | mes-boundary-qa | `.claude/_workspace/qa/*.md` |

## 워크플로우

### Phase 0: 컨텍스트 확인 (필수 — 매번 첫 단계)

1. **`memory.md`를 가장 먼저 Read** — 현재 진행 상태, 확정된 기술 결정, "다음 작업 지시" 섹션을 파악한다. 이게 이 프로젝트의 상태 저장소이며, 하네스의 `_workspace/`를 대체한다(중복 상태 파일을 만들지 않는다).
2. `backend/`, `frontend/` 디렉토리 존재 여부 확인:
   - **둘 다 없음** → **초기 셋업 모드**. memory.md의 "다음 작업 지시" 1~3번(Spring Boot/React 프로젝트 골격, docker-compose)을 대상으로 Phase 1 진행.
   - **존재함** → **기능 개발 모드**. 사용자가 특정 기능을 지정했으면 그것을, 안 했으면 memory.md "다음 작업 지시"의 다음 항목을 대상으로 진행.
3. 사용자 요청이 "이전 기능 수정/보완"이면: 해당 기능의 최근 `.claude/_workspace/qa/{기능-slug}_*.md` 리포트가 있으면 Read해서 이전에 지적된 미해결 항목을 함께 팀원에게 전달.

### Phase 1: 스펙 수집

1. 대상 기능을 F-1~F-7 중 하나(또는 초기 셋업 항목)로 확정.
2. 필요한 문서 섹션만 특정 — 전체 문서를 다 읽지 않는다:
   - 백엔드 작업 → `docs/03_기능명세서.md` 해당 섹션 + `docs/05_DB설계서_ERD.md` 관련 테이블 + `docs/06_API명세서.md` 관련 엔드포인트
   - UI가 포함된 작업 → 위에 더해 `docs/04_화면설계서.md` 공통 토큰 + 해당 화면(S-0~S-8)
   - 인증/보안 관련(F-1) → `docs/11_보안명세서.md` 추가
3. 작업 범위 분류:
   - **backend-only**: 시뮬레이터 내부 로직(F-4 4.2), 배치/스케줄러 등 화면 노출 없는 항목
   - **backend+frontend**: 대부분의 F-1~F-6 기능 (API + 화면)
   - **frontend-only**: 이미 구현된 API에 대한 화면 단독 보완
4. 각 분류에 해당하는 팀원에게만 작업을 배정한다(불필요한 팀원 참여 금지).

### Phase 2: 팀 구성

```
TeamCreate(
  team_name: "fabwatch-team",
  members: [
    { name: "backend-dev", agent_type: "backend-developer", model: "opus",
      prompt: "FabWatch 백엔드 개발자. spring-boot-domain-impl 스킬을 사용해 배정된 기능을 구현한다." },
    { name: "frontend-dev", agent_type: "frontend-developer", model: "opus",
      prompt: "FabWatch 프론트엔드 개발자. react-fsd-impl 스킬을 사용해 배정된 화면/기능을 구현한다." },
    { name: "qa", agent_type: "mes-qa", model: "opus",
      prompt: "FabWatch QA. mes-boundary-qa 스킬을 사용해 backend-dev/frontend-dev 완료 직후 경계면을 검증한다." }
  ]
)
```

작업 범위 분류에 따라 팀원 수를 조정한다(backend-only 작업이면 backend-dev + qa 2명만 구성해도 된다).

```
TaskCreate(tasks: [
  { title: "{기능명} 백엔드 구현", description: "{Phase 1에서 정리한 스펙 경로 + 요구사항}", assignee: "backend-dev" },
  { title: "{기능명} 프론트엔드 구현", description: "{스펙 경로 + 요구사항}", assignee: "frontend-dev" },
  { title: "{기능명} 경계면 검증", description: "backend-dev/frontend-dev 완료 후 mes-boundary-qa 절차 수행", assignee: "qa", depends_on: ["{기능명} 백엔드 구현", "{기능명} 프론트엔드 구현"] }
])
```

### Phase 3: 병렬 구현

**실행 방식:** backend-dev와 frontend-dev는 문서 스펙(docs/06)을 공통 계약으로 삼아 **병렬** 진행한다 — API가 100% 완성될 때까지 프론트가 대기할 필요 없음. 단, DTO 필드명 등 세부가 애매하면 frontend-dev가 backend-dev에게 SendMessage로 먼저 질의한다.

**팀원 간 통신 규칙:**
- backend-dev는 API 구현 완료 즉시 실제 응답 shape을 frontend-dev와 qa에게 SendMessage로 공유(문서와 다르면 반드시 명시)
- frontend-dev는 shape 확정 소식을 받으면 타입을 즉시 갱신
- 리더(오케스트레이터)는 TaskGet으로 진행 상황을 모니터링, 유휴 상태 팀원 발견 시 SendMessage로 확인

### Phase 4: 경계면 검증 (incremental QA)

1. backend-dev, frontend-dev 둘 다(해당하는 경우) 완료 확인 후 qa 작업 시작 — 전체 프로젝트 완성을 기다리지 않고 **이 기능 단위로 즉시** 검증.
2. qa는 mes-boundary-qa 절차대로 양쪽 코드를 동시에 Read해 교차 비교, 리포트를 `.claude/_workspace/qa/{기능-slug}_{YYYYMMDD}.md`에 저장.
3. 결함 발견 시 qa가 담당자에게 직접 SendMessage로 수정 요청 → 담당자 수정 → qa 재검증. 이 루프는 리더 개입 없이 팀 내에서 자체 반복한다.
4. 리더는 TaskGet으로 qa 작업이 완료(통과) 상태가 될 때까지 대기.

### Phase 5: 마무리 (리더가 직접 수행 — 중복 갱신 방지를 위해 팀원에게 위임하지 않음)

1. `memory.md` 갱신:
   - "진행 상태" 섹션의 완료 체크박스 추가
   - 새로 확정된 기술 결정이 있으면 "확정된 기술 결정" 표에 추가
   - "다음 작업 지시" 섹션을 다음 항목으로 갱신
   - "작업 로그" 최상단에 1줄 추가 (날짜 | 작업 | 결과/결정)
2. `docs/09_체크리스트.md`에서 해당 항목 체크.
3. qa 리포트 경로를 작업 로그에 남겨 감사 추적 가능하게 한다.
4. 팀원들에게 종료 SendMessage → `TeamDelete`.
5. 사용자에게 요약 보고: 구현한 기능, 변경 파일, QA 통과 여부, memory.md/체크리스트 갱신 내용.

## 데이터 흐름

```
memory.md(상태) → [리더] Phase 1 스펙 확정
                     │
              TeamCreate + TaskCreate
                     │
     ┌───────────────┼───────────────┐
     ↓                                ↓
[backend-dev]  ←SendMessage→  [frontend-dev]
     │  (실제 shape 공유)              │
     └──────────────┬─────────────────┘
                     ↓
                  [qa] 경계면 검증 (양쪽 코드 동시 Read)
                     │
              리포트: _workspace/qa/*.md
                     ↓
              [리더] memory.md + 체크리스트 갱신
                     ↓
                 사용자 보고
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| backend-dev/frontend-dev 빌드·테스트 실패 | 해당 팀원이 1회 자체 수정 재시도. 재실패 시 리더에게 실패 원인 보고 → memory.md에 "미완료/블로킹"으로 명시하고 다음 세션으로 이월 |
| qa가 경계면 결함 발견 | 담당자에게 재수정 요청 → 재검증. 2회 반복해도 해결 안 되면 리더에게 에스컬레이션, 사용자에게 판단 요청 |
| 팀원 유휴/무응답 | 리더가 SendMessage로 상태 확인 → 무응답 지속 시 작업 재할당 |
| 문서 스펙 자체가 모호하거나 누락 | 임의로 추측 구현하지 않는다. 가장 보수적인 기본값으로 구현 후 사용자에게 확인 요청 항목으로 보고 |
| 초기 셋업 모드에서 기존 taedibear-studio 설정 재사용 지시(memory.md 5번)가 있는데 해당 경로가 없음 | 재사용 없이 표준 Spring/Vite 초기화로 진행하고 리더 보고에 명시 |

## Phase 별 실행 모드 요약
전체 워크플로우는 단일 모드(에이전트 팀)로 진행. Phase 5(마무리)만 팀 해체 후 리더가 직접 수행 — memory.md는 단일 진실 출처라 동시 갱신 충돌을 피하기 위해서다.

## 테스트 시나리오

### 정상 흐름
1. 사용자: "F-3 점검 이력 등록 기능 구현해줘"
2. Phase 0: memory.md 확인 → backend/frontend 이미 존재 → 기능 개발 모드
3. Phase 1: docs/03 F-3, docs/05 관련 테이블(inspection_history 등), docs/06 관련 엔드포인트, docs/04 해당 화면 특정. backend+frontend 둘 다 필요로 분류
4. Phase 2: 3명 팀 구성, 3개 작업(backend/frontend/qa, qa는 depends_on)
5. Phase 3: backend-dev·frontend-dev 병렬 구현, shape 공유
6. Phase 4: qa 경계면 검증 통과, 리포트 저장
7. Phase 5: memory.md·체크리스트 갱신, 팀 정리, 사용자 보고
8. 예상 결과: `backend/src/.../inspection/`, `frontend/src/features/inspection/` 코드 생성, `.claude/_workspace/qa/f3-점검이력등록_20260807.md` 생성, memory.md 갱신됨

### 에러 흐름
1. Phase 3에서 backend-dev의 `./gradlew test` 실패(상태 머신 테스트 케이스 불일치)
2. 1회 자체 재시도 후에도 실패
3. 리더에게 실패 원인 보고
4. 리더가 frontend-dev·qa 작업은 계속 진행할지, 전체 중단할지 판단 — 이 케이스는 frontend가 backend 완료를 강하게 의존하지 않으므로 frontend는 mock 기반으로 계속, qa는 backend 항목을 "미검증"으로 리포트에 기록
5. Phase 5에서 memory.md에 "F-3 백엔드 테스트 실패로 미완료, 다음 세션 우선 처리" 명시 후 사용자에게 보고
