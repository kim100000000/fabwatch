#!/usr/bin/env bash
# FabWatch 배포 스크립트 — EC2 에서 실행 (docs/13 §4, 롤백 §9)
#
#   ./scripts/deploy.sh                  현재 브랜치를 pull → 배포 전 DB 백업 → 이미지 빌드(--pull) → 재기동 → 헬스 확인 (실패 시 자동 롤백)
#   ./scripts/deploy.sh --no-pull        git pull 생략 (이미 코드를 받아 둔 경우)
#   ./scripts/deploy.sh --skip-backup    배포 전 자동 백업 생략 (백업 자체가 고장났을 때만. 첫 배포는 원래 백업하지 않음)
#   ./scripts/deploy.sh --rollback       직전 성공 배포의 이미지 태그로 되돌림 (빌드 없음)
#   ./scripts/deploy.sh --rollback <태그>  지정한 이미지 태그로 되돌림 (docker images fabwatch-web 로 목록 확인)
#
# 이미지 태그 = git 커밋 해시(짧게). 배포마다 새 태그가 생기고 이전 태그 이미지는 로컬에 남아 있어 즉시 롤백 가능하다.
# 상태 파일: .deploy-state/current_tag, .deploy-state/previous_tag (git 무시)
#
# 배포 전 백업: mysql 이 이미 떠 있으면 scripts/backup-mysql.sh 를 먼저 실행하고, 실패하면 배포를 중단한다(마이그레이션 실패 대비, docs/13 §9).
# 이 서버는 FabWatch 전용 전제 — 정리 단계의 `docker image prune -f` 는 호스트 전체의 태그 없는(dangling) 이미지를 지운다.
#
# 주의: DB 스키마(Flyway)는 앞으로만 간다. 코드 롤백은 "새 스키마와 호환되는 이전 코드"일 때만 안전하다 → 컬럼 삭제·이름 변경은
#       두 배포에 걸쳐 나눈다(docs/13 §9). 스키마까지 되돌려야 하면 백업 복구 절차(docs/13 §9)를 따른다.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
ENV_FILE="${ENV_FILE:-.env.prod}"
COMPOSE_FILE="docker-compose.prod.yml"
STATE_DIR=".deploy-state"
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-240}"   # backend 기동(JPA·Flyway) + 인증서 발급 여유
KEEP_IMAGES="${KEEP_IMAGES:-3}"                   # 서비스별로 남겨 둘 이미지 태그 수(현재·직전 포함)

compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }
log() { printf '[deploy %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

DO_PULL=1
SKIP_BACKUP=0
ROLLBACK=0
ROLLBACK_TAG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-pull) DO_PULL=0 ;;
    --skip-backup) SKIP_BACKUP=1 ;;
    --rollback) ROLLBACK=1; if [ $# -gt 1 ] && [ "${2#--}" = "$2" ]; then ROLLBACK_TAG="$2"; shift; fi ;;
    -h|--help) awk 'NR>1 { if ($0 ~ /^#/) print; else exit }' "$0"; exit 0 ;;
    *) die "알 수 없는 옵션: $1" ;;
  esac
  shift
done

# ---- 사전 점검 --------------------------------------------------------------
command -v docker >/dev/null || die "docker 가 없습니다"
docker compose version >/dev/null 2>&1 || die "docker compose 플러그인이 없습니다"
[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없습니다. deploy/env.prod.example 을 복사해 값을 채우세요 (docs/13 §3)"

perm="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE" 2>/dev/null || echo '?')"
case "$perm" in 600|400) ;; *) log "경고: $ENV_FILE 권한이 $perm 입니다 → chmod 600 $ENV_FILE 권장" ;; esac

mkdir -p "$STATE_DIR"
PREV_TAG="$(cat "$STATE_DIR/current_tag" 2>/dev/null || true)"

# 값을 읽을 때 export 하지 않고 grep 으로만 확인한다(시크릿을 셸 환경에 올리지 않음)
env_value() { grep -E "^$1=" "$ENV_FILE" | tail -n1 | cut -d= -f2- || true; }

# ---- 헬스 확인 ---------------------------------------------------------------
container_health() { # $1=서비스명 → healthy|starting|unhealthy|none
  local cid
  cid="$(compose ps -q "$1" 2>/dev/null | head -n1)"
  [ -n "$cid" ] || { echo none; return; }
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo none
}

wait_healthy() {
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SEC )) s_mysql s_backend s_caddy
  while [ "$(date +%s)" -lt "$deadline" ]; do
    s_mysql="$(container_health mysql)"; s_backend="$(container_health backend)"; s_caddy="$(container_health caddy)"
    if [ "$s_mysql" = healthy ] && [ "$s_backend" = healthy ] && [ "$s_caddy" = healthy ]; then return 0; fi
    log "대기 중... mysql=$s_mysql backend=$s_backend caddy=$s_caddy"
    sleep 5
  done
  return 1
}

