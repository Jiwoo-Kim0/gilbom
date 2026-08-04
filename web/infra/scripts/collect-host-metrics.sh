#!/bin/sh

# Host 파일시스템·container 상태를 Prometheus textfile로 기록한다.
#
# 왜 container 안에서 수집하지 않는가:
#   - unix exporter의 filesystem collector는 host mount point를 container 안에서
#     stat해야 해서 host 루트 파일시스템 마운트를 요구한다. Day 8이 그것을 금지했다.
#   - cAdvisor 계열은 container 이름을 얻으려고 Docker socket을 요구한다. socket은
#     read-only로 마운트해도 Docker API 전체가 열린다.
# 그래서 권한을 이미 가진 host가 값을 만들고, Alloy는 결과 파일을 read-only로 읽기만 한다.

set -eu
set +x

TEXTFILE_DIR="${TEXTFILE_DIR:-/opt/gilbom/monitoring/textfile}"
DOCKER_ROOT="${DOCKER_ROOT:-/var/lib/docker}"
BACKUP_ROOT="${BACKUP_ROOT:-/opt/gilbom/backups}"
ARTIFACT_ROOT="${ARTIFACT_ROOT:-/opt/gilbom/artifacts}"
RELEASE_RECORD_ROOT="${RELEASE_RECORD_ROOT:-/var/lib/gilbom/releases}"
MOUNTPOINTS="${MOUNTPOINTS:-/}"
IMAGE_VOLUME_LABEL="${IMAGE_VOLUME_LABEL:-robot_images}"
# du는 비싸다. 파일시스템·container 상태는 매 실행마다, 경로별 사용량은 이 주기로만 갱신한다.
PATH_USAGE_INTERVAL="${PATH_USAGE_INTERVAL:-1800}"

HOST_FILE="$TEXTFILE_DIR/gilbom-host.prom"
PATH_FILE="$TEXTFILE_DIR/gilbom-host-paths.prom"

fail() {
  echo "Host metric collection failed: $1" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || fail "Docker CLI is required"
umask 022
install -d -m 0755 "$TEXTFILE_DIR"

now="$(date -u +%s)"

# textfile collector는 부분적으로 쓰인 파일도 그대로 읽는다. 임시 파일에 만든 뒤
# 같은 파일시스템 안에서 rename해 원자적으로 교체한다.
publish() {
  target=$1
  source=$2
  chmod 0644 "$source"
  mv "$source" "$target"
}

collect_filesystems() {
  for mountpoint in $MOUNTPOINTS; do
    [ -d "$mountpoint" ] || continue
    df -B1 -P "$mountpoint" \
      | awk -v mount="$mountpoint" 'NR == 2 {
          printf "gilbom_filesystem_size_bytes{mountpoint=\"%s\"} %s\n", mount, $2
          printf "gilbom_filesystem_avail_bytes{mountpoint=\"%s\"} %s\n", mount, $4
        }'
  done
}

collect_containers() {
  ids="$(docker ps --quiet --all)"
  [ -n "$ids" ] || return 0

  # 이름·상태·재시작 횟수·OOM 여부·기동 시각을 한 번의 inspect로 가져온다.
  printf '%s\n' "$ids" \
    | xargs docker inspect \
        --format '{{.Name}} {{.State.Running}} {{.RestartCount}} {{.State.OOMKilled}} {{.State.StartedAt}} {{.HostConfig.RestartPolicy.Name}}' \
    | while read -r name running restarts oom started policy; do
        name="${name#/}"
        case "$name" in
          ""|*[!A-Za-z0-9_.-]*)
            # label에 넣을 수 없는 이름은 건너뛴다. escape 실수로 형식을 깨지 않는다.
            continue
            ;;
        esac
        # restart 정책이 없는 container는 한 번 돌고 끝나는 init 작업이다
        # (alloy-data-init, mosquitto-config-init). 종료돼 있는 것이 정상이라
        # 살아 있는지 감시하면 영구히 발화하는 거짓 알람이 된다.
        [ "$policy" = no ] && continue
        started_seconds="$(date -u -d "$started" +%s 2>/dev/null || echo 0)"
        printf 'gilbom_container_running{name="%s"} %s\n' \
          "$name" "$([ "$running" = true ] && echo 1 || echo 0)"
        printf 'gilbom_container_restart_count{name="%s"} %s\n' \
          "$name" "$restarts"
        printf 'gilbom_container_oom_killed{name="%s"} %s\n' \
          "$name" "$([ "$oom" = true ] && echo 1 || echo 0)"
        printf 'gilbom_container_started_seconds{name="%s"} %s\n' \
          "$name" "$started_seconds"
      done

  # CPU·메모리는 실행 중인 container만 의미가 있다. 백분율로 받아 단위 변환을 피한다.
  docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemPerc}}' 2>/dev/null \
    | while read -r name cpu mem; do
        case "$name" in
          ""|*[!A-Za-z0-9_.-]*) continue ;;
        esac
        printf 'gilbom_container_cpu_percent{name="%s"} %s\n' \
          "$name" "${cpu%\%}"
        printf 'gilbom_container_memory_percent{name="%s"} %s\n' \
          "$name" "${mem%\%}"
      done
}

