package com.eatfood.control.security;

import com.eatfood.control.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessMillis;

    public JwtService(AppProperties props) {
        byte[] secret = Base64.getDecoder().decode(props.getSecurity().getJwt().getSecret());
        this.key = Keys.hmacShaKeyFor(secret);
        this.accessMillis = props.getSecurity().getJwt().getAccessTokenMinutes() * 60_000L;
    }

    public String generateAccessToken(String username, List<String> roles, Long cateringId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("roles", roles)
                .claim("cateringId", cateringId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessMillis))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public boolean isValid(String token) {
        try {
            Claims c = parse(token);
            return c.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> rolesOf(Claims claims) {
        Object roles = claims.get("roles");
        if (roles instanceof List<?> l) return (List<String>) l;
        return List.of();
    }

    public Map<String, Object> claimsAsMap(Claims claims) {
        return claims;
    }
}
