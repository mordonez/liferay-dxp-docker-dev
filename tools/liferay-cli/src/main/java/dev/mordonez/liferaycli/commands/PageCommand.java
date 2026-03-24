package dev.mordonez.liferaycli.commands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mordonez.liferaycli.LiferayCLIMain;
import dev.mordonez.liferaycli.http.LiferayApiClient;
import dev.mordonez.liferaycli.http.OAuthTokenClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

@Command(
    name = "page-layout",
    mixinStandardHelpOptions = true,
    description = "Exporta y compara estructuras de content pages",
    subcommands = {
        PageCommand.Export.class,
        PageCommand.Diff.class
    }
)
public class PageCommand implements Callable<Integer> {
    private static final String EXPORT_KIND = "liferay-page-layout-export";
    private static final int EXPORT_SCHEMA_VERSION = 1;

    @ParentCommand
    LiferayCLIMain.RootCommand root;

    @Override
    public Integer call() {
        System.out.println("page-layout: usa export o diff");
        return 0;
    }

    @Command(name = "export", mixinStandardHelpOptions = true, description = "Exporta una content page a JSON")
    public static class Export implements Callable<Integer> {
        @ParentCommand
        PageCommand parent;

        @Option(names = "--url", required = true, description = "URL completa o relativa de la página")
        String url;

        @Option(names = "--output", description = "Fichero de salida JSON")
        Path output;

        @Option(names = "--pretty", defaultValue = "true", negatable = true, description = "Pretty-print del JSON")
        boolean pretty;

        @Override
        public Integer call() throws Exception {
            ObjectNode export = buildPageExport(parent.root, url);
            return writeJson(parent.root.mapper(), export, output, pretty);
        }
    }

    @Command(name = "diff", mixinStandardHelpOptions = true, description = "Compara una página live contra un export o contra otra página")
    public static class Diff implements Callable<Integer> {
        @ParentCommand
        PageCommand parent;

        @Option(names = "--url", required = true, description = "Página base live")
        String url;

        @Option(names = "--file", description = "Export JSON de referencia")
        Path file;

        @Option(names = "--reference-url", description = "Página live de referencia alternativa")
        String referenceUrl;

        @Override
        public Integer call() throws Exception {
            if ((file == null && referenceUrl == null) || (file != null && referenceUrl != null)) {
                throw new IllegalArgumentException("Usa exactamente uno de --file o --reference-url");
            }

            ObjectNode left = buildPageExport(parent.root, url);
            ObjectNode right = referenceUrl != null
                ? buildPageExport(parent.root, referenceUrl)
                : readExportFile(parent.root.mapper(), file);

            ObjectNode diff = parent.root.mapper().createObjectNode();
            diff.put("kind", "liferay-page-layout-diff");
            diff.put("leftUrl", left.path("source").path("url").asText(""));
            if (referenceUrl != null) {
                diff.put("rightUrl", right.path("source").path("url").asText(""));
            }
            if (file != null) {
                diff.put("referenceFile", file.toAbsolutePath().normalize().toString());
            }

            JsonNode leftComparable = comparableNode(left);
            JsonNode rightComparable = comparableNode(right);
            String compareMode = compareMode(left, right);

            ArrayNode diffs = parent.root.mapper().createArrayNode();
            collectDiffs(parent.root.mapper(), compareMode, "", leftComparable, rightComparable, diffs, 50);

            diff.put("compareMode", compareMode);
            diff.put("equal", diffs.isEmpty());
            diff.put("diffCount", diffs.size());
            diff.set("diffs", diffs);

            System.out.println(parent.root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(diff));
            return diffs.isEmpty() ? 0 : 1;
        }
    }

