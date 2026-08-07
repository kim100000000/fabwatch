---
name: react-fsd-impl
description: "FabWatch 프론트엔드(React 18 + TS + Vite, FSD 라이트) 화면/기능을 구현하는 절차. API 훅 작성, SSE 연동+폴링 폴백, 디자인 토큰 적용 시 이 순서를 따른다. frontend-developer 에이전트가 사용."
---

# React FSD 라이트 구현 절차

FabWatch 프론트는 FSD 라이트(app/pages/features/shared) — features는 백엔드 도메인과 1:1 대응이 핵심 제약이다. 이 스킬은 그 안에서 화면/기능 하나를 구현하는 순서를 제공한다.

## 0. 시작 전 필수 확인
1. `docs/06_API명세서.md`에서 연동할 엔드포인트를 **직접 Read** — 경로·요청/응답 shape·에러 코드.
2. `docs/04_화면설계서.md`에서 해당 화면 섹션(S-0~S-8) + §1 공통 디자인 토큰을 Read.
3. 백엔드가 아직 구현 전이어도 문서 스펙 기준으로 병렬 진행 가능 — 단, 실제 shape 확정되면 backend-developer의 SendMessage를 받아 타입을 갱신한다.
4. 기존 `features/` 폴더가 있으면 인접 도메인 구조를 먼저 Read하고 동일 패턴을 따른다.

## 1. 폴더 구조

```
src/features/{domain}/
├── api/          axios 훅 (useEquipmentList 등)
├── components/   해당 도메인 전용 컴포넌트
├── types.ts      API 요청/응답 타입
└── index.ts
```
`app/`(라우터·프로바이더), `pages/`(라우트 조립), `shared/`(api 인스턴스·공통 ui·hooks·lib)는 이미 존재한다고 가정하고 재사용.

## 2. API 훅 작성 규칙
- `shared/api`의 axios 인스턴스(토큰 인터셉터 내장) 사용 — 새 axios 인스턴스 생성 금지.
- 목록 API 응답은 `{ content, totalElements, totalPages, number }` — 훅에서 `.content`를 꺼내 배열로 반환. 컴포넌트가 래핑 객체를 직접 다루지 않게 한다.
- 타입 정의는 camelCase로. 백엔드 응답이 다른 케이스면 백엔드에 먼저 질의(임의 변환 로직 추가 금지 — 계약 불일치를 코드로 숨기지 않는다).
- 에러는 `{ code, message, timestamp }` 형태로 옴 — `error.response.data.code`로 분기, `message` 문자열 매칭으로 분기 금지(다국어/문구 변경에 취약).

## 3. SSE 연동
- 설비 상태/센서/알람 등 실시간 데이터는 `EventSource` 기반 SSE 훅으로 구독.
- 연결 실패(브라우저 미지원, 네트워크 오류) 시 3초 간격 폴링으로 자동 폴백 — 폴백 여부를 콘솔 로그로 남겨 디버깅 가능하게.
- 언마운트 시 `EventSource.close()` / 폴링 인터벌 clear 반드시 처리(메모리 누수 방지).

## 4. 디자인 토큰 적용
- `docs/04` §1의 CSS 변수(`--bg-base`, `--bg-card`, `--status-run/idle/down/pm`, `--alarm-warning/major/critical` 등)를 그대로 사용. 하드코딩 헥스값 금지.
- 설비 상태·알람 심각도 색상은 현장 표준이므로 임의 변경 절대 금지 — RUN=초록, DOWN=빨강 등 매핑을 흔들면 안 된다.
- 시각 표시는 항상 KST 변환 후 렌더링(서버는 UTC로 옴).

## 5. 완료 시 보고 형식
팀 리더 + backend-developer + mes-qa에게 아래 내용을 SendMessage:
```
완료: {기능명}
변경 파일: {목록}
API 훅이 기대하는 타입: {필드명 목록}
mock 사용 여부: {있다면 위치 명시}
```

## 참고
전체 컨벤션(FSD 구조, 역할명, 시각 처리 등)은 `frontend-developer` 에이전트 정의(`.claude/agents/frontend-developer.md`)의 "작업 원칙"에 있다 — 이 스킬은 그 원칙을 실행하는 절차다.
