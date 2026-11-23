const ROBOT_STATUS_LABELS: Readonly<Record<string, string>> = {
  ONLINE: '온라인',
  OFFLINE: '오프라인',
  PROVISIONING: '등록 준비',
  ACTIVE: '운영 중',
  INACTIVE: '비활성',
  MAINTENANCE: '점검 중',
  DISABLED: '사용 중지',
  OK: '정상',
  WARNING: '경고',
  CRITICAL: '심각',
  UNKNOWN: '확인 필요',
  BOOTING: '시작 중',
  READY: '준비',
  PATROLLING: '진행',
  AUTONOMOUS: '진행',
  PAUSED: '정지',
  SAFESTOP: '긴급 정지',
  EMERGENCYSTOP: '긴급 정지',
  EMERGENCYSTOPPED: '긴급 정지',
  EMEGENCYSTOP: '긴급 정지',
  ESTOP: '긴급 정지',
  ERROR: '오류',
  SHUTTINGDOWN: '종료 중',
  CONNECTED: '실시간 연결됨',
  CONNECTING: '실시간 연결 중',
  RECONNECTING: '실시간 재연결 중',
  CREATED: '생성',
  RUNNING: '진행',
  COMPLETED: '완료',
  CANCELLED: '취소',
  FAILED: '실패',
  FAILD: '실패',
}

function normalizeStatus(value: string): string {
  return value.trim().replace(/[\s_-]/g, '').toUpperCase()
}

export function getRobotStatusLabel(
  value: string | null | undefined,
  emptyLabel = '—',
): string {
  if (value === null || value === undefined || value.trim().length === 0) {
    return emptyLabel
  }
  return ROBOT_STATUS_LABELS[normalizeStatus(value)] ?? value
}
