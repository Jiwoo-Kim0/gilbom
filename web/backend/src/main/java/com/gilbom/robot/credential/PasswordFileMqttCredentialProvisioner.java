package com.gilbom.robot.credential;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocketFactory;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.mqtt.config.MqttProperties;
import com.gilbom.robot.mqtt.factory.MqttSecurityStrategyFactory;

final class PasswordFileMqttCredentialProvisioner
        implements MqttCredentialProvisioner {

    private static final Logger log = LoggerFactory.getLogger(
            PasswordFileMqttCredentialProvisioner.class);
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final ReentrantLock PASSWORD_FILE_LOCK = new ReentrantLock();

    private final Path passwordFile;
    private final String passwordCommand;
    private final Duration processTimeout;
    private final Duration activationTimeout;
    private final Duration verificationInterval;
    private final String brokerUri;
    private final SSLSocketFactory sslSocketFactory;

    PasswordFileMqttCredentialProvisioner(
            MqttCredentialProvisioningProperties provisioningProperties,
            MqttProperties mqttProperties,
            MqttSecurityStrategyFactory securityStrategyFactory
    ) {
        this.passwordFile = requiredPath(
                provisioningProperties.getPasswordFile());
        this.passwordCommand = requiredText(
                provisioningProperties.getPasswordCommand(),
                "password command");
        this.processTimeout = requiredPositive(
                provisioningProperties.getProcessTimeout(),
                "process timeout");
        this.activationTimeout = requiredPositive(
                provisioningProperties.getActivationTimeout(),
                "activation timeout");
        this.verificationInterval = requiredPositive(
                provisioningProperties.getVerificationInterval(),
                "verification interval");
        this.brokerUri = requiredText(
                mqttProperties.getBroker(),
                "MQTT broker URI");
        this.sslSocketFactory = securityStrategyFactory
                .getNowStrategy()
                .createSslContext()
                .getSocketFactory();
    }

    @Override
    public ProvisionedMqttCredential provision(
            String username,
            String password
    ) {
        validateUsername(username);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }

        PASSWORD_FILE_LOCK.lock();
        try {
            verifyPasswordFile();
            if (findPasswordHash(username) != null) {
                throw new BusinessException(
                        ErrorCode.ROBOT_CREDENTIAL_CONFLICT,
                        "MQTT Broker에 이미 같은 로봇 계정이 있습니다.");
            }

            try {
                runPasswordCommand(
                        List.of(
                                passwordCommand,
                                passwordFile.toString(),
                                username),
                        password + System.lineSeparator()
                                + password + System.lineSeparator());

                String passwordHash = findPasswordHash(username);
                if (passwordHash == null) {
                    throw provisioningUnavailable(null);
                }
                verifyBrokerAuthentication(username, password);
                return new ProvisionedMqttCredential(passwordHash);
            } catch (RuntimeException exception) {
                removeBestEffort(username);
                throw exception;
            }
        } finally {
            PASSWORD_FILE_LOCK.unlock();
        }
    }

    @Override
    public ProvisionedMqttCredential rotate(
            String username,
            String expectedPasswordHash,
            String password
    ) {
        validateUsername(username);
        validatePasswordHash(expectedPasswordHash);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }

        PASSWORD_FILE_LOCK.lock();
        try {
            verifyPasswordFile();
            requireExpectedPasswordHash(username, expectedPasswordHash);

            try {
                runPasswordCommand(
                        List.of(
                                passwordCommand,
                                passwordFile.toString(),
                                username),
                        password + System.lineSeparator()
                                + password + System.lineSeparator());

                String passwordHash = findPasswordHash(username);
                if (passwordHash == null) {
                    throw provisioningUnavailable(null);
                }
                verifyBrokerAuthentication(username, password);
                return new ProvisionedMqttCredential(passwordHash);
            } catch (RuntimeException exception) {
                restoreBestEffort(username, expectedPasswordHash);
                throw exception;
            }
        } finally {
            PASSWORD_FILE_LOCK.unlock();
        }
    }

    @Override
    public void revoke(String username, String expectedPasswordHash) {
        validateUsername(username);
        validatePasswordHash(expectedPasswordHash);

        PASSWORD_FILE_LOCK.lock();
        try {
            verifyPasswordFile();
            requireExpectedPasswordHash(username, expectedPasswordHash);
            try {
                runPasswordCommand(
                        List.of(
                                passwordCommand,
                                "-D",
                                passwordFile.toString(),
                                username),
                        "");
                if (findPasswordHash(username) != null) {
                    throw provisioningUnavailable(null);
                }
            } catch (RuntimeException exception) {
                restoreBestEffort(username, expectedPasswordHash);
                throw exception;
            }
        } finally {
            PASSWORD_FILE_LOCK.unlock();
        }
    }

    @Override
    public void restore(String username, String passwordHash) {
        validateUsername(username);
        validatePasswordHash(passwordHash);

        PASSWORD_FILE_LOCK.lock();
        try {
            verifyPasswordFile();
            replacePasswordHash(username, passwordHash);
        } finally {
            PASSWORD_FILE_LOCK.unlock();
        }
    }

    @Override
    public void remove(String username) {
        validateUsername(username);
        PASSWORD_FILE_LOCK.lock();
        try {
            try {
                verifyPasswordFile();
                removeBestEffort(username);
            } catch (RuntimeException exception) {
                log.error(
                        "Could not remove a rolled-back MQTT credential. "
                                + "username={}",
                        username);
            }
        } finally {
            PASSWORD_FILE_LOCK.unlock();
        }
    }

    @Override
    public CredentialRegistrySnapshot inspect(String username) {
        validateUsername(username);
        PASSWORD_FILE_LOCK.lock();
        try {
            verifyPasswordFile();
            return new CredentialRegistrySnapshot(
                    findPasswordHash(username));
        } finally {
            PASSWORD_FILE_LOCK.unlock();
        }
    }

    private void requireExpectedPasswordHash(
            String username,
            String expectedPasswordHash
    ) {
        String actualPasswordHash = findPasswordHash(username);
        if (!expectedPasswordHash.equals(actualPasswordHash)) {
            throw new BusinessException(
                    ErrorCode.MQTT_CREDENTIAL_REGISTRY_DRIFT);
        }
    }

    private void replacePasswordHash(String username, String passwordHash) {
        String prefix = username + ":";
        try {
            List<String> currentLines = Files.readAllLines(
                    passwordFile,
                    StandardCharsets.UTF_8);
            java.util.ArrayList<String> updatedLines =
                    new java.util.ArrayList<>(currentLines.size() + 1);
            boolean replaced = false;
            for (String line : currentLines) {
                if (line.startsWith(prefix)) {
                    if (!replaced) {
                        updatedLines.add(prefix + passwordHash);
                        replaced = true;
                    }
                    continue;
                }
                updatedLines.add(line);
            }
            if (!replaced) {
                updatedLines.add(prefix + passwordHash);
            }
            Files.write(
                    passwordFile,
                    updatedLines,
                    StandardCharsets.UTF_8);
            if (!passwordHash.equals(findPasswordHash(username))) {
                throw provisioningUnavailable(null);
            }
        } catch (IOException exception) {
            throw provisioningUnavailable(exception);
        }
    }

    private void verifyPasswordFile() {
        if (Files.isSymbolicLink(passwordFile)
                || !Files.isRegularFile(
                        passwordFile,
                        LinkOption.NOFOLLOW_LINKS)) {
            throw provisioningUnavailable(null);
        }
    }

    private String findPasswordHash(String username) {
        String prefix = username + ":";
        try {
            return Files.readAllLines(passwordFile, StandardCharsets.UTF_8)
                    .stream()
                    .filter(line -> line.startsWith(prefix))
                    .map(line -> line.substring(prefix.length()).trim())
                    .filter(hash -> !hash.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            throw provisioningUnavailable(exception);
        }
    }

    private void runPasswordCommand(List<String> command, String standardInput) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(
                            process.getOutputStream(),
                            StandardCharsets.UTF_8))) {
                writer.write(standardInput);
            }

            if (!process.waitFor(processTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw provisioningUnavailable(null);
            }
            process.getInputStream().readAllBytes();
            if (process.exitValue() != 0) {
                throw provisioningUnavailable(null);
            }
        } catch (IOException exception) {
            throw provisioningUnavailable(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw provisioningUnavailable(exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void verifyBrokerAuthentication(String username, String password) {
        long deadline = System.nanoTime() + activationTimeout.toNanos();
        MqttException lastFailure = null;

        while (System.nanoTime() < deadline) {
            MqttAsyncClient client = null;
            try {
                client = new MqttAsyncClient(
                        brokerUri,
                        "credential-verifier-" + UUID.randomUUID(),
                        new MemoryPersistence());
                MqttConnectionOptions options = new MqttConnectionOptions();
                options.setServerURIs(new String[]{brokerUri});
                options.setUserName(username);
                options.setPassword(password.getBytes(StandardCharsets.UTF_8));
                options.setSocketFactory(sslSocketFactory);
                options.setCleanStart(true);
                options.setAutomaticReconnect(false);
                options.setConnectionTimeout(2);
                options.setKeepAliveInterval(15);

                client.connect(options).waitForCompletion(2_500L);
                client.disconnect().waitForCompletion(1_000L);
                client.close();
                return;
            } catch (MqttException exception) {
                lastFailure = exception;
                closeQuietly(client);
                pauseBeforeRetry();
            }
        }

        throw provisioningUnavailable(lastFailure);
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(verificationInterval.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw provisioningUnavailable(exception);
        }
    }

    private void closeQuietly(MqttAsyncClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnectForcibly(500L, 500L, false);
            }
            client.close();
        } catch (MqttException ignored) {
            // The verification client is disposable.
        }
    }

    private void removeBestEffort(String username) {
        if (findPasswordHash(username) == null) {
            return;
        }
        try {
            runPasswordCommand(
                    List.of(
                            passwordCommand,
                            "-D",
                            passwordFile.toString(),
                            username),
                    "");
        } catch (RuntimeException exception) {
            log.error(
                    "Could not remove a rolled-back MQTT credential. username={}",
                    username);
        }
    }

    private void restoreBestEffort(String username, String passwordHash) {
        try {
            replacePasswordHash(username, passwordHash);
        } catch (RuntimeException exception) {
            log.error(
                    "Could not restore a rolled-back MQTT credential. username={}",
                    username);
        }
    }

    private void validateUsername(String username) {
        if (username == null
                || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("invalid MQTT username");
        }
    }

    private void validatePasswordHash(String passwordHash) {
        if (passwordHash == null
                || passwordHash.isBlank()
                || passwordHash.indexOf(':') >= 0
                || passwordHash.indexOf('\r') >= 0
                || passwordHash.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid MQTT password hash");
        }
    }

    private BusinessException provisioningUnavailable(Throwable cause) {
        if (cause == null) {
            return new BusinessException(
                    ErrorCode.MQTT_CREDENTIAL_PROVISIONING_UNAVAILABLE);
        }
        return new BusinessException(
                ErrorCode.MQTT_CREDENTIAL_PROVISIONING_UNAVAILABLE,
                cause);
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value.trim();
    }

    private static Path requiredPath(String value) {
        return Path.of(requiredText(value, "password file"))
                .toAbsolutePath()
                .normalize();
    }

    private static Duration requiredPositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be positive");
        }
        return value;
    }
}
