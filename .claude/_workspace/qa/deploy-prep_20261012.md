# QA 리포트 — 배포 준비 통합 검증 (2026-10-12)

> mes-qa 에이전트 결과를 리더가 저장(에이전트는 텍스트로 반환). 코드 수정 없이 검증만 수행.
> 방법: `docker-compose.prod.yml` 전체(caddy+backend+mysql)를 임시 환경(`-p fabwatch-qa`, 임의 .env, 호스트 8088/8443)으로 실제 기동해 종단 검증. 사용자 로컬 환경(fabwatch-mysql 3307, 8080, 5173)은 검증 전후 동일 확인.

## 결론
- **HIGH 0건**, MED 6건, LOW 11건. 기동 순서·Flyway V1·부트스트랩 ADMIN·데모 로더·Caddy 경유 SSE 무버퍼(2초 간격 그대로 도착)·에러 포맷·레이트 리밋(+Retry-After)·위조 XFF 차단·권한·재기동 멱등·브라우저 CSP 위반 0 통과.
- 백엔드 611/611, 프론트 vitest 123/123, tsc/vite build/oxlint 통과.

## MED
- **M-1** docs/13 §4.4 리셋 절차 `$C rm -f mysql`이 실행 중인 mysql을 못 지워 볼륨 삭제 실패(안전하게 실패) → `rm -sf`로 정정(실측 시 재초기화 정상)
- **M-2** `SERVER_FORWARD_HEADERS_STRATEGY` 3중 불일치: compose가 `framework` 강제, application-prod.yml은 native 기본, docs/11 한 문서 안에서 충돌, docs/13 §3.2·§12.3·Caddyfile 주석은 framework 전제. 실측: Caddy 경유는 양쪽 다 우회 불가, backend 직접 호출 시 framework는 XFF 위조로 우회 가능·native는 체인 위조도 차단 → native 통일
- **M-3** 공개 데모 계정 잠금 DoS: tech@ 오답 5회 → 429 ACCOUNT_LOCKED 15분(IP 레이트 리밋으로 못 막음). ADMIN 이메일이 추측 가능하면 관리자도 차단 가능 → 부트스트랩 이메일 추측 불가 값 권고, docs 정정
- **M-4** 공개 ENGINEER 계정이 AI 일일 쿼터(전체 20건)를 몇 초 만에 소진(24회 연속 호출 → 20건 202 후 403) → 사용자별 일일 상한(`AI_USER_DAILY_QUOTA` 기본 5) 추가
- **M-5** 공개 데모 계정 가능 행위/복구 수단 부재: ENGINEER(시나리오 주입·상태 전환·수동 알람·AI), TECHNICIAN(CRITICAL 수동 알람 → 자동 DOWN 실측), 점검 대량 작성(인증 후 쓰기 API엔 레이트 리밋 없음), 즉시 복구는 백업 복구/볼륨 리셋뿐 → 수용 위험으로 docs 명시
- **M-6** 실패한 Flyway 마이그레이션 후 재배포 시 `Detected failed migration` 재시작 루프(이전 이미지 롤백은 가능) → docs/13 §9 절차, deploy.sh 배포 전 자동 백업

## LOW
L-1 docs/13 §3.2 환경변수 표 중복 행 / L-2 `/admin/ai-usage` 미구현인데 문서 서술 / L-3 deploy.sh가 pull·`build --pull` 안 함(문서와 불일치) / L-4 docs/11 '화면에서 비밀번호 변경 권장'(FR-1.3 미구현) / L-5 caddy depends_on 없음(기동 초기 502) / L-6 SSE 연결 있으면 graceful shutdown 20초(기술된 한계) / L-7 타 Origin CORS 403 평문 / L-8 권한 없는 잘못된 본문 400 우선(기존 이월) / L-9 jar의 LocalDataSeeder 상수(@Profile local, 미실행) / L-10 deploy.sh --help 범위·prune 전역 영향 / L-11 첫 배포 판정 볼륨명 하드코딩·up 실패 시 롤백 경로 안 탐. 기타: caddy uid 0(cap_drop ALL+NET_BIND_SERVICE), backend read-only rootfs 아님, Dockerfile jar 글롭이 SNAPSHOT 의존.

## 검증 통과 항목 (요약)
- 이미지: backend uid 10001 비루트, jar에 application-local.yml·fabwatch_local·개발 JWT 키 없음, Flyway·V1 포함. web 이미지는 Caddyfile+정적 파일뿐. .env* 이미지 내 없음
- 번들: 데모 꺼짐 빌드에 fabwatch123/@fabwatch.dev/admin@/localhost:8080 0건, 데모 켬 빌드에 engineer@/tech@만
- 네트워크: mysql은 internal data 네트워크만, host publish는 caddy 80/443뿐, caddy→mysql 불가
- 메모리 실측: backend 344MiB/768MiB, mysql 199MiB/600MiB, caddy 18MiB/96MiB
- 재기동 멱등(데이터 중복 0, 부트스트랩 재실행 없음), 백업→오염→복구 왕복(파일 권한 600/700), 볼륨 삭제 후 재초기화(`rm -sf`)
- deploy.sh/backup-mysql.sh 정독: set -euo pipefail, 변수 인용, 시크릿 비노출 양호

## 검증하지 못한 것
`scripts/deploy.sh` 실제 실행(자동 모드 분류기가 Production Deploy로 차단, 정독·--help·오류 경로만), 실제 EC2·공인 도메인·Let's Encrypt·진짜 클라이언트 IP(Docker Desktop은 가상화 계층 IP로 보임)·IPv6·보안그룹, 실 Claude API(mock으로 쿼터·202만), 30초 heartbeat·장시간 SSE·메모리 부하·백업 cron, 쓰기 API 과다 호출 처리량.
