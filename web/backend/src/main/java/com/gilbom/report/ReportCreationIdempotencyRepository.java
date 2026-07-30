package com.gilbom.report;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.idempotency.CanonicalRequestFingerprint;
import com.gilbom.common.idempotency.IdempotencyRequestStore;
import com.gilbom.common.idempotency.IdempotencyRequestStore.Endpoint;
import com.gilbom.common.idempotency.IdempotencyRequestStore.Principal;
import com.gilbom.common.idempotency.IdempotencyRequestStore.StoredResponse;

@Repository
public class ReportCreationIdempotencyRepository {

    private static final Endpoint CREATE_REPORT =
            new Endpoint("POST", "/api/v1/reports");

    private final IdempotencyRequestStore requestStore;
    private final CanonicalRequestFingerprint requestFingerprint;
    private final ObjectMapper objectMapper;

    public ReportCreationIdempotencyRepository(
            IdempotencyRequestStore requestStore,
            CanonicalRequestFingerprint requestFingerprint,
            ObjectMapper objectMapper
    ) {
        this.requestStore = requestStore;
        this.requestFingerprint = requestFingerprint;
        this.objectMapper = objectMapper;
    }

    public byte[] fingerprint(Object normalizedRequestBody) {
        return requestFingerprint.hash(normalizedRequestBody);
    }

    public Claim claim(
            ReportActor actor,
            UUID idempotencyKey,
            byte[] requestFingerprint
    ) {
        IdempotencyRequestStore.Claim storeClaim =
                requestStore.claim(
                        new Principal(
                                actor.creatorType().name(),
                                actor.principalKey()),
                        idempotencyKey,
                        requestFingerprint,
                        CREATE_REPORT);
        if (storeClaim.owner()) {
            return Claim.claimed(storeClaim);
        }

        StoredResponse replay = storeClaim.replay();
        ReportRecord report = response(replay.bodyJson());
        if (replay.status() != 201
                || !"REPORT".equals(replay.resourceType())
                || replay.resourceId() == null
                || !replay.resourceId().equals(report.reportId())) {
            throw new IllegalStateException(
                    "Stored report creation response is invalid");
        }
        return Claim.replay(storeClaim, report);
    }

    public void complete(Claim claim, ReportRecord report) {
        String responseBody = response(report);
        requestStore.complete(
                claim.storeClaim(),
                201,
                Map.of(
                        "Location",
                        "/api/v1/reports/" + report.reportId()),
                responseBody,
                "REPORT",
                report.reportId());
    }

    private String response(ReportRecord report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Report creation response could not be stored",
                    exception);
        }
    }

    private ReportRecord response(String value) {
        try {
            return objectMapper.readValue(
                    value,
                    ReportRecord.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored report creation response is invalid",
                    exception);
        }
    }

    public record Claim(
            IdempotencyRequestStore.Claim storeClaim,
            ReportRecord replayReport,
            boolean claimed
    ) {

        static Claim claimed(
                IdempotencyRequestStore.Claim storeClaim
        ) {
            return new Claim(
                    storeClaim,
                    null,
                    true);
        }

        static Claim replay(
                IdempotencyRequestStore.Claim storeClaim,
                ReportRecord report
        ) {
            return new Claim(
                    storeClaim,
                    report,
                    false);
        }
    }
}