    static ObjectNode buildPageExport(
        LiferayCLIMain.RootCommand root,
        String rawUrl
    ) throws Exception {
        ObjectMapper mapper = root.mapper();
        ParsedPageUrl parsed = parsePageUrl(rawUrl);
        OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
        JsonNode siteNode = resolveSite(root, token.accessToken(), parsed.siteSlug());
        long groupId = siteNode.path("id").asLong(-1L);
        String siteFriendlyUrl = siteNode.path("friendlyUrlPath").asText("/" + parsed.siteSlug());
        String siteName = siteNode.path("name").asText(parsed.siteSlug());

        LayoutContext layoutContext = resolveLayoutContext(root, token, groupId, parsed.friendlyUrl());
        if (layoutContext == null) {
            throw new IllegalStateException("Layout no encontrado para " + parsed.friendlyUrl());
        }

        ObjectNode export = mapper.createObjectNode();
        export.put("kind", EXPORT_KIND);
        export.put("schemaVersion", EXPORT_SCHEMA_VERSION);
        export.put("generatedAt", Instant.now().toString());

        ObjectNode source = mapper.createObjectNode();
        source.put("baseUrl", root.settings().baseUrl());
        source.put("url", buildPageUrl(siteFriendlyUrl, parsed.friendlyUrl(), parsed.privateLayout()));
        source.put("siteFriendlyUrl", siteFriendlyUrl);
        source.put("siteName", siteName);
        source.put("siteId", groupId);
        source.put("friendlyUrl", parsed.friendlyUrl());
        source.put("privateLayout", parsed.privateLayout());
        source.put("layoutId", layoutContext.layoutId());
        source.put("plid", layoutContext.plid());
        source.put("layoutType", layoutContext.layoutType());
        source.put("pageName", layoutContext.pageName());
        export.set("source", source);

        ObjectNode adminUrls = mapper.createObjectNode();
        adminUrls.put("Edit URL", root.settings().baseUrl() + buildPageUrl(siteFriendlyUrl, parsed.friendlyUrl(), parsed.privateLayout()) + "?p_l_mode=edit");
        adminUrls.put("Translate URL", buildPageTranslateUrl(root.settings().baseUrl(), parsed.siteSlug(), layoutContext.plid()));
        adminUrls.put("Configure URL (General)", buildPageConfigureUrl(root.settings().baseUrl(), parsed.siteSlug(), layoutContext.plid(), "general"));
        adminUrls.put("Configure URL (Design)", buildPageConfigureUrl(root.settings().baseUrl(), parsed.siteSlug(), layoutContext.plid(), "design"));
        adminUrls.put("Configure URL (SEO)", buildPageConfigureUrl(root.settings().baseUrl(), parsed.siteSlug(), layoutContext.plid(), "seo"));
        export.set("adminUrls", adminUrls);

        JsonNode headlessSitePage = fetchSitePage(root, token, groupId, parsed.friendlyUrl());
        if (headlessSitePage != null) {
            export.set("headlessSitePage", headlessSitePage);
        }

        JsonNode experiences = fetchSitePageExperiences(root, token, groupId, parsed.friendlyUrl());
        if (experiences != null) {
            export.set("experiences", experiences);
        }

        ObjectNode layoutStructure = mapper.createObjectNode();
        layoutStructure.put("available", false);
        layoutStructure.put("storage", "api-only");
        layoutStructure.put("warning", "Stored layout structure is not exported by the official API. Compare uses headless pageDefinition.");
        export.set("layoutStructure", layoutStructure);
        return export;
    }

    private static JsonNode comparableNode(ObjectNode export) {
        return export.path("headlessSitePage").path("pageDefinition");
    }

    private static String compareMode(ObjectNode left, ObjectNode right) {
        return "pageDefinition";
    }

