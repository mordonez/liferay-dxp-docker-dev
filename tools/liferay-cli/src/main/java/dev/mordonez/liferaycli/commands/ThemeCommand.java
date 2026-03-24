package dev.mordonez.liferaycli.commands;

import dev.mordonez.liferaycli.LiferayCLIMain;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Command(
    name = "theme",
    description = "Checks de tema",
    mixinStandardHelpOptions = true,
    subcommands = {ThemeCommand.Check.class}
)
public class ThemeCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private LiferayCLIMain.RootCommand root;

    @Override
    public Integer call() {
        System.out.println("Usa: task theme-check");
        return 0;
    }

    @Command(name = "check", description = "Detecta regresiones de iconos del ub-theme", mixinStandardHelpOptions = true)
    public static class Check implements Callable<Integer> {
        private static final Pattern ID_PATTERN = Pattern.compile("id=\"([^\"]+)\"");

        @CommandLine.ParentCommand
        private ThemeCommand parent;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                String baseUrl = root.settings().baseUrl();
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(root.settings().timeoutSeconds()))
                    .build();

                String mainCssUrl = baseUrl + "/o/ub-theme/css/main.css";
                String adminIconsUrl = baseUrl + "/o/admin-theme/images/clay/icons.svg";
                String ubIconsUrl = baseUrl + "/o/ub-theme/images/clay/icons.svg";
                Path sourceIcons = resolveRepoRoot().resolve("liferay/themes/ub-theme/src/images/clay/icons.svg");

                System.out.println("[INFO] === VERIFICACION DE REGRESIONES DEL TEMA ub-theme ===");
                requireHttp200(client, mainCssUrl, root.settings().timeoutSeconds());
                System.out.println("[INFO] ub-theme accesible");

                Set<String> adminIds = parseIconIds(fetchText(client, adminIconsUrl, root.settings().timeoutSeconds()));
                Set<String> ubIds = parseIconIds(fetchText(client, ubIconsUrl, root.settings().timeoutSeconds()));
                Set<String> missing = new TreeSet<>(adminIds);
                missing.removeAll(ubIds);

                System.out.printf("[INFO] admin-theme: %d iconos | ub-theme: %d iconos%n", adminIds.size(), ubIds.size());
                if (!missing.isEmpty()) {
                    System.out.printf("[WARN] Faltan %d iconos:%n", missing.size());
                    for (String id : missing) {
                        System.out.println("[WARN]   - " + id);
                    }
                    System.err.println("[ERROR] Regresion detectada: iconos faltantes");
                    return 1;
                }

                if (!Files.isRegularFile(sourceIcons)) {
                    System.err.println("[ERROR] Falta fichero fuente: " + sourceIcons);
                    return 1;
                }
                Set<String> sourceIds = parseIconIds(Files.readString(sourceIcons, StandardCharsets.UTF_8));
                System.out.println("[INFO] El ub-theme cubre todos los iconos del admin-theme");
                System.out.printf("[INFO] src/images/clay/icons.svg existe (%d iconos)%n", sourceIds.size());
                System.out.println("[INFO] RESULTADO: sin regresiones detectadas");
                return 0;
            }
            catch (Exception ex) {
                System.err.println("THEME_ERROR: " + ex.getMessage());
                return 1;
            }
        }

        private static String fetchText(HttpClient client, String url, int timeoutSeconds)
            throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " en " + url);
            }
            return response.body();
        }

        private static void requireHttp200(HttpClient client, String url, int timeoutSeconds)
            throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " en " + url);
            }
        }

        private static Set<String> parseIconIds(String svg) {
            Set<String> ids = new TreeSet<>();
            Matcher matcher = ID_PATTERN.matcher(svg == null ? "" : svg);
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
            return ids;
        }

        private static Path resolveRepoRoot() {
            Path current = Path.of(".").toAbsolutePath().normalize();
            while (current != null) {
                if (Files.isRegularFile(current.resolve("docker/docker-compose.yml")) && Files.isDirectory(current.resolve("liferay"))) {
                    return current;
                }
                current = current.getParent();
            }
            throw new IllegalStateException("No se pudo resolver repo root desde cwd actual");
        }
    }
}
