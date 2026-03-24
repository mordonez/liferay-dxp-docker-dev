package dev.mordonez.liferaycli.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RuntimeConfig(
    String baseUrl,
    String clientId,
    String clientSecret,
    String scopeAliases,
    int timeoutSeconds,
    Map<String, String> paths
) {
    private static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final String DEFAULT_SCOPES =
        "Liferay.Headless.Admin.User.everything.read," +
        "Liferay.Data.Engine.REST.everything.read," +
        "Liferay.Data.Engine.REST.everything.write," +
        "Liferay.Headless.Delivery.everything.read," +
        "Liferay.Headless.Delivery.everything.write," +
        "Liferay.Headless.Admin.Content.everything.read," +
        "liferay-json-web-services.everything.read," +
        "liferay-json-web-services.everything.write";

    public static RuntimeConfig load(Path startDir, Map<String, String> processEnv) {
        // LIFERAY_CLI_REPO_ROOT allows running outside of the monorepo structure.
        // When set, file-based config (docker/.env, OSGi bootstrap config) is loaded from there.
        // When not set, we heuristically search parent dirs for docker/docker-compose.yml + liferay/.
        // If no monorepo structure is found, file-based config is skipped and env vars / profile take over.
        String repoRootEnv = processEnv.get("LIFERAY_CLI_REPO_ROOT");
        Path repoRoot = (repoRootEnv != null && !repoRootEnv.isBlank())
            ? Path.of(repoRootEnv).toAbsolutePath().normalize()
            : resolveRepoRoot(startDir);

        Map<String, String> fileEnv = repoRoot != null
            ? loadEnvFile(repoRoot.resolve("docker").resolve(".env"))
            : Map.of();

        // Load profile YAML (priority: env var path > repo root > cwd)
        Map<String, String> profileEnv = loadProfile(processEnv, repoRoot);

        String rawBindIp = fileEnv.get("BIND_IP");
        // A specific non-wildcard BIND_IP (not 0.0.0.0) is authoritative: it takes priority
        // over profile defaults so that docker/.env remains the single source of truth.
        // Only treat BIND_IP as authoritative when it is a routable (non-loopback, non-wildcard) address.
        // Loopback addresses (127.x, localhost) and wildcards (0.0.0.0) are equivalent to the default
        // and should not override a profile URL that may point to a different host/port.
        boolean hasSpecificBindIp = rawBindIp != null && !rawBindIp.isBlank()
            && !rawBindIp.equals("0.0.0.0")
            && !rawBindIp.equals("127.0.0.1")
            && !rawBindIp.equals("localhost");
        String bindIp = hasSpecificBindIp ? rawBindIp.trim() : "localhost";
        String httpPort = firstNonBlank(fileEnv.get("LIFERAY_HTTP_PORT"), "8080");
        String bindIpUrl = hasSpecificBindIp ? "http://" + bindIp + ":" + httpPort : null;
        String fallbackUrl = "http://" + bindIp + ":" + httpPort;

        // New LIFERAY_CLI_* vars take priority.
        // If BIND_IP is set to a specific IP in docker/.env, it takes priority over profile defaults.
        String baseUrl = firstNonBlank(
            processEnv.get("LIFERAY_CLI_URL"),
            fileEnv.get("LIFERAY_CLI_URL"),
            bindIpUrl,
            profileEnv.get("liferay.url"),
            fallbackUrl,
            DEFAULT_BASE_URL
        );

        String clientId = firstNonBlank(
            processEnv.get("LIFERAY_CLI_OAUTH2_CLIENT_ID"),
            fileEnv.get("LIFERAY_CLI_OAUTH2_CLIENT_ID"),
            profileEnv.get("liferay.oauth2.clientId")
        );

        String clientSecret = firstNonBlank(
            processEnv.get("LIFERAY_CLI_OAUTH2_CLIENT_SECRET"),
            fileEnv.get("LIFERAY_CLI_OAUTH2_CLIENT_SECRET"),
            profileEnv.get("liferay.oauth2.clientSecret")
        );

        String scopeAliases = DEFAULT_SCOPES;

        int timeoutSeconds = parsePositiveInt(
            firstNonBlank(
                processEnv.get("LIFERAY_CLI_HTTP_TIMEOUT_SECONDS"),
                fileEnv.get("LIFERAY_CLI_HTTP_TIMEOUT_SECONDS"),
                profileEnv.get("liferay.oauth2.timeoutSeconds")
            ),
            30
        );

        // Extract paths from profile
        Map<String, String> paths = new LinkedHashMap<>();
        profileEnv.forEach((k, v) -> {
            if (k.startsWith("paths.")) {
                paths.put(k.substring(6), v);
            }
        });

        return new RuntimeConfig(
            baseUrl,
            clientId,
            clientSecret,
            scopeAliases,
            timeoutSeconds,
            paths
        );
    }

    /**
     * Loads profile values from .liferay-cli.yml.
     * Search order:
     * 1. ${LIFERAY_CLI_PROFILE_PATH}
     * 2. ${REPO_ROOT}/.liferay-cli.yml
     * 3. ./  (cwd)
     *
     * Returns a flat map with dotted keys (e.g. "liferay.url", "liferay.oauth2.clientId").
     */
    static Map<String, String> loadProfile(Map<String, String> processEnv, Path repoRoot) {
        Map<String, String> result = new LinkedHashMap<>();

        String profilePath = processEnv.get("LIFERAY_CLI_PROFILE_PATH");
        Path profileFile = null;

        if (profilePath != null && !profilePath.isBlank()) {
            Path candidate = Path.of(profilePath);
            if (Files.isRegularFile(candidate)) {
                profileFile = candidate;
            }
        }

        if (profileFile == null && repoRoot != null) {
            Path candidate = repoRoot.resolve(".liferay-cli.yml");
            if (Files.isRegularFile(candidate)) {
                profileFile = candidate;
            }
        }

        if (profileFile == null) {
            Path candidate = Path.of(".liferay-cli.yml");
            if (Files.isRegularFile(candidate)) {
                profileFile = candidate;
            }
        }

        if (profileFile == null) {
            return result;
        }

        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            JsonNode root = yamlMapper.readTree(profileFile.toFile());
            flattenNode(root, "", result);
        }
        catch (IOException e) {
            // Non-fatal: profile file malformed → skip
        }
        return result;
    }

    private static void flattenNode(JsonNode node, String prefix, Map<String, String> target) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flattenNode(entry.getValue(), key, target);
            });
        }
        else if (node.isValueNode()) {
            target.put(prefix, node.asText(""));
        }
    }

    /**
     * Walks parent directories looking for a directory that has both
     * {@code docker/docker-compose.yml} and a {@code liferay/} subdirectory.
     * Returns {@code null} if no such directory is found (e.g. when running
     * outside of the monorepo). In that case callers fall back to env vars
     * and the profile YAML.
     */
    static Path resolveRepoRoot(Path startDir) {
        Path current = startDir.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("docker").resolve("docker-compose.yml"))
                && Files.isDirectory(current.resolve("liferay"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    static Map<String, String> loadEnvFile(Path envFile) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(envFile)) {
            return values;
        }

        try {
            List<String> lines = Files.readAllLines(envFile);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int idx = line.indexOf('=');
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                values.put(key, value);
            }
            return values;
        }
        catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + envFile, e);
        }
    }

    static Map<String, String> loadPropertiesLikeConfig(Path configFile) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(configFile)) {
            return values;
        }
        try {
            List<String> lines = Files.readAllLines(configFile);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int idx = line.indexOf('=');
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
            return values;
        }
        catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + configFile, e);
        }
    }

    public List<ClientCredential> oauthCredentials() {
        List<ClientCredential> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addCredential(result, seen, clientId, clientSecret);
        return result;
    }

    private static void addCredential(
        List<ClientCredential> target,
        Set<String> seen,
        String candidateId,
        String candidateSecret
    ) {
        if (candidateId == null || candidateId.isBlank() || candidateSecret == null || candidateSecret.isBlank()) {
            return;
        }
        String fingerprint = candidateId + ":" + candidateSecret;
        if (seen.add(fingerprint)) {
            target.add(new ClientCredential(candidateId, candidateSecret));
        }
    }

    private static int parsePositiveInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        }
        catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    public record ClientCredential(String clientId, String clientSecret) {
    }
}
