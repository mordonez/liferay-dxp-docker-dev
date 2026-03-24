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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "resource",
    description = "Operaciones de recursos Liferay",
    mixinStandardHelpOptions = true,
    subcommands = {
        ResourceCommand.StructureGet.class,
        ResourceCommand.TemplateGet.class,
        ResourceCommand.StructureExport.class,
        ResourceCommand.TemplateExport.class,
        ResourceCommand.AdtList.class,
        ResourceCommand.FragmentsList.class,
        ResourceSyncCommands.FragmentsExport.class,
        ResourceSyncCommands.FragmentsSync.class,
        ResourceSyncCommands.StructureExportAll.class,
        ResourceSyncCommands.TemplateExportAll.class,
        ResourceSyncCommands.AdtExportAll.class,
        ResourceSyncCommands.ExportAndSync.class,
        ResourceSyncCommands.StructureSync.class,
        ResourceSyncCommands.StructureSyncAll.class,
        ResourceSyncCommands.StructureWithTemplatesSync.class,
        ResourceSyncCommands.StructureMigrateContent.class,
        ResourceSyncCommands.StructureMigrationRun.class,
        ResourceSyncCommands.StructureMigrationPipeline.class,
        ResourceSyncCommands.StructureDelete.class,
        ResourceSyncCommands.TemplateSync.class,
        ResourceSyncCommands.TemplateSyncAll.class,
        ResourceSyncCommands.TemplateDelete.class,
        ResourceSyncCommands.AdtGet.class,
        ResourceCommand.ResolveAdt.class,
        ResourceSyncCommands.AdtSync.class,
        ResourceSyncCommands.AdtSyncAll.class,
        ResourceSyncCommands.AdtDelete.class
    }
)
public class ResourceCommand implements Callable<Integer> {
    private static final String ADT_RESOURCE_CLASS_NAME = "com.liferay.portlet.display.template.PortletDisplayTemplate";
    private static final String DDM_STRUCTURE_CLASS_NAME = "com.liferay.dynamic.data.mapping.model.DDMStructure";
    private static final String JOURNAL_ARTICLE_CLASS_NAME = "com.liferay.journal.model.JournalArticle";
    private static final Map<String, String> ADT_CLASS_BY_WIDGET_TYPE = Map.ofEntries(
        Map.entry("asset-entry", "com.liferay.asset.kernel.model.AssetEntry"),
        Map.entry("breadcrumb", "com.liferay.portal.kernel.servlet.taglib.ui.BreadcrumbEntry"),
        Map.entry(
            "category-facet",
            "com.liferay.portal.search.web.internal.category.facet.portlet.CategoryFacetPortlet"
        ),
        Map.entry(
            "custom-facet",
            "com.liferay.portal.search.web.internal.custom.facet.portlet.CustomFacetPortlet"
        ),
        Map.entry(
            "custom-filter",
            "com.liferay.portal.search.web.internal.custom.filter.display.context.CustomFilterDisplayContext"
        ),
        Map.entry("language-selector", "com.liferay.portal.kernel.servlet.taglib.ui.LanguageEntry"),
        Map.entry("navigation-menu", "com.liferay.portal.kernel.theme.NavItem"),
        Map.entry(
            "search-result-summary",
            "com.liferay.portal.search.web.internal.result.display.context.SearchResultSummaryDisplayContext"
        ),
        Map.entry("searchbar", "com.liferay.portal.search.web.internal.search.bar.portlet.SearchBarPortlet"),
        Map.entry(
            "similar-results",
            "com.liferay.portal.search.similar.results.web.internal.display.context.SimilarResultsDocumentDisplayContext"
        )
    );
    @CommandLine.ParentCommand
    private LiferayCLIMain.RootCommand root;

    LiferayCLIMain.RootCommand root() {
        return root;
    }

