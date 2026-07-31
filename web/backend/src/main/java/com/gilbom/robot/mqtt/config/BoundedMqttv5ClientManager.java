package com.gilbom.robot.mqtt.config;

import java.lang.reflect.Method;

import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.springframework.integration.mqtt.core.Mqttv5ClientManager;
import org.springframework.util.ReflectionUtils;

/**
 * MQTT 연결 종료의 quiesce와 disconnect 대기를 모두 운영 상한 안으로 제한한다.
 *
 * <p>Spring Integration의 기본 manager는 Paho의 단일 인자
 * {@code disconnectForcibly(timeout)}를 호출한다. 이 오버로드는 전달한 값을
 * disconnect timeout으로만 사용하고 quiesce timeout은 30초로 고정한다. Docker의
 * stop grace period도 30초이므로 Broker 장애 중에는 정상 종료가 SIGKILL 경계까지
 * 밀릴 수 있다.
 */
public final class BoundedMqttv5ClientManager
        extends Mqttv5ClientManager {

    private static final Method STOP_RECONNECT_CYCLE_METHOD =
            findStopReconnectCycleMethod();

    public BoundedMqttv5ClientManager(
            MqttConnectionOptions connectionOptions,
            String clientId
    ) {
        super(connectionOptions, clientId);
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            IMqttAsyncClient client = getClient();
            if (client == null) {
                return;
            }

            try {
                long timeout = getDisconnectCompletionTimeout();
                client.disconnectForcibly(timeout, timeout, false);
                if (getConnectionInfo().isAutomaticReconnect()
                        && client instanceof MqttAsyncClient mqttClient) {
                    ReflectionUtils.invokeMethod(
                            STOP_RECONNECT_CYCLE_METHOD,
                            mqttClient);
                }
            } catch (MqttException exception) {
                logger.error("Could not disconnect from the MQTT client",
                        exception);
            } finally {
                try {
                    client.close(true);
                } catch (MqttException exception) {
                    logger.error("Could not close the MQTT client", exception);
                }
                setClient(null);
            }
        } finally {
            lock.unlock();
        }
    }

    private static Method findStopReconnectCycleMethod() {
        Method method = ReflectionUtils.findMethod(
                MqttAsyncClient.class,
                "stopReconnectCycle");
        if (method == null) {
            throw new IllegalStateException(
                    "Paho MQTT reconnect shutdown method is unavailable");
        }
        ReflectionUtils.makeAccessible(method);
        return method;
    }
}
