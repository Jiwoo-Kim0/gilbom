import { getApiErrorMessage } from '../../shared/api/apiClient.ts'
import type { DefectImageUrl } from '../defect/defectTypes.ts'

export type ReportEvidenceImageResult =
  | { kind: 'ready'; defectId: string; image: DefectImageUrl }
  | { kind: 'none'; defectId: string }
  | { kind: 'error'; defectId: string; message: string }

type ImageLoader = (defectId: string) => Promise<DefectImageUrl>

export async function loadReportEvidenceImages(
  defectIds: readonly string[],
  imageLoader: ImageLoader,
): Promise<ReportEvidenceImageResult[]> {
  return Promise.all(defectIds.map(async (defectId) => {
    try {
      return {
        kind: 'ready' as const,
        defectId,
        image: await imageLoader(defectId),
      }
    } catch (cause) {
      const status = (cause as { status?: number }).status
      return status === 404
        ? { kind: 'none' as const, defectId }
        : {
            kind: 'error' as const,
            defectId,
            message: getApiErrorMessage(cause),
          }
    }
  }))
}
