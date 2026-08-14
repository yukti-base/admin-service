package org.yuktisetu.adminservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.stereotype.Component;

import java.security.PublicKey;

// Verify-only counterpart to auth-service's JwtTokenProvider. admin-service
// never signs a token, so there is no issueAccessToken here and no private
// key anywhere in this service's dependency graph.
@Component
public class JwtTokenVerifier {

    private final PublicKey publicKey;

    public JwtTokenVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    public Claims verify(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new JwtVerificationException("Token has expired.", e);
        } catch (SignatureException | MalformedJwtException | UnsupportedJwtException e) {
            throw new JwtVerificationException("Token is invalid.", e);
        }
    }

    public static class JwtVerificationException extends RuntimeException {
        public JwtVerificationException(String message, Throwable cause) { super(message, cause); }
    }
}
