let scriptPromise: Promise<void> | null = null

// Naver Maps는 client id별 <script> 태그 로드 방식만 제공하므로 앱 전체에서 한 번만 주입한다.
// 통합 콘솔에서 새로 발급된 Client ID는 ncpClientId가 아닌 ncpKeyId 파라미터를 사용한다.
export function loadNaverMapsScript(clientId: string): Promise<void> {
  if (window.naver?.maps) {
    return Promise.resolve()
  }

  if (!scriptPromise) {
    scriptPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${clientId}`
      script.async = true
      script.onload = () => resolve()
      script.onerror = () => {
        scriptPromise = null
        reject(new Error('Naver Maps 스크립트를 불러오지 못했습니다.'))
      }
      document.head.appendChild(script)
    })
  }

  return scriptPromise
}
