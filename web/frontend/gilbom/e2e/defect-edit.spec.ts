import { expect, test, type Page, type Route } from '@playwright/test'
import { defectAt, fulfillJson, USER_ID } from './support/defectPage'
import { installFakeMap } from './support/fakeNaverMap'

/**
 * 결함 수정 창. 네이티브 `<select>`를 알약 줄로, 체크박스를 스위치로 바꾼 뒤에도
 * **고른 값이 PATCH 본문에 그대로 실리는지**가 관심사다.
 *
 * <p>모양만 바뀌었지 요소는 라디오·체크박스 그대로라 접근성 이름으로 찾을 수 있다.
 * 그 성질도 함께 지킨다.
 */

const TARGET = defectAt(0)

const CSRF_TOKEN = 'defect-edit-e2e-csrf'

/** 서버로 나간 PATCH 본문·헤더를 모은다. */
interface EditStub {
  patches: unknown[]
  csrfHeaders: (string | undefined)[]
  /** PATCH에 이 상태로 답한다. 저장 실패 경로를 시험할 때 바꾼다. */
  patchStatus: number
}

async function stubEditPage(page: Page): Promise<EditStub> {
  const stub: EditStub = { patches: [], csrfHeaders: [], patchStatus: 200 }
  await installFakeMap(page, {
    startZoom: 14,
    baseLat: 37.50123,
    baseLng: 127.04321,
  })

  await page.route('**/api/v1/**', async (route: Route) => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/api/v1/auth/session-state') {
      return fulfillJson(route, {
        authenticationState: 'AUTHENTICATED',
        challengeExpiresAt: null,
      })
    }
    if (pathname === '/api/v1/auth/me') {
      return fulfillJson(route, {
        userId: USER_ID,
        displayName: '결함 수정 e2e',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: ['DEFECT_STATUS_VIEW', 'DEFECT_STATUS_UPDATE'],
      })
    }
    // PATCH·POST는 CSRF 토큰을 먼저 받아온다. 막아두지 않으면 저장이 시작도 못 한다.
    if (pathname === '/api/v1/auth/csrf-token') {
      return fulfillJson(route, {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: CSRF_TOKEN,
      })
    }
    if (pathname === '/api/v1/defects/map') {
      return fulfillJson(route, { type: 'FeatureCollection', features: [] })
    }
    if (pathname === '/api/v1/defects') {
      return fulfillJson(route, {
        content: [TARGET],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      })
    }

    const detail = new RegExp(
      '^/api/v1/defects/([\\w-]+)(/image-url|/observations|/history)?$',
    ).exec(pathname)
    if (detail !== null) {
      const [, , sub] = detail
      if (sub !== undefined) {
        // 사진·관측·이력은 이 시험의 관심사가 아니다. 없는 상태가 정상 경로다.
        return fulfillJson(route, {}, 404)
      }
      if (route.request().method() === 'PATCH') {
        stub.patches.push(route.request().postDataJSON())
        stub.csrfHeaders.push(route.request().headers()['x-csrf-token'])
        if (stub.patchStatus !== 200) {
          return fulfillJson(
            route,
            {
              code: 'DEFECT_NOT_CHANGEABLE',
              message: '종결된 결함은 수정할 수 없습니다.',
            },
            stub.patchStatus,
          )
        }
        return fulfillJson(route, {
          defect: { ...TARGET, riskLevel: 'CRITICAL' },
          resolutionNote: null,
          riskScore: 90,
          riskRuleVersion: 'v1',
          riskCalculatedAt: '2026-08-01T00:00:00Z',
          mergedIntoDefectId: null,
          mergedDefectIds: [],
          createdAt: '2026-08-01T00:00:00Z',
          updatedAt: '2026-08-02T00:00:00Z',
        })
      }
      return fulfillJson(route, {
        defect: TARGET,
        resolutionNote: null,
        riskScore: 40,
        riskRuleVersion: 'v1',
        riskCalculatedAt: '2026-08-01T00:00:00Z',
        mergedIntoDefectId: null,
        mergedDefectIds: [],
        createdAt: '2026-08-01T00:00:00Z',
        updatedAt: '2026-08-01T00:00:00Z',
      })
    }
    return fulfillJson(route, {}, 404)
  })

  return stub
}

