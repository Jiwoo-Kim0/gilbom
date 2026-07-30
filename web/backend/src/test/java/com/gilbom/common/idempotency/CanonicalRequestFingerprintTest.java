package com.gilbom.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class CanonicalRequestFingerprintTest {

    private final CanonicalRequestFingerprint fingerprint =
            new CanonicalRequestFingerprint(new ObjectMapper());

    @Test
    void ignoresObjectAndNestedMapFieldOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("title", "주간 보고서");
        first.put("statistics", Map.of(
                "total", 10,
                "critical", 2));

        Map<String, Object> reorderedStatistics =
                new LinkedHashMap<>();
        reorderedStatistics.put("critical", 2);
        reorderedStatistics.put("total", 10);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("statistics", reorderedStatistics);
        second.put("title", "주간 보고서");

        assertThat(fingerprint.hash(first))
                .containsExactly(fingerprint.hash(second));
    }

    @Test
    void changesWhenBodyValueOrArrayOrderChanges() {
        Map<String, Object> original = Map.of(
                "title", "주간 보고서",
                "defectIds", List.of("A", "B"));
        Map<String, Object> changedValue = Map.of(
                "title", "월간 보고서",
                "defectIds", List.of("A", "B"));
        Map<String, Object> changedOrder = Map.of(
                "title", "주간 보고서",
                "defectIds", List.of("B", "A"));

        assertThat(fingerprint.hash(original))
                .isNotEqualTo(fingerprint.hash(changedValue))
                .isNotEqualTo(fingerprint.hash(changedOrder));
    }
}
