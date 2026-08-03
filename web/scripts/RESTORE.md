# 결함 이미지·DB 복원 절차

`scripts/backup-defect-images.sh`가 만든 스냅샷을 되돌린다.

> 이 문서는 개발용 `compose.yaml`과 `gilbom_robot_images` volume 전용이다.
> `/opt/gilbom/deploy` 운영 환경에는 적용하지 않는다. 운영 복원은
> `infra/scripts/backup-volumes.sh`가 만든 전체 bundle을 대상으로 별도 승인 후
> 수행한다.
>
> **운영 backup이 실제로 복원되는지 확인하는 것은 `scripts/restore-drill.sh`다.**
> 운영 stack을 건드리지 않고 임시 PostgreSQL과 임시 volume에 복원한 뒤 스키마·
> Flyway version·주요 데이터·TOTP key version·이미지 SHA-256·MQTT credential까지
> 검사한다. 절차는
> [day10-observability-backup-runbook.md](../docs/day10-observability-backup-runbook.md) 6절에 있다.

**둘을 항상 같이 복원한다.** 한쪽만 되돌리면 화면이 깨지는데, 그 방식이 눈에 잘 안 띈다.

| 복원한 것 | 증상 |
| --- | --- |
| DB만 | 결함 목록·지도는 정상. 사진만 전부 `503 S3_UNAVAILABLE` |
| 볼륨만 | 파일은 있는데 참조하는 행이 없어 어디서도 열리지 않는다 |

---

## 준비

```bash
cd web
ls backups/                     # 스냅샷 목록
cat backups/<타임스탬프>/manifest.txt   # 파일 수·행 수 확인
```

`manifest.txt`의 두 숫자를 적어둔다. 복원 후 검증에 쓴다.

## 1. 스택을 내린다

```bash
docker compose down
```

`-v`를 붙이지 않는다. 붙이면 지금 있는 볼륨까지 지워져 되돌릴 수 없다.

## 2. 이미지 볼륨 복원

```bash
SNAPSHOT="$PWD/backups/<타임스탬프>"

docker volume rm gilbom_robot_images        # 없으면 오류가 나도 무시
docker volume create gilbom_robot_images

docker run --rm \
    -v gilbom_robot_images:/data \
    -v "$SNAPSHOT:/in:ro" \
    alpine tar xzf /in/robot-images.tar.gz -C /data
```

## 3. DB 복원

DB만 먼저 띄운다.

```bash
docker compose up -d db
docker compose exec -T db pg_isready --username gilbom_db_admin --dbname gilbom
```

`--clean`은 기존 객체를 지우고 덮어쓴다. 운영 데이터가 들어 있는 DB에 그대로 쓰지 않는다.

```bash
docker compose exec -T db \
    pg_restore --username gilbom_db_admin --dbname gilbom --clean --if-exists \
    < "$SNAPSHOT/gilbom.dump"
```

## 4. 검증 — manifest와 대조

```bash
docker run --rm -v gilbom_robot_images:/data:ro alpine \
    sh -c 'find /data -type f -name "*.jpg" | wc -l'

docker compose exec -T db psql --username gilbom_db_admin --dbname gilbom \
    --tuples-only --no-align --command \
    "SELECT count(*) FROM storage.robot_image WHERE image_status = 'AVAILABLE' AND deleted_at IS NULL;"
```

두 값이 `manifest.txt`와 같아야 한다. **다르면 반쪽 복원이므로 다시 한다.**

## 5. 나머지 기동 후 화면 확인

```bash
docker compose up -d
```

결함 카드를 하나 열어 사진이 뜨는지 본다. 여기까지 봐야 복원이 끝난 것이다.
숫자만 맞고 파일이 깨져 있을 수도 있다.

---

## 서명 키 주의

이미지 조회 URL은 `GILBOM_STORAGE_URL_SIGNING_KEY_BASE64`로 서명한다. 이 값을 `.env`나
Docker secret으로 고정해두지 않으면 **기동할 때마다 임시 키가 생성되고, 이전에 발급된
URL이 전부 무효가 된다.** 복원 자체와는 무관하지만, "복원했는데 사진이 안 뜬다"의 원인이
여기일 수 있다. 화면을 새로고침해 URL을 다시 발급받으면 된다.
