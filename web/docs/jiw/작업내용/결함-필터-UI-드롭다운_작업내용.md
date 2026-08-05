# 결함 필터 UI — 드롭다운 전환과 화면 배치 재구성 작업내용

기간: 2026-08-05
범위: 결함 관리 화면 필터 UI와 배치. 조회 계약·서버는 그대로다.
두 차례로 나눠 진행했다. §1~6이 1차(드롭다운 전환, 종료 날짜 제거),
§7이 2차(검색 분리, 필터를 지도 오른쪽으로, 기간 조건 제거)다.

관련 문서: [결함-목록-지도-상세화면_작업내용.md](결함-목록-지도-상세화면_작업내용.md),
[결함-API와-SSE_작업내용.md](결함-API와-SSE_작업내용.md)

---

## 1. 배경

필터 패널이 세로로 너무 길었다. 처리 상태 6개 + 결함 유형 4개 + 심각도 4개, **체크박스
14개가 모두 펼쳐져 있어** 사이드바 아래쪽의 "최소 AI 신뢰도" 슬라이더와 "필터 초기화"가
스크롤 없이는 보이지 않았다. 지도·목록보다 필터가 긴 화면이 됐다.

종료 날짜 입력도 실효가 없었다. 결함은 미래에 생기지 않으니 상한은 늘 "지금"이고,
프리셋 버튼("오늘", "지난 7일", "지난 30일")은 그 "지금"을 눌린 순간의 값으로 못박아
넣고 있었다.

---

## 2. 무엇을 바꿨나

| 파일 | 변경 |
| --- | --- |
| `DefectFilterDropdown.tsx` | **신규.** 다중 선택 조건 하나를 접어두는 드롭다운 |
| `DefectFilter.tsx` | 종료 입력 삭제, 체크박스 3묶음 → 드롭다운 3개, `rangeFor`가 `to`를 비움 |
| `DefectManagePage.tsx` | URL에서 `to`를 읽지도 쓰지도 않는다 |
| `defectTypes.ts` | `DefectFilters.to`에 "필터 패널은 노출하지 않는다" 주석 |
| `App.css` | 드롭다운 트리거·패널·개수 배지 스타일, 쓰지 않게 된 체크박스 인접 규칙 삭제 |

검증: 프론트 `tsc -b`·`eslint` 통과, 단위 테스트 44 pass / 0 fail.
Playwright로 실제 화면을 띄워 접힘·펼침·체크·바깥 클릭 닫힘과 URL 반영까지 확인했다
(§5의 임시 스펙, 커밋하지 않음).

---

## 3. 설계 판단과 그 이유

### 3.1 조건 셋만 접었다. 검색·날짜·신뢰도는 그대로 뒀다

접는 이유는 "길이"뿐이므로, 한 줄이면 끝나는 컨트롤을 굳이 클릭 뒤로 숨기지 않았다.
드롭다운으로 바꾼 것은 항목이 여럿인 다중 선택 셋(처리 상태·결함 유형·심각도)뿐이다.

### 3.2 닫힌 상태에서도 무엇이 걸려 있는지 보인다

접기의 대가는 "지금 무슨 조건이 걸렸는지 안 보인다"다. 트리거 버튼에 요약을 남겨 그것을
막았다.

- 선택 없음 → `전체` (빈 배열은 조건 없음, 즉 전체 조회다)
- 1~2개 → 라벨을 이어붙임 (`신규, 접수`)
- 3개 이상 → `신규 외 3개` + 오른쪽에 개수 배지

첫 진입 기본값이 `NEW·ACCEPTED·IN_PROGRESS` 셋이므로, 열자마자 트리거에 `신규 외 2개`와
배지 `3`이 보인다. **종결 결함이 기본으로 빠져 있다는 사실이 접힌 채로도 드러난다.**

### 3.3 선택 상태는 여전히 URL이 소유한다

드롭다운은 **열림 여부만** 자기 `useState`로 갖는다. 체크 결과는 그대로 부모의
`onChange` → URL query다. 링크 공유와 새로고침 유지가 깨지지 않는다.

