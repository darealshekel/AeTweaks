package com.aetweaks.mmmsync.google;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import com.aetweaks.mmmsync.config.BackendConfig;
import com.aetweaks.mmmsync.model.ServiceAccountCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class GoogleAccessTokenService
{
    private final Gson gson;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ServiceAccountCredentials credentials;

    private String accessToken;
    private Instant expiresAt = Instant.EPOCH;

    public GoogleAccessTokenService(Gson gson, BackendConfig config) throws IOException
    {
        this.gson = gson;
        String json = config.serviceAccountJson().isBlank()
                ? Files.readString(config.serviceAccountFile())
                : config.serviceAccountJson();
        this.credentials = gson.fromJson(json, ServiceAccountCredentials.class);
    }

    public synchronized String getAccessToken() throws Exception
    {
        if (this.accessToken != null && Instant.now().isBefore(this.expiresAt.minusSeconds(60)))
        {
            return this.accessToken;
        }

        String assertion = createAssertion();
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8) +
                "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(this.credentials.token_uri()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            throw new IOException("Google token request failed: " + response.statusCode() + " " + response.body());
        }

        JsonObject body = this.gson.fromJson(response.body(), JsonObject.class);
        this.accessToken = body.get("access_token").getAsString();
        this.expiresAt = Instant.now().plusSeconds(body.get("expires_in").getAsLong());
        return this.accessToken;
    }

    private String createAssertion() throws Exception
    {
        Instant now = Instant.now();
        String header = base64Url(this.gson.toJson(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = base64Url(this.gson.toJson(Map.of(
                "iss", this.credentials.client_email(),
                "scope", "https://www.googleapis.com/auth/spreadsheets",
                "aud", this.credentials.token_uri(),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(3600).getEpochSecond()
        )));

        String unsigned = header + "." + claims;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(parsePrivateKey(this.credentials.private_key()));
        signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
        return unsigned + "." + base64Url(signature.sign());
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception
    {
        String sanitized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] bytes = Base64.getDecoder().decode(sanitized);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private String base64Url(String value)
    {
        return base64Url(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes)
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
