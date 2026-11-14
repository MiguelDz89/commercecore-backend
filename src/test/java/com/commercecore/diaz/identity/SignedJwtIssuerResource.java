package com.commercecore.diaz.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SignedJwtIssuerResource implements QuarkusTestResourceLifecycleManager {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    static KeyPair keyPair;
    static String issuer;
    private HttpServer server;

    @Override
    public Map<String, String> start() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keyPair = generator.generateKeyPair();
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            issuer = "http://127.0.0.1:" + server.getAddress().getPort();
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            byte[] metadata = JSON.writeValueAsBytes(Map.of("issuer", issuer, "jwks_uri", issuer + "/jwks",
                    "authorization_endpoint", issuer + "/authorize", "token_endpoint", issuer + "/token"));
            byte[] jwks = JSON.writeValueAsBytes(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "test-key",
                    "use", "sig", "alg", "RS256", "n", unsigned(publicKey.getModulus()),
                    "e", unsigned(publicKey.getPublicExponent())))));
            server.createContext("/.well-known/openid-configuration", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, metadata.length);
                try (var out = exchange.getResponseBody()) { out.write(metadata); }
            });
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var out = exchange.getResponseBody()) { out.write(jwks); }
            });
            server.start();
            return Map.of("quarkus.oidc.auth-server-url", issuer,
                    "quarkus.oidc.client-id", "commercecore-api");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start test issuer", e);
        }
    }

    static String token(String tokenIssuer, boolean includeSubject, long expiration, long issuedAt) {
        try {
            var claims = new LinkedHashMap<String, Object>();
            claims.put("iss", tokenIssuer);
            claims.put("aud", List.of("commercecore-api"));
            claims.put("iat", issuedAt);
            claims.put("exp", expiration);
            if (includeSubject) claims.put("sub", "test-subject");
            claims.put("resource_access", Map.of("commercecore-api", Map.of("roles", List.of("PLATFORM_SUPER_ADMIN"))));
            String content = BASE64.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "RS256", "kid", "test-key", "typ", "JWT")))
                    + "." + BASE64.encodeToString(JSON.writeValueAsBytes(claims));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(content.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return content + "." + BASE64.encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign test token", e);
        }
    }

    private static String unsigned(BigInteger value) {
        byte[] data = value.toByteArray();
        if (data.length > 1 && data[0] == 0) data = java.util.Arrays.copyOfRange(data, 1, data.length);
        return BASE64.encodeToString(data);
    }

    @Override
    public void stop() {
        if (server != null) server.stop(0);
        keyPair = null;
    }
}
