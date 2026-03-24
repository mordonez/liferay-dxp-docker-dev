package dev.mordonez.liferaycli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mordonez.liferaycli.LiferayCLIMain;
import dev.mordonez.liferaycli.http.LiferayApiClient;
import dev.mordonez.liferaycli.http.OAuthTokenClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "audit", mixinStandardHelpOptions = true, description = "Auditoría rápida de conectividad y recursos")
public class AuditCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private LiferayCLIMain.RootCommand root;

    @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
    String site;

    @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
    String format;

    @Override
    public Integer call() {
        try {
            if (!"text".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format)) {
                System.err.println("AUDIT_ERROR: formato no soportado: " + format);
                return 1;
            }

            OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());

            Map<String, Object> audit = new HashMap<>();
            audit.put("baseUrl", root.settings().baseUrl());
            audit.put("clientId", root.settings().clientId());
            audit.put("tokenType", token.tokenType());
            audit.put("expiresIn", token.expiresIn());

            int healthStatus = requestStatus(
                "/o/headless-admin-user/v1.0/sites/by-friendly-url-path/global",
                token.accessToken()
            );
            audit.put("healthStatus", healthStatus);

            long siteId = resolveSiteId(site, token.accessToken());
            audit.put("site", site);
            audit.put("siteId", siteId);

            int structures = countPaged(
                "/o/data-engine/v2.0/sites/" + siteId + "/data-definitions/by-content-type/journal",
                token.accessToken()
            );
            int templates = countPaged(
                "/o/headless-delivery/v1.0/sites/" + siteId + "/content-templates",
                token.accessToken()
            );
            audit.put("structureCount", structures);
            audit.put("templateCount", templates);

            if ("json".equalsIgnoreCase(format)) {
                System.out.println(root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(audit));
                return 0;
            }

            System.out.println("AUDIT_OK");
            System.out.println("baseUrl=" + audit.get("baseUrl"));
            System.out.println("clientId=" + audit.get("clientId"));
            System.out.println("site=" + audit.get("site") + " (" + audit.get("siteId") + ")");
            System.out.println("healthStatus=" + audit.get("healthStatus"));
            System.out.println("structureCount=" + audit.get("structureCount"));
            System.out.println("templateCount=" + audit.get("templateCount"));
            return 0;
        }
        catch (Exception ex) {
            System.err.println("AUDIT_ERROR: " + ex.getMessage());
            return 1;
        }
    }

    private int requestStatus(String path, String accessToken) throws Exception {
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            path,
            accessToken,
            root.settings().timeoutSeconds()
        );
        return response.statusCode();
    }

    private long resolveSiteId(String siteValue, String accessToken) throws Exception {
        if (siteValue.matches("^\\d+$")) {
            return Long.parseLong(siteValue);
        }
        String normalized = siteValue.startsWith("/") ? siteValue.substring(1) : siteValue;
        String encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            "/o/headless-admin-user/v1.0/sites/by-friendly-url-path/" + encoded,
            accessToken,
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("site resolve status=" + response.statusCode());
        }
        JsonNode json = root.mapper().readTree(response.body());
        long id = json.path("id").asLong(-1L);
        if (id <= 0) {
            throw new IllegalStateException("site no encontrado: " + siteValue);
        }
        return id;
    }

    private int countPaged(String basePath, String accessToken) throws Exception {
        int page = 1;
        int lastPage = 1;
        int total = 0;

        while (page <= lastPage) {
            String path = basePath + "?page=" + page + "&pageSize=200";
            LiferayApiClient.ApiResponse response = root.apiClient().get(
                root.settings().baseUrl(),
                path,
                accessToken,
                root.settings().timeoutSeconds()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("paged status=" + response.statusCode() + " path=" + basePath);
            }
            JsonNode payload = root.mapper().readTree(response.body());
            JsonNode items = payload.path("items");
            if (items.isArray()) {
                total += items.size();
            }
            lastPage = payload.path("lastPage").asInt(1);
            page++;
        }
        return total;
    }
}
