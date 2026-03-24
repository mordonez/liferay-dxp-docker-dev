package dev.mordonez.liferaycli.commands;

import dev.mordonez.liferaycli.LiferayCLIMain;
import dev.mordonez.liferaycli.http.LiferayApiClient;
import dev.mordonez.liferaycli.http.OAuthTokenClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "health", mixinStandardHelpOptions = true, description = "Chequeo rapido de API Liferay")
public class HealthCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private LiferayCLIMain.RootCommand root;

    @Override
    public Integer call() {
        try {
            OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
            LiferayApiClient.ApiResponse response = root.apiClient().get(
                root.settings().baseUrl(),
                "/o/headless-admin-user/v1.0/sites/by-friendly-url-path/global",
                token.accessToken(),
                root.settings().timeoutSeconds()
            );

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("HEALTH_OK");
                System.out.println("status=" + response.statusCode());
                System.out.println("baseUrl=" + root.settings().baseUrl());
                return 0;
            }

            System.err.println("HEALTH_ERROR: status=" + response.statusCode());
            System.err.println(response.body());
            return 1;
        }
        catch (Exception ex) {
            System.err.println("HEALTH_ERROR: " + ex.getMessage());
            return 1;
        }
    }
}
