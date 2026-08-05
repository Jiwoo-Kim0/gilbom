# 결함 화면 e2e 스펙 도입 작업내용

기간: 2026-08-05
범위: 결함 화면(목록 표·필터·검색) Playwright e2e 스펙 3개와 공용 스텁. 제품 코드는 그대로다.

관련 문서: [결함-필터-UI-드롭다운_작업내용.md](결함-필터-UI-드롭다운_작업내용.md)(여기서 지킨 것들),
[결함-상태전이-자유화_작업내용.md](결함-상태전이-자유화_작업내용.md)

---

## 1. 배경

하루 동안 결함 화면을 일곱 차례 고쳤고, 그때마다 브라우저를 띄워 폭·간격·조회 횟수를
직접 재서 확인했다. **그 스펙을 매번 지웠다.** 같은 회귀가 다시 나면 또 눈으로 찾아야 한다.

특히 이 화면의 문제들은 눈으로만 판정하기 어려웠다.

- 열이 겹쳐 보이는 것 → `overflow: hidden`이 없어 글자가 옆 칸 위에 그려진 것
- 라벨 간격이 좁은 것 → `<legend>`에 부모의 `gap`이 걸리지 않은 것
- 검색이 느린 것 → 타자마다 요청이 나가던 것

전부 **숫자를 재야** 드러났다. 재는 코드를 남기는 것이 이번 작업이다.

그리고 결함 화면은 다른 팀원 작업이 두 차례 머지로 들어온 자리다(`DefectMap`,
`DefectList`, `DefectSearchBar`). 남이 고칠 때 깨뜨리면 바로 드러나야 한다.

---

## 2. 무엇을 만들었나

| 파일 | 내용 |
| --- | --- |
| `e2e/support/defectPage.ts` | 공용 스텁 — 인증·목록·지도 응답, SSE 침묵, 페이지 열기, 검색 타자 넣기 |
| `e2e/defect-list.spec.ts` | 표 열 폭·겹침·행 높이, 배지 색, 지도 범례 색, 행 클릭 → 지도 스크롤 (4개) |
| `e2e/defect-filter.spec.ts` | 드롭다운·URL 반영·시작 날짜·라벨 간격·유형 색·초기화 (6개) |
| `e2e/defect-search.spec.ts` | 디바운스·Enter·한글 IME·초기화 취소·조건 동시 변경 (6개) |
| `package.json` | `npm run test:e2e:defect` |

전부 **서버 없이** 돈다. `page.route`로 API를 가짜 응답으로 막는다. 실제 API 계약은
백엔드 테스트(817개)가 지키고, 여기서는 화면만 본다.

결과: 16개 전부 통과. **네 번 연속 돌려 흔들림이 없는 것까지 확인했다**(§5).
(처음 14개로 시작했고, 유형 색 점과 지도 범례를 넣을 때 두 개를 더했다.)

---

## 3. 무엇을 지키기로 했나

깨지면 아픈 것만 골랐다. 스펙이 많아지면 화면을 고칠 때마다 스펙을 고치게 된다.

### 3.1 눈으로 못 잡는 값

- **인접한 두 칸의 글자 사이 거리 ≥ 10px.** 열 폭이 아니라 `Range`로 잡은 실제 글자
  영역을 잰다. 칸이 넓어도 값이 꽉 차면 붙어 보인다.
- **유형·심각도 폭 < 위치 폭.** 값이 짧은 열이 가장 넓어지는 배분으로 되돌아가는 것을 막는다.
- **행 높이 전부 같음.** 위치가 접혀 들쭉날쭉해지는 것을 막는다.
- **잘리는 칸은 위치뿐.** 코드·시각이 잘리면 값을 읽을 수 없다.
- **필터 네 칸의 라벨→컨트롤 간격이 같음.** `<legend>` 구조로 되돌아가면 어긋난다.

### 3.2 조회가 몇 번 나가는가

디바운스는 "몇 번 나갔나"로만 판정할 수 있다. 목록 조회 query string을 모아 센다.

- 8글자 → **1건**
- 중간에 멈추면 그때 한 번(2건)
- Enter는 300ms를 기다리지 않음
- 한글은 조합 중 0건, 확정 뒤 1건
- 초기화가 대기 중인 입력에 무효화되지 않음
- 기다리는 사이 심각도를 바꿔도 **마지막 조회에 둘 다** 실림

### 3.3 URL이 조건을 들고 있는가

새로고침·링크 공유가 이 화면의 계약이다. 드롭다운 체크가 `status=`·`risk=`·`type=`으로,
시작 날짜가 `from=…T00:00`으로 실리는지 본다.

---

## 4. 스텁을 어떻게 짰나

