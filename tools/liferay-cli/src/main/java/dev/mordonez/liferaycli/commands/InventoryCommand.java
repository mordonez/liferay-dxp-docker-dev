package dev.mordonez.liferaycli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mordonez.liferaycli.LiferayCLIMain;
import dev.mordonez.liferaycli.http.LiferayApiClient;
import dev.mordonez.liferaycli.http.OAuthTokenClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "inventory",
    description = "Inventario de recursos",
    mixinStandardHelpOptions = true,
    subcommands = {
        InventoryCommand.Sites.class,
        InventoryCommand.Structures.class,
        InventoryCommand.Templates.class,
        InventoryCommand.Pages.class,
        InventoryCommand.Page.class,
        InventoryCommand.Articles.class
    }
)
public class InventoryCommand implements Callable<Integer> {
    private static final String JOURNAL_ARTICLE_CLASS_NAME = "com.liferay.journal.model.JournalArticle";
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    @CommandLine.ParentCommand
    private LiferayCLIMain.RootCommand root;

    @Override
    public Integer call() {
        System.out.println("Usa: liferay-cli inventory sites|structures|templates|pages|page|articles");
        return 0;
    }

    @Command(name = "articles", aliases = {"ls-articles", "ls-journal"}, mixinStandardHelpOptions = true, description = "Lista artículos Journal de un site")
    public static class Articles implements Callable<Integer> {
        @CommandLine.ParentCommand
        private InventoryCommand parent;

        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
        String site;

        @Option(names = "--structure-id", description = "Filtrar por ID de estructura (ej. 36871 para Basic Web Content)")
        Long structureId;

        @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
        String format;

        @Option(names = "--page-size", defaultValue = "200", description = "Tamaño de página")
        int pageSize;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                long siteId = resolveSiteId(root, token, site);

                String basePath = "/o/headless-delivery/v1.0/sites/" + siteId + "/structured-contents?flatten=true";
                if (structureId != null) {
                    String filter = URLEncoder.encode("contentStructureId eq " + structureId, StandardCharsets.UTF_8);
                    basePath += "&filter=" + filter;
                }