async function openEditModal(page: Page): Promise<void> {
  await page.goto('/defects?allStatus=1')
  await expect(page.locator('.defect-table-body-row')).toHaveCount(1)
  await page.locator('.defect-table-body-row').first().click()
  await expect(page.locator('.defect-card')).toBeVisible()
  await page.getByRole('button', { name: '결함 수정' }).click()
  await expect(page.getByRole('dialog', { name: /결함 수정/ })).toBeVisible()
}

test.describe('결함 수정 창', () => {
  test('유형·심각도는 펼쳐진 알약이고 지금 값이 골라져 있다', async ({ page }) => {
    await stubEditPage(page)
    await openEditModal(page)
    const dialog = page.getByRole('dialog', { name: /결함 수정/ })

    // 네이티브 select는 없다. 선택지가 전부 보여야 한다.
    await expect(dialog.locator('select')).toHaveCount(0)

    const types = dialog.getByRole('radiogroup', { name: '결함 유형' })
    await expect(types.getByRole('radio')).toHaveCount(4)
    // 시험 대상은 파손·낮음이다(`defectAt(0)`).
    await expect(types.getByRole('radio', { name: '파손' })).toBeChecked()

    const risks = dialog.getByRole('radiogroup', { name: '심각도' })
    await expect(risks.getByRole('radio')).toHaveCount(4)
    await expect(risks.getByRole('radio', { name: '낮음' })).toBeChecked()

    // 유형 알약에는 지도 마커와 같은 색 점이 붙는다.
    await expect(types.locator('.defect-type-dot')).toHaveCount(4)
  })

  test('바꾼 축과 사유만 저장되고 창이 닫힌다', async ({ page }) => {
    const stub = await stubEditPage(page)
    await openEditModal(page)
    const dialog = page.getByRole('dialog', { name: /결함 수정/ })

    // 아직 바꾼 것이 없으면 저장이 막히고 그 이유가 버튼 옆에 뜬다.
    const save = dialog.getByRole('button', { name: '저장' })
    await expect(save).toBeDisabled()
    await expect(dialog).toContainText('아직 바꾼 항목이 없습니다.')

    await dialog.getByRole('radiogroup', { name: '심각도' })
      .getByText('심각').click()
    // 사유가 비어 있으면 여전히 막힌다 — 무엇이 모자란지 말이 바뀐다.
    await expect(save).toBeDisabled()
    await expect(dialog).toContainText('수정 사유를 적어야')

    await dialog.getByLabel('수정 사유').fill('현장 확인 결과 파손 심함')
    await expect(save).toBeEnabled()
    await save.click()

    await expect(dialog).toHaveCount(0)
    // 손대지 않은 축은 본문에 없어야 한다. 서버가 null을 "변경 없음"으로 읽는다.
    expect(stub.patches).toEqual([
      { reason: '현장 확인 결과 파손 심함', riskLevel: 'CRITICAL' },
    ])
    expect(stub.csrfHeaders).toEqual([CSRF_TOKEN])
  })

  test('저장이 실패하면 창을 닫지 않고 이유를 보여준다', async ({ page }) => {
    const stub = await stubEditPage(page)
    stub.patchStatus = 409
    await openEditModal(page)
    const dialog = page.getByRole('dialog', { name: /결함 수정/ })

    await dialog.getByRole('radiogroup', { name: '심각도' })
      .getByText('높음').click()
    await dialog.getByLabel('수정 사유').fill('심각도 조정')
    await dialog.getByRole('button', { name: '저장' }).click()

    await expect(dialog.getByRole('alert'))
      .toContainText('종결된 결함은 수정할 수 없습니다.')
    // 창이 닫히면 방금 적은 사유가 사라진다. 고친 값을 그대로 들고 남아 있어야 한다.
    await expect(dialog).toBeVisible()
    await expect(dialog.getByLabel('수정 사유')).toHaveValue('심각도 조정')
  })

  test('재검토 스위치는 켜고 끌 수 있는 체크박스다', async ({ page }) => {
    const stub = await stubEditPage(page)
    await openEditModal(page)
    const dialog = page.getByRole('dialog', { name: /결함 수정/ })

    // 모양은 스위치지만 요소는 체크박스라 이름으로 찾힌다. 대상은 켜진 상태다.
    const review = dialog.getByRole('checkbox', { name: /재검토 필요/ })
    await expect(review).toBeChecked()

    await dialog.locator('.defect-switch').click()
    await expect(review).not.toBeChecked()

    await dialog.getByLabel('수정 사유').fill('오탐이라 재검토 해제')
    await dialog.getByRole('button', { name: '저장' }).click()

    await expect(dialog).toHaveCount(0)
    expect(stub.patches).toEqual([
      { reason: '오탐이라 재검토 해제', reviewRequired: false },
    ])
  })

  test('고정된 상단 바보다 앞에 나오고 상단 바까지 덮는다', async ({ page }) => {
    await stubEditPage(page)
    await openEditModal(page)

    const layers = await page.evaluate(() => {
      const overlay = document.querySelector('.defect-modal-backdrop')!
      const topbar = document.querySelector('.topbar')!
      const sidebar = document.querySelector('.nb-sidebar')!
      /** 그 자리에서 가장 위에 있는 것이 이 창(막 또는 카드)인가. */
      const overlayOnTopOf = (element: Element) => {
        const rect = element.getBoundingClientRect()
        const top = document.elementFromPoint(
          rect.left + rect.width / 2,
          rect.top + rect.height / 2,
        )
        return top !== null && overlay.contains(top)
      }
      const box = overlay.getBoundingClientRect()
      return {
        overOnTopbar: overlayOnTopOf(topbar),
        overOnSidebar: overlayOnTopOf(sidebar),
        coversViewport:
          Math.round(box.top) === 0
          && Math.round(box.height) === window.innerHeight,
        overlayZ: Number(getComputedStyle(overlay).zIndex),
        topbarZ: Number(getComputedStyle(topbar).zIndex),
        sidebarZ: Number(getComputedStyle(sidebar).zIndex),
        dialogTop: Math.round(
          document.querySelector('.defect-edit-modal')!
            .getBoundingClientRect().top,
        ),
      }
    })

    /*
     * 예전에는 이 막이 z-index 40이라 상단 바(50) **뒤로** 깔렸다. 창이 제목에 잘리고
     * 어두운 막이 상단 바만 비껴가 그 줄만 환하게 남았다.
     */
    expect(layers.overOnTopbar, '상단 바 자리에서 이 창이 가장 위여야 한다')
      .toBe(true)
    expect(layers.overOnSidebar, '왼쪽 바도 함께 덮는다').toBe(true)
    expect(layers.coversViewport, '막이 화면 전체를 덮는다').toBe(true)
    // 층 순서 자체를 못박는다. 숫자가 뒤집히면 어두운 막이 그 바만 비껴간다.
    expect(layers.overlayZ).toBeGreaterThan(layers.topbarZ)
    expect(layers.overlayZ).toBeGreaterThan(layers.sidebarZ)
    // 창 자체도 상단 바에 잘리지 않는다.
    expect(layers.dialogTop).toBeGreaterThanOrEqual(0)
  })

  test('Esc로 닫힌다', async ({ page }) => {
    await stubEditPage(page)
    await openEditModal(page)

    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog', { name: /결함 수정/ })).toHaveCount(0)
    // 뒤에 있던 상세 카드는 그대로 남는다.
    await expect(page.locator('.defect-card')).toBeVisible()
  })
})
