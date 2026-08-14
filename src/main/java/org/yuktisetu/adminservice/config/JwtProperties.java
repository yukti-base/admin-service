package org.yuktisetu.adminservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Deliberately smaller than auth-service's JwtProperties: this service never
// issues tokens, so there is no private-key path and no TTL config here --
// only what's needed to VERIFY a token auth-service already signed.
@ConfigurationProperties(prefix = "yuktisetu.jwt")
public class JwtProperties {

    private String publicKeyPath;
    private String issuer;

    public String getPublicKeyPath() { return publicKeyPath; }
    public void setPublicKeyPath(String publicKeyPath) { this.publicKeyPath = publicKeyPath; }

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
}
