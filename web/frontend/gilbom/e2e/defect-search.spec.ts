import { expect, test } from '@playwright/test'

import {
  openDefectPage,
  stubDefectPage,
  typeInSearch,
} from './support/defectPage'

/**
 * 검색어는 URL로 가고 **URL 변경이 곧 조회 요청**이다. 그래서 타자마다 보내지 않고
 * 300ms 묶는다(`SEARCH_DEBOUNCE_MS`). 미루는 사이에 세상이 바뀌는 경우까지 함께 지킨다.
 */
test.describe('결함 검색 디바운스', () => {
  test('여덟 글자를 쳐도 조회는 한 번만 나간다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)
    listQueries.length = 0

    // 60ms 간격 — 사람이 빠르게 치는 속도이고 디바운스 시간보다 짧다.
    await typeInSearch(page, 'INC-2026', 60)
    expect(listQueries, '치는 동안에는 조회가 없다').toEqual([])

    await expect(page).toHaveURL(/q=INC-2026/)
    await expect
      .poll(() => listQueries.length, { message: '멈춘 뒤 한 번만 나간다' })
      .toBe(1)
    expect(listQueries[0]).toContain('q=INC-2026')
  })

  test('중간에 멈추면 그때 한 번 나간다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)
    listQueries.length = 0

    await typeInSearch(page, 'INC', 60)
    await expect(page).toHaveURL(/q=INC(&|$)/)
    await expect.poll(() => listQueries.length).toBe(1)

    await typeInSearch(page, '-2026', 60)
    await expect(page).toHaveURL(/q=INC-2026/)
    await expect.poll(() => listQueries.length).toBe(2)
  })

  test('Enter는 기다리지 않고 즉시 보낸다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)
    listQueries.length = 0

    await typeInSearch(page, 'INC-2026', 10)
    await page.getByRole('searchbox').press('Enter')

    // 디바운스(300ms)를 기다리지 않고 바로 반영돼야 한다.
    await expect(page).toHaveURL(/q=INC-2026/, { timeout: 250 })
    await expect.poll(() => listQueries.length).toBe(1)

    // 남은 타이머가 같은 값을 두 번 보내지 않는다.
    await page.waitForTimeout(400)
    expect(listQueries).toHaveLength(1)
    // 폼 제출로 화면이 새로 뜨지 않는다.
    await expect(page.getByRole('searchbox')).toHaveValue('INC-2026')
  })

  test('한글은 조합이 끝난 뒤 한 번만 나간다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)
    listQueries.length = 0
    await page.getByRole('searchbox').click()

    // 조합 중 값을 URL에 반영하면 controlled value와 IME가 충돌해 글자가 깨진다.
    const cdp = await page.context().newCDPSession(page)
    for (const text of ['ㄱ', '가', '강']) {
      await cdp.send('Input.imeSetComposition', {
        text,
        selectionStart: 1,
        selectionEnd: 1,
      })
    }
    await page.waitForTimeout(450)
    expect(listQueries, '조합 중에는 조회가 없다').toEqual([])

    await cdp.send('Input.insertText', { text: '강' })
    await expect(page).toHaveURL(/q=%EA%B0%95/)
    await expect.poll(() => listQueries.length).toBe(1)
    await expect(page.getByRole('searchbox')).toHaveValue('강')
  })

  test('대기 중인 입력이 초기화를 무효로 만들지 않는다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)
    listQueries.length = 0

    // 타이머가 도는 중에 초기화를 누른다. 페이지 안에서 눌러 300ms를 넘기지 않는다.
    await typeInSearch(page, 'INC', 10)
    await page.evaluate(() => {
      const reset = [...document.querySelectorAll('button')].find(
        (button) => button.textContent?.includes('필터 초기화'),
      )
      ;(reset as HTMLButtonElement).click()
    })
    await page.waitForTimeout(600)

    // 검색어가 이미 비어 있던 탓에 `value`가 안 바뀌어도 취소돼야 한다.
    expect(listQueries.filter((query) => query.includes('q='))).toEqual([])
    await expect(page.getByRole('searchbox')).toHaveValue('')
    await expect(page).not.toHaveURL(/q=/)
  })

  test('기다리는 사이 다른 조건을 바꿔도 둘 다 살아남는다', async ({ page }) => {
    const listQueries: string[] = []
    await stubDefectPage(page, { listQueries })
    await openDefectPage(page)
    listQueries.length = 0

    await typeInSearch(page, 'INC', 10)
    const filter = page.getByRole('form', { name: '결함 필터' })
    await filter.getByRole('button', { name: /심각도/ }).click()
    await filter.getByRole('group', { name: '심각도' }).getByText('심각').click()

    /*
     * 심각도는 즉시, 검색어는 300ms 뒤에 나가므로 **마지막** 조회에 둘이 함께 실려야
     * 한다. 타이머가 렌더 당시의 onChange를 물고 있으면 낡은 조건으로 URL을 덮어써
     * 방금 고른 심각도가 사라진다.
     */
    await expect
      .poll(
        () => {
          const last = listQueries.at(-1) ?? ''
          return last.includes('q=INC') && last.includes('severities=CRITICAL')
        },
        { message: '마지막 조회에 검색어와 심각도가 함께 실려야 한다' },
      )
      .toBe(true)
  })
})
