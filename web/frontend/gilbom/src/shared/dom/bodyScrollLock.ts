let bodyScrollLockCount = 0

/**
 * CSP의 `style-src 'self'`를 유지하면서 modal 뒤의 body scroll을 잠근다.
 *
 * 여러 modal이 겹쳐도 먼저 닫힌 modal이 다른 modal의 lock을 풀지 않도록
 * reference count를 사용한다.
 */
export function lockBodyScroll(): () => void {
  bodyScrollLockCount += 1
  document.body.classList.add('modal-open')

  let released = false
  return () => {
    if (released) return
    released = true
    bodyScrollLockCount = Math.max(0, bodyScrollLockCount - 1)
    if (bodyScrollLockCount === 0) {
      document.body.classList.remove('modal-open')
    }
  }
}
