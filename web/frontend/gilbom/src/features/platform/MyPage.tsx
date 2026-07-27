export function MyPage() {
  return (
    <section className="page-stack" aria-labelledby="my-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">ACCOUNT</p>
          <h2 id="my-page-title">내 계정</h2>
          <p>프로필, 비밀번호, MFA, 활성 세션 관리 기능이 배치될 공통 화면입니다.</p>
        </div>
      </div>

      <div className="settings-grid">
        <article className="content-card">
          <h3>프로필</h3>
          <p>로그인 사용자 API 연결 후 이름과 계정 상태를 표시합니다.</p>
        </article>
        <article className="content-card">
          <h3>보안</h3>
          <p>비밀번호 변경과 TOTP 재등록은 서버 재인증을 통과한 뒤 실행합니다.</p>
        </article>
      </div>
    </section>
  )
}