체크할 때마다 URL이 바뀌어 리렌더가 일어나지만, 컴포넌트가 언마운트되지 않으므로
**패널은 열린 채 남는다.** 여러 개를 연달아 체크할 수 있다.

### 3.4 패널은 아래 컨트롤을 밀지 않고 그 위에 얹는다

`position: absolute` + `z-index: 20`. 펼칠 때 아래 항목이 밀려 내려가면 방금 누른 위치가
움직여 다음 클릭이 어긋난다. `.defect-filter`나 `.content-card`에 `overflow: hidden`이
없어 그대로 얹힌다.

### 3.5 종료 시각은 UI·URL에서만 뺐다. 타입과 API에는 남겼다

`DefectFilters.to`와 `defectApi`의 `to` 처리는 지웠다면 요약 조회
(`getDefectSummary`의 `Pick<DefectFilters, 'from' | 'to' | 'patrolAreaId'>`)까지
번졌다. 기간을 못박아 조회하는 쪽에는 상한이 필요하므로 계약은 그대로 두고,
**결함 필터 패널만 노출을 끊었다.**

대신 `readFilters`에서 `to`를 읽지 않게 했다. 그러지 않으면 예전 링크에 남은 `to=`가
**화면에 보이지 않는 상한**으로 계속 걸려 "왜 새 결함이 안 나오지"가 된다. 프리셋도
`to: ''`를 함께 써서 남아 있던 값을 지운다.

### 3.6 접근성

- 트리거는 `aria-expanded`·`aria-controls`. 버튼 안에는 `전체`처럼 값만 있어
  그것만으로는 무슨 조건인지 알 수 없으므로, 위의 라벨과 함께 읽히도록
  `aria-labelledby="{라벨} {트리거}"`로 묶었다 → "처리 상태, 전체".
- 패널은 `role="group"` + `aria-label`.
- Esc로 닫고 **포커스를 트리거로 되돌린다.** 사라진 패널 안에 포커스를 남기면 키보드
  사용자가 자기 위치를 잃는다.
- 바깥 클릭은 `pointerdown`으로 닫는다.

---

## 4. 작업 중 겪은 함정

### 4.1 effect에서 setState는 lint가 막는다

`disabled`가 켜지면 열린 패널을 닫으려고 `useEffect(() => { if (disabled) setOpen(false) })`
를 썼더니 `react-hooks/set-state-in-effect`가 에러로 잡았다(경고가 아니라 에러다).

파생값으로 바꿨다.

```tsx
const expanded = open && !disabled
```

렌더에서 가리기만 하므로 `disabled`가 풀리면 원래 상태로 돌아온다. effect보다 짧고
동작도 낫다.

### 4.2 `.defect-filter-checkbox + .defect-filter-checkbox`가 이중 간격을 만들었다

패널이 `display: grid; gap: 10px`인데 기존 인접 선택자 규칙의 `margin-top: 10px`이 더해져
20px가 됐다. 이 클래스는 이제 드롭다운 패널에서만 쓰이므로 인접 규칙을 지웠다.

### 4.3 WSL에서 프론트를 띄우는 방법 — Linux node가 없다

이 WSL에는 **Linux node가 설치돼 있지 않다**(`command -v node`가 빈 결과). `npx`/`npm`은
Windows 쪽(`/mnt/c/Program Files/nodejs`)으로 잡히고, cmd.exe가 UNC 경로를 작업
디렉터리로 못 써서 `npm run dev`·`npm run lint`가 통째로 실패한다.

정리하면 이렇게 나눠 쓴다.

| 무엇 | 어떻게 |
| --- | --- |
| tsc / eslint / node --test | `"/mnt/c/Program Files/nodejs/node.exe" node_modules/<도구>` — 순수 JS라 Windows node로 돌아간다 |
| vite dev | Windows node로는 **안 된다.** `node_modules`가 Linux용으로 설치돼 있어 `@rolldown/binding-win32-x64-msvc`가 없다 |

그래서 dev 서버는 컨테이너로 띄웠다.

