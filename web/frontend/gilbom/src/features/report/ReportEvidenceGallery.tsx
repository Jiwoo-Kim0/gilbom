import {
  useEffect,
  useState,
} from 'react'
import { getDefectImageUrl } from '../defect/defectApi'
import {
  loadReportEvidenceImages,
  type ReportEvidenceImageResult,
} from './reportEvidence'

type ReportEvidenceGalleryProps = {
  defectIds: readonly string[]
  canView: boolean
}

type GalleryState =
  | { kind: 'loading' }
  | { kind: 'ready'; results: ReportEvidenceImageResult[] }

export function ReportEvidenceGallery({
  defectIds,
  canView,
}: ReportEvidenceGalleryProps) {
  const [state, setState] = useState<GalleryState>({ kind: 'loading' })

  useEffect(() => {
    if (!canView || defectIds.length === 0) {
      return
    }
    let cancelled = false
    void loadReportEvidenceImages(defectIds, getDefectImageUrl).then((results) => {
      if (!cancelled) {
        setState({ kind: 'ready', results })
      }
    })
    return () => {
      cancelled = true
    }
  }, [canView, defectIds])

  if (defectIds.length === 0) {
    return null
  }

  if (!canView) {
    return (
      <section className="report-evidence-section" aria-labelledby="report-evidence-title">
        <h4 id="report-evidence-title">결함 이미지</h4>
        <p className="report-evidence-note">
          결함 이미지를 보려면 결함 조회 권한이 필요합니다.
        </p>
      </section>
    )
  }

  const readyImages = state.kind === 'ready'
    ? state.results.filter((result) => result.kind === 'ready')
    : []
  const failedCount = state.kind === 'ready'
    ? state.results.filter((result) => result.kind === 'error').length
    : 0

  return (
    <section className="report-evidence-section" aria-labelledby="report-evidence-title">
      <h4 id="report-evidence-title">결함 이미지</h4>
      {state.kind === 'loading' ? (
        <p className="report-evidence-note" role="status">
          결함 이미지를 불러오는 중입니다.
        </p>
      ) : null}
      {state.kind === 'ready' && readyImages.length === 0 ? (
        <p className="report-evidence-note">
          표시할 수 있는 결함 이미지가 없습니다.
        </p>
      ) : null}
      {readyImages.length > 0 ? (
        <div className="report-evidence-gallery">
          {readyImages.map(({ defectId, image }, index) => (
            <figure key={defectId} className="report-evidence-figure">
              <img
                src={image.imageUrl}
                alt={`결함 증거 이미지 ${index + 1}`}
                loading="lazy"
                decoding="async"
                referrerPolicy="no-referrer"
              />
              <figcaption>결함 근거 {index + 1}</figcaption>
            </figure>
          ))}
        </div>
      ) : null}
      {failedCount > 0 ? (
        <p className="report-evidence-note" role="status">
          일부 결함 이미지를 불러오지 못했습니다.
        </p>
      ) : null}
    </section>
  )
}
