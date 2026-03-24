package dev.mordonez.liferaycli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mordonez.liferaycli.commands.AuthCommand;
import dev.mordonez.liferaycli.commands.AuditCommand;
import dev.mordonez.liferaycli.commands.HealthCommand;
import dev.mordonez.liferaycli.commands.InventoryCommand;
import dev.mordonez.liferaycli.commands.PageCommand;
import dev.mordonez.liferaycli.commands.ResourceCommand;
import dev.mordonez.liferaycli.commands.ThemeCommand;
import dev.mordonez.liferaycli.config.RuntimeConfig;
import dev.mordonez.liferaycli.http.LiferayApiClient;
import dev.mordonez.liferaycli.http.OAuthTokenClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.net.http.HttpClient;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;

public class LiferayCLIMain {
    public static void main(String[] args) {
        RuntimeConfig config = RuntimeConfig.load(Paths.get("."), System.getenv());
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
            .build();

        RootCommand root = new RootCommand(config, new OAuthTokenClient(httpClient), new LiferayApiClient(httpClient));
        int exitCode = new CommandLine(root).execute(args);
        System.exit(exitCode);
    }

    @Command(
        name = "liferay-cli",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "Herramienta CLI para gestión de recursos y diagnóstico de Liferay DXP",
        subcommands = {
            AuthCommand.class,      // Gestión de tokens OAuth2
            AuditCommand.class,     // Auditoría rápida de conectividad
            InventoryCommand.class, // Inventario de recursos (sites, page, etc)
            PageCommand.class,      // Export/diff de estructura de content pages
            ResourceCommand.class,  // Gestión de Estructuras, Plantillas, ADTs
            ThemeCommand.class,     // Diagnóstico de temas y regresiones
            HealthCommand.class     // Estado del nodo y JVM
        }
    )
    public static class RootCommand implements Callable<Integer> {
        final RuntimeConfig settings;
        final OAuthTokenClient tokenClient;
        final LiferayApiClient apiClient;
        final ObjectMapper mapper;

        public RootCommand(RuntimeConfig settings, OAuthTokenClient tokenClient, LiferayApiClient apiClient) {
            this.settings = settings;
            this.tokenClient = tokenClient;
            this.apiClient = apiClient;
            this.mapper = new ObjectMapper();
        }

        @Override
        public Integer call() {
            System.out.println("liferay-cli: usa --help para ver comandos");
            return 0;
        }

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        public RuntimeConfig settings() {
            return settings;
        }

        public OAuthTokenClient tokenClient() {
            return tokenClient;
        }

        public LiferayApiClient apiClient() {
            return apiClient;
        }

        public ObjectMapper mapper() {
            return mapper;
        }

        public Map<String, String> runtimeInfo() {
            return Map.of(
                "baseUrl", settings.baseUrl(),
                "clientId", settings.clientId(),
                "scopeAliases", settings.scopeAliases(),
                "timeoutSeconds", String.valueOf(settings.timeoutSeconds())
            );
        }
    }
}
