package com.lcdw.springai.docmind01.Service.Security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Slf4j
@Service
public class JwtService {

    @Value("${app.jwt.secret:}")
    private String configuredSecret;

    @Value("${app.jwt.expiration-minutes:1440}")
    private long expirationMinutes;

    private SecretKey key;

    @PostConstruct
    void init() {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            key = Jwts.SIG.HS256.key().build();
            log.warn("app.jwt.secret is not set - generating a random key for this run only. "
                    + "Every restart invalidates all issued tokens and signs everyone out. "
                    + "Set app.jwt.secret (at least 32 characters) in src/main/resources/application-dev.yml.");
            return;
        }

        byte[] secretBytes = configuredSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 characters to be usable with HS256");
        }
        key = Keys.hmacShaKeyFor(secretBytes);
        log.info("JWT signing key loaded from app.jwt.secret, tokens valid for {} minutes", expirationMinutes);
    }

    public String generateToken(String userId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }

    /** Returns the verified claims, or null when the token is malformed, tampered with or expired. */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("rejected JWT: {}", e.getMessage());
            return null;
        }
    }
}
