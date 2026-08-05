import { expect, test } from '@playwright/test'

import { openDefectPage, stubDefectPage } from './support/defectPage'

/**
 * 결함 필터 패널. 조건은 전부 URL에 실려야 하고(새로고침·링크 공유), 접힌 드롭다운도
 * 무엇이 걸렸는지 보여줘야 한다.
 */
test.describe('결함 필터', () => {
  test('조건 셋은 드롭다운이고 고른 것이 URL에 실린다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    const filter = page.getByRole('form', { name: '결함 필터' })
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
    await page.getByRole('heading', { level: 2 }).click()
    await expect(panel).toHaveCount(0)
    await expect(statusTrigger).toContainText('외 3개')
  })

  test('심각도·결함 유형도 URL에 실린다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)
    const filter = page.getByRole('form', { name: '결함 필터' })

    await filter.getByRole('button', { name: /심각도/ }).click()
    await filter.getByRole('group', { name: '심각도' }).getByText('심각').click()
    await expect(page).toHaveURL(/risk=CRITICAL/)

    await page.getByRole('heading', { level: 2 }).click()
    await filter.getByRole('button', { name: /결함 유형/ }).click()
    await filter.getByRole('group', { name: '결함 유형' }).getByText('파손').click()
    await expect(page).toHaveURL(/type=DAMAGED/)
  })

  test('시작 날짜는 그 날 0시로 조회에 실린다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)
    const filter = page.getByRole('form', { name: '결함 필터' })

    await filter.locator('.defect-filter-date-input').fill('2026-08-03')
    // 화면은 날짜만 받지만 값은 로컬 0시(datetime-local)로 들고 다닌다.
    await expect(page).toHaveURL(/from=2026-08-03T00%3A00/)
    await expect
      .poll(() => listQueries.at(-1) ?? '')
      .toMatch(/from=\d{4}-\d{2}-\d{2}T/)
    // 상한은 보내지 않는다. 결함은 미래에 생기지 않는다.
    expect(listQueries.at(-1)).not.toContain('to=')

    await filter.getByRole('button', { name: '전체 기간' }).click()
    await expect(page).not.toHaveURL(/from=/)
  })

  test('라벨과 컨트롤 사이 간격이 네 칸 모두 같다', async ({ page }) => {
    await stubDefectPage(page)
    await openDefectPage(page)

    // `<legend>`는 grid 항목이 아니어서 부모의 gap이 걸리지 않는다. 그 구조로 되돌아가면
    // 시작 날짜만 라벨과 입력이 붙어 보인다.
    const gaps = await page.evaluate(() => {
      function gapBetween(label: Element, control: Element): number {
        return Math.round(
          control.getBoundingClientRect().top
          - label.getBoundingClientRect().bottom,
        )
      }

      const values: number[] = []
      const heading = document.querySelector('.defect-filter-group-heading')!
      values.push(
        gapBetween(
          heading,
          document.querySelector('.defect-filter-date-input')!,
        ),
      )
      for (const dropdown of document.querySelectorAll(
        '.defect-filter-dropdown',
      )) {
        values.push(
          gapBetween(
            dropdown.querySelector('.defect-filter-dropdown-label')!,
            dropdown.querySelector('.defect-filter-dropdown-trigger')!,
          ),
        )
      }
      values.push(
        gapBetween(
          document.querySelector('label.defect-filter-group-heading')!,
          document.querySelector('input[type="range"]')!,
        ),
      )
      return values
    })

    expect(Math.max(...gaps) - Math.min(...gaps)).toBeLessThanOrEqual(1)
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
