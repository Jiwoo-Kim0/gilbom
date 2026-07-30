package com.gilbom.report.generation;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 보고서 기간의 상위 결함 대표 이미지 바이트를 읽는 소비자 Port다.
 *
 * <p>구현체는 기간 내 대표 결함 상위 N건에서 각 대표 관측 이미지 한 장을 선택한다.
 * Presigned URL, bucket, object key와 로컬 경로는 반환하지 않는다.
 */
public interface DefectImageContentReader {

    int DEFAULT_MAX_IMAGES = 1;
    int MAX_IMAGES = 3;

    List<ImageContent> readTopDefectImages(
            ReportPeriod period,
            int maxImages);

    record ReportPeriod(
            Instant from,
            Instant to
    ) {

        public ReportPeriod {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            if (to.isBefore(from)) {
                throw new IllegalArgumentException(
                        "Report image period is invalid");
            }
        }
    }

    final class ImageContent {

        private final UUID defectId;
        private final String incidentCode;
        private final byte[] bytes;
        private final String contentType;
        private final int width;
        private final int height;

        public ImageContent(
                UUID defectId,
                String incidentCode,
                byte[] bytes,
                String contentType,
                int width,
                int height
        ) {
            this.defectId = Objects.requireNonNull(defectId);
            this.incidentCode = Objects.requireNonNull(
                    incidentCode);
            this.bytes = Arrays.copyOf(
                    Objects.requireNonNull(bytes),
                    bytes.length);
            this.contentType = Objects.requireNonNull(
                    contentType);
            this.width = width;
            this.height = height;
        }

        public UUID defectId() {
            return defectId;
        }

        public String incidentCode() {
            return incidentCode;
        }

        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        public String contentType() {
            return contentType;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        @Override
        public String toString() {
            return "ImageContent{"
                    + "defectId=" + defectId
                    + ", incidentCode='" + incidentCode + '\''
                    + ", bytes=[REDACTED]"
                    + ", byteSize=" + bytes.length
                    + ", contentType='" + contentType + '\''
                    + ", width=" + width
                    + ", height=" + height
                    + '}';
        }
    }
}
