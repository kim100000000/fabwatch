---
name: mes-boundary-qa
description: "FabWatch 백엔드-프론트엔드 경계면 교차 검증 절차. API shape vs 프론트 타입, 상태 머신 전이 완전성, 컨벤션(soft delete/에러포맷/역할명) 검사 시 이 순서를 따른다. mes-qa 에이전트가 사용."
---

# MES 경계면 교차 검증 절차

핵심 전제: 백엔드와 프론트엔드가 각각 정상 컴파일/빌드되어도, 연결 지점의 계약 불일치는 잡히지 않는다. TypeScript 제네릭 캐스팅이나 `npm run build` 성공은 런타임 정합성을 보장하지 않는다. 반드시 **양쪽 코드를 동시에 열어** 비교한다.

## 1. API 응답 ↔ 프론트 훅 타입 교차 검증
```
1. backend/.../controller의 반환 DTO(또는 ResponseEntity.body) shape 추출
2. frontend/src/features/{domain}/api의 대응 훅에서 기대하는 타입(types.ts) 확인
3. 필드명·타입·nullable 여부가 일치하는지 비교
4. 목록 API는 { content, totalElements, ... } 래핑을 훅이 .content로 unwrap하는지 확인
5. snake_case(DB, docs/05) → camelCase(API, docs/06) → camelCase(프론트 타입) 전 구간 일관성 확인
```
**특히 의심할 패턴**: 페이지네이션 shape 불일치, DB 컬럼명이 그대로 API에 새어나온 경우, 즉시 응답(202)과 최종 결과 shape 혼동.

## 2. 상태 머신 전이 완전성 추적
```
1. docs/03_기능명세서.md F-2의 상태 전이 규칙(허용된 전이 목록) 추출
2. backend의 상태 변경 코드(설비 상태 필드를 바꾸는 모든 지점)를 Grep으로 전수 검색
3. 각 전이가 F-2 규칙에 정의되어 있는지 확인 (무단 전이 없음)
4. F-2에 정의된 전이 중 코드에 없는 것 식별 (죽은 전이 없음)
5. 프론트의 상태 기반 분기(status === 'X')에서 X가 실제 도달 가능한 상태인지 확인
```

## 3. API 엔드포인트 ↔ 프론트 훅 1:1 매핑
```
1. docs/06_API명세서.md의 엔드포인트 목록과 backend 실제 구현 목록을 대조
2. frontend/src/features/*/api의 훅이 호출하는 URL 목록 추출
3. 구현된 API 중 호출하는 훅이 없는 것 식별 → 의도(관리용 등)인지 누락인지 판단
```

## 4. 컨벤션 위반 검사 (Grep 기반)
- soft delete: `deleteById(` 등 물리 삭제 호출이 있는지 검색 — 있으면 위반
- 에러 포맷: 컨트롤러에서 `ResponseEntity.badRequest()`/즉흥 에러 응답을 직접 만드는 곳이 있는지 검색 — `GlobalExceptionHandler` 우회 여부
- 역할명: `MAINTENANCE`라는 role 문자열이 코드/타입에 등장하는지 검색 (TECHNICIAN이어야 함)
- 비밀정보: API 키/비밀번호 패턴이 코드에 하드코딩됐는지 검색
- 가상 데이터: 실제 회사명·공정 수치로 의심되는 시드값이 있는지 확인

## 5. 검증 리포트 작성
`.claude/_workspace/qa/{기능-slug}_{YYYYMMDD}.md`에 저장:
```markdown
# QA 리포트 — {기능명} ({날짜})

## 통과
- {항목}

## 실패 (수정 필요)
- {파일}:{라인} — {문제} — {수정 방법}

## 미검증 (확인 불가/범위 밖)
- {항목} — {사유}
```

## 6. 결함 발견 시 행동
- 담당 에이전트(backend-developer/frontend-developer)에게 즉시 SendMessage: `파일:라인 — 문제 — 수정 방법`
- 경계면 이슈(양쪽 다 관련)는 두 에이전트 모두에게 알림
- 재검증까지 완료 처리하지 않는다

## 참고
검증 우선순위와 체크리스트 전문은 `mes-qa` 에이전트 정의(`.claude/agents/mes-qa.md`)에 있다 — 이 스킬은 그 체크리스트를 실행하는 절차다.
