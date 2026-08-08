import { useEffect, useState } from 'react'
import { ImageOff, X } from 'lucide-react'
import { getDefectClusterDetails } from './defectApi'
import type { DefectClusterItem, DefectMapFeature } from './defectTypes'

/**
 * 이 수 이하로 묶인 덩어리만 목록으로 편다. 더 많으면 목록이 지도를 가릴 뿐이다.
 * 그 위는 지금처럼 확대만 한다.
 *
 * <p>서버의 `cluster-details` 상한(50)보다 넉넉히 작다. 이 값을 조금 올려도 400이
 * 나지는 않는다.
 */
export const CLUSTER_LIST_MAX = 10

/**
 * 목록에 담는 줄 수의 상한.
 *
 * <p>목록은 누른 묶음이 아니라 **확대된 화면에 보이는 결함 전부**를 담는다(`DefectMap`의
 * 묶음 클릭 처리 참고). 조밀한 동네에서는 그 수가 크게 늘 수 있어 서버
 * `cluster-details` 상한(50) 아래로 자른다. 자를 때는 가운데(누른 자리)에서 가까운 것부터
 * 남긴다.
 */
export const CLUSTER_LIST_VISIBLE_MAX = 30

/**
 * `undefined`는 아직 조회 중, `null`은 없음이다. 둘을 구분해야 사진이 없는 결함에
 * 자리표시가 영영 도는 것을 막는다.
 */
type ClusterEntry = {
  location?: string | null
  imageUrl?: string | null
}

interface DefectClusterListProps {
  features: DefectMapFeature[]
  selectedDefectId: string | null
  /** 좌표를 함께 넘긴다. 상위가 그 마커를 지도 가운데로 옮긴다. */
  onSelect: (defectId: string, latitude: number, longitude: number) => void
  onClose: () => void
}

function formatCoordinates(feature: DefectMapFeature): string {
  const [longitude, latitude] = feature.geometry.coordinates
  return `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`
}

/**
 * 묶음을 눌렀을 때 그 안에 무엇이 있는지 보여주는 지도 위 목록이다.
 *
 * <p>확대만 해서는 "이 동네에 몇 건"까지만 알 수 있고, 결함 코드를 보려면 마커를 하나씩
 * 눌러봐야 했다. 여기서 코드·사진·위치를 한 번에 훑고 고른다.
 *
 * <p>줄을 누르면 목록 표에서 고른 것과 같다 — 오른쪽에 상세 카드가 뜨고 지도의 그 마커에
 * 선택 표시가 붙는다. 목록은 닫지 않는다. 형제 결함을 이어서 훑는 것이 이 목록의 쓸모다.
 *
 * <p>지도 GeoJSON에는 결함 코드까지만 담겨 있어 위치 이름과 사진은 따로 조회한다.
 * 결함마다 상세·이미지를 하나씩 부르면 10건짜리 묶음 하나에 스무 개가 한꺼번에 나갔고,
 * 그중 하나만 401이어도 전역 처리가 로그인을 끊었다. **한 번의 묶음 조회로 받는다.**
 */
export function DefectClusterList({
  features,
  selectedDefectId,
  onSelect,
  onClose,
}: DefectClusterListProps) {
  /*
   * 다른 묶음을 누르면 상위가 `key`를 바꿔 이 컴포넌트를 새로 마운트한다. 그래서
   * 여기서 이전 묶음의 조회 결과를 지울 일이 없다.
   */
  const [entries, setEntries] = useState<Record<string, ClusterEntry>>({})
  const defectIds = features.map((feature) => feature.properties.defectId)
  // 배열은 매 렌더 새 참조라 그대로 의존성에 넣으면 조회가 멈추지 않는다.
  const defectKey = defectIds.join(',')

  useEffect(() => {
    if (defectIds.length === 0) {
      return
    }
    /*
     * 목록을 닫거나 다른 묶음을 누르면 **요청 자체를 끊는다.** 결과만 버리면 서버 일은
     * 그대로 남아, 여러 번 눌렀을 때 이전 요청들이 계속 살아 있다.
     */
    const controller = new AbortController()

    /**
     * 조회가 끝나면 요청한 결함 **전부**에 값을 채운다. 응답에 없는 결함(그새 안 보이게
     * 됐거나 병합된 것)까지 `null`로 못박아야 사진 자리표시가 영영 돌지 않는다.
     */
    const settle = (items: DefectClusterItem[]) => {
      const byId = new Map(items.map((item) => [item.defectId, item]))
      setEntries(Object.fromEntries(defectIds.map((defectId) => {
        const item = byId.get(defectId)
        return [defectId, {
          location: item?.locationLabel ?? item?.zoneId ?? null,
          imageUrl: item?.imageUrl ?? null,
        } satisfies ClusterEntry]
      })))
    }

    getDefectClusterDetails(defectIds, controller.signal)
      .then((response) => settle(response.items))
      // 목록에서 실패를 따로 말해봐야 할 수 있는 일이 없다. 사진·위치 없음으로 접는다.
      .catch(() => {
        if (!controller.signal.aborted) {
          settle([])
        }
      })

    return () => controller.abort()
    // features는 같은 내용이어도 참조가 바뀐다. 구성원이 실제로 달라질 때만 다시 읽는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defectKey])

  return (
    <section className="defect-cluster-list" aria-label="묶인 결함 목록">
      <header className="defect-cluster-list-header">
        {/* 담긴 것이 "누른 묶음"이 아니라 "지금 화면"이라 말도 그렇게 바꿨다. */}
        <h3>화면의 결함 {features.length}건</h3>
        <button
          type="button"
          className="defect-card-close"
          aria-label="묶인 결함 목록 닫기"
          onClick={onClose}
        >
          <X size={16} aria-hidden="true" />
        </button>
      </header>

      <ul className="defect-cluster-list-items">
        {features.map((feature) => {
          const { defectId, incidentCode } = feature.properties
          const [longitude, latitude] = feature.geometry.coordinates
          const entry = entries[defectId] ?? {}
          const location = entry.location ?? formatCoordinates(feature)
          return (
            <li key={defectId}>
              <button
                type="button"
                className={`defect-cluster-list-item${
                  defectId === selectedDefectId ? ' is-selected' : ''
                }`}
                aria-current={defectId === selectedDefectId}
                onClick={() => onSelect(defectId, latitude, longitude)}
              >
                <span className="defect-cluster-list-photo">
                  {entry.imageUrl === undefined && (
                    <span
                      className="defect-cluster-list-photo-loading"
                      aria-hidden="true"
                    />
                  )}
                  {entry.imageUrl !== undefined
                    && (entry.imageUrl === null ? (
                      <ImageOff size={18} aria-label="사진 없음" />
                    ) : (
                      <img src={entry.imageUrl} alt="" />
                    ))}
                </span>
                <span className="defect-cluster-list-text">
                  <strong>{incidentCode}</strong>
                  <span className="defect-cluster-list-location">
                    {location}
                  </span>
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