    @Override
    public Integer call() {
        System.out.println(
            "Gestión de recursos Liferay (Estructuras, Plantillas, ADTs, Fragments)\n\n" +
            "Sincronización (local -> portal):\n" +
            "  sync-structure  [ss]     --key KEY           Sincroniza una estructura específica\n" +
            "  sync-template   [st]     --id ID             Sincroniza una plantilla específica\n" +
            "  sync-adt        [sa]     --file FILE         Sincroniza un ADT específico\n" +
            "  sync-fragments  [sf]     --site SITE         Sincroniza fragmentos de un site\n" +
            "  resolve-adt     [ra]     --display-style ... Resuelve widget/template/file local de un ADT\n\n" +
            "Exportación (portal -> local):\n" +
            "  export-structures [es]   [--site SITE]      Exporta estructuras a JSON\n" +
            "  export-templates  [et]   [--site SITE]      Exporta plantillas a JSON\n\n" +
            "Utilidades:\n" +
            "  list-adts       [la]     [--site SITE]      Lista ADTs disponibles\n" +
            "  list-fragments  [lf]     [--site SITE]      Lista Fragment Sets\n" +
            "  get-structure   [gs]     --key KEY           Muestra JSON de estructura\n" +
            "  get-template    [gt]     --id ID             Muestra JSON de plantilla\n\n" +
            "Nota: los comandos bulk sync/import-all siguen existiendo pero no forman parte del camino normal.\n" +
            "      Úsalos solo de forma explícita y confirmada; para cambios puntuales usa sync individuales.\n\n" +
            "Usa 'resource <comando> --help' para ver opciones detalladas."
        );
        return 0;
    }

    @Command(name = "get-structure", aliases = {"gs", "structure-get"}, mixinStandardHelpOptions = true, description = "Obtiene estructura por key")
    public static class StructureGet implements Callable<Integer> {
        @CommandLine.ParentCommand
        private ResourceCommand parent;

        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
        String site;

        @Option(names = "--key", required = true, description = "DataDefinition key de la estructura")
        String key;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                long siteId = resolveSiteId(root, token, site);
                JsonNode payload = fetchStructureByKey(root, token, siteId, key);
                System.out.println(root.mapper().writeValueAsString(payload));
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "get-template", aliases = {"gt", "template-get"}, mixinStandardHelpOptions = true, description = "Obtiene template por id/key")
    public static class TemplateGet implements Callable<Integer> {
        @CommandLine.ParentCommand
        private ResourceCommand parent;

        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
        String site;

        @Option(names = "--id", required = true, description = "Template id o key")
        String id;

        @Option(names = "--page-size", defaultValue = "200", description = "Tamano de pagina")
        int pageSize;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                JsonNode template = findTemplate(root, token, site, id);
                System.out.println(root.mapper().writeValueAsString(template));
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "export-structure-file", aliases = {"esf", "structure-export"}, mixinStandardHelpOptions = true, description = "Exporta estructura a fichero JSON")
    public static class StructureExport implements Callable<Integer> {
        @CommandLine.ParentCommand
        private ResourceCommand parent;

        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
        String site;

        @Option(names = "--key", required = true, description = "DataDefinition key de la estructura")
        String key;

        @Option(names = "--output", required = true, description = "Ruta fichero destino")
        Path output;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                long siteId = resolveSiteId(root, token, site);
                JsonNode payload = fetchStructureByKey(root, token, siteId, key);
                writeJson(root, payload, output);
                System.out.println("EXPORT_OK structure " + key + " -> " + output);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "export-template-file", aliases = {"etf", "template-export"}, mixinStandardHelpOptions = true, description = "Exporta template a fichero JSON")
    public static class TemplateExport implements Callable<Integer> {
        @CommandLine.ParentCommand
        private ResourceCommand parent;

        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
        String site;

        @Option(names = "--id", required = true, description = "Template id, key, ERC o name")
        String id;

        @Option(names = "--output", required = true, description = "Ruta fichero destino")
        Path output;

        @Option(names = "--page-size", defaultValue = "200", description = "Tamano de pagina")
        int pageSize;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                JsonNode payload = findTemplate(root, token, site, id);
                writeJson(root, payload, output);
                System.out.println("EXPORT_OK template " + id + " -> " + output);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "list-adts", aliases = {"la", "adt-list"}, mixinStandardHelpOptions = true, description = "Lista ADTs por site")
    public static class AdtList implements Callable<Integer> {
        @CommandLine.ParentCommand
        private ResourceCommand parent;

        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
        String site;

        @Option(names = "--widget-type", defaultValue = "", description = "Filtro por widget type")
        String widgetType;

        @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
        String format;

        @Option(
            names = "--include-script",
            defaultValue = "false",
            description = "Incluye script runtime en salida JSON"
        )
        boolean includeScript;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                JsonNode siteNode = resolveSite(root, token.accessToken(), site);
                long siteId = siteNode.path("id").asLong(-1L);
                long companyId = resolveCompanyId(root, token.accessToken(), siteNode);
                if (siteId <= 0 || companyId <= 0) {
                    throw new IllegalStateException("site sin id/companyId valido: " + site);
                }
                if (!"text".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format)) {
                    throw new IllegalArgumentException("formato no soportado: " + format);
                }

