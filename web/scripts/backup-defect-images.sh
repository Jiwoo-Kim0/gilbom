#!/usr/bin/env bash
#
# 결함 이미지와 그 메타데이터를 한 번에 스냅샷한다.
#
# 외부 오브젝트 스토리지를 쓰지 않으므로(2026-08-01 결정) robot_images 볼륨이 JPEG의
# 유일한 사본이다. 그런데 파일 경로·크기·해시는 DB의 storage.robot_image에만 있어서,
# 둘 중 하나만 복원하면 조회가 전부 깨진다.
#
#   DB만 복원  -> 결함과 이미지 메타데이터는 보이는데 파일이 없어 503 S3_UNAVAILABLE
#   볼륨만 복원 -> 파일은 있는데 가리키는 행이 없어 아무 데서도 열리지 않는다
#
# 그래서 이 스크립트는 둘을 항상 같이 뜨고, 개수가 맞는지 확인해 manifest에 남긴다.
#
# 사용법:
#   scripts/backup-defect-images.sh              # ./backups 아래에 생성
#   scripts/backup-defect-images.sh /mnt/backup  # 위치 지정
#
# 이 파일은 개발용 compose.yaml 전용이다. EC2 운영 backup은 writer를 quiesce하고
# 전체 영속 volume과 checksum을 함께 보존하는 infra/scripts/backup-volumes.sh를
# 사용한다. 복원은 RESTORE.md 참고. 스택을 내린 상태에서 해야 한다.

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(dirname "${SCRIPT_DIR}")"
readonly OUTPUT_ROOT="${1:-${PROJECT_DIR}/backups}"

readonly IMAGE_VOLUME="${GILBOM_IMAGE_VOLUME:-gilbom_robot_images}"
readonly DB_SERVICE="${GILBOM_DB_SERVICE:-db}"
readonly DB_NAME="${POSTGRES_DB:-gilbom}"
readonly DB_USER="${POSTGRES_ADMIN_USER:-gilbom_db_admin}"

cd "${PROJECT_DIR}"

if [[ -f runtime.env || -f image.env ]]; then
    echo "!! 운영 split env가 감지됐다." >&2
    echo "   EC2에서는 infra/scripts/backup-volumes.sh를 사용하라." >&2
    exit 1
fi

stamp="$(date +%Y%m%d-%H%M%S)"
target="${OUTPUT_ROOT}/${stamp}"
mkdir -p "${target}"

echo "==> 백업 위치: ${target}"

# --- 1. 볼륨 안의 JPEG -----------------------------------------------------
if ! docker volume inspect "${IMAGE_VOLUME}" >/dev/null 2>&1; then
    echo "!! 볼륨 ${IMAGE_VOLUME}이 없다. GILBOM_IMAGE_VOLUME으로 이름을 지정하라." >&2
    echo "   현재 볼륨 목록:" >&2
    docker volume ls --format '   {{.Name}}' >&2
    exit 1
fi

echo "==> 이미지 볼륨 아카이브"
docker run --rm \
    -v "${IMAGE_VOLUME}:/data:ro" \
    -v "${target}:/out" \
    alpine tar czf /out/robot-images.tar.gz -C /data .

file_count="$(docker run --rm -v "${IMAGE_VOLUME}:/data:ro" alpine \
    sh -c 'find /data -type f -name "*.jpg" | wc -l' | tr -d '[:space:]')"

# --- 2. DB 덤프 ------------------------------------------------------------
# 이미지 메타데이터만 떠도 되지만, 그 행이 참조하는 결함·관측까지 함께 있어야 복원 후
# 화면이 성립한다. 전체 덤프가 작고 확실하다.
echo "==> DB 덤프"
docker compose exec -T "${DB_SERVICE}" \
    pg_dump --username "${DB_USER}" --dbname "${DB_NAME}" --format=custom \
    > "${target}/gilbom.dump"

row_count="$(docker compose exec -T "${DB_SERVICE}" \
    psql --username "${DB_USER}" --dbname "${DB_NAME}" --tuples-only --no-align \
    --command "SELECT count(*) FROM storage.robot_image WHERE image_status = 'AVAILABLE' AND deleted_at IS NULL;" \
    | tr -d '[:space:]')"

# --- 3. manifest -----------------------------------------------------------
# 복원한 뒤 이 숫자와 비교하면 반쪽 복원을 바로 알아챌 수 있다.
cat > "${target}/manifest.txt" <<EOF
생성 시각      : $(date --iso-8601=seconds)
이미지 볼륨    : ${IMAGE_VOLUME}
JPEG 파일 수   : ${file_count}
AVAILABLE 행 수: ${row_count}
DB            : ${DB_NAME} (${DB_USER})
EOF

echo
cat "${target}/manifest.txt"
echo

if [ "${file_count}" != "${row_count}" ]; then
    # 실패로 처리하지 않는다. 업로드 직후·삭제 직후에는 잠깐 어긋날 수 있고,
    # 백업 자체는 이미 떠 있다. 다만 눈에 띄게 남긴다.
    echo "!! 파일 수(${file_count})와 DB 행 수(${row_count})가 다르다." >&2
    echo "   백업은 정상 생성됐다. 복원 전에 어느 쪽이 맞는지 확인하라." >&2
fi

echo "==> 완료: $(du -sh "${target}" | cut -f1)"