                JsonNode rows = fetchPagedItems(root, token, basePath, pageSize);

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(rows));
                    return 0;
                }

                int count = 0;
                for (JsonNode row : rows) {
                    long id = row.path("id").asLong(-1L);
                    String key = row.path("key").asText("");
                    String title = row.path("title").asText("");
                    String friendlyUrl = row.path("friendlyUrlPath").asText("");
                    System.out.printf("- id=%d key=%s title=%s url=%s%n", id, key, title, friendlyUrl);
                    count++;
                }
                System.out.println("total=" + count);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("INVENTORY_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "sites", aliases = {"ls-sites", "s"},
        mixinStandardHelpOptions = true,
        description = "Lista sites accesibles por JSONWS/Headless API"
    )
    public static class Sites implements Callable<Integer> {
        @CommandLine.ParentCommand
        private InventoryCommand parent;

        @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
        String format;

        @Option(names = "--page-size", defaultValue = "200", description = "Tamano maximo de pagina")
        int pageSize;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                List<JsonNode> siteRows = fetchSitesViaJsonws(root, token);
                ArrayNode normalizedSites = root.mapper().createArrayNode();
                for (JsonNode siteRow : siteRows) {
                    normalizedSites.add(buildSiteInventoryNode(root, siteRow));
                }

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(normalizedSites));
                    return 0;
                }

                if (normalizedSites.isEmpty()) {
                    System.out.println("Sin datos de sites");
                    return 0;
                }

                int count = 0;
                Iterator<JsonNode> iterator = normalizedSites.iterator();
                while (iterator.hasNext()) {
                    JsonNode item = iterator.next();
                    long id = item.path("groupId").asLong(-1L);
                    String friendly = item.path("siteFriendlyUrl").asText(item.path("friendlyURL").asText(""));
                    String name = item.path("nameCurrentValue").asText(item.path("name").asText(""));
                    System.out.printf("- id=%d site=%s name=%s pages=%s%n",
                        id,
                        friendly,
                        name,
                        item.path("pagesCommand").asText(""));
                    count++;
                }
                System.out.println("total=" + count);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("INVENTORY_ERROR: " + ex.getMessage());
                return 1;
            }
        }

        private List<JsonNode> fetchSitesViaJsonws(LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token)
            throws Exception {
            LiferayApiClient api = root.apiClient();
            int timeoutSeconds = root.settings().timeoutSeconds();

            LiferayApiClient.ApiResponse companiesResponse = api.get(
                root.settings().baseUrl(),
                "/api/jsonws/company/get-companies",
                token.accessToken(),
                timeoutSeconds
            );
            if (companiesResponse.statusCode() < 200 || companiesResponse.statusCode() >= 300) {
                throw new IllegalStateException("company/get-companies status=" + companiesResponse.statusCode());
            }

            JsonNode companies = root.mapper().readTree(companiesResponse.body());
            List<JsonNode> rows = new ArrayList<>();
            for (JsonNode company : companies) {
                long companyId = company.path("companyId").asLong(0L);
                if (companyId <= 0) {
                    continue;
                }

                int total = fetchSearchCount(root, token, companyId);
                for (int start = 0; start < total; start += pageSize) {
                    int end = start + pageSize;
                    String path = "/api/jsonws/group/search?companyId=" + companyId +
                        "&name=&description=&params=%7B%7D&start=" + start + "&end=" + end;
                    LiferayApiClient.ApiResponse response = api.get(
                        root.settings().baseUrl(),
                        path,
                        token.accessToken(),
                        timeoutSeconds
                    );
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("group/search status=" + response.statusCode());
                    }
                    JsonNode page = root.mapper().readTree(response.body());
                    if (page.isArray()) {
                        for (JsonNode row : page) {
                            if (row.path("site").asBoolean(false)) {
                                rows.add(row);
                            }
                        }
                    }
                }
            }
            return rows;
        }

        private ObjectNode buildSiteInventoryNode(LiferayCLIMain.RootCommand root, JsonNode siteRow) {
            ObjectNode siteNode = root.mapper().createObjectNode();
            long groupId = siteRow.path("groupId").asLong(-1L);
            String siteFriendlyUrl = siteRow.path("friendlyURL").asText("");
            String name = siteRow.path("nameCurrentValue").asText(siteRow.path("name").asText(""));

            siteNode.put("groupId", groupId);
            siteNode.put("siteFriendlyUrl", siteFriendlyUrl);
            siteNode.put("name", name);
            siteNode.put("pagesCommand", buildPagesCommand(siteFriendlyUrl));
            return siteNode;
        }

        private int fetchSearchCount(LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, long companyId)
            throws Exception {
            String params = URLEncoder.encode("{}", StandardCharsets.UTF_8);
            String path = "/api/jsonws/group/search-count?companyId=" + companyId +
                "&name=&description=&params=" + params;
            LiferayApiClient.ApiResponse response = root.apiClient().get(
                root.settings().baseUrl(),
                path,
                token.accessToken(),
                root.settings().timeoutSeconds()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("group/search-count status=" + response.statusCode());
            }
            String body = response.body().trim();
            if (body.startsWith("\"") && body.endsWith("\"") && body.length() >= 2) {
                body = body.substring(1, body.length() - 1);
            }
            return Integer.parseInt(body);
        }
    }

    @Command(name = "structures", aliases = {"ls-structures", "ls-ss"}, mixinStandardHelpOptions = true, description = "Lista estructuras Journal de un site")
    public static class Structures implements Callable<Integer> {
        @CommandLine.ParentCommand
        private InventoryCommand parent;

        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
        String site;

        @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
        String format;

        @Option(names = "--page-size", defaultValue = "200", description = "Tamano de pagina")
        int pageSize;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                long siteId = resolveSiteId(root, token, site);
                String basePath = "/o/data-engine/v2.0/sites/" + siteId +
                    "/data-definitions/by-content-type/journal";
                JsonNode rows = fetchPagedItems(root, token, basePath, pageSize);

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(root.mapper().writeValueAsString(rows));
                    return 0;
                }

                int count = 0;
                for (JsonNode row : rows) {
                    long id = row.path("id").asLong(-1L);
                    String key = row.path("dataDefinitionKey").asText("");
                    String name = localizedName(row.path("name"));
                    System.out.printf("- id=%d key=%s name=%s%n", id, key, name);
                    count++;
                }
                System.out.println("total=" + count);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("INVENTORY_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "templates", aliases = {"ls-templates", "ls-st"}, mixinStandardHelpOptions = true, description = "Lista templates web de un site")
    public static class Templates implements Callable<Integer> {
        @CommandLine.ParentCommand
        private InventoryCommand parent;

        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
        String site;

        @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
        String format;

        @Option(names = "--page-size", defaultValue = "200", description = "Tamano de pagina")
        int pageSize;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                long siteId = resolveSiteId(root, token, site);
                String basePath = "/o/headless-delivery/v1.0/sites/" + siteId + "/content-templates";
                JsonNode rows = fetchPagedItems(root, token, basePath, pageSize);

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(root.mapper().writeValueAsString(rows));
                    return 0;
                }

                int count = 0;
                for (JsonNode row : rows) {
                    String key = row.path("id").asText("");
                    String name = row.path("name").asText(key);
                    long structureId = row.path("contentStructureId").asLong(-1L);
                    System.out.printf("- key=%s structureId=%d name=%s%n", key, structureId, name);
                    count++;
                }
                System.out.println("total=" + count);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("INVENTORY_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "pages", aliases = {"ls-pages", "ls-layouts"},
        mixinStandardHelpOptions = true,
        description = "Lista jerarquicamente las paginas de un site"
    )
    public static class Pages implements Callable<Integer> {
        private static final String INVENTORY_TYPE_PAGES = "pages";

        @CommandLine.ParentCommand
        private InventoryCommand parent;

        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
        String site;

        @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
        String format;

        @Option(names = "--private-layout", defaultValue = "false", description = "Listar paginas privadas en lugar de publicas")
        boolean privateLayout;

        @Option(names = "--max-depth", defaultValue = "12", description = "Profundidad maxima de recursion")
        int maxDepth;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                JsonNode siteNode = resolveSite(root, token.accessToken(), site);

                long groupId = siteNode.path("id").asLong(-1L);
                String siteSlug = siteNode.path("friendlyUrlPath").asText(site).replaceFirst("^/", "");
                String siteName = siteNode.path("name").asText(siteSlug);

                ObjectNode result = root.mapper().createObjectNode();
                result.put("inventoryType", INVENTORY_TYPE_PAGES);
                result.put("groupId", groupId);
                result.put("siteName", siteName);
                result.put("siteFriendlyUrl", "/" + siteSlug);
                result.put("privateLayout", privateLayout);
                result.put("sitePathPrefix", buildSitePathPrefix(siteSlug, privateLayout));
                result.put("inspectCommandTemplate", "inventory page --url <fullUrl>");

                ArrayNode pages = fetchLayoutTree(root, token, groupId, siteSlug, privateLayout, 0L, 0);
                result.set("pages", pages);
                result.put("pageCount", countPages(pages));

                outputPagesResult(root, result, format);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("INVENTORY_ERROR: " + ex.getMessage());
                return 1;
            }
        }

        private ArrayNode fetchLayoutTree(
            LiferayCLIMain.RootCommand root,
            OAuthTokenClient.TokenResponse token,
            long groupId,
            String siteSlug,
            boolean privateLayout,
            long parentLayoutId,
            int depth
        ) throws Exception {
            ArrayNode pages = root.mapper().createArrayNode();
            if (depth > Math.max(0, maxDepth)) {
                return pages;
            }

            JsonNode layouts = fetchLayoutsByParent(root, token, groupId, privateLayout, parentLayoutId);
            if (!layouts.isArray()) {
                return pages;
            }

            for (JsonNode layout : layouts) {
                ObjectNode pageNode = root.mapper().createObjectNode();
                pageNode.put("pageType", "regularPage");
                pageNode.put("pageSubtype", layout.path("type").asText(""));
                pageNode.put("name", layout.path("nameCurrentValue").asText(""));
                pageNode.put("friendlyUrl", layout.path("friendlyURL").asText(""));
                pageNode.put("fullUrl", buildInventoryPageUrl(
                    siteSlug,
                    privateLayout,
                    layout.path("friendlyURL").asText("")
                ));
                pageNode.put("pageCommand", buildPageCommand(pageNode.path("fullUrl").asText("")));
                pageNode.put("layoutId", layout.path("layoutId").asLong(-1L));
                pageNode.put("plid", layout.path("plid").asLong(-1L));
                pageNode.put("hidden", layout.path("hidden").asBoolean(false));

                String targetUrl = extractLayoutTargetUrl(layout);
                if (!targetUrl.isEmpty()) {
                    pageNode.put("targetUrl", targetUrl);
                }

                ArrayNode children = fetchLayoutTree(
                    root,
                    token,
                    groupId,
                    siteSlug,
                    privateLayout,
                    layout.path("layoutId").asLong(0L),
                    depth + 1
                );
                pageNode.set("children", children);
                pages.add(pageNode);
            }

            return pages;
        }

        private static int countPages(ArrayNode pages) {
            int count = 0;
            for (JsonNode page : pages) {
                count++;
                JsonNode children = page.path("children");
                if (children.isArray()) {
                    count += countPages((ArrayNode) children);
                }
            }
            return count;
        }

        private static void outputPagesResult(LiferayCLIMain.RootCommand root, ObjectNode result, String format) throws Exception {
            if ("json".equalsIgnoreCase(format)) {
                System.out.println(root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
                return;
            }

            System.out.println("SITE PAGES");
            System.out.printf("- Site: %s%n", result.path("siteName").asText(""));
            System.out.printf("- Site Friendly URL: %s%n", result.path("siteFriendlyUrl").asText(""));
            System.out.printf("- Site Path Prefix: %s%n", result.path("sitePathPrefix").asText(""));
            System.out.printf("- Group ID (siteId): %d%n", result.path("groupId").asLong(-1L));
            System.out.printf("- Scope: %s%n", result.path("privateLayout").asBoolean(false) ? "private" : "public");
            System.out.printf("- Total Pages: %d%n", result.path("pageCount").asInt(0));
            System.out.printf("- Inspect Command Template: %s%n", result.path("inspectCommandTemplate").asText(""));
            printPageTree(result.path("pages"), 0);
        }

        private static void printPageTree(JsonNode pages, int depth) {
            if (!pages.isArray()) {
                return;
            }
            String indent = "  ".repeat(Math.max(0, depth));
            for (JsonNode page : pages) {
                StringBuilder line = new StringBuilder();
                line.append(indent)
                    .append("- ")
                    .append(page.path("name").asText(""))
                    .append(" [")
                    .append(page.path("pageSubtype").asText(""))
                    .append("] ")
                    .append(page.path("fullUrl").asText(page.path("friendlyUrl").asText("")));
                if (page.path("hidden").asBoolean(false)) {
                    line.append(" (hidden)");
                }
                String targetUrl = page.path("targetUrl").asText("");
                if (!targetUrl.isEmpty()) {
                    line.append(" -> ").append(targetUrl);
                }
                System.out.println(line);
                printPageTree(page.path("children"), depth + 1);
            }
        }
    }

    @Command(name = "page", aliases = {"ls-page", "p"}, mixinStandardHelpOptions = true, description = "Analiza una pagina por URL para inventariar sus componentes")
    public static class Page implements Callable<Integer> {
        private static final String PAGE_TYPE_DISPLAY = "displayPage";
        private static final String PAGE_TYPE_REGULAR = "regularPage";
        private static final String PAGE_TYPE_SITE_ROOT = "siteRoot";
        private static final String PAGE_SUBTYPE_JOURNAL_ARTICLE = "journalArticle";
        private static final String TYPE_FRAGMENT = "fragment";
        private static final String TYPE_WIDGET = "widget";

        @CommandLine.ParentCommand
        private InventoryCommand parent;

        @Option(names = "--url", required = false, description = "Friendly URL completa (ej. /web/guest/home o /global/p/ejemplo)")
        String url;

        @Option(names = "--site", description = "Site friendly URL (ej. guest, global, actualitat)")
        String siteArg;

        @Option(names = "--friendly-url", description = "Friendly URL de la pagina (ej. /home, /p/ejemplo)")
        String friendlyUrlArg;

        @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
        String format;

        @Option(names = "--verbose", defaultValue = "false", description = "Muestra mas detalle tecnico")
        boolean verbose;

        private enum InventoryPageRoute {
            SITE_ROOT,
            DISPLAY_PAGE,
            LAYOUT_PAGE
        }

        private record ResolvedInventoryRequest(
            String siteSlug,
            String friendlyUrl,
            InventoryPageRoute route,
            String displayPageUrlTitle
        ) {}

        private record SiteContext(
            JsonNode siteNode,
            long groupId,
            String siteSlug,
            String siteName
        ) {}

        private record LayoutContext(
            JsonNode layout,
            long layoutId,
            long plid,
            String layoutType,
            String pageName
        ) {}

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                ResolvedInventoryRequest request = resolveInventoryRequest();
                SiteContext siteContext = resolveSiteContext(root, token, request.siteSlug());

                return switch (request.route()) {
                    case SITE_ROOT -> handleSiteRoot(root, token, siteContext, format);
                    case DISPLAY_PAGE -> handleDisplayPage(
                        root, token, siteContext, request.displayPageUrlTitle(), format, verbose);
                    case LAYOUT_PAGE -> handleLayoutPage(
                        root, token, siteContext, request.friendlyUrl(), format, verbose);
                };

            } catch (Exception ex) {
                System.err.println("INVENTORY_ERROR: " + (ex.getMessage() != null ? ex.getMessage() : ex.toString()));
                if (verbose) {
                    ex.printStackTrace();
                }
                return 1;
            }
        }

        private ResolvedInventoryRequest resolveInventoryRequest() {
            String siteSlug;
            String normalizedFriendlyUrl;
            String sanitizedUrl = sanitizeInventoryUrl(url);

            if (sanitizedUrl != null && !sanitizedUrl.isEmpty()) {
                if (sanitizedUrl.startsWith("/web/")) {
                    int nextSlash = sanitizedUrl.indexOf('/', 5);
                    siteSlug = sanitizedUrl.substring(5, nextSlash > 0 ? nextSlash : sanitizedUrl.length());
                    normalizedFriendlyUrl = nextSlash > 0 ? sanitizedUrl.substring(nextSlash) : "/";
                } else if (sanitizedUrl.startsWith("/group/")) {
                    int nextSlash = sanitizedUrl.indexOf('/', 7);
                    siteSlug = sanitizedUrl.substring(7, nextSlash > 0 ? nextSlash : sanitizedUrl.length());
                    normalizedFriendlyUrl = nextSlash > 0 ? sanitizedUrl.substring(nextSlash) : "/";
                } else {
                    siteSlug = "global";
                    normalizedFriendlyUrl = sanitizedUrl.startsWith("/") ? sanitizedUrl : "/" + sanitizedUrl;
                }
            } else if (siteArg != null && friendlyUrlArg != null) {
                siteSlug = siteArg.startsWith("/") ? siteArg.substring(1) : siteArg;
                String sanitizedFriendlyUrl = sanitizeInventoryUrl(friendlyUrlArg);
                normalizedFriendlyUrl = sanitizedFriendlyUrl.startsWith("/")
                    ? sanitizedFriendlyUrl
                    : "/" + sanitizedFriendlyUrl;
            } else {
                throw new IllegalArgumentException("Debe proporcionar --url O bien (--site Y --friendly-url)");
            }

            if ("/".equals(normalizedFriendlyUrl)) {
                return new ResolvedInventoryRequest(siteSlug, normalizedFriendlyUrl, InventoryPageRoute.SITE_ROOT, null);
            }

            String urlTitle = extractDisplayPageUrlTitle(normalizedFriendlyUrl);
            if (urlTitle != null) {
                return new ResolvedInventoryRequest(siteSlug, normalizedFriendlyUrl, InventoryPageRoute.DISPLAY_PAGE, urlTitle);
            }

            return new ResolvedInventoryRequest(siteSlug, normalizedFriendlyUrl, InventoryPageRoute.LAYOUT_PAGE, null);
        }

        private static SiteContext resolveSiteContext(
            LiferayCLIMain.RootCommand root,
            OAuthTokenClient.TokenResponse token,
            String siteSlug
        ) throws Exception {
            JsonNode siteNode = resolveSite(root, token.accessToken(), siteSlug);
            long groupId = siteNode.path("id").asLong(-1L);
            String resolvedSiteSlug = siteNode.path("friendlyUrlPath").asText(siteSlug).replaceFirst("^/", "");
            String siteName = siteNode.path("name").asText(resolvedSiteSlug);
            return new SiteContext(siteNode, groupId, resolvedSiteSlug, siteName);
        }

        private static String extractDisplayPageUrlTitle(String friendlyUrl) {
            String candidate = friendlyUrl.startsWith("/") ? friendlyUrl.substring(1) : friendlyUrl;
            if (!candidate.startsWith("w/")) {
                return null;
            }
            return candidate.substring(2);
        }

        private int handleDisplayPage(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            SiteContext siteContext, String urlTitle, String format, boolean verbose) throws Exception {

            // 1. Try headless delivery for article data
            String filter = URLEncoder.encode("friendlyUrlPath eq '" + urlTitle + "'", StandardCharsets.UTF_8);
            String headlessPath = "/o/headless-delivery/v1.0/sites/" + siteContext.groupId() +
                "/structured-contents?filter=" + filter + "&pageSize=1";
            LiferayApiClient.ApiResponse resp = root.apiClient().get(
                root.settings().baseUrl(), headlessPath, token.accessToken(), root.settings().timeoutSeconds());

            ObjectNode result = root.mapper().createObjectNode();
            result.put("pageType", PAGE_TYPE_DISPLAY);
            result.put("pageSubtype", PAGE_SUBTYPE_JOURNAL_ARTICLE);
            result.put("urlTitle", urlTitle);
            result.put("groupId", siteContext.groupId());
            result.put("siteName", siteContext.siteName());
            result.put("url", "/web/" + siteContext.siteSlug() + "/w/" + urlTitle);

            JsonNode headlessArticle = null;
            String warning = null;
            if (resp.statusCode() == 200) {
                JsonNode payload = root.mapper().readTree(resp.body());
                JsonNode items = payload.path("items");
                if (items.isArray() && items.size() > 0) {
                    headlessArticle = items.get(0);
                }
            } else if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                warning = "headless-delivery access denied (status=" + resp.statusCode() +
                    "). Check token scope includes Liferay.Headless.Delivery.everything.read";
            }

            // 2. Always call JSONWS for folderId (and as fallback if headless returned nothing)
            JsonNode jsonwsArticle = fetchArticleByUrlTitle(root, token, siteContext.groupId(), urlTitle);

            if (headlessArticle == null && warning == null) {
                headlessArticle = jsonwsArticle;
            }

            if (headlessArticle != null) {
                long folderId = jsonwsArticle != null ? jsonwsArticle.path("folderId").asLong(0L) : 0L;
                result.put("folderId", folderId);
                String folderBreadcrumb = buildFolderBreadcrumb(root, token, folderId);
                if (!folderBreadcrumb.isBlank()) {
                    result.put("folderBreadcrumb", folderBreadcrumb);
                }

                ObjectNode articleNode = root.mapper().createObjectNode();
                long articleNumId = headlessArticle.path("id").asLong(
                    headlessArticle.path("resourcePrimKey").asLong(-1L));
                articleNode.put("id", articleNumId);
                String key = headlessArticle.has("key")
                    ? headlessArticle.path("key").asText("")
                    : headlessArticle.path("articleId").asText("");
                articleNode.put("key", key);
                String friendlyUrlPath = headlessArticle.has("friendlyUrlPath")
                    ? headlessArticle.path("friendlyUrlPath").asText("")
                    : urlTitle;
                articleNode.put("friendlyUrlPath", friendlyUrlPath);
                String title = headlessArticle.has("title")
                    ? headlessArticle.path("title").asText("")
                    : headlessArticle.path("titleCurrentValue").asText("");
                articleNode.put("title", title);
                articleNode.put("folderId", folderId);
                result.put("articleTitle", title);

                long contentStructureId = headlessArticle.path("contentStructureId").asLong(-1L);
                long structuredContentId = resolveStructuredContentId(headlessArticle, jsonwsArticle, articleNumId);
                JsonNode structuredContent = fetchStructuredContentById(root, token, siteContext.groupId(), structuredContentId);
                if (contentStructureId <= 0 && structuredContent != null) {
                    contentStructureId = structuredContent.path("contentStructureId").asLong(-1L);
                }
                articleNode.put("contentStructureId", contentStructureId);

                ArrayNode templatesNode = root.mapper().createArrayNode();
                if (contentStructureId > 0) {
                    JsonNode structure = fetchContentStructure(root, token, contentStructureId);
                    JsonNode dataDefinition = fetchDataDefinition(root, token, contentStructureId);
                    long structureSiteId = -1L;
                    String structureName = "";
                    String structureKey = "";

                    // Try to get structure key from available sources
                    if (jsonwsArticle != null && jsonwsArticle.has("ddmStructureKey")) {
                        structureKey = jsonwsArticle.path("ddmStructureKey").asText("");
                    }

                    if (structure != null) {
                        structureName = structure.path("name").asText("");
                    }

                    if (dataDefinition != null) {
                        if (structureKey.isEmpty()) {
                            structureKey = dataDefinition.path("dataDefinitionKey").asText("");
                        }
                        if (structureName.isEmpty()) {
                            JsonNode dataDefinitionName = dataDefinition.path("name");
                            if (dataDefinitionName.isObject()) {
                                structureName = localizedName(dataDefinitionName);
                            } else {
                                structureName = dataDefinitionName.asText("");
                            }
                        }
                        if (structureSiteId <= 0) {
                            structureSiteId = dataDefinition.path("siteId").asLong(-1L);
                        }
                    }

                    if (structureName.isEmpty() && structure != null) {
                        structureName = structure.path("name").asText("");
                    }

                    // Fallback: if structureSiteId is still missing, try using the article's groupId
                    if (structureSiteId <= 0) {
                         structureSiteId = siteContext.groupId();
                    }

                    if (verbose) {
                         articleNode.put("structureSiteId", structureSiteId);
                    }

                    // Content structure block for new format
                    ObjectNode csNode = root.mapper().createObjectNode();
                    csNode.put("id", contentStructureId);
                    csNode.put("key", structureKey);
                    csNode.put("name", structureName);
                    result.set("contentStructure", csNode);

                    // Fetch content templates for this structure
                    templatesNode = fetchContentTemplatesForStructure(root, token, contentStructureId, structureSiteId);
                    if (templatesNode.size() > 0) {
                        articleNode.set("contentTemplates", templatesNode);
                    }

                    // Fetch display page template. Display page templates can live on the current site
                    // even when the underlying structure is inherited from a parent/global site.
                    if ((!structureName.isEmpty() || !structureKey.isEmpty()) && siteContext.groupId() > 0) {
                        ObjectNode displayPageTemplate = fetchDisplayPageTemplateForStructure(
                            root, token, siteContext.groupId(), structureName, structureKey);
                        if (displayPageTemplate == null &&
                            structureSiteId > 0 &&
                            structureSiteId != siteContext.groupId()) {
                            displayPageTemplate = fetchDisplayPageTemplateForStructure(
                                root, token, structureSiteId, structureName, structureKey);
                        }
                        if (displayPageTemplate != null) {
                            // Derive FTL file from content templates
                            String file = deriveTemplateFile(templatesNode);
                            if (!file.isEmpty()) {
                                displayPageTemplate.put("file", file);
                            }
                            articleNode.set("displayPageTemplate", displayPageTemplate);
                        }
                    }
                }

                if (jsonwsArticle != null) {
                    ObjectNode propertiesNode = buildDisplayPageProperties(root, token, jsonwsArticle, structuredContent);
                    if (propertiesNode.size() > 0) {
                        result.set("articleProperties", propertiesNode);
                    }
                }
                result.set("journalArticle", articleNode);

                // Build admin URLs
                String baseUrl = root.settings().baseUrl();
                String articleId = key.isEmpty() ? String.valueOf(articleNumId) : key;
                ObjectNode adminUrls = root.mapper().createObjectNode();
                adminUrls.put("Edit Article URL",
                    buildJournalEditUrl(baseUrl, siteContext.siteSlug(), siteContext.groupId(), articleId));
                adminUrls.put("Folder URL",
                    buildJournalFolderUrl(baseUrl, siteContext.siteSlug(), siteContext.groupId(), folderId));
                result.set("adminUrls", adminUrls);

            } else {
                result.put("warning", warning != null ? warning :
                    "No structured content found with friendlyUrlPath=" + urlTitle);
            }

            outputResult(root, result, format);
            return 0;
        }

        private int handleSiteRoot(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            SiteContext siteContext, String format) throws Exception {

            String path = "/api/jsonws/layout/get-layouts?groupId=" + siteContext.groupId() +
                "&privateLayout=false&parentLayoutId=0";
            LiferayApiClient.ApiResponse resp = root.apiClient().get(
                root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());

            ObjectNode result = root.mapper().createObjectNode();
            result.put("pageType", PAGE_TYPE_SITE_ROOT);
            result.put("groupId", siteContext.groupId());
            result.put("siteName", siteContext.siteName());

            ArrayNode pagesNode = root.mapper().createArrayNode();
            if (resp.statusCode() == 200) {
                JsonNode layouts = root.mapper().readTree(resp.body());
                if (layouts.isArray()) {
                    for (JsonNode layout : layouts) {
                        ObjectNode pageNode = root.mapper().createObjectNode();
                        pageNode.put("layoutId", layout.path("layoutId").asLong(-1L));
                        pageNode.put("friendlyURL", layout.path("friendlyURL").asText(""));
                        pageNode.put("name", layout.path("nameCurrentValue").asText(""));
                        pageNode.put("type", layout.path("type").asText(""));
                        pagesNode.add(pageNode);
                    }
                }
            }
            result.set("pages", pagesNode);

            outputResult(root, result, format);
            return 0;
        }

        private int handleLayoutPage(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            SiteContext siteContext, String friendlyUrl, String format, boolean verbose) throws Exception {

            LayoutContext layoutContext = resolveLayoutContext(root, token, siteContext.groupId(), friendlyUrl);
            if (layoutContext == null) {
                ObjectNode err = root.mapper().createObjectNode();
                err.put("error", "Layout not found");
                err.put("friendlyUrl", friendlyUrl);
                err.put("site", siteContext.siteSlug());
                outputResult(root, err, format);
                return 1;
            }

            ObjectNode result = root.mapper().createObjectNode();
            result.put("pageType", PAGE_TYPE_REGULAR);
            result.put("pageSubtype", layoutContext.layoutType());
            result.put("url", "/web/" + siteContext.siteSlug() + friendlyUrl);
            result.put("friendlyUrl", friendlyUrl);
            result.put("siteName", siteContext.siteName());
            result.put("groupId", siteContext.groupId());
            result.put("pageName", layoutContext.pageName());
            result.put("componentInspectionSupported", supportsComponentInspection(layoutContext.layoutType()));

            ObjectNode layoutNode = root.mapper().createObjectNode();
            layoutNode.put("layoutId", layoutContext.layoutId());
            layoutNode.put("groupId", siteContext.groupId());
            layoutNode.put("friendlyURL", layoutContext.layout().path("friendlyURL").asText(""));
            layoutNode.put("plid", layoutContext.plid());
            layoutNode.put("type", layoutContext.layoutType());
            layoutNode.put("name", layoutContext.pageName());
            result.set("layout", layoutNode);

            ObjectNode layoutDetails = buildLayoutDetails(root, layoutContext.layout());
            if (layoutDetails.size() > 0) {
                result.set("layoutDetails", layoutDetails);
                String targetUrl = layoutDetails.path("targetUrl").asText("");
                if (!targetUrl.isEmpty()) {
                    result.put("targetUrl", targetUrl);
                }
            }

            ObjectNode adminUrls = root.mapper().createObjectNode();
            adminUrls.put("Edit URL", root.settings().baseUrl() + "/web/" + siteContext.siteSlug() + friendlyUrl + "?p_l_mode=edit");
            adminUrls.put("Translate URL", buildPageTranslateUrl(root.settings().baseUrl(), siteContext.siteSlug(), layoutContext.plid()));
            adminUrls.put("Configure URL (General)", buildPageConfigureUrl(root.settings().baseUrl(), siteContext.siteSlug(), layoutContext.plid(), "general"));
            adminUrls.put("Configure URL (Design)", buildPageConfigureUrl(root.settings().baseUrl(), siteContext.siteSlug(), layoutContext.plid(), "design"));
            adminUrls.put("Configure URL (SEO)", buildPageConfigureUrl(root.settings().baseUrl(), siteContext.siteSlug(), layoutContext.plid(), "seo"));
            result.set("adminUrls", adminUrls);
            result.set("automationCommands", buildPageAutomationCommands(root, adminUrls));
            result.put(
                "mutationStrategy",
                "Use adminUrls with playwright-cli for layout/composition changes. inventory page is read-only; do not guess headless write endpoints unless liferay-cli exposes a dedicated command."
            );

            if (!supportsComponentInspection(layoutContext.layoutType())) {
                outputResult(root, result, format);
                return 0;
            }

            JsonNode pageElement = fetchSitePageElement(root, token, siteContext.groupId(), friendlyUrl);
            List<JsonNode> fragmentEntryLinks = tryFetchFragmentEntryLinks(
                root, token, siteContext.groupId(), layoutContext.plid());
            List<PageElementInfo> pageElements = collectPageElements(pageElement, fragmentEntryLinks);
            List<ObjectNode> journalArticles = collectLayoutJournalArticles(
                root, token, siteContext.groupId(), fragmentEntryLinks);
            Map<Long, JsonNode> structureCache = collectLayoutContentStructures(
                root, token, journalArticles);

            setRegularPageResultCollections(root, result, pageElements, journalArticles, structureCache);

            outputResult(root, result, format);
            return 0;
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
                layout,
                layout.path("layoutId").asLong(-1L),
                layout.path("plid").asLong(-1L),
                layout.path("type").asText(""),
                layout.path("nameCurrentValue").asText("")
            );
        }

        private static boolean supportsComponentInspection(String layoutType) {
            return "content".equalsIgnoreCase(layoutType);
        }

        private static ObjectNode buildLayoutDetails(LiferayCLIMain.RootCommand root, JsonNode layout) {
            ObjectNode layoutDetails = root.mapper().createObjectNode();
            Map<String, String> typeSettings = parseLayoutTypeSettings(layout.path("typeSettings").asText(""));

            String layoutTemplateId = typeSettings.getOrDefault("layout-template-id", "");
            if (!layoutTemplateId.isEmpty()) {
                layoutDetails.put("layoutTemplateId", layoutTemplateId);
            }

            String targetUrl = firstNonBlank(
                typeSettings.get("url"),
                typeSettings.get("embeddedLayoutURL")
            );
            if (!targetUrl.isEmpty()) {
                layoutDetails.put("targetUrl", targetUrl);
            }

            String layoutUpdateable = typeSettings.get("layoutUpdateable");
            if (layoutUpdateable != null && !layoutUpdateable.isBlank()) {
                layoutDetails.put("layoutUpdateable", Boolean.parseBoolean(layoutUpdateable));
            }

            return layoutDetails;
        }

        private static List<PageElementInfo> collectPageElements(JsonNode pageElement, List<JsonNode> fragmentEntryLinks) {
            List<JsonNode> availableLinks = new ArrayList<>(fragmentEntryLinks);
            List<PageElementInfo> pageElements = new ArrayList<>();
            collectPageElementsRecursive(pageElement, pageElements);

            for (PageElementInfo el : pageElements) {
                if (!TYPE_WIDGET.equals(el.type)) {
                    continue;
                }
                Iterator<JsonNode> it = availableLinks.iterator();
                while (it.hasNext()) {
                    JsonNode link = it.next();
                    String widgetId = link.path("portletId").asText("");
                    if (widgetId.contains(el.widgetName)) {
                        el.portletId = widgetId;
                        it.remove();
                        break;
                    }
                }
                if (el.portletId == null) {
                    el.portletId = ADT_WIDGET_BY_DIR.getOrDefault(el.widgetName, el.widgetName);
                }
            }

            return pageElements;
        }

        private static List<ObjectNode> collectLayoutJournalArticles(
            LiferayCLIMain.RootCommand root,
            OAuthTokenClient.TokenResponse token,
            long groupId,
            List<JsonNode> fragmentEntryLinks
        ) {
            Map<String, ArticleRef> articleRefs = extractArticleRefs(root, fragmentEntryLinks, groupId);
            List<ObjectNode> journalArticles = new ArrayList<>();

            for (ArticleRef ref : articleRefs.values()) {
                ObjectNode articleNode = root.mapper().createObjectNode();
                articleNode.put("articleId", ref.articleId);
                if (ref.ddmTemplateKey != null) {
                    articleNode.put("ddmTemplateKey", ref.ddmTemplateKey);
                }

                JsonNode article = fetchJournalArticle(root, token, ref.resolvedGroupId, ref.articleId);
                if (article != null) {
                    articleNode.put("title", article.path("titleCurrentValue").asText(ref.articleId));
                    articleNode.put("ddmStructureKey", article.path("ddmStructureKey").asText(""));
                    JsonNode structuredContent = fetchStructuredContentById(
                        root, token, groupId, article.path("id").asLong(-1L));
                    if (structuredContent != null) {
                        articleNode.put("contentStructureId", structuredContent.path("contentStructureId").asLong(-1L));
                    }
                }

                journalArticles.add(articleNode);
            }

            return journalArticles;
        }

        private static Map<Long, JsonNode> collectLayoutContentStructures(
            LiferayCLIMain.RootCommand root,
            OAuthTokenClient.TokenResponse token,
            List<ObjectNode> journalArticles
        ) {
            Map<Long, JsonNode> structureCache = new LinkedHashMap<>();

            for (ObjectNode articleNode : journalArticles) {
                long contentStructureId = articleNode.path("contentStructureId").asLong(-1L);
                if (contentStructureId <= 0 || structureCache.containsKey(contentStructureId)) {
                    continue;
                }
                JsonNode contentStructure = fetchContentStructure(root, token, contentStructureId);
                if (contentStructure != null) {
                    structureCache.put(contentStructureId, contentStructure);
                }
            }

            return structureCache;
        }

        private static void setRegularPageResultCollections(
            LiferayCLIMain.RootCommand root,
            ObjectNode result,
            List<PageElementInfo> pageElements,
            List<ObjectNode> journalArticles,
            Map<Long, JsonNode> structureCache
        ) {
            ArrayNode fragmentsNode = root.mapper().createArrayNode();
            ArrayNode widgetsNode = root.mapper().createArrayNode();
            for (PageElementInfo el : pageElements) {
                ObjectNode elementNode = root.mapper().createObjectNode();
                elementNode.put("type", el.type);
                if (TYPE_FRAGMENT.equals(el.type)) {
                    elementNode.put("fragmentKey", el.fragmentKey);
                } else {
                    elementNode.put("widgetName", el.widgetName);
                    if (el.portletId != null && !el.portletId.isEmpty()) {
                        elementNode.put("portletId", el.portletId);
                    }
                    widgetsNode.add(elementNode);
                }
                if (!el.configuration.isEmpty()) {
                    ObjectNode configNode = root.mapper().createObjectNode();
                    el.configuration.forEach(configNode::put);
                    elementNode.set("configuration", configNode);
                }
                fragmentsNode.add(elementNode);
            }
            result.set("fragmentEntryLinks", fragmentsNode);
            result.set("widgets", widgetsNode);

            ArrayNode articlesNode = root.mapper().createArrayNode();
            journalArticles.forEach(articlesNode::add);
            result.set("journalArticles", articlesNode);

            ArrayNode structuresNode = root.mapper().createArrayNode();
            for (Map.Entry<Long, JsonNode> entry : structureCache.entrySet()) {
                ObjectNode structureNode = root.mapper().createObjectNode();
                structureNode.put("contentStructureId", entry.getKey());
                structureNode.put("name", entry.getValue().path("name").asText(""));
                structuresNode.add(structureNode);
            }
            result.set("contentStructures", structuresNode);
        }





        private static JsonNode fetchSitePageElement(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            long groupId, String friendlyUrl) {
            try {
                String slug = friendlyUrl.startsWith("/") ? friendlyUrl.substring(1) : friendlyUrl;
                String path = "/o/headless-delivery/v1.0/sites/" + groupId + "/site-pages/" + slug +
                    "?fields=pageDefinition";
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) return null;
                JsonNode body = root.mapper().readTree(resp.body());
                return body.path("pageDefinition").path("pageElement");
            } catch (Exception ignored) { return null; }
        }

        private static void collectPageElementsRecursive(JsonNode element, List<PageElementInfo> result) {
            if (element == null || element.isMissingNode()) return;
            String type = element.path("type").asText("");
            if ("Fragment".equals(type)) {
                String key = element.path("definition").path("fragment").path("key").asText("");
                if (!key.isEmpty()) {
                    PageElementInfo info = PageElementInfo.fragment(key);
                    JsonNode fragmentConfig = element.path("definition").path("fragmentConfig");
                    if (!fragmentConfig.isMissingNode()) {
                        Iterator<Map.Entry<String, JsonNode>> fields = fragmentConfig.fields();
                        while(fields.hasNext()) {
                            Map.Entry<String, JsonNode> f = fields.next();
                            info.configuration.put(f.getKey(), f.getValue().asText());
                        }
                    }
                    result.add(info);
                }
            } else if ("Widget".equals(type)) {
                JsonNode widgetInstance = element.path("definition").path("widgetInstance");
                String widgetName = widgetInstance.path("widgetName").asText("");
                if (!widgetName.isEmpty()) {
                     PageElementInfo info = PageElementInfo.widget(widgetName);
                     JsonNode widgetConfig = widgetInstance.path("widgetConfig");
                     if (!widgetConfig.isMissingNode()) {
                         Iterator<Map.Entry<String, JsonNode>> fields = widgetConfig.fields();
                         while(fields.hasNext()) {
                             Map.Entry<String, JsonNode> f = fields.next();
                             info.configuration.put(f.getKey(), f.getValue().asText());
                         }
                     }
                     result.add(info);
                }
            }
            JsonNode children = element.path("pageElements");
            if (children.isArray()) {
                for (JsonNode child : children) {
                    collectPageElementsRecursive(child, result);
                }
            }
        }

        private static void outputResult(LiferayCLIMain.RootCommand root, ObjectNode result, String format) throws Exception {
            if ("json".equalsIgnoreCase(format)) {
                System.out.println(root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
                return;
            }

            if (result.has("error")) {
                System.err.println("ERROR: " + result.path("error").asText(""));
                if (result.has("friendlyUrl")) System.err.println("  Friendly URL: " + result.path("friendlyUrl").asText(""));
                if (result.has("site")) System.err.println("  Site: " + result.path("site").asText(""));
                return;
            }

            String pageType = result.path("pageType").asText("?");

            if (PAGE_TYPE_DISPLAY.equals(pageType)) {
                System.out.println("DISPLAY PAGE (Journal Article)");
                System.out.printf("- Name: %s%n", result.path("articleTitle").asText(""));
                System.out.printf("- Type: %s%n", pageType);
                System.out.printf("- Asset Type: %s%n", result.path("pageSubtype").asText(PAGE_SUBTYPE_JOURNAL_ARTICLE));
                System.out.printf("- URL: %s%n", result.path("url").asText(""));
                System.out.printf("- Friendly URL: %s%n", result.path("urlTitle").asText(""));
                System.out.printf("- Site: %s%n", result.path("siteName").asText(""));
                System.out.printf("- Group ID (siteId): %d%n", result.path("groupId").asLong(-1L));
                System.out.printf("- Folder ID: %d%n", result.path("folderId").asLong(0L));
                if (result.hasNonNull("folderBreadcrumb")) {
                    System.out.printf("- Folders: %s%n", result.path("folderBreadcrumb").asText(""));
                }

                JsonNode cs = result.path("contentStructure");
                if (!cs.isMissingNode()) {
                    System.out.println("- Content Structure:");
                    System.out.printf("  - ID: %d%n", cs.path("id").asLong(-1L));
                    System.out.printf("  - Key: %s%n", cs.path("key").asText(""));
                    if (cs.has("name") && !cs.path("name").asText("").isEmpty()) {
                        System.out.printf("  - Name: %s%n", cs.path("name").asText(""));
                    }
                }

                JsonNode article = result.path("journalArticle");
                JsonNode dpt = article.path("displayPageTemplate");
                if (!dpt.isMissingNode() && !dpt.isNull()) {
                    System.out.println("- Display Page Template:");
                    System.out.printf("  - Title: %s%n", dpt.path("title").asText(""));
                    System.out.printf("  - Key: %s%n", dpt.path("key").asText(""));
                    String file = dpt.path("file").asText("");
                    if (!file.isEmpty()) {
                        System.out.printf("  - File: %s%n", file);
                    }
                }

                JsonNode templates = article.path("contentTemplates");
                if (templates.isArray() && templates.size() > 0) {
                    System.out.printf("- Content Templates (%d):%n", templates.size());
                    for (JsonNode t : templates) {
                        System.out.printf("  - id=%s name=%s%n", t.path("id").asText(""), t.path("name").asText(""));
                    }
                }

                printArticleProperties(result.path("articleProperties"));
                printAdminUrls(result.path("adminUrls"));

                if (result.hasNonNull("warning")) {
                    System.out.printf("WARNING: %s%n", result.path("warning").asText(""));
                }

            } else {
                System.out.println("REGULAR PAGE");
                System.out.printf("- Name: %s%n", result.path("pageName").asText(""));
                System.out.printf("- Type: %s%n", pageType);
                System.out.printf("- Layout Type: %s%n", result.path("pageSubtype")
                    .asText(result.path("layout").path("type").asText("")));
                System.out.printf("- URL: %s%n", result.path("url").asText(""));
                System.out.printf("- Friendly URL: %s%n", result.path("friendlyUrl").asText(""));
                System.out.printf("- Site: %s%n", result.path("siteName").asText(""));
                System.out.printf("- Group ID (siteId): %d%n", result.path("groupId").asLong(-1L));
                System.out.printf("- PLID: %d%n", result.path("layout").path("plid").asLong(-1L));
                System.out.printf("- Layout ID: %d%n", result.path("layout").path("layoutId").asLong(-1L));
                if (result.hasNonNull("targetUrl")) {
                    System.out.printf("- Target URL: %s%n", result.path("targetUrl").asText(""));
                }
                JsonNode layoutDetails = result.path("layoutDetails");
                if (!layoutDetails.isMissingNode()) {
                    String layoutTemplateId = layoutDetails.path("layoutTemplateId").asText("");
                    if (!layoutTemplateId.isEmpty()) {
                        System.out.printf("- Layout Template: %s%n", layoutTemplateId);
                    }
                }

                printAdminUrls(result.path("adminUrls"));
                printAutomationCommands(result.path("automationCommands"));
                if (result.hasNonNull("mutationStrategy")) {
                    System.out.printf("MUTATION STRATEGY%n- %s%n", result.path("mutationStrategy").asText(""));
                }

                if (result.path("componentInspectionSupported").asBoolean(false)) {
                    JsonNode elements = result.path("fragmentEntryLinks");
                    System.out.printf("FRAGMENTS (%d)%n", elements.size());
                    int i = 1;
                    for (JsonNode el : elements) {
                        String elType = el.path("type").asText(TYPE_FRAGMENT);
                        if (TYPE_WIDGET.equals(elType)) {
                            String widgetName = el.path("widgetName").asText("");
                            System.out.printf("%d. %s%n", i++, widgetName);
                            String portletId = el.path("portletId").asText("");
                            if (!portletId.isEmpty() && !portletId.equals(widgetName)) {
                                System.out.printf("   - Portlet ID: %s%n", portletId);
                            }
                        } else {
                            System.out.printf("%d. %s%n", i++, el.path("fragmentKey").asText(""));
                        }

                        JsonNode config = el.path("configuration");
                        if (!config.isMissingNode()) {
                            System.out.println("   - Configuration:");
                            Iterator<Map.Entry<String, JsonNode>> it = config.fields();
                            while(it.hasNext()) {
                                Map.Entry<String, JsonNode> e = it.next();
                                System.out.printf("     - %s: %s%n", e.getKey(), e.getValue().asText());
                            }
                        }
                    }
                }
            }
        }

        private static void printAdminUrls(JsonNode adminUrls) {
            System.out.println("ADMIN URLS");
            if (adminUrls.isMissingNode()) return;
            for (Iterator<Map.Entry<String, JsonNode>> it = adminUrls.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                System.out.printf("- %s: %s%n", e.getKey(), e.getValue().asText(""));
            }
        }

        private static void printAutomationCommands(JsonNode automationCommands) {
            if (automationCommands.isMissingNode() || automationCommands.size() == 0) {
                return;
            }
            System.out.println("AUTOMATION COMMANDS");
            for (Iterator<Map.Entry<String, JsonNode>> it = automationCommands.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                System.out.printf("- %s: %s%n", e.getKey(), e.getValue().asText(""));
            }
        }

        private static ObjectNode buildPageAutomationCommands(
            LiferayCLIMain.RootCommand root,
            ObjectNode adminUrls
        ) {
            ObjectNode commands = root.mapper().createObjectNode();
            String configPath = ".playwright/cli.config.json";
            putPlaywrightCommand(commands, "Open Edit URL", "page-edit", adminUrls.path("Edit URL").asText(""), configPath);
            putPlaywrightCommand(commands, "Open Configure URL (General)", "page-config-general", adminUrls.path("Configure URL (General)").asText(""), configPath);
            putPlaywrightCommand(commands, "Open Configure URL (Design)", "page-config-design", adminUrls.path("Configure URL (Design)").asText(""), configPath);
            putPlaywrightCommand(commands, "Open Configure URL (SEO)", "page-config-seo", adminUrls.path("Configure URL (SEO)").asText(""), configPath);
            return commands;
        }

        private static void putPlaywrightCommand(
            ObjectNode commands,
            String key,
            String session,
            String url,
            String configPath
        ) {
            if (url == null || url.isBlank()) {
                return;
            }
            commands.put(
                key,
                "playwright-cli -s=" + session + " open \"" + url + "\" --config=" + configPath
            );
        }

        private static void printArticleProperties(JsonNode articleProperties) {
            if (articleProperties.isMissingNode() || articleProperties.isNull() || articleProperties.size() == 0) {
                return;
            }

            System.out.println("ARTICLE PROPERTIES");

            JsonNode basicInfo = articleProperties.path("basicInfo");
            if (!basicInfo.isMissingNode() && basicInfo.size() > 0) {
                System.out.println("- Basic Info:");
                printPropertyLine("Identifier", basicInfo.path("identifier").asText(""));
                printPropertyLine("Version", basicInfo.path("version").asText(""));
                printPropertyLine("Status", basicInfo.path("status").asText(""));
                printPropertyLine("Default Template", basicInfo.path("defaultTemplateKey").asText(""));
                printPropertyLine("External Reference Code", basicInfo.path("externalReferenceCode").asText(""));
            }

            JsonNode editorial = articleProperties.path("editorial");
            if (!editorial.isMissingNode() && editorial.size() > 0) {
                System.out.println("- Editorial:");
                printPropertyLine("Owner", editorial.path("owner").asText(""));
                printPropertyLine("Last Editor", editorial.path("lastEditor").asText(""));
            }

            JsonNode categorization = articleProperties.path("categorization");
            if (!categorization.isMissingNode() && categorization.size() > 0) {
                System.out.println("- Categorization:");
                printCategoryGroups("Public Categories", categorization.path("publicCategories"));
                printCategoryGroups("Internal Categories", categorization.path("internalCategories"));
                JsonNode tags = categorization.path("tags");
                if (tags.isArray()) {
                    printPropertyLine("Tags", joinTextValues(tags));
                }
            }

            JsonNode scheduling = articleProperties.path("scheduling");
            if (!scheduling.isMissingNode() && scheduling.size() > 0) {
                System.out.println("- Scheduling:");
                printPropertyLine("Display Date", scheduling.path("displayDate").asText(""));
                printPropertyLine("Last Publish Date", scheduling.path("lastPublishDate").asText(""));
                printPropertyLine("Expiration Date", scheduling.path("expirationDate").asText(""));
                printPropertyLine("Review Date", scheduling.path("reviewDate").asText(""));
                printPropertyLine("Modified Date", scheduling.path("modifiedDate").asText(""));
            }

            JsonNode search = articleProperties.path("search");
            if (!search.isMissingNode() && search.size() > 0) {
                System.out.println("- Search:");
                printPropertyLine("Indexable", booleanLabel(search.path("indexable")));
                printPropertyLine("Visible", booleanLabel(search.path("visible")));
                printPropertyLine("Listable", booleanLabel(search.path("listable")));
                if (search.has("priority")) {
                    printPropertyLine("Priority", search.path("priority").asText(""));
                }
            }

            JsonNode media = articleProperties.path("media");
            if (!media.isMissingNode() && media.size() > 0) {
                System.out.println("- Media:");
                printPropertyLine("Featured Image", media.path("featuredImage").asText(""));
                printPropertyLine("Small Image", media.path("smallImage").asText(""));
            }

            JsonNode contentFields = articleProperties.path("contentFields");
            if (contentFields.isArray() && !contentFields.isEmpty()) {
                System.out.println("- Content Fields:");
                for (JsonNode field : contentFields) {
                    printPropertyLine(field.path("path").asText(field.path("label").asText("")), field.path("value").asText(""));
                }
            }
        }

        private static void printCategoryGroups(String label, JsonNode groups) {
            if (!groups.isArray() || groups.isEmpty()) {
                return;
            }
            System.out.println("  - " + label + ":");
            for (JsonNode group : groups) {
                String vocabulary = group.path("vocabulary").asText("");
                String values = joinTextValues(group.path("categories"));
                if (!vocabulary.isEmpty() && !values.isEmpty()) {
                    System.out.println("    - " + vocabulary + ": " + values);
                }
            }
        }

        private static void printPropertyLine(String label, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            System.out.printf("  - %s: %s%n", label, value);
        }

        private static class PageElementInfo {
            String type;
            String fragmentKey;
            String widgetName;
            String portletId;
            Map<String, String> configuration = new LinkedHashMap<>();

            static PageElementInfo fragment(String key) {
                PageElementInfo p = new PageElementInfo();
                p.type = TYPE_FRAGMENT;
                p.fragmentKey = key;
                return p;
            }

            static PageElementInfo widget(String widgetName) {
                PageElementInfo p = new PageElementInfo();
                p.type = TYPE_WIDGET;
                p.widgetName = widgetName;
                return p;
            }
        }

        private static class ArticleRef {
            String articleId;
            long resolvedGroupId;
            String ddmTemplateKey;
            ArticleRef(String articleId, long resolvedGroupId, String ddmTemplateKey) {
                this.articleId = articleId;
                this.resolvedGroupId = resolvedGroupId;
                this.ddmTemplateKey = ddmTemplateKey;
            }
        }

        private static Map<String, ArticleRef> extractArticleRefs(
            LiferayCLIMain.RootCommand root, List<JsonNode> links, long defaultGroupId) {
            Map<String, ArticleRef> refs = new LinkedHashMap<>();
            for (JsonNode link : links) {
                String ev = link.path("editableValues").asText("");
                if (ev.isEmpty() || "{}".equals(ev)) continue;
                try {
                    JsonNode evNode = root.mapper().readTree(ev);
                    Iterator<Map.Entry<String, JsonNode>> fields = evNode.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> f = fields.next();
                        if (!f.getKey().contains("journal_content") && !f.getKey().contains("JournalContent")) continue;
                        JsonNode prefsMap = f.getValue().path("portletPreferencesMap");
                        if (prefsMap.isMissingNode()) {
                            prefsMap = f.getValue().path("configuration").path("portletPreferencesMap");
                        }
                        String articleId = prefsMap.path("articleId").isArray()
                            ? prefsMap.path("articleId").get(0).asText("") : prefsMap.path("articleId").asText("");
                        if (articleId.isEmpty()) continue;
                        long gId = prefsMap.path("groupId").isArray()
                            ? prefsMap.path("groupId").get(0).asLong(defaultGroupId)
                            : prefsMap.path("groupId").asLong(defaultGroupId);
                        String tplKey = prefsMap.path("ddmTemplateKey").isArray()
                            ? prefsMap.path("ddmTemplateKey").get(0).asText(null)
                            : prefsMap.path("ddmTemplateKey").asText(null);
                        refs.putIfAbsent(articleId, new ArticleRef(articleId, gId > 0 ? gId : defaultGroupId, tplKey));
                    }
                } catch (Exception ignored) {}
            }
            return refs;
        }

        private static List<JsonNode> tryFetchFragmentEntryLinks(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            long groupId, long plid) {
            try {
                if (!supportsFragmentEntryLinkJsonWs(root, token)) {
                    return new ArrayList<>();
                }
                String path = "/api/jsonws/fragment.fragmententrylink/get-fragment-entry-links" +
                    "?groupId=" + groupId + "&plid=" + plid;
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() == 200) {
                    JsonNode body = root.mapper().readTree(resp.body());
                    List<JsonNode> links = new ArrayList<>();
                    if (body.isArray()) for (JsonNode item : body) links.add(item);
                    return links;
                }
            } catch (Exception ignored) {}
            return new ArrayList<>();
        }

        private static boolean supportsFragmentEntryLinkJsonWs(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token) {
            try {
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(),
                    "/api/jsonws?discover",
                    token.accessToken(),
                    root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) {
                    return false;
                }
                JsonNode body = root.mapper().readTree(resp.body());
                JsonNode services = body.path("services");
                if (!services.isArray()) {
                    return false;
                }
                for (JsonNode service : services) {
                    String path = service.path("path").asText("");
                    if ("/fragment.fragmententrylink/get-fragment-entry-links".equals(path)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
            return false;
        }

        private static JsonNode findLayoutByFriendlyUrl(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            long groupId, String friendlyUrl) throws Exception {
            String url = friendlyUrl.startsWith("/") ? friendlyUrl : "/" + friendlyUrl;
            return searchLayouts(root, token, groupId, 0, url, 0);
        }

        private static JsonNode searchLayouts(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            long groupId, long parentId, String targetUrl, int depth) throws Exception {
            if (depth > 12) return null;
            JsonNode body = fetchLayoutsByParent(root, token, groupId, false, parentId);
            if (!body.isArray()) return null;
            for (JsonNode layout : body) {
                if (targetUrl.equalsIgnoreCase(layout.path("friendlyURL").asText(""))) return layout;
            }
            for (JsonNode layout : body) {
                long layoutId = layout.path("layoutId").asLong(0L);
                JsonNode found = searchLayouts(root, token, groupId, layoutId, targetUrl, depth + 1);
                if (found != null) return found;
            }
            return null;
        }

        private static JsonNode fetchJournalArticle(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            long groupId, String articleId) {
            try {
                String path = "/api/jsonws/journal.journalarticle/get-latest-article" +
                    "?groupId=" + groupId + "&articleId=" + URLEncoder.encode(articleId, StandardCharsets.UTF_8) +
                    "&status=0";
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) return null;
                JsonNode node = root.mapper().readTree(resp.body());
                return node.has("exception") ? null : node;
            } catch (Exception ignored) { return null; }
        }

        private static JsonNode fetchArticleByUrlTitle(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            long groupId, String urlTitle) {
            try {
                String path = "/api/jsonws/journal.journalarticle/get-article-by-url-title" +
                    "?groupId=" + groupId + "&urlTitle=" + URLEncoder.encode(urlTitle, StandardCharsets.UTF_8);
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) return null;
                JsonNode node = root.mapper().readTree(resp.body());
                return (node.has("exception") || !node.has("articleId")) ? null : node;
            } catch (Exception ignored) { return null; }
        }

        private static String buildFolderBreadcrumb(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, long folderId) {
            if (folderId <= 0) {
                return "";
            }
            List<String> names = new ArrayList<>();
            long currentFolderId = folderId;
            int guard = 0;

            while (currentFolderId > 0 && guard++ < 50) {
                JsonNode folder = fetchJournalFolder(root, token, currentFolderId);
                if (folder == null) {
                    break;
                }
                String name = folder.path("name").asText("");
                if (!name.isBlank()) {
                    names.add(0, name);
                }
                long parentFolderId = folder.path("parentFolderId").asLong(0L);
                if (parentFolderId <= 0 || parentFolderId == currentFolderId) {
                    break;
                }
                currentFolderId = parentFolderId;
            }

            return String.join(" > ", names);
        }

        private static JsonNode fetchJournalFolder(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, long folderId) {
            try {
                String path = "/api/jsonws/journal.journalfolder/get-folder?folderId=" + folderId;
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) return null;
                JsonNode node = root.mapper().readTree(resp.body());
                return node.has("exception") ? null : node;
            } catch (Exception ignored) { return null; }
        }

        private static JsonNode fetchStructuredContentById(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            long groupId, long id) {
            if (id <= 0) return null;
            try {
                String path = "/o/headless-delivery/v1.0/structured-contents/" + id;
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) return null;
                return root.mapper().readTree(resp.body());
            } catch (Exception ignored) { return null; }
        }

        private static long resolveStructuredContentId(JsonNode headlessArticle, JsonNode jsonwsArticle, long fallbackId) {
            long[] candidates = {
                jsonwsArticle != null ? jsonwsArticle.path("resourcePrimKey").asLong(-1L) : -1L,
                headlessArticle != null ? headlessArticle.path("resourcePrimKey").asLong(-1L) : -1L,
                headlessArticle != null ? headlessArticle.path("id").asLong(-1L) : -1L,
                fallbackId
            };
            for (long candidate : candidates) {
                if (candidate > 0) {
                    return candidate;
                }
            }
            return -1L;
        }

        private static ObjectNode buildDisplayPageProperties(
            LiferayCLIMain.RootCommand root,
            OAuthTokenClient.TokenResponse token,
            JsonNode article,
            JsonNode structuredContent
        ) {
            ObjectNode properties = root.mapper().createObjectNode();

            ObjectNode basicInfo = root.mapper().createObjectNode();
            basicInfo.put("identifier", article.path("articleId").asText(""));
            basicInfo.put("version", article.path("version").asText(""));
            basicInfo.put("status", workflowStatusLabel(article.path("status").asInt(-1)));
            basicInfo.put("defaultTemplateKey", firstNonBlank(
                article.path("DDMTemplateKey").asText(""),
                article.path("ddmTemplateKey").asText("")
            ));
            basicInfo.put("externalReferenceCode", article.path("externalReferenceCode").asText(""));
            properties.set("basicInfo", basicInfo);

            ObjectNode editorial = root.mapper().createObjectNode();
            editorial.put("owner", article.path("userName").asText(""));
            editorial.put("lastEditor", firstNonBlank(
                article.path("statusByUserName").asText(""),
                article.path("userName").asText("")
            ));
            properties.set("editorial", editorial);

            ObjectNode scheduling = root.mapper().createObjectNode();
            scheduling.put("displayDate", formatDateValue(article.path("displayDate")));
            scheduling.put("lastPublishDate", firstNonBlank(
                formatDateValue(article.path("lastPublishDate")),
                formatDateValue(structuredContent != null ? structuredContent.path("datePublished") : null)
            ));
            scheduling.put("expirationDate", formatDateValue(article.path("expirationDate")));
            scheduling.put("reviewDate", formatDateValue(article.path("reviewDate")));
            scheduling.put("modifiedDate", firstNonBlank(
                formatDateValue(article.path("modifiedDate")),
                formatDateValue(structuredContent != null ? structuredContent.path("dateModified") : null)
            ));
            properties.set("scheduling", scheduling);

            ObjectNode search = root.mapper().createObjectNode();
            search.put("indexable", article.path("indexable").asBoolean(true));

            long resourcePrimKey = article.path("resourcePrimKey").asLong(-1L);
            if (resourcePrimKey > 0) {
                JsonNode assetEntry = fetchAssetEntry(root, token, resourcePrimKey);
                if (assetEntry != null) {
                    search.put("visible", assetEntry.path("visible").asBoolean(true));
                    search.put("listable", assetEntry.path("listable").asBoolean(true));
                    search.put("priority", firstNonBlank(
                        assetEntry.path("priority").asText(""),
                        structuredContent != null ? structuredContent.path("priority").asText("") : ""
                    ));
                }

                ObjectNode categorization = fetchCategorization(root, token, resourcePrimKey);
                if (categorization.size() > 0) {
                    properties.set("categorization", categorization);
                }
            }

            ObjectNode media = buildMediaProperties(root, article, structuredContent);
            if (media.size() > 0) {
                properties.set("media", media);
            }

            ArrayNode contentFields = summarizeContentFields(root, structuredContent != null
                ? structuredContent.path("contentFields")
                : null);
            if (contentFields.size() > 0) {
                properties.set("contentFields", contentFields);
            }

            properties.set("search", search);
            return properties;
        }

        private static ObjectNode buildMediaProperties(
            LiferayCLIMain.RootCommand root,
            JsonNode article,
            JsonNode structuredContent
        ) {
            ObjectNode media = root.mapper().createObjectNode();

            String featuredImage = firstNonBlank(
                extractFirstImageSummary(structuredContent != null ? structuredContent.path("contentFields") : null),
                "No"
            );
            media.put("featuredImage", featuredImage);

            boolean smallImage = article.path("smallImage").asBoolean(false);
            String smallImageValue = "No";
            if (smallImage) {
                smallImageValue = firstNonBlank(
                    article.path("smallImageURL").asText(""),
                    article.path("smallImageId").asText("").isBlank()
                        ? ""
                        : "Image ID " + article.path("smallImageId").asText(""),
                    "Yes"
                );
            }
            media.put("smallImage", smallImageValue);
            return media;
        }

        private static ArrayNode summarizeContentFields(LiferayCLIMain.RootCommand root, JsonNode contentFields) {
            ArrayNode summarizedFields = root.mapper().createArrayNode();
            if (contentFields == null || !contentFields.isArray()) {
                return summarizedFields;
            }
            List<String> path = new ArrayList<>();
            for (JsonNode contentField : contentFields) {
                appendContentFieldSummary(root, summarizedFields, contentField, path);
            }
            return summarizedFields;
        }

        private static void appendContentFieldSummary(
            LiferayCLIMain.RootCommand root,
            ArrayNode target,
            JsonNode contentField,
            List<String> parentPath
        ) {
            if (contentField == null || contentField.isMissingNode() || contentField.isNull()) {
                return;
            }

            String label = firstNonBlank(contentField.path("label").asText(""), contentField.path("name").asText(""));
            String name = contentField.path("name").asText("");
            String type = firstNonBlank(contentField.path("dataType").asText(""), inferContentFieldType(contentField));
            String value = summarizeContentFieldValue(contentField.path("contentFieldValue"));

            if (!value.isBlank()) {
                ObjectNode fieldNode = root.mapper().createObjectNode();
                fieldNode.put("path", buildContentFieldPath(parentPath, label, name));
                fieldNode.put("label", label);
                fieldNode.put("name", name);
                fieldNode.put("type", type);
                fieldNode.put("value", value);
                target.add(fieldNode);
            }

            JsonNode nestedFields = contentField.path("nestedContentFields");
            if (!nestedFields.isArray() || nestedFields.isEmpty()) {
                return;
            }

            List<String> nestedPath = parentPath;
            if (shouldIncludeContentFieldLabelInPath(label, name)) {
                nestedPath = new ArrayList<>(parentPath);
                nestedPath.add(label);
            }

            for (JsonNode nestedField : nestedFields) {
                appendContentFieldSummary(root, target, nestedField, nestedPath);
            }
        }

        private static String buildContentFieldPath(List<String> parentPath, String label, String name) {
            List<String> segments = new ArrayList<>(parentPath);
            String ownLabel = firstNonBlank(label, name);
            if (!ownLabel.isBlank()) {
                segments.add(ownLabel);
            }
            return String.join(" > ", segments);
        }

        private static boolean shouldIncludeContentFieldLabelInPath(String label, String name) {
            if (label == null || label.isBlank()) {
                return false;
            }
            String normalizedLabel = label.trim().toLowerCase(Locale.ROOT);
            if ("grup de camps".equals(normalizedLabel) || "group of fields".equals(normalizedLabel)) {
                return false;
            }
            return name == null || !name.toLowerCase(Locale.ROOT).endsWith("fieldset");
        }

        private static String inferContentFieldType(JsonNode contentField) {
            JsonNode value = contentField.path("contentFieldValue");
            if (value.has("image")) {
                return "image";
            }
            if (value.has("document")) {
                return "document";
            }
            if (value.has("data")) {
                return "string";
            }
            return "";
        }

        private static String summarizeContentFieldValue(JsonNode contentFieldValue) {
            if (contentFieldValue == null || contentFieldValue.isMissingNode() || contentFieldValue.isNull()) {
                return "";
            }

            String textValue = normalizeContentFieldText(contentFieldValue.path("data").asText(""));
            if (!textValue.isBlank()) {
                return textValue;
            }

            String imageValue = summarizeReferencedContent(contentFieldValue.path("image"));
            if (!imageValue.isBlank()) {
                return imageValue;
            }

            String documentValue = summarizeReferencedContent(contentFieldValue.path("document"));
            if (!documentValue.isBlank()) {
                return documentValue;
            }

            if (contentFieldValue.isObject() && contentFieldValue.size() > 0 && hasMeaningfulJsonValue(contentFieldValue)) {
                return compactValue(contentFieldValue.toString(), 180);
            }

            return "";
        }

        private static String extractFirstImageSummary(JsonNode contentFields) {
            if (contentFields == null || !contentFields.isArray()) {
                return "";
            }
            for (JsonNode contentField : contentFields) {
                String imageValue = summarizeReferencedContent(contentField.path("contentFieldValue").path("image"));
                if (!imageValue.isBlank()) {
                    return imageValue;
                }
                String nestedImage = extractFirstImageSummary(contentField.path("nestedContentFields"));
                if (!nestedImage.isBlank()) {
                    return nestedImage;
                }
            }
            return "";
        }

        private static String summarizeReferencedContent(JsonNode contentReference) {
            if (contentReference == null || contentReference.isMissingNode() || contentReference.isNull()) {
                return "";
            }
            return firstNonBlank(
                contentReference.path("title").asText(""),
                contentReference.path("name").asText(""),
                contentReference.path("contentUrl").asText(""),
                contentReference.path("url").asText(""),
                contentReference.path("id").asText("")
            );
        }

        private static boolean hasMeaningfulJsonValue(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return false;
            }
            if (node.isTextual()) {
                return !node.asText("").isBlank();
            }
            if (node.isNumber() || node.isBoolean()) {
                return true;
            }
            if (node.isArray()) {
                for (JsonNode item : node) {
                    if (hasMeaningfulJsonValue(item)) {
                        return true;
                    }
                }
                return false;
            }
            if (node.isObject()) {
                Iterator<JsonNode> values = node.elements();
                while (values.hasNext()) {
                    if (hasMeaningfulJsonValue(values.next())) {
                        return true;
                    }
                }
                return false;
            }
            return !node.asText("").isBlank();
        }

        private static ObjectNode fetchDisplayPageTemplateForStructure(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            long siteId, String structureName, String structureKey) {
            try {
                int page = 1;
                int lastPage = 1;
                while (page <= lastPage) {
                    String path = "/o/headless-admin-content/v1.0/sites/" + siteId +
                        "/display-page-templates?pageSize=100&page=" + page;
                    LiferayApiClient.ApiResponse resp = root.apiClient().get(
                        root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                    if (resp.statusCode() != 200) {
                        return null;
                    }
                    JsonNode payload = root.mapper().readTree(resp.body());
                    JsonNode items = payload.path("items");
                    if (items.isArray()) {
                        for (JsonNode item : items) {
                            String subtype = item.path("displayPageTemplateSettings")
                                .path("contentAssociation")
                                .path("contentSubtype").asText("");

                            boolean matchName = !structureName.isEmpty() && structureName.equalsIgnoreCase(subtype);
                            boolean matchKey = !structureKey.isEmpty() && structureKey.equalsIgnoreCase(subtype);

                            if (matchName || matchKey) {
                                ObjectNode result = root.mapper().createObjectNode();
                                result.put("key", item.path("displayPageTemplateKey").asText(""));
                                result.put("title", item.path("title").asText(""));
                                result.put("markedAsDefault", item.path("markedAsDefault").asBoolean(false));
                                return result;
                            }
                        }
                    }
                    lastPage = payload.path("lastPage").asInt(1);
                    page++;
                }
            } catch (Exception e) {
                // Silent fail
            }
            return null;
        }

        private static JsonNode fetchContentStructure(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, long id) {
            try {
                String path = "/o/headless-delivery/v1.0/content-structures/" + id;
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) return null;
                return root.mapper().readTree(resp.body());
            } catch (Exception ignored) { return null; }
        }

        private static JsonNode fetchAssetEntry(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, long classPk) {
            try {
                String path = "/api/jsonws/assetentry/get-entry?className=" +
                    URLEncoder.encode(JOURNAL_ARTICLE_CLASS_NAME, StandardCharsets.UTF_8) +
                    "&classPK=" + classPk;
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) return null;
                JsonNode node = root.mapper().readTree(resp.body());
                return node.has("exception") ? null : node;
            } catch (Exception ignored) { return null; }
        }

        private static ObjectNode fetchCategorization(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, long classPk) {
            ObjectNode categorization = root.mapper().createObjectNode();
            ArrayNode publicGroups = root.mapper().createArrayNode();
            ArrayNode internalGroups = root.mapper().createArrayNode();
            ArrayNode tags = fetchAssetTags(root, token, classPk);
            Map<Long, JsonNode> vocabularyCache = new LinkedHashMap<>();
            Map<String, List<String>> publicByVocabulary = new LinkedHashMap<>();
            Map<String, List<String>> internalByVocabulary = new LinkedHashMap<>();

            ArrayNode categories = fetchAssetCategories(root, token, classPk);
            for (JsonNode category : categories) {
                long vocabularyId = category.path("vocabularyId").asLong(-1L);
                JsonNode vocabulary = vocabularyId > 0
                    ? vocabularyCache.computeIfAbsent(vocabularyId, id -> fetchVocabulary(root, token, id))
                    : null;
                String vocabularyName = vocabulary != null
                    ? firstNonBlank(vocabulary.path("titleCurrentValue").asText(""), vocabulary.path("name").asText(""))
                    : "";
                if (vocabularyName.isBlank()) {
                    vocabularyName = "Vocabulary " + vocabularyId;
                }
                String categoryTitle = firstNonBlank(
                    category.path("titleCurrentValue").asText(""),
                    category.path("name").asText("")
                );
                if (categoryTitle.isBlank()) {
                    continue;
                }

                boolean internal = vocabulary != null && vocabulary.path("visibilityType").asInt(0) != 0;
                Map<String, List<String>> target = internal ? internalByVocabulary : publicByVocabulary;
                target.computeIfAbsent(vocabularyName, ignored -> new ArrayList<>()).add(categoryTitle);
            }

            addCategoryGroups(root, publicGroups, publicByVocabulary);
            addCategoryGroups(root, internalGroups, internalByVocabulary);

            if (publicGroups.size() > 0) {
                categorization.set("publicCategories", publicGroups);
            }
            if (internalGroups.size() > 0) {
                categorization.set("internalCategories", internalGroups);
            }
            if (tags.size() > 0) {
                categorization.set("tags", tags);
            }
            return categorization;
        }

        private static void addCategoryGroups(
            LiferayCLIMain.RootCommand root,
            ArrayNode target,
            Map<String, List<String>> groups
        ) {
            for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
                ObjectNode group = root.mapper().createObjectNode();
                group.put("vocabulary", entry.getKey());
                ArrayNode categories = root.mapper().createArrayNode();
                for (String value : entry.getValue()) {
                    categories.add(value);
                }
                group.set("categories", categories);
                target.add(group);
            }
        }

        private static ArrayNode fetchAssetCategories(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, long classPk) {
            ArrayNode categories = root.mapper().createArrayNode();
            try {
                String path = "/api/jsonws/assetcategory/get-categories?className=" +
                    URLEncoder.encode(JOURNAL_ARTICLE_CLASS_NAME, StandardCharsets.UTF_8) +
                    "&classPK=" + classPk;
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) {
                    return categories;
                }
                JsonNode payload = root.mapper().readTree(resp.body());
                if (payload.isArray()) {
                    payload.forEach(categories::add);
                }
            } catch (Exception ignored) {}
            return categories;
        }

        private static ArrayNode fetchAssetTags(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, long classPk) {
            ArrayNode tags = root.mapper().createArrayNode();
            try {
                String path = "/api/jsonws/assettag/get-tags?className=" +
                    URLEncoder.encode(JOURNAL_ARTICLE_CLASS_NAME, StandardCharsets.UTF_8) +
                    "&classPK=" + classPk;
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) {
                    return tags;
                }
                JsonNode payload = root.mapper().readTree(resp.body());
                if (payload.isArray()) {
                    for (JsonNode tag : payload) {
                        String name = tag.path("name").asText("");
                        if (!name.isBlank()) {
                            tags.add(name);
                        }
                    }
                }
            } catch (Exception ignored) {}
            return tags;
        }

        private static JsonNode fetchVocabulary(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, long vocabularyId) {
            try {
                String path = "/api/jsonws/assetvocabulary/get-vocabulary?vocabularyId=" + vocabularyId;
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) return null;
                JsonNode node = root.mapper().readTree(resp.body());
                return node.has("exception") ? null : node;
            } catch (Exception ignored) { return null; }
        }

        private static JsonNode fetchDataDefinition(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, long id) {
            try {
                String path = "/o/data-engine/v2.0/data-definitions/" + id;
                LiferayApiClient.ApiResponse resp = root.apiClient().get(
                    root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                if (resp.statusCode() != 200) return null;
                return root.mapper().readTree(resp.body());
            } catch (Exception ignored) { return null; }
        }

        private static ArrayNode fetchContentTemplatesForStructure(
            LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token,
            long contentStructureId, long siteId) {
            ArrayNode result = root.mapper().createArrayNode();
            if (siteId <= 0) return result;
            Map<String, JsonNode> templatesByLogicalName = new LinkedHashMap<>();
            try {
                int page = 1;
                int lastPage = 1;
                while (page <= lastPage) {
                    String path = "/o/headless-delivery/v1.0/sites/" + siteId +
                        "/content-templates?pageSize=100&page=" + page;
                    LiferayApiClient.ApiResponse resp = root.apiClient().get(
                        root.settings().baseUrl(), path, token.accessToken(), root.settings().timeoutSeconds());
                    if (resp.statusCode() != 200) break;
                    JsonNode payload = root.mapper().readTree(resp.body());
                    JsonNode items = payload.path("items");
                    if (items.isArray()) {
                        for (JsonNode item : items) {
                            if (item.path("contentStructureId").asLong(-1L) == contentStructureId) {
                                rememberPreferredTemplate(templatesByLogicalName, item);
                            }
                        }
                    }
                    lastPage = payload.path("lastPage").asInt(1);
                    page++;
                }
            } catch (Exception ignored) {}
            for (JsonNode template : templatesByLogicalName.values()) {
                ObjectNode t = root.mapper().createObjectNode();
                t.put("id", template.path("id").asText(""));
                t.put("name", template.path("name").asText(""));
                result.add(t);
            }
            return result;
        }

        private static void rememberPreferredTemplate(Map<String, JsonNode> templatesByLogicalName, JsonNode candidate) {
            String logicalName = candidate.path("name").asText("");
            if (logicalName.isEmpty()) {
                logicalName = candidate.path("id").asText("");
            }
            if (logicalName.isEmpty()) {
                return;
            }

            JsonNode current = templatesByLogicalName.get(logicalName);
            if (current == null || shouldPreferTemplateCandidate(candidate, current)) {
                templatesByLogicalName.put(logicalName, candidate);
            }
        }

        private static boolean shouldPreferTemplateCandidate(JsonNode candidate, JsonNode current) {
            String candidateId = candidate.path("id").asText("");
            String currentId = current.path("id").asText("");

            boolean candidateHasStableId = hasStableTemplateId(candidateId);
            boolean currentHasStableId = hasStableTemplateId(currentId);
            if (candidateHasStableId != currentHasStableId) {
                return candidateHasStableId;
            }

            String candidateTimestamp = preferredTemplateTimestamp(candidate);
            String currentTimestamp = preferredTemplateTimestamp(current);
            if (!candidateTimestamp.isEmpty() && !currentTimestamp.isEmpty() && !candidateTimestamp.equals(currentTimestamp)) {
                return candidateTimestamp.compareTo(currentTimestamp) > 0;
            }

            return candidateId.compareTo(currentId) > 0;
        }

        private static boolean hasStableTemplateId(String id) {
            if (id == null || id.isEmpty()) {
                return false;
            }
            for (int i = 0; i < id.length(); i++) {
                if (!Character.isDigit(id.charAt(i))) {
                    return true;
                }
            }
            return false;
        }

        private static String preferredTemplateTimestamp(JsonNode template) {
            String modified = template.path("dateModified").asText("");
            if (!modified.isEmpty()) {
                return modified;
            }
            return template.path("dateCreated").asText("");
        }

        private static String deriveTemplateFile(ArrayNode templates) {
            if (templates == null || templates.size() == 0) return "";
            JsonNode firstMatch = null;
            for (JsonNode t : templates) {
                String name = t.path("name").asText("");
                if (name.contains("DETALLE") || name.contains("DETAIL")) {
                    return name + ".ftl";
                }
                if (firstMatch == null) firstMatch = t;
            }
            return firstMatch != null ? firstMatch.path("name").asText("") + ".ftl" : "";
        }

        private static String controlPanelBase(String baseUrl, String siteSlug, String portletId) {
            return baseUrl + "/group/" + siteSlug + "/~/control_panel/manage" +
                "?p_p_id=" + portletId + "&p_p_lifecycle=0&p_p_state=maximized";
        }

        private static String buildJournalEditUrl(String baseUrl, String siteSlug, long groupId, String articleId) {
            String pfx = "&_com_liferay_journal_web_portlet_JournalPortlet_";
            return controlPanelBase(baseUrl, siteSlug, "com_liferay_journal_web_portlet_JournalPortlet") +
                pfx + "mvcRenderCommandName=%2Fjournal%2Fedit_article" +
                pfx + "groupId=" + groupId +
                pfx + "articleId=" + URLEncoder.encode(articleId, StandardCharsets.UTF_8);
        }

        private static String buildJournalFolderUrl(String baseUrl, String siteSlug, long groupId, long folderId) {
            String pfx = "&_com_liferay_journal_web_portlet_JournalPortlet_";
            return controlPanelBase(baseUrl, siteSlug, "com_liferay_journal_web_portlet_JournalPortlet") +
                pfx + "mvcRenderCommandName=%2Fjournal%2Fview_folder" +
                pfx + "groupId=" + groupId +
                pfx + "folderId=" + folderId;
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
    }

    private static long resolveSiteId(LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, String site)
        throws Exception {
        JsonNode siteNode = resolveSite(root, token.accessToken(), site);
        long id = siteNode.path("id").asLong(-1L);
        if (id <= 0) {
            throw new IllegalStateException("site no encontrado: " + site);
        }
        return id;
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

        // Fallback fuzzy search by listing sites
        int page = 1;
        int lastPage = 1;
        String cleanInput = normalized.toLowerCase(Locale.ROOT);

        while (page <= lastPage) {
            LiferayApiClient.ApiResponse listResponse = root.apiClient().get(
                root.settings().baseUrl(),
                "/o/headless-admin-user/v1.0/sites?pageSize=100&page=" + page,
                accessToken,
                root.settings().timeoutSeconds()
            );

            if (listResponse.statusCode() >= 200 && listResponse.statusCode() < 300) {
                JsonNode payload = root.mapper().readTree(listResponse.body());
                if (payload.has("lastPage")) {
                    lastPage = payload.path("lastPage").asInt(1);
                }
                JsonNode items = payload.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String fUrl = item.path("friendlyUrlPath").asText("").toLowerCase(Locale.ROOT);
                        if (fUrl.startsWith("/")) fUrl = fUrl.substring(1);

                        String name = item.path("name").asText("");
                        if (name.isEmpty() && item.has("name")) {
                             JsonNode n = item.path("name");
                             if (n.isObject() && n.fields().hasNext()) {
                                 name = n.fields().next().getValue().asText();
                             }
                        }
                        name = name.toLowerCase(Locale.ROOT);

                        if (fUrl.equals(cleanInput) || fUrl.contains(cleanInput) || name.contains(cleanInput)) {
                            return item;
                        }
                    }
                }
                page++;
            } else {
                break;
            }
        }

        throw new IllegalStateException("No se pudo resolver el site '" + site + "'. Intento directo falló con status=" + response.statusCode() + ". El buscador difuso no encontró coincidencias en la lista de sites.");
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

    private static Map<String, String> parseLayoutTypeSettings(String rawTypeSettings) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (rawTypeSettings == null || rawTypeSettings.isBlank()) {
            return settings;
        }
        String[] lines = rawTypeSettings.split("\\R");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            int separatorIndex = line.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = line.substring(0, separatorIndex).trim();
            String value = line.substring(separatorIndex + 1).trim();
            if (!key.isEmpty()) {
                settings.put(key, value);
            }
        }
        return settings;
    }

    private static String extractLayoutTargetUrl(JsonNode layout) {
        Map<String, String> typeSettings = parseLayoutTypeSettings(layout.path("typeSettings").asText(""));
        return firstNonBlank(typeSettings.get("url"), typeSettings.get("embeddedLayoutURL"));
    }

    private static String buildInventoryPageUrl(String siteSlug, boolean privateLayout, String friendlyUrl) {
        return buildSitePathPrefix(siteSlug, privateLayout) + friendlyUrl;
    }

    private static String buildSitePathPrefix(String siteSlug, boolean privateLayout) {
        String normalizedSiteSlug = siteSlug.startsWith("/") ? siteSlug.substring(1) : siteSlug;
        return (privateLayout ? "/group/" : "/web/") + normalizedSiteSlug;
    }

    private static String buildPagesCommand(String siteFriendlyUrl) {
        return "inventory pages --site " + siteFriendlyUrl;
    }

    private static String buildPageCommand(String fullUrl) {
        return "inventory page --url " + fullUrl;
    }

    private static JsonNode fetchPagedItems(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        String basePath,
        int pageSize
    ) throws Exception {
        int page = 1;
        int lastPage = 1;
        List<JsonNode> all = new ArrayList<>();

        while (page <= lastPage) {
            String separator = basePath.contains("?") ? "&" : "?";
            String path = basePath + separator + "page=" + page + "&pageSize=" + pageSize;
            LiferayApiClient.ApiResponse response = root.apiClient().get(
                root.settings().baseUrl(),
                path,
                token.accessToken(),
                root.settings().timeoutSeconds()
            );
            if (response.statusCode() == 403) {
                throw new IllegalStateException(
                    "403 Forbidden en " + basePath +
                    " (revisa scopes OAuth2 del bootstrap para Data Engine/Headless Delivery)"
                );
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("paged request status=" + response.statusCode() + " en " + basePath);
            }

            JsonNode payload = root.mapper().readTree(response.body());
            JsonNode items = payload.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    all.add(item);
                }
            }
            lastPage = payload.path("lastPage").asInt(1);
            page++;
        }

        return root.mapper().valueToTree(all);
    }

    private static String sanitizeInventoryUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String sanitized = rawUrl.trim();
        if (sanitized.isEmpty()) {
            return sanitized;
        }
        try {
            URI uri = new URI(sanitized);
            if (uri.isAbsolute()) {
                String rawPath = uri.getRawPath();
                sanitized = rawPath == null ? "" : rawPath;
            }
        }
        catch (URISyntaxException ignored) {
            // Fall back to the raw string for non-URI inputs like plain friendly URLs.
        }
        int fragmentIndex = sanitized.indexOf('#');
        if (fragmentIndex >= 0) {
            sanitized = sanitized.substring(0, fragmentIndex);
        }
        int queryIndex = sanitized.indexOf('?');
        if (queryIndex >= 0) {
            sanitized = sanitized.substring(0, queryIndex);
        }
        if (!sanitized.isEmpty() && !sanitized.startsWith("/")) {
            sanitized = "/" + sanitized;
        }
        sanitized = sanitized.replaceFirst("^/[a-z]{2}(?:_[A-Z]{2})?/(web|group)(?=/|$)", "/$1");
        return sanitized.trim();
    }

    private static String formatDateValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isNumber()) {
            return formatEpochMillis(node.asLong(0L));
        }
        String rawValue = node.asText("").trim();
        if (rawValue.isEmpty()) {
            return "";
        }
        if (rawValue.matches("^\\d+$")) {
            return formatEpochMillis(Long.parseLong(rawValue));
        }
        try {
            return DATE_TIME_FORMATTER.format(Instant.parse(rawValue));
        } catch (DateTimeParseException ignored) {
            return rawValue;
        }
    }

    private static String formatEpochMillis(long millis) {
        if (millis <= 0) {
            return "";
        }
        return DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(millis));
    }

    private static String workflowStatusLabel(int status) {
        return switch (status) {
            case 0 -> "Approved";
            case 1 -> "Draft";
            case 2 -> "Expired";
            case 3 -> "Pending";
            case 4 -> "Scheduled";
            case 5 -> "Denied";
            case 8 -> "Inactive";
            default -> status < 0 ? "" : String.valueOf(status);
        };
    }

    private static String booleanLabel(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.asBoolean(false) ? "Yes" : "No";
    }

    private static String joinTextValues(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (JsonNode value : values) {
            String text = value.asText("");
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return String.join(", ", out);
    }

    private static String normalizeContentFieldText(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "";
        }
        String normalized = rawValue
            .replaceAll("(?is)<br\\s*/?>", " ")
            .replaceAll("(?is)</p>", " ")
            .replaceAll("(?is)<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return compactValue(normalized, 180);
    }

    private static String compactValue(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String localizedName(JsonNode nameNode) {
        if (nameNode == null || !nameNode.isObject()) {
            return "";
        }
        String[] keys = {"en_US", "ca_ES", "es_ES"};
        for (String key : keys) {
            String value = nameNode.path(key).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return nameNode.toString();
    }

    private static final Map<String, String> ADT_WIDGET_BY_DIR = Map.ofEntries(
        Map.entry("asset_entry", "asset-entry"),
        Map.entry("breadcrumb", "breadcrumb"),
        Map.entry("category_facet", "category-facet"),
        Map.entry("custom_facet", "custom-facet"),
        Map.entry("custom_filter", "custom-filter"),
        Map.entry("language_selector", "language-selector"),
        Map.entry("navigation_menu", "navigation-menu"),
        Map.entry("search_result_summary", "search-result-summary"),
        Map.entry("searchbar", "searchbar"),
        Map.entry("search_results", "similar-results")
    );
}