```bash
docker run -d --rm --name gilbom-vite-ui -p 4174:4174 \
  -v /home/ssafy/S15P11D101/web/frontend/gilbom:/app -w /app \
  -e VITE_NAVER_MAP_CLIENT_ID=e2e-map-client \
  node:24.18.0-alpine node node_modules/vite/bin/vite.js --host 0.0.0.0 --port 4174
```

`--network host`는 **쓰면 안 된다.** 여기 docker는 Docker Desktop이라 host 네트워크가
WSL이 아니라 Docker VM이고, 그러면 WSL·Windows 어디서도 포트가 안 보인다.
`-p`로 퍼블리시해야 (기존 `gilbom-frontend`가 `127.0.0.1:3000`으로 보이는 것과 같은 방식)
양쪽에서 붙는다. `--host 0.0.0.0`도 필요하다.

### 4.4 Playwright 임시 스펙은 프로젝트 안에 둬야 한다

설정·스펙을 스크래치패드(`/tmp/...`)에 두면 `@playwright/test`를 못 찾는다
(`node_modules`가 부모 경로에 없다). 프로젝트 하위 임시 폴더에 두고 끝나면 지웠다.

또 Windows Playwright가 WSL 공유로 만든 `test-results/`는 **root 소유로 생겨 `rm`이
막힌다.** 만든 쪽에서 지워야 한다.

```bash
powershell.exe -NoProfile -Command "Remove-Item -Recurse -Force '\\wsl.localhost\Ubuntu\...\test-results'"
```

### 4.5 `check()`는 실패하고 `click()`은 성공했다

`<label>` 안의 체크박스를 Playwright `check()`로 누르면
`Clicking the checkbox did not change its state`가 났다. 같은 요소를 `click()`으로 누르면
정상이다(`isChecked() === true`, URL에 `status=RESOLVED` 추가). 실제 동작에는 문제가 없고
`check()`의 검증 방식 문제라, 시간을 더 쓰지 않고 `click()`으로 확인했다. **결함 화면
e2e를 쓸 때 같은 곳에서 막힐 수 있다.**

---

## 5. 검증에 쓴 임시 스펙

커밋하지 않았다. 다시 볼 일이 있으면 이 시나리오를 다시 만든다.

1. `/api/v1/auth/session-state`·`auth/me`·`defects`·`defects/map`을 `page.route`로 스텁
2. `/defects` 진입 → `종료` 텍스트 0개, `시작` 1개
3. `처리 상태` 트리거 클릭 → `role=group` 패널에 체크박스 6개
4. `완료` 체크 → URL에 `status=RESOLVED` 추가, 패널은 그대로 열림
5. 제목(바깥) 클릭 → 패널 사라짐, 트리거에 `신규 외 3개` + 배지 `4`

---

## 6. 1차 뒤 남았던 것

- 날짜 프리셋 3개가 여전히 세로로 3줄을 차지한다 → **2차에서 기간 조건 자체를 없애며
  해결됐다(§7).**
- 결함 화면 e2e 스펙이 아직 없다. 만들 때 §4.5를 참고한다.

---

## 7. 2차 — 검색 분리, 필터를 지도 오른쪽으로, 기간 조건 제거

같은 날 손그림 시안으로 배치를 다시 받았다.

```
┌──────────────────────────────────────┐
│                검색                   │   ← 가로 전체
└──────────────────────────────────────┘
┌───────────────────────┐ ┌──────────┐
│                       │ │          │
│         지도           │ │   필터    │   ← 필터가 오른쪽
│                       │ │          │
└───────────────────────┘ └──────────┘
```

### 7.1 무엇을 바꿨나

| 파일 | 변경 |
| --- | --- |
| `DefectSearchBar.tsx` | **신규.** 검색만 떼어낸 가로 카드. 돋보기 아이콘 + `type=search` |
| `DefectFilter.tsx` | 검색·날짜 범위 삭제. 이제 조건 드롭다운 3개 + 신뢰도 + 초기화뿐 |
| `DefectManagePage.tsx` | 검색바를 지도 위로, `defect-layout` 안 순서를 지도 → 필터로 |
| `defectTypes.ts` | `from`도 `to`와 함께 "화면은 노출하지 않는다"로 주석 |
| `App.css` | `.defect-layout`을 `1fr 280px`로 뒤집고 `.defect-search-bar` 추가, 쓰지 않게 된 날짜 프리셋 스타일 삭제 |

