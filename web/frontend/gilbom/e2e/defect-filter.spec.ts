import { expect, test } from '@playwright/test'

import { openDefectPage, stubDefectPage } from './support/defectPage'

/**
 * 결함 조건. 검색어를 뺀 조건은 전부 "필터" 모달 안에 있고, 고른 것은 "적용하기"에서
 * 한 번에 URL로 간다(새로고침·링크 공유).
 *
 * <p>툴바에는 왼쪽 검색창과 오른쪽 버튼 둘(필터·초기화)만 있다. 조건마다 알약을
 * 늘어놓던 배치는 여러 개를 고르면 줄을 넘나들어 걷어냈다.
 */
test.describe('결함 필터 툴바', () => {
  test('툴바는 검색창과 버튼 둘뿐이다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    const box = await page.evaluate(() => {
      const rect = (selector: string) =>
        document.querySelector(selector)!.getBoundingClientRect()
      const search = rect('.defect-search-bar')
      const bar = rect('.defect-filter-bar')
      return {
        searchWidth: Math.round(search.width),
        searchRight: Math.round(search.right),
        barLeft: Math.round(bar.left),
        sameRow: Math.abs(search.top - bar.top) < search.height,
        // 알약은 없다. 조건 버튼은 "필터" 하나다.
        chips: document.querySelectorAll('.defect-filter-chip').length,
        barButtons: document.querySelectorAll('.defect-filter-bar button').length,
        mapWidth: Math.round(rect('.defect-map-card').width),
        toolbarWidth: Math.round(rect('.defect-toolbar').width),
      }
    })

    expect(box.searchWidth).toBeLessThan(360)
    expect(box.sameRow, '버튼은 검색창 옆이지 아래가 아니다').toBe(true)
    expect(box.barLeft).toBeGreaterThan(box.searchRight)
    expect(box.chips, '조건 알약은 없앴다').toBe(0)
    expect(box.barButtons, '필터와 초기화 둘뿐이다').toBe(2)
    // 조건 사이드바가 사라졌으니 지도가 가로 폭을 전부 쓴다.
    expect(box.mapWidth).toBe(box.toolbarWidth)
  })

  test('기본 상태는 신규·접수·처리 중 셋이 켜져 있다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)

    // 첫 조회부터 셋이 실린다. 종결된 결함은 조치할 것이 없어 기본에서 뺀다.
    const first = listQueries[0] ?? ''
    expect(first).toContain('workflowStatuses=NEW')
    expect(first).toContain('workflowStatuses=ACCEPTED')
    expect(first).toContain('workflowStatuses=IN_PROGRESS')
    expect(first).not.toContain('workflowStatuses=RESOLVED')

    // 필터를 열어도 같은 셋이 체크돼 있어야 한다.
    await page.getByRole('button', { name: /^필터/ }).click()
    const status = page.getByRole('group', { name: '처리 상태' })
    await expect(status.getByRole('checkbox')).toHaveCount(6)
    await expect(status.getByRole('checkbox', { name: '신규' })).toBeChecked()
    await expect(status.getByRole('checkbox', { name: '접수' })).toBeChecked()
    await expect(status.getByRole('checkbox', { name: '처리 중' })).toBeChecked()
    await expect(status.getByRole('checkbox', { name: '완료' })).not.toBeChecked()
  })

  test('처리 상태를 더 고르면 URL에 실린다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    await page.getByRole('button', { name: /^필터/ }).click()
    const dialog = page.getByRole('dialog', { name: '상세 필터' })
    await dialog.getByRole('group', { name: '처리 상태' }).getByText('완료').click()

    // 적용 전에는 URL이 움직이지 않는다.
    await expect(page).not.toHaveURL(/status=RESOLVED/)
    await dialog.getByRole('button', { name: '적용하기' }).click()
    await expect(page).toHaveURL(/status=RESOLVED/)
    await expect(page).toHaveURL(/status=NEW/)
  })

  test('심각도·결함 유형도 URL에 실린다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    await page.getByRole('button', { name: /^필터/ }).click()
    const dialog = page.getByRole('dialog', { name: '상세 필터' })
    await dialog.getByRole('group', { name: '심각도' }).getByText('심각').click()
    await dialog.getByRole('group', { name: '결함 유형' }).getByText('파손').click()
    await dialog.getByRole('button', { name: '적용하기' }).click()

    await expect(page).toHaveURL(/risk=CRITICAL/)
    await expect(page).toHaveURL(/type=DAMAGED/)
  })

  test('시작 날짜는 그 날 0시로 조회에 실린다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)

    await page.getByRole('button', { name: /^필터/ }).click()
    const dialog = page.getByRole('dialog', { name: '상세 필터' })
    await dialog.locator('.defect-filter-date-input').fill('2026-08-03')
    await dialog.getByRole('button', { name: '적용하기' }).click()

    // 화면은 날짜만 받지만 값은 로컬 0시(datetime-local)로 들고 다닌다.
    await expect(page).toHaveURL(/from=2026-08-03T00%3A00/)
    await expect
      .poll(() => listQueries.at(-1) ?? '')
      .toMatch(/from=\d{4}-\d{2}-\d{2}T/)
    // 상한은 보내지 않는다. 결함은 미래에 생기지 않는다.
    expect(listQueries.at(-1)).not.toContain('to=')

    // 다시 열면 "전체 기간"으로 되돌리는 통로가 보인다.
    await page.getByRole('button', { name: /^필터/ }).click()
    const reopened = page.getByRole('dialog', { name: '상세 필터' })
    await reopened.getByRole('button', { name: '전체 기간' }).click()
    await reopened.getByRole('button', { name: '적용하기' }).click()
    await expect(page).not.toHaveURL(/from=/)
  })

  test('걸린 조건 수가 필터 버튼에 배지로 보인다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    // 기본 상태는 손댄 조건이 없다 — 배지도 없다.
    const openButton = page.getByRole('button', { name: /^필터/ })
    await expect(page.locator('.defect-filter-open-count')).toHaveCount(0)

    await openButton.click()
    const dialog = page.getByRole('dialog', { name: '상세 필터' })
    await dialog.getByRole('group', { name: '심각도' }).getByText('심각').click()
    await dialog.getByRole('group', { name: '결함 유형' }).getByText('파손').click()
    await dialog.getByRole('group', { name: '결함 유형' }).getByText('미설치').click()
    await dialog.getByRole('button', { name: '적용하기' }).click()

    // 고른 항목 수(3)가 아니라 손댄 조건 묶음 수(2)다.
    await expect(page.locator('.defect-filter-open-count')).toHaveText('2')
    await expect(openButton).toHaveAttribute('aria-label', /2개/)
  })

  test('결함 유형 목록은 지도 마커와 같은 색 점을 쓴다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    await page.getByRole('button', { name: /^필터/ }).click()
    const dialog = page.getByRole('dialog', { name: '상세 필터' })
    const panel = dialog.getByRole('group', { name: '결함 유형' })
    await expect(panel.getByRole('checkbox')).toHaveCount(4)

    // 네 유형의 점 색이 서로 다르고, 지도 마커와 같은 변수를 쓴다.
    const colors = await panel.locator('.defect-type-dot').evaluateAll(
      (dots) => dots.map((dot) => getComputedStyle(dot).backgroundColor),
    )
    expect(colors).toHaveLength(4)
    expect(new Set(colors).size, '유형마다 다른 색이어야 한다').toBe(4)

    const markerColor = await page.evaluate(() => {
      const probe = document.createElement('span')
      probe.className = 'defect-marker defect-marker-damaged'
      document.body.append(probe)
      const color = getComputedStyle(probe).backgroundColor
      probe.remove()
      return color
    })
    expect(colors[0], '파손 점은 파손 마커와 같은 색이다').toBe(markerColor)

    // 처리 상태에는 점을 두지 않는다. 마커 색이 상태와 무관해졌다.
    await expect(
      dialog.getByRole('group', { name: '처리 상태' })
        .locator('.defect-status-dot, .defect-type-dot'),
    ).toHaveCount(0)
  })

  test('초기화는 검색어를 지우고 기본 세 상태로 되돌린다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page, '?q=INC&risk=CRITICAL&allStatus=1')

    const search = page.getByRole('form', { name: '결함 검색' })
    await expect(search.getByRole('searchbox')).toHaveValue('INC')

    await page.getByRole('button', { name: '초기화' }).click()
    await expect(search.getByRole('searchbox')).toHaveValue('')
    await expect(page).not.toHaveURL(/q=/)
    await expect(page).not.toHaveURL(/risk=/)

    /*
     * 빈 조건이 아니라 **첫 진입과 같은 조건**으로 돌아간다. 전부 풀린 채로 두면
     * 종결까지 쏟아진다. `allStatus`(전부 풀었음 표식)도 사라져야 한다.
     */
    await expect(page).not.toHaveURL(/allStatus/)
    await expect(page).toHaveURL(/status=NEW/)
    await expect(page).toHaveURL(/status=ACCEPTED/)
    await expect(page).toHaveURL(/status=IN_PROGRESS/)
    await expect(page).not.toHaveURL(/status=RESOLVED/)
  })
})

