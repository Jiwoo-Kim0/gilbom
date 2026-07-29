package com.gilbom.robot.credential;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "mqtt.credential-provisioning",
        name = "enabled",
        havingValue = "true")
public class MqttCredentialRegistryDriftJob {

    private final MqttCredentialRegistryService registryService;

    public MqttCredentialRegistryDriftJob(
            MqttCredentialRegistryService registryService
    ) {
        this.registryService = registryService;
    }

    @Scheduled(
            fixedDelayString =
                    "${mqtt.credential-provisioning."
                            + "registry-scan-interval:1m}"
    )
    public void detectRegistryDrift() {
        registryService.scanRegistry();
    }
}
