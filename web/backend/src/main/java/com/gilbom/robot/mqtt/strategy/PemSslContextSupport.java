package com.gilbom.robot.mqtt.strategy;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;

final class PemSslContextSupport {

    private static final char[] EMPTY_PASSWORD = new char[0];
    private static final String[] PRIVATE_KEY_ALGORITHMS = {"RSA", "EC", "Ed25519"};

    /* PEM 인증서와 개인키를 Java가 TLS 통신에 사용할 수 있는 객체로 변환*/
    
    private PemSslContextSupport() {
    }

    //서버(브로커)를 신뢰할지 검증하는 데 쓰는 `CA 인증서`를 로드합니다.
    static TrustManager[] createTrustManagers(
    		String trustCertificatePath)
    
    		// 경로가 비어있으면 null
            throws GeneralSecurityException, IOException {
    	
		        if (isBlank(trustCertificatePath)) {
		            return null;
		        }

		    
	        Collection<? extends Certificate> certificates = readCertificates(
	                requiredFile(trustCertificatePath, "trust certificate")
	        );
	        KeyStore trustStore = emptyKeyStore();
	
	        int index = 0;
	        for (Certificate certificate : certificates) {
	            trustStore.setCertificateEntry("mqtt-ca-" + index++, certificate);
	        }
	
	        TrustManagerFactory factory = TrustManagerFactory.getInstance(
	                TrustManagerFactory.getDefaultAlgorithm()
	        );
	        factory.init(trustStore);
	        return factory.getTrustManagers();
    }
    //클라이언트(로봇/Spring Boot 앱) 자신을 서버에 증명할 클라이언트 인증서 + 개인키를 로드합니다.
    static KeyManager[] createKeyManagers(
    		String clientCertificatePath,
    		String clientPrivateKeyPath
    		)
            throws GeneralSecurityException, IOException {
    	//절대 경로 가져옵니다.(디버깅을 쉽게 하도록 하기위해)
        Path certificateFile = requiredFile(clientCertificatePath, "client certificate");
        Path privateKeyFile = requiredFile(clientPrivateKeyPath, "client private key");

        //PEM 파일에서 인증서들을 파싱
        Collection<? extends Certificate> certificates = readCertificates(certificateFile);
        
        //X.509 표준을 따르는 디지털 인증서를 객체로 제공 
        X509Certificate[] certificateChain = certificates.stream()
                .map(certificate -> {
                    if (certificate instanceof X509Certificate x509Certificate) {
                        return x509Certificate;
                    }
                    throw new BusinessException(
                            ErrorCode.MQTT_CERTIFICATE_NOT_X509,
                            "`" + certificate.getType() + "` 인증서는 X.509 형식이 아닙니다."
                    );
                })
                .toArray(X509Certificate[]::new);
        //클라이언트 인증서 블록+ 그 인증서를 서명한 중간 CA 인증서 블록들 까지 포함
        //Certificate 2 X509Certificate로  이것도 디버깅용도  TLS/MTLS가 X.509형식을 사용한다.
        
        //서버 개인키도 인증서로 읽는다. PASSWORD는 레퍼런스로 채워줌
        PrivateKey privateKey = readPkcs8PrivateKey(privateKeyFile);
        KeyStore keyStore = emptyKeyStore();
        
        //KeyStore는 여러 개의 키/인증서 항목을 담을 수 있는 일종의 맵 같은 저장소
        keyStore.setKeyEntry(
        		"mqtt-client", //클라이언트가 서버에게 자신의 신원을 증명할 때, 사용 개인키와 인증서 묶음을 구분하는 라벨 Map구조의 키값
        		privateKey, //서버가 보낸 값에 이 개인키로 서명
        		EMPTY_PASSWORD, //개인키 항목을 암호화해서 보호할 때 쓰는 비밀번호
        		certificateChain); //개인키에 대응하는 인증서 체인
        

        KeyManagerFactory factory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
        );
        //등록할 때 사용한 비밀번호와 읽을 때 사용하는 비밀번호가 같은지 확인
        factory.init(keyStore, EMPTY_PASSWORD);
        return factory.getKeyManagers();
    }

    private static KeyStore emptyKeyStore() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        return keyStore;
    }

    private static Collection<? extends Certificate> readCertificates(Path path)
            throws GeneralSecurityException, IOException {
        try (InputStream input = Files.newInputStream(path)) {
            Collection<? extends Certificate> certificates = CertificateFactory
                    .getInstance("X.509")
                    .generateCertificates(input);

            if (certificates.isEmpty()) {
                throw new GeneralSecurityException("No X.509 certificate found: " + path);
            }
            return certificates;
        }
    }

    private static PrivateKey readPkcs8PrivateKey(Path path)//개인키 추출 (평문 개인키)
            throws GeneralSecurityException, IOException {
        String pem = Files.readString(path, StandardCharsets.UTF_8);
        if (pem.contains("-----BEGIN RSA PRIVATE KEY-----")
                || pem.contains("-----BEGIN EC PRIVATE KEY-----")) {
            throw new GeneralSecurityException(
                    "Client private key must use unencrypted PKCS#8 PEM format "
                            + "(-----BEGIN PRIVATE KEY-----): " + path
            );
        }
        if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
            throw new GeneralSecurityException(
                    "Unsupported or encrypted client private key: " + path
            );
        }

        String encoded = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded));

        GeneralSecurityException lastFailure = null;
        for (String algorithm : PRIVATE_KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            } catch (InvalidKeySpecException exception) {
                lastFailure = exception;
            }
        }
        throw new GeneralSecurityException(
                "Unsupported PKCS#8 private key algorithm: " + path,
                lastFailure
        );
    }

    private static Path requiredFile(String value, String description) {
        if (isBlank(value)) {
            throw new BusinessException(
                    ErrorCode.MQTT_SECURITY_CONFIGURATION_INVALID,
                    "MQTT " + description + " path must be configured"
            );
        }

        Path path = Path.of(value).toAbsolutePath().normalize(); //절대 경로로 바꿔주기
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(
                    ErrorCode.MQTT_SECURITY_CONFIGURATION_INVALID,
                    "MQTT " + description + " file does not exist"
            );
        }
        return path;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
