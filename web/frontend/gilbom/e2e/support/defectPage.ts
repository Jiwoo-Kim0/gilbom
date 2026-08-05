import { expect, type Page, type Route } from '@playwright/test'

/**
 * 결함 화면 e2e가 함께 쓰는 스텁이다.
 *
 * <p>세 스펙(목록 표·필터·검색)이 같은 인증·조회 응답을 필요로 해서 여기로 모았다.
 * 서버를 띄우지 않고 화면만 검증한다. 실제 API 계약은 백엔드 테스트가 지킨다.
 */

export const USER_ID = '83000000-0000-4000-8000-000000000001'

const TYPES = ['DAMAGED', 'MISSING', 'INSTALLATION_INVALID', 'UNKNOWN_ANOMALY']
const RISKS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
const STATUSES = [
  'NEW',
  'ACCEPTED',
  'IN_PROGRESS',
  'RESOLVED',
  'FALSE_POSITIVE',
  'REJECTED',
]
/** 셋째 건은 이름이 없어 구역 코드 + 좌표로 떨어지는 경우를 만든다. */
const LABELS = [
  '역삼역 3번 출구 앞 보도',
  '테헤란로 어린이보호구역 횡단보도',
  null,
  '강남구청역 2번 출구',
]

export async function fulfillJson(
  route: Route,
  body: unknown,
  status = 200,
): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

/**
 * 표 폭을 실제로 시험하려면 가장 긴 값이 필요하다. 결함 코드는 계약 형식
 * (`INC-YYYYMMDD-NNNNNN`) 그대로, 마지막 건은 해가 넘어간 관측이다.
 */
export function defectAt(index: number) {
  return {
    defectId: `83000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
    incidentCode: `INC-20260805-${String(index + 1).padStart(6, '0')}`,
    assetType: 'BRAILLE_BLOCK',
    blockType: 'LINEAR',
    defectType: TYPES[index % TYPES.length],
    riskLevel: RISKS[index % RISKS.length],
    riskScore: 40 + index,
    workflowStatus: STATUSES[index % STATUSES.length],
    resolution: null,
    location: { longitude: 127.04321 + index / 1000, latitude: 37.50123 },
    locationAccuracyM: 1.5,
    zoneId: index % 4 === 2 ? 'ZONE-GN-0007' : null,
    patrolAreaId: null,
    patrolRouteId: null,
    locationLabel: LABELS[index % LABELS.length],
    representativeImageUrl: null,
    firstSeenAt: '2026-08-01T02:00:00Z',
    lastSeenAt: index === 7
      ? '2025-12-30T23:45:00Z'
      : `2026-08-0${(index % 4) + 1}T1${index % 9}:30:00Z`,
    observationCount: index === 7 ? 128 : index + 1,
    latestConfidence: 0.5 + index / 40,
    reviewRequired: index % 5 === 0,
  }
}

interface StubOptions {
  /** 목록에 실을 결함 수. */
  count?: number
  /**
   * 목록 조회가 들어올 때마다 query string을 담는다. 조회 횟수를 세는 스펙이 쓴다.
   */
  listQueries?: string[]
}

/**
 * 실시간 스트림을 껐다. 스텁 서버에는 SSE 종단이 없어 그대로 두면 훅이 계속
 * 재연결을 시도하고, 그 소음이 조회 횟수를 세는 스펙을 흔든다.
 */
async function silenceRealtime(page: Page): Promise<void> {
  await page.addInitScript(() => {
    class SilentEventSource {
      static readonly CONNECTING = 0
      static readonly OPEN = 1
      static readonly CLOSED = 2
      readonly readyState = SilentEventSource.OPEN
      onopen: (() => void) | null = null
      onerror: (() => void) | null = null
      onmessage: (() => void) | null = null
      addEventListener(): void {}
      removeEventListener(): void {}
      close(): void {}
    }
    Object.defineProperty(window, 'EventSource', {
      writable: true,
      value: SilentEventSource,
    })
  })
}

/** 결함 화면이 부르는 API를 모두 막는다. */
export async function stubDefectPage(
  page: Page,
  options: StubOptions = {},
): Promise<ReturnType<typeof defectAt>[]> {
  const { count = 8, listQueries } = options
  const defects = Array.from({ length: count }, (_, index) => defectAt(index))

  await silenceRealtime(page)
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const pathname = url.pathname

    if (pathname === '/api/v1/auth/session-state') {
      await fulfillJson(route, {
        authenticationState: 'AUTHENTICATED',
        challengeExpiresAt: null,
      })
      return
    }
    if (pathname === '/api/v1/auth/me') {
      await fulfillJson(route, {
        userId: USER_ID,
        displayName: '결함 화면 e2e 관리자',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: ['DEFECT_STATUS_VIEW', 'DEFECT_STATUS_UPDATE'],
      })
      return
    }
    if (pathname === '/api/v1/defects/map') {
      await fulfillJson(route, { type: 'FeatureCollection', features: [] })
      return
    }
    if (pathname === '/api/v1/defects') {
      listQueries?.push(url.search)
      await fulfillJson(route, {
        content: defects,
        page: 0,
        size: 20,
        totalElements: defects.length,
        totalPages: 1,
      })
      return
    }
    await fulfillJson(route, {}, 404)
  })

  return defects
}

/** 결함 화면을 열고 목록이 그려질 때까지 기다린다. */
export async function openDefectPage(
  page: Page,
  query = '',
  expectedRows = 8,
): Promise<void> {
  await page.goto(`/defects${query}`)
  await expect(page.locator('.defect-table-body-row')).toHaveCount(expectedRows)
}

/**
 * 검색창에 페이지 안에서 타자를 넣는다.
 *
 * <p>Playwright의 키 입력은 CDP 왕복 때문에 글자 사이가 벌어질 때가 있어, 디바운스
 * 시간(300ms)을 재는 스펙이 실행마다 다르게 실패했다. 간격을 페이지 안에서 만든다.
 * React는 native input 이벤트를 듣기 때문에 value setter를 직접 부르고 이벤트를 쏜다.
 */
export async function typeInSearch(
  page: Page,
  text: string,
  gapMs: number,
): Promise<void> {
  await page.evaluate(
    async ({ text, gapMs }) => {
      const input = document.querySelector<HTMLInputElement>(
        '.defect-search-bar input',
      )
      if (input === null) {
        throw new Error('검색 입력을 찾지 못했다')
      }
      const setValue = Object.getOwnPropertyDescriptor(
        HTMLInputElement.prototype,
        'value',
      )?.set
      if (setValue === undefined) {
        throw new Error('value setter를 찾지 못했다')
      }
      for (const char of text) {
        setValue.call(input, input.value + char)
        input.dispatchEvent(new Event('input', { bubbles: true }))
        await new Promise((resolve) => setTimeout(resolve, gapMs))
      }
    },
    { text, gapMs },
  )
}
