# Day 10 운영 비파괴 검증

## 판정 원칙

이 검증은 운영 상태를 바꾸지 않는다. Host·Docker·Release Artifact·Backup을
읽고, 외부 HTTPS 요청과 EC2/AWS Describe 조회만 실행한다. 익명 세션을 만드는
CSRF endpoint GET도 수행하지 않는다.

자동 검증 범위:

- 호스트의 wildcard listen port 전체 inventory와 금지된 내부 port
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

### AWS API 인증이 없는 EC2

IAM Instance Profile, AWS CLI 자격증명, 사전 수집한 SG JSON이 모두 없다면
EC2 내부에서 SG Inbound rule을 읽을 수 없다. IMDSv2는 연결된 SG ID까지만
제공하며 실제 rule은 제공하지 않는다.

이 경우 나머지 검증을 중단하지 않고 다음 명령으로 수집한다.

```sh
sudo /usr/local/sbin/gilbom-production-readonly-evidence \
  --skip-aws-security-groups \
  > /tmp/day10-production-readonly.json
jq . /tmp/day10-production-readonly.json
```

결과는 거짓 PASS가 아닌 `status=PARTIAL`이며 다음 두 항목만
`unverifiedItems`에 남는다.

- `AWS_SECURITY_GROUP_INBOUND`
- `EFFECTIVE_EXTERNAL_PORT_REACHABILITY`

나머지 TLS·CORS·Cookie·Header·Release·Cosign·CVE·Backup·Container·
Jenkins/DinD 검증은 정상적으로 PASS/FAIL 판정한다.

## 2026-08-03 운영 실행 결과

- 운영 수집기: `PARTIAL`
- TLS·CORS·Cookie·보안 Header: PASS
- Release SHA·Manifest·Cosign Blob 검증: PASS
- Critical 0·High 0 취약점 정책: PASS
- 최신 암호화 Backup `SHA256SUMS`: PASS
- DB·MQTT·Backend·Frontend health: PASS
- deploy 계정 Docker group 미가입: PASS
- Jenkins Docker Socket 미마운트·DinD TLS: PASS
- S3: N/A

최초 host inventory와 독립 off-host probe에서 Gerrit Code Review 개발용
인증 서비스가 Apache TLS TCP/8989로 외부에 노출된 것을 발견했다. 운영자가
미사용 서비스임을 확인한 뒤 별도 승인 조치로 `httpd.service`와
`gerrit.service`를 중지하고 부팅 자동 시작을 해제했다. 설정과 데이터는
삭제하지 않았다.

조치 후 독립 off-host probe 결과:

- OPEN: 22, 80, 443, 8883
- CLOSED_OR_FILTERED: 3001, 3100, 5432, 8080, 8081, 8988, 8989,
  9090, 9093, 29418
- 예상하지 않은 외부 OPEN port: 없음

따라서 effective TCP 외부 노출 검증은 PASS다. 다만 AWS API 인증이 없어
IMDSv2로 고정한 실제 연결 SG 3개의 Inbound rule source·CIDR·UDP·ICMP
원문은 확인하지 못했다. 전체 상태는 이 한 항목 때문에 PARTIAL이며, 이를
PASS로 바꾸려면 AWS Describe 원문 또는 동등한 제어 영역 증거가 필요하다.

## Security Group 정책

- 호스트 wildcard listener와 SG Inbound의 교집합만 실제 외부 도달 가능
  port로 판정한다. SG에서 막힌 host listener는 증거에 별도로 기록한다.
- TCP/80, TCP/443만 Internet 전체 공개를 허용한다.
- SSH 22와 MQTT TLS 8883은 필요한 고정 CIDR로만 제한한다.
- UDP·ICMP·모든 protocol 인바운드는 허용하지 않는다.
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
