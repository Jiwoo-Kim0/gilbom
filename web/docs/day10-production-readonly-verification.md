# Day 10 운영 비파괴 검증

## 판정 원칙

이 검증은 운영 상태를 바꾸지 않는다. Host·Docker·Release Artifact·Backup을
읽고, 외부 HTTPS 요청과 EC2/AWS Describe 조회만 실행한다. 익명 세션을 만드는
CSRF endpoint GET도 수행하지 않는다.

자동 검증 범위:

- 호스트의 wildcard listen port와 금지된 내부 port
- 실제 EC2에 연결된 Security Group의 Inbound rule
- 기본 Trust Store를 이용한 HTTPS hostname·certificate chain과 만료일
- 비허용 Origin의 CORS 거부
- 서명된 운영 이미지와 활성 `ec2,docker` Profile의 Cookie 정책
- CSP, HSTS와 보안 응답 Header
- `current.sha`, `previous.sha`, Release Manifest
- 실제 Artifact checksum과 Cosign Blob signature 재검증
- Cosign 공개키·Bundle·Archive SHA-256
- 배포 Preflight의 Critical·미승인 High 0 정책
- 최신 Volume backup의 `SHA256SUMS`
- DB·MQTT·Backend·Frontend container health
- deploy 계정 Docker group 미가입
- Jenkins Docker Socket 미마운트와 DinD TLS

S3는 사용하지 않으므로 결과에 `N/A`로 기록한다.

## 설치

운영 EC2의 현재 repository revision에서 실행한다.

```sh
cd /opt/gilbom/app-git/web
sudo sh infra/scripts/install-production-readonly-verifier.sh
```

## 실행

EC2 Instance Profile에 `ec2:DescribeSecurityGroups`가 있고 AWS CLI가 설치된
경우 한 명령으로 실행한다.

```sh
sudo /usr/local/sbin/gilbom-production-readonly-evidence \
  > /tmp/day10-production-readonly.json
jq . /tmp/day10-production-readonly.json
```

Instance Profile을 사용하지 않는다면 AWS Console 또는 관리자 단말에서 현재
EC2에 연결된 정확한 Group ID만 조회한다. 이 명령은 AWS를 변경하지 않는다.

```sh
aws ec2 describe-security-groups \
  --region ap-northeast-2 \
  --group-ids <현재-EC2에-연결된-SG-ID> \
  --output json \
  > gilbom-security-groups.json
```

파일을 EC2의 고정 경로로 올린 뒤 실행한다.

```sh
sudo install -o root -g root -m 0400 \
  /home/ubuntu/gilbom-security-groups.json \
  /tmp/gilbom-security-groups.json

sudo /usr/local/sbin/gilbom-production-readonly-evidence \
  --aws-security-groups /tmp/gilbom-security-groups.json \
  > /tmp/day10-production-readonly.json

jq . /tmp/day10-production-readonly.json
```

`status=PASS`여야 완료다. AWS JSON의 Group ID가 IMDSv2에서 읽은 현재 EC2의
실제 연결 Group과 다르면 실패한다.

## Security Group 정책

- TCP/80, TCP/443만 Internet 전체 공개를 허용한다.
- SSH 22와 MQTT TLS 8883은 필요한 고정 CIDR로만 제한한다.
- PostgreSQL 5432, Backend 8080, Jenkins 8081, Grafana 3001,
  Prometheus 9090, Alertmanager 9093, Loki 3100은 Inbound rule 자체를
  허용하지 않는다.
- 모든 protocol 또는 광범위한 port range의 Internet 공개는 실패한다.

## 수행하지 않는 작업

- 로그인·MFA·계정 잠금 유발
- DB/MQTT/Backend 장애 주입
- Backup 복원
- k6 또는 대용량 부하
- EC2 reboot
- 인증서 갱신·교체
- Security Group 변경

운영 reboot는 유지보수 시간과 별도 승인 아래 독립 작업으로만 수행한다.
