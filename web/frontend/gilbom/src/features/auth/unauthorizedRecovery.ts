/**
 * 401 한 건을 곧바로 로그아웃으로 옮기지 않기 위한 정책이다.
 *
 * <p>예전에는 어떤 요청이든 401이면 그 자리에서 인증 상태를 지웠고, 라우트 가드가 곧바로
 * `/login`으로 보냈다. 화면 한 귀퉁이의 보조 조회 하나가 실패했을 뿐인데 **로그아웃 버튼을
 * 누른 것과 같은 일**이 벌어졌다. 지도 묶음 목록처럼 요청 여러 개가 한꺼번에 나가는 곳에서
 * 특히 잘 드러났다.
 *
 * <p>그래서 401을 "로그아웃하라"가 아니라 "세션을 다시 확인해봐라"로 읽는다. 세션이
 * 살아 있으면 그 요청만 실패한 것이고, 화면은 그대로 둔다.
 *
 * <p>확인이 도는 동안 들어온 401은 **버린다.** 같은 세션에 대한 같은 질문이라 다시 물어야
 * 할 이유가 없다 — 스무 개가 한꺼번에 401이어도 확인은 한 번이다.
 */
export type SessionProbe = () => Promise<boolean>

export function createUnauthorizedRecovery(
  /** 아직 로그인 상태인가. 이 조회 자체는 401을 다시 알리지 않아야 한다(무한 되돌이). */
  probe: SessionProbe,
  signOut: () => void,
): () => void {
  let checking = false

  return () => {
    if (checking) {
      return
    }
    checking = true
    void probe()
      .then((stillAuthenticated) => {
        if (!stillAuthenticated) {
          signOut()
        }
      })
      /*
       * 확인에 실패하면 **로그아웃 쪽으로 기운다.** 세션이 살아 있다고 말해줄 근거가
       * 없는데 화면을 열어두면, 실제로 끊긴 세션에서 사용자가 계속 조작하게 된다.
       */
      .catch(() => signOut())
      .finally(() => {
        checking = false
      })
  }
}
