package dev.mordonez.liferaycli.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mordonez.liferaycli.config.RuntimeConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class OAuthTokenClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int INVALID_CLIENT_RETRY_SECONDS = 5;
    private static final int INVALID_CLIENT_MAX_WAIT_SECONDS = 90;
    private final HttpClient httpClient;

    public OAuthTokenClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public TokenResponse fetchClientCredentialsToken(RuntimeConfig settings) throws IOException, InterruptedException {
        String scope = settings.scopeAliases().replace(',', ' ').trim();
        String baseFormBody = "grant_type=client_credentials";
        String scopedFormBody = baseFormBody;
        if (!scope.isBlank()) {
            scopedFormBody = baseFormBody + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);
        }
        URI endpoint = URI.create(settings.baseUrl() + "/o/oauth2/token");
        long startMs = System.currentTimeMillis();
        int round = 0;

        while (true) {
            round++;
            Map<String, Integer> attempts = new LinkedHashMap<>();
            String lastBody = "";
            int lastStatus = -1;
            boolean onlyInvalidClient = true;

            for (RuntimeConfig.ClientCredential credential : settings.oauthCredentials()) {
                HttpResponse<String> response = sendCredentialWithFallback(endpoint, settings, scopedFormBody, credential);
                if (shouldRetryWithoutScope(response, scope)) {
                    response = sendCredentialWithFallback(endpoint, settings, baseFormBody, credential);
                }
                lastStatus = response.statusCode();
                lastBody = response.body();
                attempts.put(credential.clientId(), response.statusCode());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parseTokenResponse(response);
                }
                if (isInvalidClient(response)) {
                    continue;
                }
                onlyInvalidClient = false;
                throw new IllegalStateException(
                    "Token request failed (" + response.statusCode() + ") con clientId=" +
                        credential.clientId() + ": " + response.body()
                );
            }

            if (!onlyInvalidClient) {
                throw new IllegalStateException(
                    "Token request failed (" + lastStatus + "): " + lastBody +
                        " [clients intentados=" + attempts + "]"
                );
            }

            long elapsedSeconds = (System.currentTimeMillis() - startMs) / 1000L;
            if (elapsedSeconds >= INVALID_CLIENT_MAX_WAIT_SECONDS) {
                throw new IllegalStateException(
                    "Token request failed (" + lastStatus + "): " + lastBody +
                        " [clients intentados=" + attempts + "]"
                );
            }
            long remaining = Math.max(0L, INVALID_CLIENT_MAX_WAIT_SECONDS - elapsedSeconds);
            System.err.printf(
                "[auth] invalid_client en bootstrap OAuth2; reintentando en %ds (intento=%d, restante=%ds)%n",
                INVALID_CLIENT_RETRY_SECONDS,
                round,
                remaining
            );
            Thread.sleep(INVALID_CLIENT_RETRY_SECONDS * 1000L);
        }
    }

    private HttpResponse<String> sendCredentialWithFallback(
        URI endpoint,
        RuntimeConfig settings,
        String baseFormBody,
        RuntimeConfig.ClientCredential credential
    ) throws IOException, InterruptedException {
        String basic = credential.clientId() + ":" + credential.clientSecret();
        String basicEncoded = Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
        String basicAuth = "Basic " + basicEncoded;
        HttpResponse<String> response = sendTokenRequest(
            endpoint,
            settings.timeoutSeconds(),
            baseFormBody,
            basicAuth
        );

        if (shouldRetryWithClientSecretPost(response)) {
            String postFormBody = baseFormBody +
                "&client_id=" + URLEncoder.encode(credential.clientId(), StandardCharsets.UTF_8) +
                "&client_secret=" + URLEncoder.encode(credential.clientSecret(), StandardCharsets.UTF_8);
            response = sendTokenRequest(endpoint, settings.timeoutSeconds(), postFormBody, null);
        }
        return response;
    }

    private static TokenResponse parseTokenResponse(HttpResponse<String> response) throws IOException {
        JsonNode json = MAPPER.readTree(response.body());
        String accessToken = json.path("access_token").asText("");
        String tokenType = json.path("token_type").asText("Bearer");
        long expiresIn = json.path("expires_in").asLong(0L);
        if (accessToken.isBlank()) {
            throw new IllegalStateException("Respuesta OAuth2 sin access_token");
        }
        return new TokenResponse(accessToken, tokenType, expiresIn);
    }

    private HttpResponse<String> sendTokenRequest(
        URI endpoint,
        int timeoutSeconds,
        String formBody,
        String authHeader
    ) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(formBody));
        if (authHeader != null && !authHeader.isBlank()) {
            builder.header("Authorization", authHeader);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static boolean shouldRetryWithClientSecretPost(HttpResponse<String> response) {
        if (response == null) {
            return false;
        }
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body().trim();

        // Common OAuth2 error path.
        if (status == 400 || status == 401) {
            return body.toLowerCase().contains("invalid_client");
        }

        // Some environments return 2xx with empty/non-token payload when Authorization: Basic is ignored.
        if (status >= 200 && status < 300) {
            if (body.isEmpty()) {
                return true;
            }
            try {
                JsonNode json = MAPPER.readTree(body);
                return json.path("access_token").asText("").isBlank();
            }
            catch (Exception ignored) {
                return true;
            }
        }

        return false;
    }

    private static boolean isInvalidClient(HttpResponse<String> response) {
        if (response == null) {
            return false;
        }
        int status = response.statusCode();
        if (status != 400 && status != 401) {
            return false;
        }
        String body = response.body() == null ? "" : response.body().toLowerCase();
        return body.contains("invalid_client");
    }

    private static boolean shouldRetryWithoutScope(HttpResponse<String> response, String scope) {
        if (scope == null || scope.isBlank() || response == null) {
            return false;
        }
        if (response.statusCode() != 400) {
            return false;
        }
        String body = response.body() == null ? "" : response.body().toLowerCase();
        return body.contains("invalid_grant");
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }
}