### 4.1 지도는 건드리지 않았다

상세 카드는 `DefectMap` 안 `.defect-map-canvas-wrap`에 뜨는데, 이 wrap은 지도 스크립트가
성공적으로 로드됐을 때만 렌더된다. 네이버 스크립트에 의존하면 인터넷 없는 환경에서
스펙이 깨진다.

그래서 **지도 로드가 필요한 검증은 넣지 않았다.** 행 클릭 스크롤은
`.defect-map-card`(로드 성공·실패 양쪽에서 항상 있는 카드) 좌표만 본다. 상세 카드 자체를
시험하려면 `mission-map.spec.ts`처럼 `naver.maps` 가짜를 심어야 한다 — §6에 남겼다.

### 4.2 SSE를 껐다

스텁 서버에는 `/api/v1/stream/defects` 종단이 없다. 그대로 두면 훅이 계속 재연결을
시도하고, 그 소음이 조회 횟수를 세는 스펙을 흔든다. `addInitScript`로 `EventSource`를
아무것도 하지 않는 것으로 바꿨다.

### 4.3 타자는 페이지 안에서 넣는다

**이 함정에 두 번 걸렸다.** Playwright의 `pressSequentially`는 키마다 CDP를 왕복하는데,
WSL↔Windows 사이에서 그 왕복이 300ms를 넘는 때가 있다. 그러면 디바운스가 타자 중간에
터져 실행마다 다른 결과가 나온다. 처음에는 구현 버그로 오인해 한참 뒤졌다.

간격을 페이지 안에서 만들면 러너 속도와 무관해진다. React는 native `input` 이벤트를
듣기 때문에 value setter를 직접 부르고 이벤트를 쏘면 된다.

```ts
const setValue = Object.getOwnPropertyDescriptor(
  HTMLInputElement.prototype, 'value')!.set!
for (const char of text) {
  setValue.call(input, input.value + char)
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await new Promise((resolve) => setTimeout(resolve, gapMs))
}
```

### 4.4 개수는 폴링으로 기다린다

요청이 `page.route`에 잡히는 시점도 CDP 왕복에 걸린다. URL이 바뀐 직후
`expect(queries).toHaveLength(1)`로 못 박으면 아직 안 잡혀 실패한다.
`expect.poll(() => queries.length).toBe(1)`로 기다린다.

반대로 **"아직 안 나갔어야 한다"는 즉시 확인**해야 한다(폴링하면 기다리는 동안 나간다).
치는 도중의 `toEqual([])`은 동기 검사로 둔다.

### 4.5 한글 IME는 CDP로 흉내낸다

Playwright에 조합 입력 API가 없다. Chromium CDP를 직접 쓴다.

```ts
const cdp = await page.context().newCDPSession(page)
await cdp.send('Input.imeSetComposition', { text: 'ㄱ', selectionStart: 1, selectionEnd: 1 })
await cdp.send('Input.insertText', { text: '강' })   // 확정
```

---

## 5. 어떻게 돌리나

```bash
cd web/frontend/gilbom
npm run test:e2e:defect
```

프로젝트 `playwright.config.ts`가 `npm run dev`로 서버를 띄우고 Chromium으로 돈다.

**이 WSL에서는 그 경로를 쓸 수 없다** — Linux node가 없어 `npm run dev`가 실패한다
([결함-필터-UI-드롭다운_작업내용.md](결함-필터-UI-드롭다운_작업내용.md) §4.3). 그래서 검증은
컨테이너로 dev 서버를 띄우고, 같은 `baseURL`을 보는 임시 config로 했다(커밋하지 않음).
서버를 누가 띄우는지만 다르고 스펙·브라우저는 같다. **정식 경로(`npm run test:e2e:defect`)는
Linux node가 있는 환경에서 한 번 확인이 필요하다**(§6).

---

## 6. 남은 것

### 내 몫

- **정식 스크립트로 한 번 돌려보기.** 이 환경에서는 `npm run dev`가 안 돼 컨테이너 서버로만
  확인했다. 다른 사람 기계나 CI에서 `npm run test:e2e:defect`가 그대로 도는지 봐야 한다.
- 상세 카드(상태 드롭다운 다섯 항목, 완료 선택 시 처리 결과 필수)는 지도 스크립트가 필요해
  빠졌다. `mission-map.spec.ts`의 `naver.maps` 가짜를 공용 support로 빼내면 넣을 수 있다.
- 이 스펙들은 CI에 걸려 있지 않다. Jenkins에 붙이려면 브라우저 설치가 필요하다
  (`playwright install chromium`). 붙이는 판단은 팀과 함께.

### 다른 팀원 몫

- 없다. `e2e/`와 `package.json` 스크립트만 추가했다.
