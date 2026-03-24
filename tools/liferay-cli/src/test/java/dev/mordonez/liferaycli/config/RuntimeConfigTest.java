package dev.mordonez.liferaycli.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigTest {

    @Test
    void load_prefersProcessEnvOverFileEnv() throws Exception {
        Path repo = createRepoSkeleton();
        Files.writeString(repo.resolve("docker/.env"), String.join("\n",
            "BIND_IP=10.0.0.10",
            "LIFERAY_HTTP_PORT=9090",
            "LIFERAY_CLI_OAUTH2_CLIENT_ID=file-id",
            "LIFERAY_CLI_OAUTH2_CLIENT_SECRET=file-secret"
        ));

        RuntimeConfig config = RuntimeConfig.load(
            repo.resolve("liferay"),
            Map.of(
                "LIFERAY_CLI_URL", "http://override:8081",
                "LIFERAY_CLI_OAUTH2_CLIENT_ID", "env-id",
                "LIFERAY_CLI_OAUTH2_CLIENT_SECRET", "env-secret",
                "LIFERAY_CLI_HTTP_TIMEOUT_SECONDS", "45"
            )
        );

        assertEquals("http://override:8081", config.baseUrl());
        assertEquals("env-id", config.clientId());
        assertEquals("env-secret", config.clientSecret());
        assertEquals(45, config.timeoutSeconds());
        assertEquals("env-id", config.oauthCredentials().get(0).clientId());
    }

    @Test
    void load_usesDockerEnvFallbacksWhenProcessEnvMissing() throws Exception {
        Path repo = createRepoSkeleton();
        Files.writeString(repo.resolve("docker/.env"), String.join("\n",
            "BIND_IP=127.0.0.7",
            "LIFERAY_HTTP_PORT=8181",
            "LIFERAY_CLI_OAUTH2_CLIENT_ID=cli-id",
            "LIFERAY_CLI_OAUTH2_CLIENT_SECRET=cli-secret"
        ));

        RuntimeConfig config = RuntimeConfig.load(repo.resolve("liferay"), Map.of());

        assertEquals("http://127.0.0.7:8181", config.baseUrl());
        assertEquals("cli-id", config.clientId());
        assertEquals("cli-secret", config.clientSecret());
    }

    @Test
    void load_emptyCredentialsWhenNotConfigured() throws Exception {
        Path repo = createRepoSkeleton();
        Files.writeString(repo.resolve("docker/.env"), "BIND_IP=127.0.0.1\nLIFERAY_HTTP_PORT=8080\n");

        RuntimeConfig config = RuntimeConfig.load(repo.resolve("liferay"), Map.of());

        assertEquals("", config.clientId());
        assertEquals("", config.clientSecret());
        assertTrue(config.oauthCredentials().isEmpty());
    }

    @Test
    void testEnvVarPriority_processEnvTakesPrecedenceOverFileEnv() throws Exception {
        Path repo = createRepoSkeleton();
        Files.writeString(repo.resolve("docker/.env"), String.join("\n",
            "BIND_IP=127.0.0.1",
            "LIFERAY_HTTP_PORT=8080",
            "LIFERAY_CLI_OAUTH2_CLIENT_ID=file-client-id",
            "LIFERAY_CLI_OAUTH2_CLIENT_SECRET=file-client-secret"
        ));

        RuntimeConfig config = RuntimeConfig.load(
            repo.resolve("liferay"),
            Map.of(
                "LIFERAY_CLI_URL", "http://new-var:9000",
                "LIFERAY_CLI_OAUTH2_CLIENT_ID", "env-client-id",
                "LIFERAY_CLI_OAUTH2_CLIENT_SECRET", "env-client-secret",
                "LIFERAY_CLI_HTTP_TIMEOUT_SECONDS", "60"
            )
        );

        assertEquals("http://new-var:9000", config.baseUrl());
        assertEquals("env-client-id", config.clientId());
        assertEquals("env-client-secret", config.clientSecret());
        assertEquals(60, config.timeoutSeconds());
    }

    @Test
    void testProfileLoad_yamlFileIsReadWhenPresent() throws Exception {
        Path repo = createRepoSkeleton();
        Files.writeString(repo.resolve("docker/.env"), "BIND_IP=127.0.0.1\nLIFERAY_HTTP_PORT=8080\n");
        Files.writeString(repo.resolve(".liferay-cli.yml"), String.join("\n",
            "liferay:",
            "  url: http://from-profile:7070",
            "  oauth2:",
            "    clientId: profile-client-id",
            "    clientSecret: profile-client-secret",
            "    timeoutSeconds: 55"
        ));

        // No process env vars for URL/clientId → should fall through to profile
        RuntimeConfig config = RuntimeConfig.load(repo.resolve("liferay"), Map.of());

        assertEquals("http://from-profile:7070", config.baseUrl(),
            "URL del perfil YAML debe usarse cuando no hay vars de entorno");
        assertEquals("profile-client-id", config.clientId());
        assertEquals("profile-client-secret", config.clientSecret());
        assertEquals(55, config.timeoutSeconds());
    }

    @Test
    void testProfileLoad_envVarOverridesProfile() throws Exception {
        Path repo = createRepoSkeleton();
        Files.writeString(repo.resolve("docker/.env"), "BIND_IP=127.0.0.1\nLIFERAY_HTTP_PORT=8080\n");
        Files.writeString(repo.resolve(".liferay-cli.yml"), String.join("\n",
            "liferay:",
            "  url: http://from-profile:7070",
            "  oauth2:",
            "    clientId: profile-client-id",
            "    clientSecret: profile-client-secret"
        ));

        // Env var takes priority over profile
        RuntimeConfig config = RuntimeConfig.load(
            repo.resolve("liferay"),
            Map.of(
                "LIFERAY_CLI_URL", "http://env-override:9999",
                "LIFERAY_CLI_OAUTH2_CLIENT_ID", "env-client-id",
                "LIFERAY_CLI_OAUTH2_CLIENT_SECRET", "env-client-secret"
            )
        );

        assertEquals("http://env-override:9999", config.baseUrl(),
            "Var de entorno debe tener prioridad sobre perfil YAML");
        assertEquals("env-client-id", config.clientId());
    }

    private static Path createRepoSkeleton() throws Exception {
        Path repo = Files.createTempDirectory("liferay-cli-runtime-test");
        Files.createDirectories(repo.resolve("docker"));
        Files.createDirectories(repo.resolve("liferay"));
        Files.writeString(repo.resolve("docker/docker-compose.yml"), "services:\n");
        return repo;
    }
}
