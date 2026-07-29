package com.gilbom.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.defect.DefectDtos.DefectMergeRequest;
import com.gilbom.defect.DefectRepository.DefectAggregate;
import com.gilbom.defect.DefectRepository.DefectState;
import com.gilbom.defect.realtime.DefectRealtimeEvent;

/**
 * 병합이 SSE 이벤트를 발행하는지 검증한다.
 *
 * <p>통합 테스트의 fixture는 목록 내용을 정확히 단정하므로 병합용 결함을 추가하면
 * 형제 테스트가 깨진다. 발행 여부는 순수 로직이라 단위 수준에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefectCommandServiceMergeEventTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000051");
    private static final UUID TARGET_ID =
            UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-000000000051");
    private static final UUID SOURCE_ONE_ID =
            UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-000000000052");
    private static final UUID SOURCE_TWO_ID =
            UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-000000000053");
    private static final UUID PATROL_AREA_ID =
            UUID.fromString("3c9a2f4e-1c4d-4a5b-9c8d-000000000051");
    private static final Instant NOW =
            Instant.parse("2026-07-29T02:00:00Z");

    @Mock
    private DefectRepository repository;

    @Mock
    private DefectQueryService queryService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DefectCommandService service;

    @BeforeEach
    void setUp() {
        service = new DefectCommandService(
                repository,
                queryService,
                auditLogService,
                eventPublisher);

        when(repository.lockForUpdate(eq(USER_ID), eq(TARGET_ID), any()))
                .thenReturn(java.util.Optional.of(state(
                        TARGET_ID,
                        "INC-20260729-000051")));
        when(repository.lockForUpdate(eq(USER_ID), eq(SOURCE_ONE_ID), any()))
                .thenReturn(java.util.Optional.of(state(
                        SOURCE_ONE_ID,
                        "INC-20260729-000052")));
        when(repository.lockForUpdate(eq(USER_ID), eq(SOURCE_TWO_ID), any()))
                .thenReturn(java.util.Optional.of(state(
                        SOURCE_TWO_ID,
                        "INC-20260729-000053")));
        when(repository.markMergedInto(any(), eq(TARGET_ID))).thenReturn(1);
        when(repository.recalculateAggregate(TARGET_ID))
                .thenReturn(new DefectAggregate(3, NOW, NOW));
    }

    @Test
    void publishesOneEventPerAbsorbedDefectAndOneForRepresentative() {
        service.merge(
                USER_ID,
                new DefectMergeRequest(
                        TARGET_ID,
                        List.of(SOURCE_ONE_ID, SOURCE_TWO_ID),
                        "동일 위치 중복"),
                AuditRequestContext.requestIdOnly(UUID.randomUUID()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.times(3))
                .publishEvent(captor.capture());

        List<DefectRealtimeEvent> events = captor.getAllValues().stream()
                .map(DefectRealtimeEvent.class::cast)
                .toList();

        // 흡수된 결함 2건은 mergedIntoDefectId로 대표를 가리킨다.
        assertThat(events)
                .filteredOn(event -> event.mergedIntoDefectId() != null)
                .hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.eventType()).isEqualTo("defect.updated");
                    assertThat(event.mergedIntoDefectId()).isEqualTo(TARGET_ID);
                    assertThat(event.changedFields())
                            .containsExactly("mergedIntoDefectId");
                    // 구독자의 구역 필터가 동작하려면 patrolAreaId가 실려야 한다.
                    assertThat(event.patrolAreaId()).isEqualTo(PATROL_AREA_ID);
                })
                .extracting(DefectRealtimeEvent::defectId)
                .containsExactlyInAnyOrder(SOURCE_ONE_ID, SOURCE_TWO_ID);

        // 대표 결함은 집계 변경을 알린다.
        assertThat(events)
                .filteredOn(event -> event.mergedIntoDefectId() == null)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.defectId()).isEqualTo(TARGET_ID);
                    assertThat(event.eventType()).isEqualTo("defect.updated");
                    assertThat(event.changedFields()).containsExactly(
                            "observationCount",
                            "firstSeenAt",
                            "lastSeenAt");
                });
    }

    private static DefectState state(UUID defectId, String incidentCode) {
        return new DefectState(
                defectId,
                incidentCode,
                "LINEAR",
                "DAMAGED",
                "HIGH",
                null,
                "NEW",
                null,
                null,
                127.01,
                37.51,
                null,
                "A-01",
                null,
                false,
                PATROL_AREA_ID,
                null,
                NOW,
                NOW,
                1);
    }
}
