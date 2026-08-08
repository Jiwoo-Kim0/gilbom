import assert from 'node:assert/strict'
import test from 'node:test'
import { createUnauthorizedRecovery } from '../src/features/auth/unauthorizedRecovery.ts'

/**
 * 401 한 건이 로그아웃으로 증폭되지 않는지 본다.
 *
 * <p>지도 묶음을 누르면 보조 조회가 한꺼번에 나간다. 그중 하나가 401이었을 때 전역 처리가
 * 인증 상태를 지우고 라우트 가드가 `/login`으로 보내, 로그아웃 버튼을 누른 적이 없는데도
 * 로그인 화면으로 튕겼다.
 */

/** 마이크로태스크 큐를 비운다. `probe`가 몇 번 불렸는지 보려면 한 바퀴 돌려야 한다. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

test('세션이 살아 있으면 401이 와도 로그아웃하지 않는다', async () => {
  let signedOut = 0
  const recover = createUnauthorizedRecovery(async () => true, () => {
    signedOut += 1
  })

  recover()
  await flush()

  assert.equal(signedOut, 0, '보조 조회 하나의 401로 세션을 지우면 안 된다')
})

test('세션이 정말 끊겼으면 로그아웃한다', async () => {
  let signedOut = 0
  const recover = createUnauthorizedRecovery(async () => false, () => {
    signedOut += 1
  })

  recover()
  await flush()

  assert.equal(signedOut, 1)
})

test('한꺼번에 들어온 401 스무 건에도 세션 확인은 한 번이다', async () => {
  let probes = 0
  let signedOut = 0
  const recover = createUnauthorizedRecovery(
    async () => {
      probes += 1
      return true
    },
    () => {
      signedOut += 1
    },
  )

  // 묶음 10건이 상세·이미지를 따로 부르던 시절의 요청 수다.
  for (let index = 0; index < 20; index += 1) {
    recover()
  }
  await flush()

  assert.equal(probes, 1, '같은 세션에 대한 같은 질문을 스무 번 할 이유가 없다')
  assert.equal(signedOut, 0)
})

test('확인이 끝난 뒤의 401은 다시 확인한다', async () => {
  let probes = 0
  const answers = [true, false]
  let signedOut = 0
  const recover = createUnauthorizedRecovery(
    async () => {
      probes += 1
      return answers.shift() ?? false
    },
    () => {
      signedOut += 1
    },
  )

  recover()
  await flush()
  // 첫 확인이 끝난 뒤라 이번에는 막히지 않는다. 그새 세션이 끊겼으면 이때 지운다.
  recover()
  await flush()

  assert.equal(probes, 2)
  assert.equal(signedOut, 1)
})

test('세션 확인 자체가 실패해도 증거 없이 로그아웃하지 않는다', async () => {
  let signedOut = 0
  const recover = createUnauthorizedRecovery(
    () => Promise.reject(new Error('세션 조회 실패')),
    () => {
      signedOut += 1
    },
  )

  recover()
  await flush()

  assert.equal(
    signedOut,
    0,
    '확인 요청 실패는 서버 세션이 삭제됐다는 증거가 아니다',
  )
})

test('확인 실패 뒤 다음 401에서는 세션 상태를 다시 확인한다', async () => {
  let probes = 0
  let signedOut = 0
  const recover = createUnauthorizedRecovery(
    async () => {
      probes += 1
      if (probes === 1) {
        throw new Error('일시적인 세션 조회 실패')
      }
      return false
    },
    () => {
      signedOut += 1
    },
  )

  recover()
  await flush()
  assert.equal(signedOut, 0)

  recover()
  await flush()
  assert.equal(probes, 2)
  assert.equal(signedOut, 1)
})
