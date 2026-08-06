import { useEffect, useState } from 'react'
import { ImageOff, X } from 'lucide-react'
import { getDefect, getDefectImageUrl } from './defectApi'
import type { DefectMapFeature } from './defectTypes'

/**
 * 이 수 이하로 묶인 덩어리만 목록으로 편다. 더 많으면 목록이 지도를 가릴 뿐이고,
 * 결함 하나마다 상세·이미지를 두 번씩 조회하는 비용도 그만큼 늘어난다.
 * 그 위는 지금처럼 확대만 한다.
 */
export const CLUSTER_LIST_MAX = 10

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
 * <p>지도 GeoJSON에는 결함 코드까지만 담겨 있어 위치 이름과 사진은 결함마다 따로
 * 조회한다. {@link CLUSTER_LIST_MAX}건이 상한이라 최대 20번이고, 누른 순간에만 나간다.
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
  // 배열은 매 렌더 새 참조라 그대로 의존성에 넣으면 조회가 멈추지 않는다.
  const defectIds = features
    .map((feature) => feature.properties.defectId)
    .join(',')

  useEffect(() => {
    let cancelled = false

    const patch = (defectId: string, partial: ClusterEntry) => {
      if (cancelled) return
      setEntries((previous) => ({
        ...previous,
        [defectId]: { ...previous[defectId], ...partial },
      }))
    }

    for (const feature of features) {
      const { defectId } = feature.properties
      getDefect(defectId)
        .then((detail) =>
          patch(defectId, {
            location: detail.defect.locationLabel
              ?? detail.defect.zoneId
              ?? null,
          }),
        )
        .catch(() => patch(defectId, { location: null }))
      getDefectImageUrl(defectId)
        // 사진이 아직 연결되지 않은 결함은 정상이다(404). 오류도 "없음"으로 접는다 —
        // 목록에서 실패를 따로 말해봐야 할 수 있는 일이 없다.
        .then((image) => patch(defectId, { imageUrl: image.imageUrl }))
        .catch(() => patch(defectId, { imageUrl: null }))
    }

    return () => {
      cancelled = true
    }
    // features는 같은 내용이어도 참조가 바뀐다. 구성원이 실제로 달라질 때만 다시 읽는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defectIds])

  return (
    <section className="defect-cluster-list" aria-label="묶인 결함 목록">
      <header className="defect-cluster-list-header">
        <h3>이 위치의 결함 {features.length}건</h3>
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
