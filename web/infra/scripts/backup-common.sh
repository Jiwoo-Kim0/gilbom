# shellcheck shell=sh
#
# Backup 스크립트가 함께 쓰는 절차다. DB backup과 volume backup이 암호화·checksum·
# manifest·보존기간·metric을 각자 구현하면 한쪽만 고쳐지는 일이 생긴다.
#
# 이 파일은 실행 파일이 아니라 . (dot)으로 읽어 쓴다.

BACKUP_RECIPIENT_KEY_FILE="${BACKUP_RECIPIENT_KEY_FILE:-/opt/gilbom/secrets/backup-recipient.asc}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
BACKUP_TEXTFILE_DIR="${BACKUP_TEXTFILE_DIR:-/opt/gilbom/monitoring/textfile}"

gilbom_backup_fail() {
  echo "$1" >&2
  exit 1
}

# 암호화 없이 반출하지 않는다는 규칙을 스크립트가 강제한다.
gilbom_backup_require_gpg() {
  command -v gpg >/dev/null 2>&1 \
    || gilbom_backup_fail "gpg is required to encrypt backups"
  [ -f "$BACKUP_RECIPIENT_KEY_FILE" ] \
    || gilbom_backup_fail \
      "backup recipient public key is missing: $BACKUP_RECIPIENT_KEY_FILE"
  [ -s "$BACKUP_RECIPIENT_KEY_FILE" ] \
    || gilbom_backup_fail "backup recipient public key is empty"
}

# 수신자 공개키의 지문 목록이다. 파일에 공개키가 여러 개 들어 있으면 전부 돌려준다.
# manifest에 남겨 어느 키들로 잠갔는지 나중에 알 수 있게 한다.
gilbom_backup_recipient_fingerprints() {
  home="$(mktemp -d)"
  chmod 0700 "$home"
  gpg --batch --quiet --no-options --homedir "$home" \
    --import "$BACKUP_RECIPIENT_KEY_FILE" >/dev/null 2>&1 \
    || {
      rm -rf "$home"
      gilbom_backup_fail "backup recipient public key could not be imported"
    }
  # pub 줄 바로 뒤의 fpr만 취한다. subkey 지문까지 넣으면 같은 키를 두 번 지정한다.
  fingerprints="$(
    gpg --batch --quiet --no-options --homedir "$home" \
      --list-keys --with-colons 2>/dev/null \
      | awk -F: '
          $1 == "pub" { want = 1; next }
          $1 == "fpr" && want { print $10; want = 0 }
        '
  )"
  rm -rf "$home"
  [ -n "$fingerprints" ] \
    || gilbom_backup_fail "backup recipient fingerprint could not be read"
  printf '%s\n' "$fingerprints"
}

# 평문을 남기지 않는다. 성공하면 원본을 지운다.
#
# 등록된 공개키 전부를 수신자로 넣는다. 개인키 하나를 잃거나 담당자 한 명이 빠져도
# 나머지 사람이 열 수 있어야 하기 때문이다. 수신자를 늘려도 개인키는 서로 오가지 않는다.
gilbom_backup_encrypt_file() {
  plain=$1
  encrypted="$plain.gpg"
  # 임시 homedir을 쓰는 이유: root의 기본 keyring을 건드리지 않기 위해서다.
  home="$(mktemp -d)"
  chmod 0700 "$home"
  gpg --batch --quiet --no-options --homedir "$home" \
    --import "$BACKUP_RECIPIENT_KEY_FILE" >/dev/null 2>&1 \
    || {
      rm -rf "$home"
      gilbom_backup_fail "backup recipient public key could not be imported"
    }

  set --
  for fingerprint in $(gilbom_backup_recipient_fingerprints)
  do
    set -- "$@" --recipient "$fingerprint"
  done
  [ "$#" -gt 0 ] || {
    rm -rf "$home"
    gilbom_backup_fail "no backup recipient was resolved"
  }

  # 공개키를 방금 직접 넣었으므로 신뢰도 판정은 의미가 없다.
  gpg --batch --yes --quiet --no-options --homedir "$home" \
    --trust-model always \
    "$@" \
    --output "$encrypted" \
    --encrypt "$plain" \
    || {
      rm -rf "$home"
      rm -f "$encrypted"
      gilbom_backup_fail "backup encryption failed: $plain"
    }
  rm -rf "$home"
  [ -s "$encrypted" ] || gilbom_backup_fail "encrypted backup is empty: $encrypted"
  chmod 0600 "$encrypted"
  rm -f "$plain"
  printf '%s\n' "$encrypted"
}

gilbom_backup_checksum() {
  target=$1
  ( cd "$(dirname "$target")" && sha256sum "$(basename "$target")" ) \
    > "$target.sha256"
  chmod 0600 "$target.sha256"
}

