package dev.mordonez.liferaycli.commands;

import dev.mordonez.liferaycli.LiferayCLIMain;
import dev.mordonez.liferaycli.http.OAuthTokenClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "auth",
    description = "Operaciones de autenticacion OAuth2",
    subcommands = {AuthCommand.Check.class, AuthCommand.Token.class}
)
public class AuthCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private LiferayCLIMain.RootCommand root;

    @Override
    public Integer call() {
        System.out.println("Usa: ub-cli auth check|token");
        return 0;
    }

    @Command(name = "check", mixinStandardHelpOptions = true, description = "Obtiene token client_credentials")
    public static class Check implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AuthCommand parent;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                System.out.println("AUTH_OK");
                System.out.println("baseUrl=" + root.settings().baseUrl());
                System.out.println("clientId=" + root.settings().clientId());
                System.out.println("tokenType=" + token.tokenType());
                System.out.println("expiresIn=" + token.expiresIn());
                return 0;
            }
            catch (Exception ex) {
                System.err.println("AUTH_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "token", mixinStandardHelpOptions = true, description = "Obtiene token OAuth2 para scripting")
    public static class Token implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AuthCommand parent;

        @Option(names = "--raw", defaultValue = "false", description = "Imprime solo el access token")
        boolean raw;

        @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
        String format;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());

                if (raw && "text".equalsIgnoreCase(format)) {
                    System.out.println(token.accessToken());
                    return 0;
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("baseUrl", root.settings().baseUrl());
                payload.put("clientId", root.settings().clientId());
                payload.put("tokenType", token.tokenType());
                payload.put("expiresIn", token.expiresIn());
                payload.put("accessTokenMasked", mask(token.accessToken()));
                if (raw) {
                    payload.put("accessToken", token.accessToken());
                }

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload));
                    return 0;
                }
                if (!"text".equalsIgnoreCase(format)) {
                    System.err.println("AUTH_ERROR: formato no soportado: " + format);
                    return 1;
                }

                System.out.println("AUTH_TOKEN_OK");
                System.out.println("baseUrl=" + payload.get("baseUrl"));
                System.out.println("clientId=" + payload.get("clientId"));
                System.out.println("tokenType=" + payload.get("tokenType"));
                System.out.println("expiresIn=" + payload.get("expiresIn"));
                System.out.println("accessTokenMasked=" + payload.get("accessTokenMasked"));
                if (raw) {
                    System.out.println("accessToken=" + token.accessToken());
                }
                return 0;
            }
            catch (Exception ex) {
                System.err.println("AUTH_ERROR: " + ex.getMessage());
                return 1;
            }
        }

        private static String mask(String value) {
            if (value == null || value.isBlank()) {
                return "****";
            }
            if (value.length() <= 8) {
                return "****";
            }
            return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
        }
    }
}
