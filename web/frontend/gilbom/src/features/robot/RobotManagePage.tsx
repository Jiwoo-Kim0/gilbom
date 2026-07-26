export function RobotManagePage() {
  return (
    <section className="page-stack" aria-labelledby="robot-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">FLEET</p>
          <h2 id="robot-page-title">등록 로봇과 운행 상태</h2>
          <p>로봇 목록 API가 연결되면 상태·배정·인증정보를 이 화면에서 관리합니다.</p>
        </div>
        <button className="primary-button" type="button" disabled>
          로봇 등록 준비 중
        </button>
      </div>

      <div className="summary-grid" aria-label="로봇 현황">
        <article className="summary-card">
          <span>전체 로봇</span>
          <strong>—</strong>
          <small>API 연결 전</small>
        </article>
        <article className="summary-card">
          <span>온라인</span>
          <strong>—</strong>
          <small>실시간 상태 예정</small>
        </article>
        <article className="summary-card">
          <span>확인 필요</span>
          <strong>—</strong>
          <small>이벤트 연동 예정</small>
        </article>
      </div>

      <div className="content-card empty-state">
        <span className="empty-state-icon" aria-hidden="true">
          RO
        </span>
        <h3>로봇 목록을 연결할 준비가 되었습니다.</h3>
        <p>공통 레이아웃과 라우팅만 제공하며 실제 운영 데이터는 임의로 만들지 않습니다.</p>
      </div>
    </section>
  )
}