                String filter = normalizeAdtWidgetType(widgetType);
                List<Map<String, Object>> rows = new ArrayList<>();
                long resourceClassNameId = fetchClassNameId(root, token.accessToken(), ADT_RESOURCE_CLASS_NAME);

                for (Map.Entry<String, String> entry : ADT_CLASS_BY_WIDGET_TYPE.entrySet()) {
                    String currentWidgetType = entry.getKey();
                    if (!filter.isBlank() && !currentWidgetType.equals(filter)) {
                        continue;
                    }
                    long classNameId = fetchClassNameId(root, token.accessToken(), entry.getValue());
                    JsonNode templates = listDdmTemplates(
                        root,
                        token.accessToken(),
                        companyId,
                        siteId,
                        classNameId,
                        resourceClassNameId
                    );
                    if (templates.isArray()) {
                        for (JsonNode item : templates) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            String displayName = item.path("nameCurrentValue").asText("").trim();
                            String fallback = item.path("templateKey").asText("");
                            row.put("adtName", displayName.isBlank() ? fallback : displayName);
                            row.put("displayName", displayName);
                            row.put("widgetType", currentWidgetType);
                            row.put("templateId", item.path("templateId").asLong(-1L));
                            row.put("templateKey", item.path("templateKey").asText(""));
                            row.put("classNameId", classNameId);
                            if (includeScript) {
                                row.put("script", item.path("script").asText(""));
                            }
                            rows.add(row);
                        }
                    }
                }

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(rows));
                    return 0;
                }

                for (Map<String, Object> row : rows) {
                    System.out.println(
                        row.get("widgetType") + "\t" +
                            row.get("templateId") + "\t" +
                            row.get("templateKey") + "\t" +
                            row.get("adtName")
                    );
                }
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "resolve-adt",
        aliases = {"ra", "adt-resolve"},
        mixinStandardHelpOptions = true,
        description = {
            "Resuelve un ADT a partir de displayStyle/templateId y localiza sus ficheros locales.",
            "",
            "Ejemplos:",
            "  task liferay -- resource resolve-adt --display-style ddmTemplate_19690804 --site /global",
            "  task liferay -- resource resolve-adt --id 19690804",
            "  task liferay -- resource resolve-adt --name UB_ADT_ACTIVIDADES_SEARCH --widget-type search-result-summary --site /actualitat"
        }
    )
    public static class ResolveAdt implements Callable<Integer> {
        @CommandLine.ParentCommand
        private ResourceCommand parent;

        @Option(names = "--site", defaultValue = "", description = "Site por friendly URL o ID. Si se omite, busca en todos los sites accesibles.")
        String site;

        @Option(names = "--display-style", description = "Display style runtime, por ejemplo ddmTemplate_19690804")
        String displayStyle;

        @Option(names = "--id", description = "Template id numérico del ADT")
        String id;

        @Option(names = {"--name", "--key"}, description = "Template key o nombre visible del ADT")
        String name;

        @Option(names = "--widget-type", defaultValue = "", description = "Filtro opcional por widget type")
        String widgetType;

        @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
        String format;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                String resolvedTemplateId = templateIdFromDisplayStyle(displayStyle, id);
                String normalizedWidget = normalizeAdtWidgetType(widgetType);

                if (resolvedTemplateId.isBlank() && isBlank(name)) {
                    throw new IllegalArgumentException("resolve-adt requiere --display-style, --id o --name");
                }
                if (!"text".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format)) {
                    throw new IllegalArgumentException("formato no soportado: " + format);
                }

                List<JsonNode> siteNodes = collectSearchSites(root, token, site);
                ArrayNode rows = root.mapper().createArrayNode();
                for (JsonNode siteNode : siteNodes) {
                    rows.addAll(resolveAdtsForSite(root, token, siteNode, resolvedTemplateId, name, normalizedWidget));
                }
                if (rows.isEmpty()) {
                    throw new IllegalStateException("ADT no encontrada. Usa list-adts o prueba con --site explícito.");
                }

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(rows));
                    return 0;
                }

                for (JsonNode row : rows) {
                    System.out.printf(
                        "- site=%s widgetType=%s templateId=%s displayStyle=%s key=%s%n",
                        row.path("siteFriendlyUrl").asText(""),
                        row.path("widgetType").asText(""),
                        row.path("templateId").asText(""),
                        row.path("displayStyle").asText(""),
                        row.path("templateKey").asText("")
                    );
                    System.out.printf("  sync=%s%n", row.path("syncCommand").asText(""));
                    String preferredLocalFile = row.path("preferredLocalFile").asText("");
                    if (!preferredLocalFile.isBlank()) {
                        System.out.printf("  preferredLocalFile=%s%n", preferredLocalFile);
                    }
                    JsonNode localFiles = row.path("localFiles");
                    if (localFiles.isArray() && !localFiles.isEmpty()) {
                        System.out.println("  localFiles:");
                        for (JsonNode localFile : localFiles) {
                            System.out.printf("    - %s%n", localFile.asText(""));
                        }
                    }
                }
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "list-fragments", aliases = {"lf", "fragments-list"}, mixinStandardHelpOptions = true, description = "Lista fragments por site")
    public static class FragmentsList implements Callable<Integer> {
        @CommandLine.ParentCommand
        private ResourceCommand parent;

        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID")
        String site;

        @Option(names = "--format", defaultValue = "text", description = "Formato: text o json")
        String format;

        @Option(
            names = "--include-content",
            defaultValue = "false",
            description = "Incluye html/css/js/configuration del fragmento"
        )
        boolean includeContent;

        @Override
        public Integer call() {
            try {
                LiferayCLIMain.RootCommand root = parent.root;
                OAuthTokenClient.TokenResponse token = root.tokenClient().fetchClientCredentialsToken(root.settings());
                long siteId = resolveSiteId(root, token, site);
                if (!"text".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format)) {
                    throw new IllegalArgumentException("formato no soportado: " + format);
                }

                JsonNode collections = listFragmentCollections(root, token.accessToken(), siteId);
                List<Map<String, Object>> rows = new ArrayList<>();
                if (collections.isArray()) {
                    for (JsonNode collection : collections) {
                        long collectionId = collection.path("fragmentCollectionId").asLong(-1L);
                        String collectionName = collection.path("name").asText("");
                        if (collectionId <= 0) {
                            continue;
                        }
                        JsonNode fragments = listFragments(root, token.accessToken(), collectionId);
                        if (!fragments.isArray()) {
                            continue;
                        }
                        for (JsonNode fragment : fragments) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("fragmentId", fragment.path("fragmentEntryId").asLong(-1L));
                            row.put("fragmentKey", fragment.path("fragmentEntryKey").asText(""));
                            row.put("fragmentName", fragment.path("name").asText(""));
                            row.put("collectionId", collectionId);
                            row.put("collectionName", collectionName);
                            row.put("collectionKey", collection.path("fragmentCollectionKey").asText(""));
                            row.put("collectionDescription", collection.path("description").asText(""));
                            row.put("icon", fragment.path("icon").asText(""));
                            row.put("type", fragment.path("type").asInt(0));
                            if (includeContent) {
                                row.put("html", fragment.path("html").asText(""));
                                row.put("css", fragment.path("css").asText(""));
                                row.put("js", fragment.path("js").asText(""));
                                row.put("configuration", fragment.path("configuration").asText(""));
                            }
                            rows.add(row);
                        }
                    }
                }

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(rows));
                    return 0;
                }

                for (Map<String, Object> row : rows) {
                    System.out.println(
                        row.get("fragmentId") + "\t" +
                            row.get("fragmentKey") + "\t" +
                            row.get("collectionName")
                    );
                }
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    private static long resolveSiteId(LiferayCLIMain.RootCommand root, OAuthTokenClient.TokenResponse token, String site)
        throws Exception {
        JsonNode json = resolveSite(root, token.accessToken(), site);
        long resolved = json.path("id").asLong(-1L);
        if (resolved <= 0) {
            throw new IllegalStateException("site no encontrado: " + site);
        }
        return resolved;
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

        throw new IllegalStateException("site resolve status=" + response.statusCode() + " for " + site);
    }

    private static JsonNode fetchStructureByKey(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        long siteId,
        String key
    ) throws Exception {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        String path = "/o/data-engine/v2.0/sites/" + siteId +
            "/data-definitions/by-content-type/journal/by-data-definition-key/" + encodedKey;

        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            path,
            token.accessToken(),
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("structure-get status=" + response.statusCode());
        }
        return root.mapper().readTree(response.body());
    }

    private static JsonNode findTemplate(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        String site,
        String id
    ) throws Exception {
        JsonNode siteNode = resolveSite(root, token.accessToken(), site);
        long siteId = siteNode.path("id").asLong(-1L);
        long companyId = resolveCompanyId(root, token.accessToken(), siteNode);
        if (siteId <= 0 || companyId <= 0) {
            throw new IllegalStateException("site sin id/companyId valido: " + site);
        }

        long classNameId = fetchClassNameId(root, token.accessToken(), DDM_STRUCTURE_CLASS_NAME);
        long resourceClassNameId = fetchClassNameId(root, token.accessToken(), JOURNAL_ARTICLE_CLASS_NAME);
        JsonNode templates = listDdmTemplates(root, token.accessToken(), companyId, siteId, classNameId, resourceClassNameId);
        if (!templates.isArray() || templates.isEmpty()) {
            templates = listDdmTemplatesCompany(root, token.accessToken(), companyId, classNameId, resourceClassNameId);
        }
        if (templates.isArray()) {
            for (JsonNode item : templates) {
                String templateId = item.path("templateId").asText("");
                String templateKey = item.path("templateKey").asText("");
                String externalReferenceCode = item.path("externalReferenceCode").asText("");
                String nameCurrentValue = item.path("nameCurrentValue").asText("");
                if (id.equals(templateId) || id.equals(templateKey) || id.equals(externalReferenceCode) || id.equals(nameCurrentValue)) {
                    return toTemplatePayload(root, item);
                }
            }
        }
        throw new IllegalStateException("template no encontrado: " + id);
    }

    private static ObjectNode toTemplatePayload(LiferayCLIMain.RootCommand root, JsonNode ddmTemplate) {
        ObjectNode payload = root.mapper().createObjectNode();
        String templateId = ddmTemplate.path("templateId").asText("");
        String templateKey = ddmTemplate.path("templateKey").asText(templateId);
        String name = ddmTemplate.path("nameCurrentValue").asText(templateKey);
        payload.put("id", templateKey);
        payload.put("templateId", templateId);
        payload.put("templateKey", templateKey);
        payload.put("externalReferenceCode", ddmTemplate.path("externalReferenceCode").asText(templateKey));
        payload.put("name", name);
        payload.put("contentStructureId", ddmTemplate.path("classPK").asLong(-1L));
        payload.put("templateScript", ddmTemplate.path("script").asText(""));
        payload.set("raw", ddmTemplate.deepCopy());
        return payload;
    }

    private static String templateIdFromDisplayStyle(String displayStyle, String id) {
        if (!isBlank(id)) {
            return id.trim();
        }
        if (isBlank(displayStyle)) {
            return "";
        }
        String trimmed = displayStyle.trim();
        if (trimmed.startsWith("ddmTemplate_")) {
            return trimmed.substring("ddmTemplate_".length());
        }
        return trimmed;
    }

    private static String normalizeAdtWidgetType(String widgetType) {
        if (widgetType == null) {
            return "";
        }
        String normalized = widgetType.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("search-results".equals(normalized)) {
            return "search-result-summary";
        }
        return normalized;
    }

    private static String adtWidgetDir(String widgetType) {
        if ("similar-results".equals(widgetType)) {
            return "search_results";
        }
        return widgetType.replace('-', '_');
    }

    private static List<JsonNode> collectSearchSites(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        String requestedSite
    ) throws Exception {
        List<JsonNode> sites = new ArrayList<>();
        if (!isBlank(requestedSite)) {
            JsonNode requested = resolveSite(root, token.accessToken(), requestedSite);
            sites.add(requested);
            String friendlyUrl = requested.path("friendlyUrlPath").asText("");
            if (!"/global".equals(friendlyUrl)) {
                sites.add(resolveSite(root, token.accessToken(), "/global"));
            }
            return dedupeSites(sites);
        }

        sites.add(resolveSite(root, token.accessToken(), "/global"));
        sites.addAll(listAccessibleSites(root, token));
        return dedupeSites(sites);
    }

    private static List<JsonNode> dedupeSites(List<JsonNode> sites) {
        Map<Long, JsonNode> deduped = new LinkedHashMap<>();
        for (JsonNode siteNode : sites) {
            long siteId = siteNode.path("id").asLong(-1L);
            if (siteId > 0) {
                deduped.putIfAbsent(siteId, siteNode);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private static List<JsonNode> listAccessibleSites(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token
    ) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        LiferayApiClient.ApiResponse companiesResponse = root.apiClient().get(
            root.settings().baseUrl(),
            "/api/jsonws/company/get-companies",
            token.accessToken(),
            root.settings().timeoutSeconds()
        );
        if (companiesResponse.statusCode() < 200 || companiesResponse.statusCode() >= 300) {
            throw new IllegalStateException("company/get-companies status=" + companiesResponse.statusCode());
        }

        JsonNode companies = root.mapper().readTree(companiesResponse.body());
        for (JsonNode company : companies) {
            long companyId = company.path("companyId").asLong(0L);
            if (companyId <= 0) {
                continue;
            }
            String searchPath = "/api/jsonws/group/search?companyId=" + companyId +
                "&name=&description=&params=%7B%7D&start=0&end=200";
            LiferayApiClient.ApiResponse response = root.apiClient().get(
                root.settings().baseUrl(),
                searchPath,
                token.accessToken(),
                root.settings().timeoutSeconds()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                continue;
            }
            JsonNode page = root.mapper().readTree(response.body());
            if (!page.isArray()) {
                continue;
            }
            for (JsonNode row : page) {
                if (!row.path("site").asBoolean(false)) {
                    continue;
                }
                ObjectNode normalized = root.mapper().createObjectNode();
                normalized.put("id", row.path("groupId").asLong(-1L));
                normalized.put("companyId", companyId);
                normalized.put("friendlyUrlPath", row.path("friendlyURL").asText(""));
                normalized.put("name", row.path("nameCurrentValue").asText(row.path("name").asText("")));
                rows.add(normalized);
            }
        }
        return rows;
    }

    private static ArrayNode resolveAdtsForSite(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        JsonNode siteNode,
        String templateId,
        String name,
        String widgetType
    ) throws Exception {
        ArrayNode rows = root.mapper().createArrayNode();
        long siteId = siteNode.path("id").asLong(-1L);
        long companyId = resolveCompanyId(root, token.accessToken(), siteNode);
        String siteFriendlyUrl = siteNode.path("friendlyUrlPath").asText("");
        if (siteId <= 0 || companyId <= 0) {
            return rows;
        }

        long resourceClassNameId = fetchClassNameId(root, token.accessToken(), ADT_RESOURCE_CLASS_NAME);
        for (Map.Entry<String, String> entry : ADT_CLASS_BY_WIDGET_TYPE.entrySet()) {
            String currentWidgetType = entry.getKey();
            if (!widgetType.isBlank() && !currentWidgetType.equals(widgetType)) {
                continue;
            }
            long classNameId = fetchClassNameId(root, token.accessToken(), entry.getValue());
            JsonNode templates = listDdmTemplates(
                root,
                token.accessToken(),
                companyId,
                siteId,
                classNameId,
                resourceClassNameId
            );
            if (!templates.isArray()) {
                continue;
            }
            for (JsonNode item : templates) {
                if (matchesAdt(item, templateId, name)) {
                    rows.add(toResolvedAdtNode(root, siteFriendlyUrl, siteId, currentWidgetType, item));
                }
            }
        }
        return rows;
    }

    private static boolean matchesAdt(JsonNode item, String templateId, String name) {
        String currentTemplateId = item.path("templateId").asText("");
        String templateKey = item.path("templateKey").asText("");
        String externalReferenceCode = item.path("externalReferenceCode").asText("");
        String displayName = item.path("nameCurrentValue").asText("");
        if (!isBlank(templateId) && templateId.equals(currentTemplateId)) {
            return true;
        }
        if (!isBlank(name)) {
            return name.equals(templateKey) || name.equals(externalReferenceCode) || name.equals(displayName);
        }
        return false;
    }

    private static ObjectNode toResolvedAdtNode(
        LiferayCLIMain.RootCommand root,
        String siteFriendlyUrl,
        long siteId,
        String widgetType,
        JsonNode item
    ) throws Exception {
        ObjectNode row = root.mapper().createObjectNode();
        String templateId = item.path("templateId").asText("");
        String templateKey = item.path("templateKey").asText("");
        String displayName = item.path("nameCurrentValue").asText(templateKey);
        ArrayNode localFiles = root.mapper().createArrayNode();

        List<String> candidates = new ArrayList<>();
        if (!templateKey.isBlank()) {
            candidates.add(templateKey);
        }
        if (!displayName.isBlank() && !displayName.equals(templateKey)) {
            candidates.add(displayName);
        }
        List<String> resolvedLocalFiles = findLocalAdtFiles(root, siteFriendlyUrl, widgetType, candidates);
        for (String localFile : resolvedLocalFiles) {
            localFiles.add(localFile);
        }

        row.put("siteFriendlyUrl", siteFriendlyUrl);
        row.put("siteId", siteId);
        row.put("widgetType", widgetType);
        row.put("templateId", templateId);
        row.put("displayStyle", templateId.isBlank() ? "" : "ddmTemplate_" + templateId);
        row.put("templateKey", templateKey);
        row.put("adtName", displayName.isBlank() ? templateKey : displayName);
        row.put("displayName", displayName);
        row.put("externalReferenceCode", item.path("externalReferenceCode").asText(""));
        row.set("localFiles", localFiles);
        if (!resolvedLocalFiles.isEmpty()) {
            row.put("preferredLocalFile", resolvedLocalFiles.get(0));
            row.put(
                "syncCommand",
                "resource sync-adt --file " + resolvedLocalFiles.get(0) + " --widget-type " + widgetType + " --site " + siteFriendlyUrl
            );
        }
        else {
            row.put("preferredLocalFile", "");
            row.put(
                "syncCommand",
                "resource sync-adt --name " + templateKey + " --widget-type " + widgetType + " --site " + siteFriendlyUrl
            );
        }
        return row;
    }

    private static List<String> findLocalAdtFiles(
        LiferayCLIMain.RootCommand root,
        String siteFriendlyUrl,
        String widgetType,
        List<String> candidateNames
    ) throws Exception {
        String configPath = root.settings().paths().getOrDefault("adts", "liferay/resources/templates/application_display");
        Path repoRoot = resolveRepoRoot(configPath);
        Path adtsDir = repoRoot.resolve(configPath).normalize();
        if (!Files.isDirectory(adtsDir) || candidateNames.isEmpty()) {
            return List.of();
        }

        String widgetDir = adtWidgetDir(widgetType);
        String preferredSiteToken = normalizeSiteToken(siteFriendlyUrl);
        List<Path> matches = new ArrayList<>();
        try (var stream = Files.walk(adtsDir)) {
            stream
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    String normalizedPath = path.toString().replace('\\', '/');
                    if (!normalizedPath.contains("/" + widgetDir + "/")) {
                        return;
                    }
                    String fileName = path.getFileName().toString();
                    for (String candidateName : candidateNames) {
                        if (fileName.equals(candidateName + ".ftl")) {
                            matches.add(path);
                            break;
                        }
                    }
                });
        }

        matches.sort(
            Comparator
                .comparingInt((Path path) -> localAdtPathRank(path, preferredSiteToken))
                .thenComparing(path -> adtsDir.relativize(path).toString())
        );

        List<String> normalized = new ArrayList<>();
        for (Path match : matches) {
            normalized.add(repoRoot.relativize(match.toAbsolutePath().normalize()).toString());
        }
        return normalized;
    }

    private static Path resolveRepoRoot(String relativePath) {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(relativePath))) {
                return candidate;
            }
        }
        return current;
    }

    private static int localAdtPathRank(Path path, String preferredSiteToken) {
        String normalized = path.toString().replace('\\', '/');
        if (!preferredSiteToken.isBlank() && normalized.contains("/" + preferredSiteToken + "/")) {
            return 0;
        }
        if (normalized.contains("/global/")) {
            return 1;
        }
        return 2;
    }

    private static String normalizeSiteToken(String siteFriendlyUrl) {
        if (isBlank(siteFriendlyUrl)) {
            return "";
        }
        String normalized = siteFriendlyUrl.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static long fetchClassNameId(LiferayCLIMain.RootCommand root, String accessToken, String className) throws Exception {
        String encoded = URLEncoder.encode(className, StandardCharsets.UTF_8);
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            "/api/jsonws/classname/fetch-class-name?value=" + encoded,
            accessToken,
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("classname status=" + response.statusCode());
        }
        JsonNode payload = root.mapper().readTree(response.body());
        long classNameId = payload.path("classNameId").asLong(-1L);
        if (classNameId <= 0) {
            throw new IllegalStateException("classNameId no resuelto para " + className);
        }
        return classNameId;
    }

    private static JsonNode listDdmTemplates(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long companyId,
        long groupId,
        long classNameId,
        long resourceClassNameId
    ) throws Exception {
        String path = "/api/jsonws/ddm.ddmtemplate/get-templates?companyId=" + companyId +
            "&groupId=" + groupId +
            "&classNameId=" + classNameId +
            "&resourceClassNameId=" + resourceClassNameId +
            "&status=0";
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            path,
            accessToken,
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("ddmtemplate status=" + response.statusCode());
        }
        return root.mapper().readTree(response.body());
    }

    private static JsonNode listDdmTemplatesCompany(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long companyId,
        long classNameId,
        long resourceClassNameId
    ) throws Exception {
        String path = "/api/jsonws/ddm.ddmtemplate/get-templates?companyId=" + companyId + "&groupId=" +
            "&classNameId=" + classNameId +
            "&resourceClassNameId=" + resourceClassNameId +
            "&status=0";
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            path,
            accessToken,
            root.settings().timeoutSeconds()
        );
        JsonNode payload;
        try {
            payload = root.mapper().readTree(response.body());
        }
        catch (Exception ex) {
            payload = root.mapper().createArrayNode();
        }
        if (payload.isArray() && !payload.isEmpty()) {
            return payload;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return root.mapper().createArrayNode();
        }
        return payload;
    }

    private static long resolveCompanyId(LiferayCLIMain.RootCommand root, String accessToken, JsonNode siteNode)
        throws Exception {
        long fromSite = siteNode.path("companyId").asLong(-1L);
        if (fromSite > 0) {
            return fromSite;
        }
        long siteId = siteNode.path("id").asLong(-1L);
        if (siteId > 0) {
            LiferayApiClient.ApiResponse groupResponse = root.apiClient().get(
                root.settings().baseUrl(),
                "/api/jsonws/group/get-group?groupId=" + siteId,
                accessToken,
                root.settings().timeoutSeconds()
            );
            if (groupResponse.statusCode() >= 200 && groupResponse.statusCode() < 300) {
                JsonNode groupPayload = root.mapper().readTree(groupResponse.body());
                long companyId = groupPayload.path("companyId").asLong(-1L);
                if (companyId > 0) {
                    return companyId;
                }
            }
        }

        String host = URI.create(root.settings().baseUrl()).getHost();
        if (host != null && !host.isBlank()) {
            String encodedHost = URLEncoder.encode(host, StandardCharsets.UTF_8);
            LiferayApiClient.ApiResponse byHost = root.apiClient().get(
                root.settings().baseUrl(),
                "/api/jsonws/company/get-company-by-virtual-host?virtualHost=" + encodedHost,
                accessToken,
                root.settings().timeoutSeconds()
            );
            if (byHost.statusCode() >= 200 && byHost.statusCode() < 300) {
                JsonNode payload = root.mapper().readTree(byHost.body());
                long companyId = payload.path("companyId").asLong(-1L);
                if (companyId > 0) {
                    return companyId;
                }
            }
        }

        LiferayApiClient.ApiResponse allCompanies = root.apiClient().get(
            root.settings().baseUrl(),
            "/api/jsonws/company/get-companies",
            accessToken,
            root.settings().timeoutSeconds()
        );
        if (allCompanies.statusCode() >= 200 && allCompanies.statusCode() < 300) {
            JsonNode payload = root.mapper().readTree(allCompanies.body());
            if (payload.isArray() && !payload.isEmpty()) {
                long first = payload.get(0).path("companyId").asLong(-1L);
                if (first > 0) {
                    return first;
                }
            }
        }
        return -1L;
    }

    private static JsonNode listFragmentCollections(LiferayCLIMain.RootCommand root, String accessToken, long groupId)
        throws Exception {
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            "/api/jsonws/fragment.fragmentcollection/get-fragment-collections?groupId=" + groupId,
            accessToken,
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("fragment collections status=" + response.statusCode());
        }
        return root.mapper().readTree(response.body());
    }

    private static JsonNode listFragments(LiferayCLIMain.RootCommand root, String accessToken, long collectionId)
        throws Exception {
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            "/api/jsonws/fragment.fragmententry/get-fragment-entries?fragmentCollectionId=" + collectionId,
            accessToken,
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("fragments status=" + response.statusCode());
        }
        return root.mapper().readTree(response.body());
    }

    private static void writeJson(LiferayCLIMain.RootCommand root, JsonNode payload, Path output) throws Exception {
        Path target = output.toAbsolutePath().normalize();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        String pretty = root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        Files.writeString(target, pretty + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
