export function ReportsPage() {
  return (
    <section className="page-stack" aria-labelledby="reports-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">REPORTS</p>
          <h2 id="reports-page-title">운영 리포트</h2>
          <p>로봇 가동률, 결함 추세 등 집계 리포트가 API 연결 후 이 화면에 표시됩니다.</p>
        </div>
      </div>

      <div className="content-card empty-state">
        <span className="empty-state-icon" aria-hidden="true">
          RP
        </span>
        <h3>리포트를 연결할 준비가 되었습니다.</h3>
        <p>공통 레이아웃과 라우팅만 제공하며 실제 운영 데이터는 임의로 만들지 않습니다.</p>
      </div>
    </section>
  )
}
