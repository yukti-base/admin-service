package org.yuktisetu.adminservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

// Public-key-only mirror of auth-service's KeyConfig. This service should
// never load a private key -- if you find yourself adding a jwtPrivateKey
// bean here later, stop: that means admin-service is trying to issue tokens,
// which is auth-service's job alone.
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class KeyConfig {

    @Bean
    public PublicKey jwtPublicKey(JwtProperties props) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String pem = stripPemHeaders(Files.readString(Path.of(props.getPublicKeyPath())));
        byte[] decoded = Base64.getDecoder().decode(pem);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private String stripPemHeaders(String pem) {
        return pem
                .replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s", "");
    }
}