검증: `tsc -b`·`eslint`·단위 테스트 44 pass. Playwright로 배치를 좌표까지 확인했다(§7.5).

### 7.2 검색은 왜 컴포넌트로 뺐나

필터 패널 안에 있던 검색창은 **폭이 280px에 묶여** `결함 코드·위치 검색` placeholder가
겨우 들어가는 크기였다. 위로 올려 921px을 쓰게 했다.

카드 자체를 입력처럼 보이게 하고 `input`의 테두리·배경은 지웠다. 카드 테두리와 입력
테두리가 겹치면 이중선이 된다. 포커스 표시는 `:focus-within`으로 **카드 쪽에** 준다.

### 7.3 기간 조건은 UI에서 완전히 빼고 계약만 남겼다

날짜 범위(프리셋 3개 + 시작 입력)를 지웠다. 조치 대상 결함을 고를 때 "언제 것인지"보다
상태·심각도가 기준이라, 실제로 눌러 쓰는 조건이 아니었다.

`DefectFilters.from`·`to`와 `defectApi`의 처리는 **남겼다.** 지우면
`getDefectSummary(Pick<DefectFilters, 'from' | 'to' | 'patrolAreaId'>)`까지 번지고,
기간을 못박아 조회하는 쪽에는 상한·하한이 필요하다.

대신 `readFilters`에서 `from`도 읽지 않게 했다(1차에서 `to`에 한 것과 같은 이유).
**화면에 없는 조건이 URL로만 걸려 있으면 "왜 결함이 안 나오지"의 원인이 되고, 화면
어디에도 단서가 없다.**

### 7.4 배치는 DOM 순서를 그대로 두고 CSS로만 뒤집었다

`.defect-layout`을 `280px 1fr` → `minmax(0, 1fr) 280px`로 바꾸고 JSX 순서도 지도 → 필터로
맞췄다. 그러면 좁은 화면(≤820px, 한 줄로 접힘)에서 필터가 **420px 지도 아래**로 밀린다.
`@media`에서 `.defect-filter { order: -1 }`로 올렸다. 검색 → 필터 → 지도 순이 된다.

`minmax(0, 1fr)`이어야 한다. 그냥 `1fr`이면 지도 캔버스가 커질 때 그리드가 밀려 필터가
잘린다.

### 7.5 확인한 것

Playwright로 좌표를 직접 비교했다(임시 스펙, 커밋하지 않음).

- 검색 `y=287 < 지도 y=365`, 검색 폭 `921 > 필터 폭 280 × 2`
- 필터 `x=948`, 지도 `x=307 + 폭 623 = 930` → **필터가 지도 오른쪽에 있다**
- 필터 안에 `searchbox` 0개, `날짜 범위`·`오늘`·`시작` 텍스트 0개
- 검색 입력 → URL `q=DEF-UI`, 조회 쿼리도 `q=DEF-UI` 포함
- `필터 초기화` → 검색창까지 빈 값 (§7.6)
- 760px 폭에서 필터 `y=371 < 지도 y=813`

### 7.6 초기화는 검색어까지 지운다

`필터 초기화`는 `EMPTY_DEFECT_FILTERS`를 그대로 쓰므로 검색어도 지운다. 검색창이 카드
밖으로 나갔어도 **검색은 조건의 일부**이고, 위에 보이는 창이 함께 비워지니 숨은 동작이
아니다.

---

## 8. 남은 것

### 내 몫

- **검색이 타이핑 한 글자마다 조회를 부른다.** `update({ q })`가 곧 URL 변경이고 그게 곧
  요청이다. 1차 이전부터 그랬지만 검색창이 커져 더 눌러쓰게 되니 디바운스(300ms 정도)를
  넣는 것이 다음 후보다. 이번 범위가 아니라 두었다.
- 결함 화면 e2e 스펙이 아직 없다. 만들 때 §4.5(`check()` 함정)를 참고한다.

### 다른 팀원 몫

- 없다. 이 작업은 `features/defect`와 `App.css` 안에서만 끝났다.
