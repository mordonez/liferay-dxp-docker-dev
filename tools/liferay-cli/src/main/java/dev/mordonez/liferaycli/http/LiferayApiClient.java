package dev.mordonez.liferaycli.http;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

public class LiferayApiClient {
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 400L;
    private final HttpClient httpClient;

    public LiferayApiClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public ApiResponse get(String baseUrl, String path, String bearerToken, int timeoutSeconds)
        throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl, path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();

        HttpResponse<String> response = sendWithRetry(request);
        return new ApiResponse(response.statusCode(), response.body());
    }

    public ApiResponse postForm(
        String baseUrl,
        String path,
        String bearerToken,
        int timeoutSeconds,
        Map<String, String> form
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl, path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Bearer " + bearerToken)
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
            .build();

        HttpResponse<String> response = sendWithRetry(request);
        return new ApiResponse(response.statusCode(), response.body());
    }

    public ApiResponse postJson(
        String baseUrl,
        String path,
        String bearerToken,
        int timeoutSeconds,
        String payload
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl, path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        HttpResponse<String> response = sendWithRetry(request);
        return new ApiResponse(response.statusCode(), response.body());
    }

    public ApiResponse putJson(
        String baseUrl,
        String path,
        String bearerToken,
        int timeoutSeconds,
        String payload
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl, path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .PUT(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        HttpResponse<String> response = sendWithRetry(request);
        return new ApiResponse(response.statusCode(), response.body());
    }

    public ApiResponse delete(String baseUrl, String path, String bearerToken, int timeoutSeconds)
        throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl, path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .DELETE()
            .build();

        HttpResponse<String> response = sendWithRetry(request);
        return new ApiResponse(response.statusCode(), response.body());
    }

    private static URI endpoint(String baseUrl, String path) {
        return URI.create(baseUrl + path);
    }

    private static String formEncode(Map<String, String> form) {
        StringJoiner encoded = new StringJoiner("&");
        for (Map.Entry<String, String> entry : form.entrySet()) {
            encoded.add(urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()));
        }
        return encoded.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        IOException lastIo = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }
            catch (IOException ioException) {
                lastIo = ioException;
                if (attempt >= MAX_ATTEMPTS || !isRetryable(ioException)) {
                    throw ioException;
                }
                sleepBeforeRetry(attempt);
            }
        }
        throw lastIo == null ? new IOException("HTTP request failed without root cause") : lastIo;
    }

    private static boolean isRetryable(IOException ioException) {
        String message = ioException.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("timed out")
            || lower.contains("connection reset")
            || lower.contains("broken pipe")
            || lower.contains("connection refused")
            || lower.contains("no bytes");
    }

    private static void sleepBeforeRetry(int attempt) throws InterruptedException {
        long delay = RETRY_DELAY_MS * attempt;
        Thread.sleep(delay);
    }

    public record ApiResponse(int statusCode, String body) {
    }
}
