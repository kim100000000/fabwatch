---
name: spring-boot-domain-impl
description: "FabWatch 백엔드(Spring Boot 3.x + JPA + MySQL) 도메인 기능을 구현하는 절차. 엔티티/레포지토리/서비스/컨트롤러/DTO 생성, 도메인 간 이벤트 통신, 상태 머신 구현, 단위 테스트 작성 시 이 순서를 따른다. backend-developer 에이전트가 사용."
---

# Spring Boot 도메인 구현 절차

FabWatch는 모듈러 모놀리스 — 도메인 우선 패키지(`com.fabwatch.{domain}`), 도메인 간 직접 참조 금지가 핵심 제약이다. 이 스킬은 그 제약 안에서 기능 하나를 처음부터 끝까지 구현하는 순서를 제공한다.

## 0. 시작 전 필수 확인
1. `docs/03_기능명세서.md`에서 배정된 기능(F-1~F-7) 섹션을 **직접 Read** — 비즈니스 규칙·예외 케이스는 요약이 아니라 원문 기준으로 구현한다.
2. `docs/05_DB설계서_ERD.md`에서 관련 테이블 정의 확인 — 컬럼명·타입·인덱스·soft delete 컬럼(`deleted_at`) 확인.
3. `docs/06_API명세서.md`에서 관련 엔드포인트 확인 — 경로·요청/응답 shape·권한(ADMIN/ENGINEER/TECHNICIAN)·에러 코드.
4. 기존 코드가 있으면 인접 도메인 패키지 구조를 먼저 Read하고 동일 컨벤션을 따른다 (신규 패턴 도입 금지).

## 1. 패키지 구조

```
com.fabwatch.{domain}/
├── controller/   {Domain}Controller
├── service/      {Domain}Service (+ Impl)
├── repository/   {Domain}Repository (extends JpaRepository)
├── entity/       {Domain} (+ 상태 enum 등)
└── dto/          Request/Response DTO
```

공통 인프라는 `com.fabwatch.common`에 이미 있다고 가정하고 재사용한다:
- `common/exception`: `GlobalExceptionHandler`, `ErrorCode` — 새 에러 코드가 필요하면 여기 추가
- `common/config`: Security, CORS, Scheduler 설정
- `common/util`: 교대 판정(D/N) 등 공통 유틸

## 2. 엔티티 작성 규칙
- `deleted_at` (nullable LocalDateTime) 컬럼 포함 — soft delete용. `@SQLDelete` + `@Where(clause = "deleted_at IS NULL")` 패턴 권장(또는 서비스 레이어에서 일관 필터링).
- 시각 필드는 UTC 저장 전제. `@CreatedDate`/`@LastModifiedDate` 등 Auditing 사용 가능.
- 상태 필드는 enum으로, 전환 허용 로직은 엔티티 또는 서비스의 **한 메서드**에 모아 산재시키지 않는다 (QA가 상태 전이 완전성을 추적하는 지점).

## 3. 도메인 간 통신
- 다른 도메인의 entity/repository를 import하는 순간 규칙 위반. 아래 둘 중 하나만 사용:
  1. **서비스 인터페이스**: 상대 도메인이 `public interface XxxQueryService`를 노출하면 그것만 호출.
  2. **스프링 이벤트**: `ApplicationEventPublisher`로 이벤트 발행 → 상대 도메인이 `@EventListener`로 구독. 예: `equipment`에서 임계치 초과 감지 → `ThresholdExceededEvent` 발행 → `alarm` 모듈이 수신해 알람 생성.
- 새 이벤트를 추가하면 이벤트명 + payload 필드를 팀 리더/관련 팀원에게 SendMessage로 공유(다른 도메인이 구독해야 하므로).

## 4. 컨트롤러/DTO 규칙
- 목록 API 응답: `PageResponse<T> { content, totalElements, totalPages, number }` 공통 래퍼 사용. 배열을 그대로 반환하지 않는다.
- 에러는 절대 컨트롤러에서 직접 `ResponseEntity.badRequest()` 등으로 즉흥 처리하지 않는다 — `GlobalExceptionHandler`가 `ErrorCode` 기반으로 `{code, message, timestamp}` 포맷을 만들도록 예외를 던진다.
- 권한 체크는 `@PreAuthorize` 또는 Security config로 — docs/06의 권한 컬럼(ADMIN/ENGINEER/TECHNICIAN/전체/공개)을 그대로 반영.

## 5. 테스트 (건너뛰지 않는다)
아래 4개 영역 중 이번 기능에 해당하는 것은 반드시 단위 테스트 작성:
- 임계치 판정 로직
- PM 일정 계산
- KPI 계산 (status_log 기반 MTBF/MTTR 등)
- 상태 머신 전환 (허용/비허용 전이 둘 다 케이스로)

`./gradlew test`로 실행 확인 후 완료 보고.

## 6. 완료 시 보고 형식
팀 리더 + frontend-developer + mes-qa에게 아래 내용을 SendMessage:
```
완료: {기능명}
변경 파일: {목록}
API: {메서드} {경로} → 요청 {shape} / 응답 {shape} (docs/06과 다른 부분 있으면 명시)
새 이벤트(있다면): {이벤트명} payload {필드}
테스트: {실행 결과 요약}
```

## 참고
전체 컨벤션(soft delete, 에러 포맷, 역할명, TENANT 주석 등)은 `backend-developer` 에이전트 정의(`.claude/agents/backend-developer.md`)의 "작업 원칙"에 있다 — 이 스킬은 그 원칙을 실행하는 절차다.