collect_deploy() {
  # 마지막으로 기록된 release manifest의 수정 시각을 배포 시각으로 본다.
  latest=0
  if [ -d "$RELEASE_RECORD_ROOT" ]; then
    latest="$(
      find "$RELEASE_RECORD_ROOT" \
        -mindepth 2 -maxdepth 2 -name release-manifest.json -type f \
        -printf '%T@\n' 2>/dev/null \
        | cut -d. -f1 \
        | sort -n \
        | tail -n 1
    )"
    [ -n "$latest" ] || latest=0
  fi
  printf 'gilbom_deploy_last_success_seconds %s\n' "$latest"
}

directory_usage() {
  path=$1
  [ -d "$path" ] || return 0
  # -x는 다른 파일시스템으로 넘어가지 않게 한다. 볼륨 안의 bind mount를 두 번 세지 않는다.
  du -sxb "$path" 2>/dev/null | awk '{print $1}'
}

collect_path_usage() {
  docker_usage="$(directory_usage "$DOCKER_ROOT" || true)"
  [ -n "${docker_usage:-}" ] \
    && printf 'gilbom_path_usage_bytes{path="docker"} %s\n' "$docker_usage"

  backup_usage="$(directory_usage "$BACKUP_ROOT" || true)"
  [ -n "${backup_usage:-}" ] \
    && printf 'gilbom_path_usage_bytes{path="backups"} %s\n' "$backup_usage"

  artifact_usage="$(directory_usage "$ARTIFACT_ROOT" || true)"
  [ -n "${artifact_usage:-}" ] \
    && printf 'gilbom_path_usage_bytes{path="artifacts"} %s\n' "$artifact_usage"

  image_volume="$(
    docker volume ls \
      --quiet \
      --filter "label=com.docker.compose.volume=$IMAGE_VOLUME_LABEL" \
      | head -n 1
  )"
  if [ -n "$image_volume" ]; then
    mountpoint="$(
      docker volume inspect --format '{{.Mountpoint}}' "$image_volume" 2>/dev/null || true
    )"
    if [ -n "$mountpoint" ]; then
      image_usage="$(directory_usage "$mountpoint" || true)"
      [ -n "${image_usage:-}" ] \
        && printf 'gilbom_path_usage_bytes{path="robot_images"} %s\n' "$image_usage"
    fi
  fi
  return 0
}

host_tmp="$(mktemp "$TEXTFILE_DIR/.gilbom-host.XXXXXX")"
trap 'rm -f "$host_tmp"' EXIT HUP INT TERM

{
  echo '# HELP gilbom_filesystem_size_bytes Total size of a host filesystem.'
  echo '# TYPE gilbom_filesystem_size_bytes gauge'
  echo '# HELP gilbom_filesystem_avail_bytes Available space on a host filesystem.'
  echo '# TYPE gilbom_filesystem_avail_bytes gauge'
  collect_filesystems
  echo '# HELP gilbom_container_running Whether a Docker container is running.'
  echo '# TYPE gilbom_container_running gauge'
  echo '# HELP gilbom_container_restart_count Docker restart count of a container.'
  echo '# TYPE gilbom_container_restart_count gauge'
  echo '# HELP gilbom_container_oom_killed Whether the last container exit was an OOM kill.'
  echo '# TYPE gilbom_container_oom_killed gauge'
  echo '# HELP gilbom_container_started_seconds Container start time in Unix seconds.'
  echo '# TYPE gilbom_container_started_seconds gauge'
  echo '# HELP gilbom_container_cpu_percent Container CPU usage percentage.'
  echo '# TYPE gilbom_container_cpu_percent gauge'
  echo '# HELP gilbom_container_memory_percent Container memory usage against its limit.'
  echo '# TYPE gilbom_container_memory_percent gauge'
  collect_containers
  echo '# HELP gilbom_deploy_last_success_seconds Newest recorded release manifest time.'
  echo '# TYPE gilbom_deploy_last_success_seconds gauge'
  collect_deploy
  echo '# HELP gilbom_host_metrics_updated_seconds Last successful host metric collection.'
  echo '# TYPE gilbom_host_metrics_updated_seconds gauge'
  printf 'gilbom_host_metrics_updated_seconds %s\n' "$now"
} > "$host_tmp"

publish "$HOST_FILE" "$host_tmp"
trap - EXIT HUP INT TERM

# 경로별 사용량은 du가 비싸서 주기를 따로 둔다. 파일이 충분히 새것이면 건너뛴다.
refresh_paths=true
if [ -f "$PATH_FILE" ]; then
  path_age="$((now - $(stat -c %Y "$PATH_FILE")))"
  [ "$path_age" -lt "$PATH_USAGE_INTERVAL" ] && refresh_paths=false
fi

if [ "$refresh_paths" = true ]; then
  path_tmp="$(mktemp "$TEXTFILE_DIR/.gilbom-host-paths.XXXXXX")"
  trap 'rm -f "$path_tmp"' EXIT HUP INT TERM
  {
    echo '# HELP gilbom_path_usage_bytes Disk usage of a tracked host path.'
    echo '# TYPE gilbom_path_usage_bytes gauge'
    collect_path_usage
  } > "$path_tmp"
  publish "$PATH_FILE" "$path_tmp"
  trap - EXIT HUP INT TERM
fi

echo "Host metric collection: PASS"