/**
 * 필터 모달 자체의 성질. 조건 여럿을 한 화면에서 짜맞추는 자리라, 고르는 동안에는
 * 조회가 나가지 않고 "적용하기"에서 한 번에 나간다.
 */
test.describe('필터 모달', () => {
  test('배경을 덮고 적용하기에서 한 번에 반영된다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)
    listQueries.length = 0

    await page.getByRole('button', { name: /^필터/ }).click()
    const dialog = page.getByRole('dialog', { name: '상세 필터' })
    await expect(dialog).toBeVisible()

    // 뒤 화면을 덮되 **비쳐 보여야** 한다. 지도에 무엇이 걸려 있었는지 보면서 고친다.
    const dim = await page
      .locator('.defect-filter-modal-backdrop')
      .evaluate((element) => getComputedStyle(element).backgroundColor)
    const alpha = Number(/[\d.]+\)$/.exec(dim)?.[0].slice(0, -1) ?? '1')
    expect(alpha, '배경은 살짝만 어두워진다').toBeGreaterThan(0)
    expect(alpha).toBeLessThan(0.5)

    await dialog.getByRole('group', { name: '심각도' }).getByText('심각').click()
    await dialog.getByRole('group', { name: '결함 유형' }).getByText('파손').click()

    // 고르는 동안에는 URL도 조회도 움직이지 않는다.
    await expect(page).not.toHaveURL(/risk=/)
    expect(listQueries).toEqual([])

    await dialog.getByRole('button', { name: '적용하기' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(page).toHaveURL(/risk=CRITICAL/)
    await expect(page).toHaveURL(/type=DAMAGED/)
    await expect.poll(() => listQueries.length, {
      message: '조건 둘을 바꿔도 조회는 한 번이다',
    }).toBe(1)
  })

  test('닫기는 고른 것을 버린다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    await page.getByRole('button', { name: /^필터/ }).click()
    const dialog = page.getByRole('dialog', { name: '상세 필터' })
    await dialog.getByRole('group', { name: '심각도' }).getByText('심각').click()
    await dialog.getByRole('button', { name: '닫기', exact: true }).click()

    await expect(dialog).toHaveCount(0)
    await expect(page).not.toHaveURL(/risk=/)

    // 다시 열면 버린 초안이 아니라 지금 걸린 조건으로 시작한다.
    await page.getByRole('button', { name: /^필터/ }).click()
    await expect(
      page.getByRole('dialog', { name: '상세 필터' })
        .getByRole('checkbox', { name: '심각' }),
    ).not.toBeChecked()
  })

  test('Esc로 닫히고 포커스가 열었던 버튼으로 돌아온다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    const opener = page.getByRole('button', { name: /^필터/ })
    await opener.click()
    await expect(page.getByRole('dialog', { name: '상세 필터' })).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog', { name: '상세 필터' })).toHaveCount(0)
    await expect(opener).toBeFocused()
  })
})
