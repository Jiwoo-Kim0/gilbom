import { expect, test } from '@playwright/test'

import { openDefectPage, stubDefectPage } from './support/defectPage'

/**
 * 결함 조건 툴바. 조건은 전부 URL에 실려야 하고(새로고침·링크 공유), 접힌 알약도
 * 무엇이 걸렸는지 보여줘야 한다.
 *
 * <p>조건은 검색창 오른쪽 한 줄에 알약으로 늘어선다. 알약은 즉시 반영, 상세 필터
 * 모달은 "적용하기"에서 한 번에 반영이다.
 */
test.describe('결함 필터', () => {
  test('조건 셋은 알약이고 고른 것이 URL에 실린다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    const filter = page.getByRole('group', { name: '결함 필터' })
    // 첫 진입 기본값은 종결을 뺀 셋이다. 접힌 채로도 그 사실이 드러난다.
    const statusTrigger = filter.getByRole('button', { name: /처리 상태/ })
    await expect(statusTrigger).toContainText('신규 외 2개')

    await statusTrigger.click()
    const panel = filter.getByRole('group', { name: '처리 상태' })
    await expect(panel.getByRole('checkbox')).toHaveCount(6)

    // 체크해도 패널은 열린 채 남아 여러 개를 연달아 고를 수 있다.
    await panel.getByText('완료').click()
    await expect(page).toHaveURL(/status=RESOLVED/)
    await expect(panel).toBeVisible()

    // 바깥을 누르면 닫히고 요약이 남는다.
    await page.getByRole('heading', { level: 1 }).click()
    await expect(panel).toHaveCount(0)
    await expect(statusTrigger).toContainText('외 3개')
  })

  test('심각도·결함 유형도 URL에 실린다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)
    const filter = page.getByRole('group', { name: '결함 필터' })

    await filter.getByRole('button', { name: /심각도/ }).click()
    await filter.getByRole('group', { name: '심각도' }).getByText('심각').click()
    await expect(page).toHaveURL(/risk=CRITICAL/)

    await page.getByRole('heading', { level: 1 }).click()
    await filter.getByRole('button', { name: /결함 유형/ }).click()
    await filter.getByRole('group', { name: '결함 유형' }).getByText('파손').click()
    await expect(page).toHaveURL(/type=DAMAGED/)
  })

  test('시작 날짜는 그 날 0시로 조회에 실린다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)
    const filter = page.getByRole('group', { name: '결함 필터' })

    // 알약과 패널 안 "전체 기간" 버튼이 이름을 나눠 갖는다. 알약 쪽만 집는다.
    const dateChip = filter.locator('.defect-filter-chip-trigger', {
      hasText: '기간',
    })
    await dateChip.click()
    await filter.locator('.defect-filter-date-input').fill('2026-08-03')
    // 화면은 날짜만 받지만 값은 로컬 0시(datetime-local)로 들고 다닌다.
    await expect(page).toHaveURL(/from=2026-08-03T00%3A00/)
    await expect
      .poll(() => listQueries.at(-1) ?? '')
      .toMatch(/from=\d{4}-\d{2}-\d{2}T/)
    // 상한은 보내지 않는다. 결함은 미래에 생기지 않는다.
    expect(listQueries.at(-1)).not.toContain('to=')

    // 걸린 조건은 알약에도 남는다.
    await expect(dateChip).toContainText('2026-08-03')

    await filter.getByRole('button', { name: '전체 기간' }).click()
    await expect(page).not.toHaveURL(/from=/)
  })

  test('검색창은 좁아지고 조건은 그 오른쪽 한 줄에 붙는다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    const box = await page.evaluate(() => {
      const search = document
        .querySelector('.defect-search-bar')!
        .getBoundingClientRect()
      const bar = document
        .querySelector('.defect-filter-bar')!
        .getBoundingClientRect()
      const map = document
        .querySelector('.defect-map-card')!
        .getBoundingClientRect()
      const toolbar = document
        .querySelector('.defect-toolbar')!
        .getBoundingClientRect()
      return {
        searchWidth: Math.round(search.width),
        searchRight: Math.round(search.right),
        barLeft: Math.round(bar.left),
        sameRow: Math.abs(search.top - bar.top) < search.height,
        mapWidth: Math.round(map.width),
        toolbarWidth: Math.round(toolbar.width),
      }
    })

    // 검색어는 대개 결함 코드 하나다. 툴바 전체를 혼자 쓰지 않는다.
    expect(box.searchWidth).toBeLessThan(360)
    expect(box.sameRow, '조건은 검색창 옆이지 아래가 아니다').toBe(true)
    expect(box.barLeft).toBeGreaterThanOrEqual(box.searchRight)
    // 조건 사이드바가 사라졌으니 지도가 가로 폭을 전부 쓴다.
    expect(box.mapWidth).toBe(box.toolbarWidth)
  })

  test('결함 유형 목록은 지도 마커와 같은 색 점을 쓴다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)
    const filter = page.getByRole('group', { name: '결함 필터' })

    await filter.getByRole('button', { name: /결함 유형/ }).click()
    const panel = filter.getByRole('group', { name: '결함 유형' })
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
    await page.getByRole('heading', { level: 1 }).click()
    await filter.getByRole('button', { name: /처리 상태/ }).click()
    await expect(
      filter.getByRole('group', { name: '처리 상태' })
        .locator('.defect-status-dot, .defect-type-dot'),
    ).toHaveCount(0)
  })

  test('필터 초기화는 검색어까지 지운다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page, '?q=INC&risk=CRITICAL')

    const search = page.getByRole('form', { name: '결함 검색' })
    await expect(search.getByRole('searchbox')).toHaveValue('INC')

    await page.getByRole('button', { name: '필터 초기화' }).click()
    await expect(search.getByRole('searchbox')).toHaveValue('')
    await expect(page).not.toHaveURL(/q=/)
    await expect(page).not.toHaveURL(/risk=/)
  })
})

/**
 * 상세 필터. 조건 여럿을 한 화면에서 짜맞추는 길이라, 알약과 달리 고르는 동안에는
 * 조회가 나가지 않고 "적용하기"에서 한 번에 나간다.
 */
test.describe('상세 필터 모달', () => {
  test('배경을 덮고 적용하기에서 한 번에 반영된다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)
    listQueries.length = 0

    await page.getByRole('button', { name: '상세 필터' }).click()
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

    await page.getByRole('button', { name: '상세 필터' }).click()
    const dialog = page.getByRole('dialog', { name: '상세 필터' })
    await dialog.getByRole('group', { name: '심각도' }).getByText('심각').click()
    await dialog.getByRole('button', { name: '닫기', exact: true }).click()

    await expect(dialog).toHaveCount(0)
    await expect(page).not.toHaveURL(/risk=/)

    // 다시 열면 버린 초안이 아니라 지금 걸린 조건으로 시작한다.
    await page.getByRole('button', { name: '상세 필터' }).click()
    await expect(
      page.getByRole('dialog', { name: '상세 필터' })
        .getByRole('checkbox', { name: '심각' }),
    ).not.toBeChecked()
  })

  test('Esc로 닫히고 포커스가 열었던 버튼으로 돌아온다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    const opener = page.getByRole('button', { name: '상세 필터' })
    await opener.click()
    await expect(page.getByRole('dialog', { name: '상세 필터' })).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog', { name: '상세 필터' })).toHaveCount(0)
    await expect(opener).toBeFocused()
  })
})
