export function RoleManagePage() {
  return (
    <section className="page-stack" aria-labelledby="role-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">ACCESS CONTROL</p>
          <h2 id="role-page-title">역할과 세부 권한</h2>
          <p>ADMIN, MANAGER, OPERATOR 역할과 permission 관계를 관리할 영역입니다.</p>
        </div>
      </div>

      <div className="content-card">
        <div className="table-heading">
          <div>
            <h3>시스템 역할</h3>
            <p>권한 API 연결 전에는 서버 seed를 정본으로 사용합니다.</p>
          </div>
          <span className="status-badge">서버 권한 우선</span>
        </div>
        <div className="role-list">
          {['ADMIN', 'MANAGER', 'OPERATOR'].map((role) => (
            <div className="role-row" key={role}>
              <strong>{role}</strong>
              <span>세부 permission 조회 준비 중</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}