    private static void collectDiffs(
        ObjectMapper mapper,
        String compareMode,
        String path,
        JsonNode left,
        JsonNode right,
        ArrayNode diffs,
        int maxDiffs
    ) {
        if (diffs.size() >= maxDiffs) {
            return;
        }
        if (left == null || left.isMissingNode()) {
            if (right != null && !right.isMissingNode()) {
                diffs.add(diffEntry(mapper, compareMode, path, left, right));
            }
            return;
        }
        if (right == null || right.isMissingNode()) {
            diffs.add(diffEntry(mapper, compareMode, path, left, right));
            return;
        }
        if (left.getNodeType() != right.getNodeType()) {
            diffs.add(diffEntry(mapper, compareMode, path, left, right));
            return;
        }
        if (left.isObject()) {
            Set<String> fields = new TreeSet<>();
            left.fieldNames().forEachRemaining(fields::add);
            right.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                collectDiffs(mapper, compareMode, joinPath(path, field), left.get(field), right.get(field), diffs, maxDiffs);
                if (diffs.size() >= maxDiffs) {
                    return;
                }
            }
            return;
        }
        if (left.isArray()) {
            int max = Math.max(left.size(), right.size());
            if (left.size() != right.size()) {
                diffs.add(diffEntry(mapper, compareMode, joinPath(path, "length"), left.size(), right.size()));
                if (diffs.size() >= maxDiffs) {
                    return;
                }
            }
            for (int i = 0; i < max; i++) {
                collectDiffs(mapper, compareMode, path + "[" + i + "]", left.get(i), right.get(i), diffs, maxDiffs);
                if (diffs.size() >= maxDiffs) {
                    return;
                }
            }
            return;
        }
        if (!left.equals(right)) {
            diffs.add(diffEntry(mapper, compareMode, path, left, right));
        }
    }

    private static ObjectNode diffEntry(ObjectMapper mapper, String compareMode, String path, Object left, Object right) {
        ObjectNode diff = mapper.createObjectNode();
        diff.put("compareMode", compareMode);
        diff.put("path", path == null || path.isBlank() ? "$" : path);
        diff.put("left", summarizeValue(left));
        diff.put("right", summarizeValue(right));
        return diff;
    }

    private static String joinPath(String current, String field) {
        String base = (current == null || current.isBlank()) ? "$" : current;
        return "$".equals(base) ? "$." + field : base + "." + field;
    }

    private static String summarizeValue(Object value) {
        if (value == null) {
            return "<missing>";
        }
        if (value instanceof JsonNode node) {
            if (node.isMissingNode()) {
                return "<missing>";
            }
            if (node.isValueNode()) {
                return node.toString();
            }
            String raw = node.toString();
            return raw.length() > 220 ? raw.substring(0, 220) + "..." : raw;
        }
        return String.valueOf(value);
    }

    private static ObjectNode readExportFile(ObjectMapper mapper, Path file) throws IOException {
        ObjectNode export = (ObjectNode) mapper.readTree(Files.readString(file));
        if (!EXPORT_KIND.equals(export.path("kind").asText(""))) {
            throw new IllegalArgumentException("El fichero no parece un export de page layout: " + file);
        }
        return export;
    }

    private static int writeJson(ObjectMapper mapper, JsonNode json, Path output, boolean pretty) throws IOException {
        String serialized = pretty
            ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json)
            : mapper.writeValueAsString(json);
        if (output == null) {
            System.out.println(serialized);
        }
        else {
            Files.createDirectories(output.toAbsolutePath().normalize().getParent());
            Files.writeString(output, serialized + System.lineSeparator());
            System.out.println(output.toAbsolutePath().normalize());
        }
        return 0;
    }

    private static ParsedPageUrl parsePageUrl(String rawUrl) {
        String sanitized = sanitizePageUrl(rawUrl);
        try {
            URI uri = new URI(sanitized);
            String path = uri.getPath();
            String[] parts = path.split("/", 4);
            if (parts.length < 4) {
                throw new IllegalArgumentException("URL de página no soportada: " + rawUrl);
            }
            boolean privateLayout = "group".equals(parts[1]);
            if (!"web".equals(parts[1]) && !privateLayout) {
                throw new IllegalArgumentException("Solo se soportan rutas /web/<site>/... o /group/<site>/...");
            }
            String siteSlug = parts[2];
            String friendlyUrl = "/" + parts[3];
            return new ParsedPageUrl(siteSlug, friendlyUrl, privateLayout);
        }
        catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL inválida: " + rawUrl, e);
        }
    }

    private static String sanitizePageUrl(String rawUrl) {
        if (rawUrl == null) {
            throw new IllegalArgumentException("La URL no puede ser null");
        }
        String sanitized = rawUrl.trim();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("La URL no puede estar vacía");
        }
        try {
            URI uri = new URI(sanitized);
            if (uri.isAbsolute()) {
                sanitized = uri.getRawPath();
            }
        }
        catch (URISyntaxException ignored) {
        }
        int fragmentIndex = sanitized.indexOf('#');
        if (fragmentIndex >= 0) {
            sanitized = sanitized.substring(0, fragmentIndex);
        }
        int queryIndex = sanitized.indexOf('?');
        if (queryIndex >= 0) {
            sanitized = sanitized.substring(0, queryIndex);
        }
        if (!sanitized.startsWith("/")) {
            sanitized = "/" + sanitized;
        }
        sanitized = sanitized.replaceFirst("^/[a-z]{2}(?:_[A-Z]{2})?/(web|group)(?=/|$)", "/$1");
        return sanitized;
    }

    private static String buildPageUrl(String siteFriendlyUrl, String friendlyUrl, boolean privateLayout) {
        String siteSlug = siteFriendlyUrl.startsWith("/") ? siteFriendlyUrl.substring(1) : siteFriendlyUrl;
        return (privateLayout ? "/group/" : "/web/") + siteSlug + friendlyUrl;
    }

    private static String buildPageTranslateUrl(String baseUrl, String siteSlug, long plid) {
        String pfx = "&_com_liferay_translation_web_portlet_TranslationPortlet_";
        return controlPanelBase(baseUrl, siteSlug, "com_liferay_translation_web_portlet_TranslationPortlet") +
            pfx + "mvcRenderCommandName=%2Ftranslation%2Fview_list" +
            pfx + "classNameId=20006" +
            pfx + "classPK=" + plid;
    }

    private static String buildPageConfigureUrl(String baseUrl, String siteSlug, long plid, String screenNavigationEntryKey) {
        String pfx = "&_com_liferay_layout_admin_web_portlet_GroupPagesPortlet_";
        return controlPanelBase(baseUrl, siteSlug, "com_liferay_layout_admin_web_portlet_GroupPagesPortlet") +
            pfx + "mvcRenderCommandName=%2Flayout_admin%2Fedit_layout" +
            pfx + "selPlid=" + plid +
            pfx + "backURL=" +
            pfx + "screenNavigationEntryKey=" + screenNavigationEntryKey;
    }

    private static String controlPanelBase(String baseUrl, String siteSlug, String portletId) {
        return baseUrl + "/ca/group/" + siteSlug + "/~/control_panel/manage?p_p_id=" + portletId +
            "&p_p_lifecycle=0&p_p_state=maximized";
    }

    private static JsonNode resolveSite(LiferayCLIMain.RootCommand root, String accessToken, String site) throws Exception {
        if (site.matches("^\\d+$")) {
            LiferayApiClient.ApiResponse response = root.apiClient().get(
                root.settings().baseUrl(),
                "/o/headless-admin-user/v1.0/sites/" + site,
                accessToken,
                root.settings().timeoutSeconds()
            );
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return root.mapper().readTree(response.body());
            }
        }

        String normalized = site.startsWith("/") ? site.substring(1) : site;
        String encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            "/o/headless-admin-user/v1.0/sites/by-friendly-url-path/" + encoded,
            accessToken,
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return root.mapper().readTree(response.body());
        }
        throw new IllegalStateException("No se pudo resolver el site " + site);
    }

    private static LayoutContext resolveLayoutContext(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        long groupId,
        String friendlyUrl
    ) throws Exception {
        JsonNode layout = findLayoutByFriendlyUrl(root, token, groupId, friendlyUrl);
        if (layout == null) {
            return null;
        }
        return new LayoutContext(
            layout.path("layoutId").asLong(-1L),
            layout.path("plid").asLong(-1L),
            layout.path("type").asText(""),
            layout.path("nameCurrentValue").asText(""),
            layout
        );
    }

    private static JsonNode findLayoutByFriendlyUrl(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        long groupId,
        String friendlyUrl
    ) throws Exception {
        String url = friendlyUrl.startsWith("/") ? friendlyUrl : "/" + friendlyUrl;
        return searchLayouts(root, token, groupId, 0, url, 0);
    }

    private static JsonNode searchLayouts(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        long groupId,
        long parentId,
        String targetUrl,
        int depth
    ) throws Exception {
        if (depth > 12) {
            return null;
        }
        JsonNode body = fetchLayoutsByParent(root, token, groupId, false, parentId);
        if (!body.isArray()) {
            return null;
        }
        for (JsonNode layout : body) {
            if (targetUrl.equalsIgnoreCase(layout.path("friendlyURL").asText(""))) {
                return layout;
            }
        }
        for (JsonNode layout : body) {
            long layoutId = layout.path("layoutId").asLong(0L);
            JsonNode found = searchLayouts(root, token, groupId, layoutId, targetUrl, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static JsonNode fetchLayoutsByParent(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        long groupId,
        boolean privateLayout,
        long parentLayoutId
    ) throws Exception {
        String path = "/api/jsonws/layout/get-layouts?groupId=" + groupId +
            "&privateLayout=" + privateLayout + "&parentLayoutId=" + parentLayoutId;
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            path,
            token.accessToken(),
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return root.mapper().createArrayNode();
        }
        JsonNode payload = root.mapper().readTree(response.body());
        return payload.isArray() ? payload : root.mapper().createArrayNode();
    }

    private static JsonNode fetchSitePage(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        long siteId,
        String friendlyUrl
    ) throws Exception {
        String slug = friendlyUrl.startsWith("/") ? friendlyUrl.substring(1) : friendlyUrl;
        String path = "/o/headless-delivery/v1.0/sites/" + siteId + "/site-pages/" + slug +
            "?fields=actions,friendlyUrlPath,id,pageDefinition,pageType,siteId,title,uuid";
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            path,
            token.accessToken(),
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        return root.mapper().readTree(response.body());
    }

    private static JsonNode fetchSitePageExperiences(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        long siteId,
        String friendlyUrl
    ) throws Exception {
        String slug = friendlyUrl.startsWith("/") ? friendlyUrl.substring(1) : friendlyUrl;
        String path = "/o/headless-delivery/v1.0/sites/" + siteId + "/site-pages/" + slug + "/experiences";
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            path,
            token.accessToken(),
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        return root.mapper().readTree(response.body());
    }

    record ParsedPageUrl(String siteSlug, String friendlyUrl, boolean privateLayout) {
    }

    record LayoutContext(long layoutId, long plid, String layoutType, String pageName, JsonNode rawLayout) {
    }
}
