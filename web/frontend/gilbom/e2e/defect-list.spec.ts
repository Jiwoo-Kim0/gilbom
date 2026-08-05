import { expect, test } from '@playwright/test'

import { openDefectPage, stubDefectPage } from './support/defectPage'

/**
 * 상세 결함 리스트의 읽기 쉬움을 지킨다.
 *
 * <p>예전에 `grid-template-columns`에 열을 여섯 개만 적어(실제로는 여덟 개) 유형·심각도가
 * 가장 넓어지고, 결함 코드는 `overflow` 없이 `nowrap`이라 넘친 글자가 옆 칸 위에 그려졌다.
 * 눈으로 보면 "겹쳐 보인다"로만 알 수 있어 폭과 글자 사이를 직접 잰다.
 */
test.describe('결함 목록 표', () => {
  test('열끼리 겹치지 않고 행 높이가 일정하다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page, '?allStatus=1')

    const report = await page.evaluate(() => {
      /** Range로 칸의 실제 글자 영역을 잡는다. 칸이 아니라 잉크를 재야 한다. */
      function inkRect(cell: Element): DOMRect {
        const range = document.createRange()
        range.selectNodeContents(cell)
        const rect = range.getBoundingClientRect()
        range.detach()
        return rect.width === 0 ? cell.getBoundingClientRect() : rect
      }

      const heads = [...document.querySelectorAll('.defect-table-head span')]
        .map((head) => head.textContent ?? '')
      const rows = [...document.querySelectorAll('.defect-table-body-row')]
      const gaps: Record<string, number> = {}
      const clipped: string[] = []

      for (const row of rows) {
        const cells = [...row.children]
        for (const [index, cell] of cells.entries()) {
          const element = cell as HTMLElement
          if (element.scrollWidth > element.clientWidth + 1) {
            clipped.push(heads[index])
          }
          if (index + 1 < cells.length) {
            const key = `${heads[index]} → ${heads[index + 1]}`
            const gap = Math.round(
              inkRect(cells[index + 1]).left - inkRect(cell).right,
            )
            gaps[key] = Math.min(gaps[key] ?? Number.MAX_SAFE_INTEGER, gap)
          }
        }
      }

      const first = rows[0] as HTMLElement
      return {
        heads,
        widths: [...first.children].map((c) => (c as HTMLElement).offsetWidth),
        heights: rows.map((row) => (row as HTMLElement).offsetHeight),
        gaps,
        clipped: [...new Set(clipped)],
      }
    })

    // 인접한 두 값이 붙어 한 덩어리로 읽히지 않는다.
    for (const [pair, gap] of Object.entries(report.gaps)) {
      expect(gap, `${pair} 사이 여유`).toBeGreaterThanOrEqual(10)
    }

    // 유형·심각도는 값이 짧다. 값이 긴 위치보다 넓으면 폭 배분이 다시 뒤집힌 것이다.
    const widthOf = (head: string) =>
      report.widths[report.heads.indexOf(head)]
    expect(widthOf('유형')).toBeLessThan(widthOf('위치'))
    expect(widthOf('심각도')).toBeLessThan(widthOf('위치'))

    // 행 높이가 흔들리면 세로로 훑을 수 없다.
    expect(Math.max(...report.heights)).toBe(Math.min(...report.heights))

    // 잘려도 되는 것은 위치(이름)뿐이다. 코드·시각이 잘리면 값을 못 읽는다.
    expect(report.clipped.filter((head) => head !== '위치')).toEqual([])
  })

  test('심각도·상태는 색 배지로 그린다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page, '?allStatus=1')

    // 예전에는 클래스만 있고 CSS가 없어 기울어진 맨 텍스트였다.
    const badge = await page.locator('.defect-risk-critical').first().evaluate(
      (element) => {
        const style = getComputedStyle(element)
        return {
          fontStyle: style.fontStyle,
          background: style.backgroundColor,
          radius: style.borderRadius,
        }
      },
    )
    expect(badge.fontStyle).toBe('normal')
    expect(badge.background).not.toBe('rgba(0, 0, 0, 0)')
    expect(badge.radius).not.toBe('0px')

    await expect(page.locator('.defect-status-new').first()).toBeVisible()
  })

  test('지도 범례가 마커 색을 설명한다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page, '?allStatus=1')

    // 마커 색 기준이 결함 유형이므로 범례도 유형이어야 한다. 색을 설명하는 것이
    // 화면에 하나도 없던 시기가 있었다.
    const legend = page.getByLabel('마커 색 범례')
    await expect(legend).toBeVisible()
    const items = legend.locator('.defect-legend-item')
    // 유형 4개 + 여러 유형 묶음 1개
    await expect(items).toHaveCount(5)
    await expect(items.nth(0)).toContainText('파손')
    await expect(items.nth(4)).toContainText('여러 유형')

    const colors = await legend.locator('.defect-type-dot').evaluateAll(
      (dots) => dots.map((dot) => getComputedStyle(dot).backgroundColor),
    )
    expect(new Set(colors).size, '칩마다 다른 색이어야 한다').toBe(5)

    const markerColor = await page.evaluate(() => {
      const probe = document.createElement('span')
      probe.className = 'defect-marker defect-marker-missing'
      document.body.append(probe)
      const color = getComputedStyle(probe).backgroundColor
      probe.remove()
      return color
    })
    expect(colors[1], '미설치 점은 미설치 마커와 같은 색이다').toBe(markerColor)

    // 범례가 지도에 딱 붙으면 칩이 지도 테두리에 얹힌 것처럼 보인다.
    const gap = await page.evaluate(() => {
      const legendRect = document
        .querySelector('.defect-map-legend')!
        .getBoundingClientRect()
      const map = document.querySelector('.defect-map-canvas-wrap')
        ?? document.querySelector('.defect-map-placeholder')!
      return Math.round(map.getBoundingClientRect().top - legendRect.bottom)
    })
    expect(gap, '범례와 지도 사이 간격').toBeGreaterThanOrEqual(8)
  })

  test('병합 선택 줄이 생겨도 목록이 밀리지 않는다', async ({ page }) => {
    await stubDefectPage(page, {
      permissions: ['DEFECT_STATUS_VIEW', 'DEFECT_STATUS_UPDATE', 'DEFECT_MERGE'],
    })
    await openDefectPage(page, '?allStatus=1')

    const bar = page.locator('.defect-merge-bar')
    /*
     * 숨은 동안에는 role 기반 locator로 못 잡는다 — `visibility: hidden`이면 접근성
     * 트리에서 빠지기 때문이다(그게 바로 원하는 상태다). 버튼은 CSS로 집는다.
     */
    const clear = bar.locator('button.secondary-button')
    const merge = bar.locator('button.primary-button')
    /*
     * 문서 좌표로 잰다. 화면 기준으로 재면 체크박스를 누를 때 일어나는 스크롤까지
     * 섞여 들어와, 자리가 밀린 것과 구분되지 않는다.
     */
    const listTop = () =>
      page.evaluate(() =>
        Math.round(
          document.querySelector('.defect-list-card')!
            .getBoundingClientRect().top + window.scrollY,
        ),
      )

    /*
     * 선택이 없을 때도 줄은 자리에 있어야 한다. 조건부로 렌더하면 체크할 때마다 목록이
     * 위아래로 움직여, 방금 누른 행이 손가락 밑에서 달아난다.
     */
    await expect(bar).toHaveClass(/is-hidden/)
    await expect(bar).toBeHidden()
    await expect(clear).toBeDisabled()
    await expect(merge).toBeDisabled()
    const before = await listTop()

    // 한 건 선택 — 줄이 보이고 목록은 그대로다.
    await page.locator('.defect-table-body-row input[type="checkbox"]')
      .first()
      .check()
    await expect(bar).toBeVisible()
    await expect(bar).toContainText('1건 선택됨')
    // 보일 때는 접근성 트리에도 들어온다.
    await expect(bar.getByRole('button', { name: '선택 해제' })).toBeVisible()
    await expect(clear).toBeEnabled()
    // 한 건만으로는 합칠 상대가 없다.
    await expect(merge).toBeDisabled()
    expect(await listTop(), '목록 위치가 그대로여야 한다').toBe(before)

    // 두 건 선택 — 병합이 열린다.
    await page.locator('.defect-table-body-row input[type="checkbox"]')
      .nth(1)
      .check()
    await expect(bar).toContainText('2건 선택됨')
    await expect(merge).toBeEnabled()
    expect(await listTop()).toBe(before)

    // 해제하면 다시 숨지만 자리는 남는다.
    await clear.click()
    await expect(bar).toHaveClass(/is-hidden/)
    expect(await listTop()).toBe(before)
  })

  test('행을 고르면 지도까지 화면이 올라간다', async ({ page }) => {
    await stubDefectPage(page, { count: 20 })
    await openDefectPage(page, '?allStatus=1', 20)

    // 목록 끝까지 내려가 지도를 화면 밖으로 보낸다.
    await page.locator('.defect-table-body-row').nth(19).scrollIntoViewIfNeeded()
    const before = await page.evaluate(() =>
      document.querySelector('.defect-map-card')!.getBoundingClientRect().top,
    )
    expect(before, '지도가 화면 위로 벗어나 있어야 한다').toBeLessThan(0)

    await page.locator('.defect-table-body-row').nth(2).click()

    // 부드러운 스크롤이 끝나기를 기다린다.
    await expect
      .poll(
        () =>
          page.evaluate(() =>
            Math.round(
              document.querySelector('.defect-map-card')!
                .getBoundingClientRect().top,
            ),
          ),
        { timeout: 5000 },
      )
      .toBeGreaterThanOrEqual(0)

    const after = await page.evaluate(() =>
      document.querySelector('.defect-map-card')!.getBoundingClientRect().top,
    )
    // scroll-margin-top(24px)만큼 띄우고 화면 안에 들어온다.
    expect(after).toBeLessThan(60)
    await expect(page.locator('.defect-table-body-row.is-selected'))
      .toHaveCount(1)
  })
})
