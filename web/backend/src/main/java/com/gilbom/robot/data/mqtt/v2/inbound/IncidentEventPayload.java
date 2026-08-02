package com.gilbom.robot.data.mqtt.v2.inbound;

import com.gilbom.robot.data.mqtt.v2.common.EventSource;
import com.gilbom.robot.data.mqtt.v2.common.MqttEventPayload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IncidentEventPayload(
        @NotBlank @Pattern(regexp = "2\\.0") String schemaVersion,
        @NotNull UUID eventId,
        @NotNull EventType eventType,
        @NotBlank String mqttId,
        @NotNull UUID bootId,
        @NotNull @Positive Long seq,
        @NotNull EventSource sourceComponent,
        @NotNull Instant occurredAt,
        @NotNull Instant sentAt,
        @NotNull @Positive @Min(60) @Max(300) Integer ttlSec,
        String missionId,
        @NotNull @Valid Location location,
        @NotNull @Valid Incident incident,
        @NotNull @Valid Evidence evidence,
        @NotNull @Valid Model model
) implements MqttEventPayload {

    @AssertTrue(message = "incident event source_component must be ai")
    public boolean isSourceValid() {
        return sourceComponent == null || sourceComponent == EventSource.AI;
    }

    public record Location(
            @NotNull Boolean valid,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
            String zoneId
    ) {

        @AssertTrue(message = "location valid flag and coordinates are inconsistent")
        public boolean isValidityConsistent() {
            if (valid == null) {
                return true;
            }
            return valid
                    ? lat != null && lon != null
                    : lat == null && lon == null;
        }
    }

    public record Incident(
            @NotNull AssetType assetType,
            @NotNull BlockType blockType,
            @NotNull DefectType defectType,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
            @Valid Attributes attributes
    ) {
    }

    public record Attributes(
            @DecimalMin("0.0") @DecimalMax("1.0") Double damageRatio
    ) {
    }

    public record Evidence(
            UUID imageId,
            @NotNull Instant capturedAt,
            @NotNull @Positive Integer imageWidth,
            @NotNull @Positive Integer imageHeight,
            @NotNull @Size(min = 4, max = 4)
            List<@NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double> bboxNorm
    ) {

        @AssertTrue(message = "bbox_norm must satisfy x_min < x_max and y_min < y_max")
        public boolean isBoundingBoxOrdered() {
            if (bboxNorm == null
                    || bboxNorm.size() != 4
                    || bboxNorm.stream().anyMatch(value -> value == null)) {
                return true;
            }
            return bboxNorm.get(0) < bboxNorm.get(2)
                    && bboxNorm.get(1) < bboxNorm.get(3);
        }
    }

    public record Model(
            @NotBlank String name,
            @NotBlank String version
    ) {
    }

    public enum EventType {
        BRAILLE_BLOCK_INCIDENT_DETECTED
    }

    public enum AssetType {
        BRAILLE_BLOCK
    }

    public enum BlockType {
        LINEAR,
        DOT,
        UNKNOWN
    }

    public enum DefectType {
        DAMAGED
    }
}