# 반출한 사람이 무엇을 받았는지 파일만 보고 확인할 수 있어야 한다.
gilbom_backup_write_manifest() {
  manifest=$1
  backup_name=$2
  fingerprint=$3
  shift 3

  created="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  {
    printf '{\n'
    printf '  "backup": "%s",\n' "$backup_name"
    printf '  "createdAt": "%s",\n' "$created"
    printf '  "host": "%s",\n' "$(hostname)"
    printf '  "encryption": "gpg-public-key",\n'
    printf '  "recipientFingerprints": "%s",\n' \
      "$(printf '%s' "$fingerprint" | tr '\n' ' ' | sed 's/ *$//')"
    printf '  "files": [\n'
    first=true
    for artifact in "$@"; do
      [ -f "$artifact" ] || continue
      if [ "$first" = true ]; then
        first=false
      else
        printf ',\n'
      fi
      printf '    {\n'
      printf '      "name": "%s",\n' "$(basename "$artifact")"
      printf '      "sizeBytes": %s,\n' "$(stat -c %s "$artifact")"
      printf '      "sha256": "%s"\n' \
        "$(sha256sum "$artifact" | awk '{print $1}')"
      printf '    }'
    done
    printf '\n  ]\n'
    printf '}\n'
  } > "$manifest"
  chmod 0600 "$manifest"
}

# 로컬 보존기간을 넘긴 번들을 지운다. 반출본은 별도 보관이므로 여기서 다루지 않는다.
gilbom_backup_prune() {
  directory=$1
  pattern=$2
  [ -d "$directory" ] || return 0
  find "$directory" \
    -maxdepth 1 \
    -type f \
    -name "$pattern" \
    -mtime "+$BACKUP_RETENTION_DAYS" \
    -delete 2>/dev/null || true
}

# Volume backup은 파일 하나가 아니라 번들 디렉터리를 만든다.
gilbom_backup_prune_directories() {
  directory=$1
  pattern=$2
  [ -d "$directory" ] || return 0
  find "$directory" \
    -mindepth 1 \
    -maxdepth 1 \
    -type d \
    -name "$pattern" \
    -mtime "+$BACKUP_RETENTION_DAYS" \
    -exec rm -rf {} + 2>/dev/null || true
}

gilbom_backup_previous_success() {
  metric_file=$1
  [ -f "$metric_file" ] || return 0
  awk '/^gilbom_backup_last_success_seconds/ { print $2; exit }' "$metric_file"
}

# 성공·실패를 모두 기록한다. 실패했다고 metric을 안 쓰면 "조용한 실패"가 된다.
# 반대로 실패가 직전 성공 시각을 덮어써도 안 되므로 이전 값을 이어받는다.
gilbom_backup_record_result() {
  backup_name=$1
  status=$2
  duration=$3
  size=${4:-0}

  [ -d "$BACKUP_TEXTFILE_DIR" ] || return 0
  metric_file="$BACKUP_TEXTFILE_DIR/gilbom-backup-$backup_name.prom"
  now="$(date -u +%s)"

  if [ "$status" -eq 1 ]; then
    last_success="$now"
  else
    last_success="$(gilbom_backup_previous_success "$metric_file")"
  fi

  temporary="$(mktemp "$BACKUP_TEXTFILE_DIR/.gilbom-backup.XXXXXX")"
  {
    echo '# HELP gilbom_backup_last_success_seconds Last successful backup time.'
    echo '# TYPE gilbom_backup_last_success_seconds gauge'
    if [ -n "$last_success" ]; then
      printf 'gilbom_backup_last_success_seconds{backup="%s"} %s\n' \
        "$backup_name" "$last_success"
    fi
    echo '# HELP gilbom_backup_last_status Result of the most recent backup run.'
    echo '# TYPE gilbom_backup_last_status gauge'
    printf 'gilbom_backup_last_status{backup="%s"} %s\n' "$backup_name" "$status"
    echo '# HELP gilbom_backup_last_duration_seconds Duration of the most recent run.'
    echo '# TYPE gilbom_backup_last_duration_seconds gauge'
    printf 'gilbom_backup_last_duration_seconds{backup="%s"} %s\n' \
      "$backup_name" "$duration"
    echo '# HELP gilbom_backup_last_size_bytes Size of the most recent backup bundle.'
    echo '# TYPE gilbom_backup_last_size_bytes gauge'
    printf 'gilbom_backup_last_size_bytes{backup="%s"} %s\n' "$backup_name" "$size"
  } > "$temporary"
  chmod 0644 "$temporary"
  mv "$temporary" "$metric_file"
}

gilbom_backup_directory_size() {
  target=$1
  if [ -d "$target" ]; then
    du -sb "$target" 2>/dev/null | awk '{print $1}'
  elif [ -f "$target" ]; then
    stat -c %s "$target"
  else
    echo 0
  fi
}