# 프록시 경유 종단 확인: /healthz → backend /actuator/health. 도메인 모드는 이 서버에서 직접 접속(--resolve)해 DNS/hairpin 에 의존하지 않는다.
smoke_test() {
  local domain; domain="$(env_value DOMAIN)"
  local out
  if [ -z "$domain" ]; then log "DOMAIN 이 비어 있어 종단 확인을 건너뜁니다"; return 0; fi
  case "$domain" in
    :*|http://*) out="$(curl -fsS --max-time 10 "http://127.0.0.1/healthz" 2>&1)" || { log "종단 확인 실패: $out"; return 1; } ;;
    *)           out="$(curl -fsS --max-time 15 -k --resolve "$domain:443:127.0.0.1" "https://$domain/healthz" 2>&1)" || { log "종단 확인 실패(인증서 발급 중일 수 있음): $out"; return 1; } ;;
  esac
  case "$out" in *'"status":"UP"'*) log "종단 확인 OK: $out" ;; *) log "종단 확인 응답 이상: $out"; return 1 ;; esac
}

# ---- 롤백 --------------------------------------------------------------------
# 실패 시 exit 하지 않고 return 1 — 자동 롤백 경로에서 호출자가 마지막 안내 메시지를 낼 수 있게 한다
do_rollback_to() { # $1=태그
  local tag="$1"
  docker image inspect "fabwatch-backend:$tag" >/dev/null 2>&1 || { log "이미지 fabwatch-backend:$tag 가 로컬에 없습니다 (docker images fabwatch-backend)"; return 1; }
  docker image inspect "fabwatch-web:$tag" >/dev/null 2>&1 || { log "이미지 fabwatch-web:$tag 가 로컬에 없습니다 (docker images fabwatch-web)"; return 1; }
  log "이미지 태그 $tag 로 롤백합니다 (빌드 없음)"
  export IMAGE_TAG="$tag"
  compose up -d --no-build --remove-orphans
  if wait_healthy && smoke_test; then
    printf '%s\n' "$tag" > "$STATE_DIR/current_tag"
    log "롤백 완료: $tag"
  else
    log "롤백 후에도 헬스 확인에 실패했습니다. 원인 확인: docker compose --env-file $ENV_FILE -f $COMPOSE_FILE logs --tail=100 backend"
    return 1
  fi
}

