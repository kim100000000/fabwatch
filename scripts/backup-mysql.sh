#!/usr/bin/env bash
# FabWatch MySQL 일일 백업 — mysqldump → gzip → N일 보관 (docs/13 §8)
#
#   ./scripts/backup-mysql.sh
#
# cron 예시 (EC2 의 ubuntu 사용자 crontab -e). 서버 시계는 UTC 이므로 18:15 UTC = 03:15 KST (사용량이 가장 적은 새벽):
#   15 18 * * *  /opt/fabwatch/scripts/backup-mysql.sh >> /opt/fabwatch/backups/backup.log 2>&1
#
# 동작
#   - mysql 컨테이너 안에서 root 자격(MYSQL_ROOT_PASSWORD, 컨테이너 환경변수)으로 mysqldump 를 실행한다.
#     비밀번호는 명령행이 아니라 MYSQL_PWD 환경변수로 넘겨 ps 에 노출되지 않는다. 이 스크립트는 .env.prod 를 읽지 않는다.
#   - --single-transaction: InnoDB 를 잠그지 않고 일관된 스냅샷을 뜬다 (서비스 중단 없음).
#   - 센서 원본(sensor_data)은 7일 보관·시뮬레이터 생성 데이터라 기본적으로 "구조만" 백업한다(용량·시간 절감). 1분 집계(sensor_data_1m)와
#     나머지 테이블은 전부 포함. 원본까지 받으려면 BACKUP_EXCLUDE_RAW=false.
#   - 임시 파일에 쓰고 gzip 무결성·최소 크기 검증을 통과해야 최종 이름으로 바꾼다 → 실패한 덤프가 정상 백업처럼 남지 않는다.
#   - BACKUP_KEEP_DAYS(기본 7)일보다 오래된 백업은 삭제한다.
#
# 한계: 같은 EC2 디스크에 쌓이므로 인스턴스/볼륨을 잃으면 같이 사라진다. 오프사이트 사본(S3 등)이나 EBS 스냅샷을 병행한다(docs/13 §8).

set -euo pipefail
umask 077   # 덤프에는 사용자 정보·해시가 들어 있다 — 소유자만 읽기

PATH="/usr/local/bin:/usr/bin:/bin:$PATH"   # cron 의 최소 PATH 보완
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-.env.prod}"
COMPOSE_FILE="docker-compose.prod.yml"
BACKUP_DIR="${BACKUP_DIR:-$(pwd)/backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-7}"
EXCLUDE_RAW="${BACKUP_EXCLUDE_RAW:-true}"
MIN_BYTES="${BACKUP_MIN_BYTES:-1024}"   # 이보다 작으면 실패한 덤프로 본다

log() { printf '[backup %s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

mkdir -p "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%d_%H%M%S)"        # 파일명은 UTC 기준
FINAL="$BACKUP_DIR/fabwatch_${STAMP}.sql.gz"
TMP="$FINAL.partial"
trap 'rm -f "$TMP"' EXIT

compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }

# 컨테이너 안에서 실행되는 스크립트 (따옴표로 감싸 호스트 셸이 변수를 확장하지 않게 한다)
REMOTE_SCRIPT='
set -e
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
COMMON="-uroot --single-transaction --quick --routines --triggers --events --default-character-set=utf8mb4 --set-gtid-purged=OFF"
if [ "$EXCLUDE_RAW" = "true" ]; then
  # 1) 원본 테이블을 뺀 모든 데이터 + 구조  2) 원본 테이블은 구조(DDL)만
  mysqldump $COMMON --databases "$MYSQL_DATABASE" --ignore-table="$MYSQL_DATABASE.sensor_data"
  mysqldump -uroot --no-data --skip-triggers --default-character-set=utf8mb4 --set-gtid-purged=OFF "$MYSQL_DATABASE" sensor_data
else
  mysqldump $COMMON --databases "$MYSQL_DATABASE"
fi
'

log "시작 (DB 컨테이너 mysqldump, 원본 센서 제외=$EXCLUDE_RAW)"
# -T: TTY 없이(크론 호환). pipefail 로 mysqldump 실패가 gzip 성공에 가려지지 않는다.
compose exec -T -e EXCLUDE_RAW="$EXCLUDE_RAW" mysql sh -c "$REMOTE_SCRIPT" | gzip -9 > "$TMP"

gzip -t "$TMP" || { log "ERROR: gzip 무결성 검사 실패"; exit 1; }
SIZE="$(wc -c < "$TMP" | tr -d ' ')"
[ "$SIZE" -ge "$MIN_BYTES" ] || { log "ERROR: 덤프가 비정상적으로 작음(${SIZE}B)"; exit 1; }
# 덤프가 끝까지 기록됐는지 (mysqldump 는 정상 종료 시 마지막에 이 줄을 쓴다)
gzip -dc "$TMP" | tail -n 5 | grep -q "Dump completed" || { log "ERROR: 덤프 끝 표식(Dump completed) 없음 — 중간에 끊긴 덤프"; exit 1; }

mv "$TMP" "$FINAL"
trap - EXIT
log "완료: $FINAL (${SIZE} bytes)"

# 보관 기간 초과분 삭제 (정상 백업이 만들어진 뒤에만 지운다)
DELETED="$(find "$BACKUP_DIR" -maxdepth 1 -name 'fabwatch_*.sql.gz' -type f -mmin "+$((KEEP_DAYS * 1440))" -print -delete | wc -l | tr -d ' ')"
log "정리: ${KEEP_DAYS}일 초과 백업 ${DELETED}개 삭제, 남은 백업 $(find "$BACKUP_DIR" -maxdepth 1 -name 'fabwatch_*.sql.gz' | wc -l | tr -d ' ')개"