if [ "$ROLLBACK" -eq 1 ]; then
  target="${ROLLBACK_TAG:-$(cat "$STATE_DIR/previous_tag" 2>/dev/null || true)}"
  [ -n "$target" ] || die "되돌릴 이전 태그를 모릅니다. 태그를 직접 지정하세요: ./scripts/deploy.sh --rollback <태그>"
  [ "$target" != "$PREV_TAG" ] || log "경고: 롤백 대상이 현재 태그($PREV_TAG)와 같습니다"
  do_rollback_to "$target" || exit 1
  if [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$target" ]; then printf '%s\n' "$PREV_TAG" > "$STATE_DIR/previous_tag"; fi
  exit 0
fi

# ---- 배포 --------------------------------------------------------------------
if [ "$DO_PULL" -eq 1 ]; then
  log "git pull --ff-only ($(git rev-parse --abbrev-ref HEAD))"
  git pull --ff-only
fi

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  die "작업 트리에 커밋되지 않은 변경이 있습니다. 서버에서 직접 수정하지 말고 되돌리세요 (git status)"
fi

NEW_TAG="$(git rev-parse --short=10 HEAD)"
[ -f backend/Dockerfile ] || die "backend/Dockerfile 이 없습니다"

# 공개 데모 설정 점검: 공개 비밀번호(번들에 노출됨)와 운영 ADMIN 비밀번호는 달라야 한다
DEMO_PW="$(env_value DEMO_ACCOUNTS_PASSWORD)"
if [ -n "$DEMO_PW" ] && [ "$DEMO_PW" = "$(env_value BOOTSTRAP_ADMIN_PASSWORD)" ]; then
  die "DEMO_ACCOUNTS_PASSWORD 가 BOOTSTRAP_ADMIN_PASSWORD 와 같습니다. 데모 비밀번호는 번들(공개 JS)에 노출되므로 반드시 다르게 하세요"
fi
if [ "$(env_value VITE_DEMO_LOGIN)" = "true" ] && [ -z "$DEMO_PW" ]; then
  log "경고: VITE_DEMO_LOGIN=true 이지만 DEMO_ACCOUNTS_PASSWORD 가 비어 있어 데모 버튼이 나오지 않습니다(운영 빌드는 비밀번호 없이 데모를 켜지 않음)"
fi

log "설정 검증 (docker compose config)"
IMAGE_TAG="$NEW_TAG" compose config -q || die "compose 설정 오류 — $ENV_FILE 의 필수 값을 확인하세요"

# compose 프로젝트명(docker-compose.prod.yml 의 name:)을 설정에서 읽어 볼륨 존재를 라벨로 판정한다 — 이름을 하드코딩하지 않는다
PROJECT="$(IMAGE_TAG="$NEW_TAG" compose config 2>/dev/null | sed -n 's/^name: //p' | head -n1)"
[ -n "$PROJECT" ] || die "compose 프로젝트명을 읽지 못했습니다"
FIRST_DEPLOY=0
if [ -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.volume=mysql_data")" ]; then
  FIRST_DEPLOY=1
fi

# 첫 배포(DB 볼륨 없음)면 운영 ADMIN 부트스트랩 값이 있어야 로그인할 계정이 생긴다
if [ "$FIRST_DEPLOY" -eq 1 ]; then
  if [ -z "$(env_value BOOTSTRAP_ADMIN_EMAIL)" ] || [ -z "$(env_value BOOTSTRAP_ADMIN_PASSWORD)" ]; then
    die "첫 배포입니다. $ENV_FILE 에 BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD 가 필요합니다 (로그인할 ADMIN 계정이 생성되지 않음)"
  fi
  log "첫 배포: 빈 DB 에서 시작합니다 (배포 전 백업 생략)"
fi

# 배포 전 자동 백업 — 새 마이그레이션(Flyway)이 중간에 실패해도 되돌릴 시점이 있게 한다. mysql 이 실행 중일 때만(첫 배포 제외).
if [ "$FIRST_DEPLOY" -eq 0 ] && [ "$SKIP_BACKUP" -eq 0 ]; then
  mysql_cid="$(compose ps -q mysql 2>/dev/null | head -n1)"
  if [ -n "$mysql_cid" ] && [ "$(docker inspect -f '{{.State.Running}}' "$mysql_cid" 2>/dev/null)" = "true" ]; then
    log "배포 전 DB 백업 (scripts/backup-mysql.sh)"
    ENV_FILE="$ENV_FILE" "$ROOT/scripts/backup-mysql.sh" || die "배포 전 백업에 실패해 배포를 중단합니다. 원인을 해결하거나 (백업이 고장난 경우에만) --skip-backup 으로 우회하세요"
  else
    log "경고: mysql 이 실행 중이 아니어서 배포 전 백업을 건너뜁니다"
  fi
elif [ "$SKIP_BACKUP" -eq 1 ]; then
  log "경고: --skip-backup — 배포 전 백업을 생략합니다"
fi

log "이미지 빌드: 태그 $NEW_TAG (이전: ${PREV_TAG:-없음}) — 베이스 이미지 최신화(--pull)"
IMAGE_TAG="$NEW_TAG" compose build --pull

# up 자체가 실패하는 경우(의존 서비스 unhealthy 로 caddy 를 못 올림 등)도 헬스 실패와 같은 롤백 경로를 탄다
log "서비스 재기동"
if IMAGE_TAG="$NEW_TAG" compose up -d --remove-orphans && wait_healthy && smoke_test; then
  printf '%s\n' "$NEW_TAG" > "$STATE_DIR/current_tag"
  if [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$NEW_TAG" ]; then printf '%s\n' "$PREV_TAG" > "$STATE_DIR/previous_tag"; fi
  log "배포 성공: $NEW_TAG"
else
  log "배포 실패 — 최근 backend 로그:"
  compose logs --tail=60 backend || true
  if [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$NEW_TAG" ]; then
    log "이전 태그 $PREV_TAG 로 자동 롤백을 시도합니다"
    if do_rollback_to "$PREV_TAG"; then
      die "새 배포($NEW_TAG)는 실패했고 $PREV_TAG 로 되돌렸습니다. 원인 수정 후 다시 배포하세요 (마이그레이션이 실패했다면 docs/13 §9 'Flyway 실패 후 재배포' — 재배포 전에 실패 행 정리가 필요합니다)"
    fi
    die "새 배포($NEW_TAG)도, 이전($PREV_TAG)으로의 자동 롤백도 실패했습니다. 수동 점검 필요: docker compose --env-file $ENV_FILE -f $COMPOSE_FILE ps / logs (DB 스키마가 앞서 있다면 docs/13 §9 백업 복구)"
  fi
  die "새 배포($NEW_TAG)가 실패했고 되돌릴 이전 배포가 없습니다 (첫 배포). 로그: docker compose --env-file $ENV_FILE -f $COMPOSE_FILE logs backend"
fi

# ---- 정리 --------------------------------------------------------------------
# 태그 없는 중간 레이어는 지우고, 서비스별 이미지는 최근 KEEP_IMAGES 개(+현재·직전 태그)만 남긴다.
docker image prune -f >/dev/null 2>&1 || true
CUR_TAG="$(cat "$STATE_DIR/current_tag")"; OLD_TAG="$(cat "$STATE_DIR/previous_tag" 2>/dev/null || true)"
for repo in fabwatch-backend fabwatch-web; do
  # docker images 는 생성 시각 내림차순 → KEEP_IMAGES 번째 이후가 오래된 것
  docker images "$repo" --format '{{.Tag}}' | awk -v n="$KEEP_IMAGES" 'NR>n' | while read -r tag; do
    if [ "$tag" = "$CUR_TAG" ] || [ "$tag" = "$OLD_TAG" ] || [ "$tag" = "<none>" ]; then continue; fi
    docker rmi "$repo:$tag" >/dev/null 2>&1 || true
  done
done
log "완료. 상태: docker compose --env-file $ENV_FILE -f $COMPOSE_FILE ps"
