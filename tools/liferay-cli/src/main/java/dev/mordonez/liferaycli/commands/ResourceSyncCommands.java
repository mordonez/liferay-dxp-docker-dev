package dev.mordonez.liferaycli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mordonez.liferaycli.LiferayCLIMain;
import dev.mordonez.liferaycli.http.LiferayApiClient;
import dev.mordonez.liferaycli.http.OAuthTokenClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * ResourceSyncCommands: Operaciones de sincronización y exportación de recursos Liferay.
 *
 * Esta clase contiene los subcomandos para gestionar Estructuras (DDM), Plantillas (FTL),
 * ADTs (Application Display Templates) y Fragments mediante la API Headless de Liferay.
 *
 * Guía para Agentes y Desarrolladores:
 * - Sincronización: Usa prefijo 'sync-' (ej. sync-structures, sync-templates).
 * - Exportación: Usa prefijo 'export-' (ej. export-structures, export-templates).
 * - Combo: 'sync-all' [alias: all] realiza exportación + sincronización de una vez.
 */
final class ResourceSyncCommands {
    private static final String ADT_RESOURCE_CLASS_NAME = "com.liferay.portlet.display.template.PortletDisplayTemplate";
    private static final String DDM_STRUCTURE_CLASS_NAME = "com.liferay.dynamic.data.mapping.model.DDMStructure";
    private static final String JOURNAL_ARTICLE_CLASS_NAME = "com.liferay.journal.model.JournalArticle";
    private static final Map<String, String> ADT_CLASS_BY_WIDGET = Map.ofEntries(
        Map.entry("asset-entry", "com.liferay.asset.kernel.model.AssetEntry"),
        Map.entry("breadcrumb", "com.liferay.portal.kernel.servlet.taglib.ui.BreadcrumbEntry"),
        Map.entry("category-facet", "com.liferay.portal.search.web.internal.category.facet.portlet.CategoryFacetPortlet"),
        Map.entry("custom-facet", "com.liferay.portal.search.web.internal.custom.facet.portlet.CustomFacetPortlet"),
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

    private ResourceSyncCommands() {
    }

    @Command(name = "sync-structure", aliases = {"ss", "structure-sync"}, mixinStandardHelpOptions = true, description = "Sincroniza una estructura")
    public static class StructureSync extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = {"--name", "--key"}, required = true)
        String name;

        @Option(names = "--file")
        String file;

        @Option(names = "--check-only", defaultValue = "false")
        boolean checkOnly;

        @Option(names = "--create-missing", defaultValue = "false")
        boolean createMissing;

        @Option(names = "--skip-update", defaultValue = "false")
        boolean skipUpdate;

        @Option(names = "--migration-plan")
        String migrationPlan;

        @Option(names = "--migration-phase", defaultValue = "")
        String migrationPhase;

        @Option(names = "--migration-dry-run", defaultValue = "false")
        boolean migrationDryRun;

        @Option(names = "--cleanup-migration", defaultValue = "false")
        boolean cleanupMigration;

        @Option(names = "--allow-breaking-change", defaultValue = "false")
        boolean allowBreakingChange;

        @Override
        public Integer call() {
            try {
                SyncContext ctx = syncStructure(
                    root(),
                    site,
                    name,
                    file,
                    checkOnly,
                    createMissing,
                    skipUpdate,
                    migrationPlan,
                    migrationPhase,
                    migrationDryRun,
                    cleanupMigration,
                    allowBreakingChange
                );
                System.out.printf("%s\t%s\t%s%n", ctx.status, name, ctx.id);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "sync-structures",
        aliases = {"ss-all", "structure-sync-all"},
        mixinStandardHelpOptions = true,
        hidden = true,
        description = "Sincroniza todas las estructuras"
    )
    public static class StructureSyncAll extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = "--dir")
        String dir;

        @Option(names = "--check-only", defaultValue = "false")
        boolean checkOnly;

        @Option(names = "--continue-on-error", defaultValue = "false")
        boolean continueOnError;

        @Option(names = "--create-missing", defaultValue = "false")
        boolean createMissing;

        @Option(names = "--bulk", defaultValue = "false", description = "Confirmación explícita para operación bulk")
        boolean bulk;

        @Override
        public Integer call() {
            int processed = 0;
            int created = 0;
            int updated = 0;
            int checked = 0;
            int failed = 0;
            try {
                requireBulkConfirmation("sync-structures", bulk);
                Path baseDir = resolveStructureBaseDir(root(), site, dir);
                List<Path> files = findFiles(baseDir, ".json");
                for (Path structureFile : files) {
                    String key = fileStem(structureFile);
                    processed++;
                    try {
                        SyncContext result = syncStructure(
                            root(),
                            site,
                            key,
                            structureFile.toString(),
                            checkOnly,
                            createMissing,
                            false,
                            null,
                            "",
                            false,
                            false,
                            false
                        );
                        switch (result.status) {
                            case "created" -> created++;
                            case "updated" -> updated++;
                            default -> checked++;
                        }
                        System.out.printf("%s\t%s\t%s%n", result.status, key, result.id);
                    }
                    catch (Exception fileEx) {
                        failed++;
                        System.err.printf("[ERROR] structure sync-all fallida en %s: %s%n", key, fileEx.getMessage());
                        if (!continueOnError) {
                            return 1;
                        }
                    }
                }
                System.out.printf(
                    "processed=%d created=%d updated=%d checked=%d failed=%d%n",
                    processed,
                    created,
                    updated,
                    checked,
                    failed
                );
                return failed > 0 && !continueOnError ? 1 : 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "migrate-content", aliases = {"mc", "structure-migrate"}, mixinStandardHelpOptions = true, description = "Migra contenido de estructura")
    public static class StructureMigrateContent extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = {"--name", "--key"}, required = true)
        String name;

        @Option(names = "--migration-plan", required = true)
        String migrationPlan;

        @Option(names = "--dry-run", defaultValue = "false")
        boolean dryRun;

        @Option(names = "--cleanup-source", defaultValue = "false")
        boolean cleanupSource;

        @Override
        public Integer call() {
            try {
                MigrationStats stats = runStructureMigration(root(), site, name, migrationPlan, dryRun, cleanupSource);
                System.out.printf(
                    "scanned=%d migrated=%d unchanged=%d failed=%d dryRun=%s%n",
                    stats.scanned,
                    stats.migrated,
                    stats.unchanged,
                    stats.failed,
                    dryRun
                );
                return stats.failed > 0 ? 1 : 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "migrate-run", aliases = {"mr", "structure-migration-run"},
        mixinStandardHelpOptions = true,
        description = "Ejecuta sync+migración desde un descriptor único"
    )
    public static class StructureMigrationRun extends BaseResourceOp {
        @Option(names = "--migration-file", required = true)
        String migrationFile;

        @Option(names = "--check-only", defaultValue = "false")
        boolean checkOnly;

        @Option(names = "--migration-dry-run", defaultValue = "false")
        boolean migrationDryRun;

        @Option(names = "--skip-update", defaultValue = "false")
        boolean skipUpdate;

        @Override
        public Integer call() {
            try {
                return runMigrationDescriptor(
                    root(),
                    migrationFile,
                    checkOnly,
                    migrationDryRun,
                    skipUpdate
                );
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "migrate-pipeline", aliases = {"mp", "structure-migration-pipeline"},
        mixinStandardHelpOptions = true,
        description = {
            "Pipeline completo descriptor: global structures + migración + templates + validación + cleanup",
            "Ejemplo:",
            "  ub-cli resource structure-migration-pipeline --migration-file liferay/resources/journal/migrations/2026-03-08-ub-str-novedad-nota-prensa.json --create-missing-templates"
        }
    )
    public static class StructureMigrationPipeline extends BaseResourceOp {
        @Option(names = "--migration-file", required = true)
        String migrationFile;

        @Option(names = "--check-only", defaultValue = "false")
        boolean checkOnly;

        @Option(names = "--migration-dry-run", defaultValue = "false")
        boolean migrationDryRun;

        @Option(names = "--run-cleanup", defaultValue = "false")
        boolean runCleanup;

        @Option(names = "--cleanup-file")
        String cleanupFile;

        @Option(names = "--skip-validation", defaultValue = "false")
        boolean skipValidation;

        @Option(names = "--create-missing-templates", defaultValue = "false", description = "Crea templates faltantes durante [3/5]")
        boolean createMissingTemplates;

        @Override
        public Integer call() {
            try {
                return runMigrationPipeline(
                    root(),
                    migrationFile,
                    checkOnly,
                    migrationDryRun,
                    runCleanup,
                    cleanupFile,
                    skipValidation,
                    createMissingTemplates
                );
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "delete-structure", aliases = {"ds", "structure-delete"}, mixinStandardHelpOptions = true, description = "Elimina una estructura")
    public static class StructureDelete extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = {"--name", "--key"})
        String name;

        @Option(names = "--id")
        String id;

        @Option(names = "--yes", defaultValue = "false")
        boolean yes;

        @Override
        public Integer call() {
            if (!yes) {
                System.err.println("RESOURCE_ERROR: falta --yes");
                return 1;
            }
            try {
                var token = token(root());
                String structureId = id;
                if (isBlank(structureId)) {
                    if (isBlank(name)) {
                        throw new IllegalArgumentException("structure-delete requiere --name o --id");
                    }
                    JsonNode structure = fetchStructureByKey(root(), token, normalizeSite(site), name);
                    structureId = structure.path("id").asText("");
                }
                if (isBlank(structureId)) {
                    throw new IllegalStateException("No se pudo resolver id de estructura");
                }
                LiferayApiClient.ApiResponse response = root().apiClient().delete(
                    root().settings().baseUrl(),
                    "/o/data-engine/v2.0/data-definitions/" + urlEncode(structureId),
                    token.accessToken(),
                    root().settings().timeoutSeconds()
                );
                ensure2xx(response, "structure-delete");
                System.out.printf("deleted\t%s\t%s%n", isBlank(name) ? structureId : name, structureId);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "sync-template", aliases = {"st", "template-sync"}, mixinStandardHelpOptions = true, description = "Sincroniza un template")
    public static class TemplateSync extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = {"--name", "--key"}, required = true)
        String name;

        @Option(names = "--file")
        String file;

        @Option(names = "--structure-key")
        String structureKey;

        @Option(names = "--check-only", defaultValue = "false")
        boolean checkOnly;

        @Option(names = "--create-missing", defaultValue = "false")
        boolean createMissing;

        @Override
        public Integer call() {
            try {
                SyncContext result = syncTemplate(root(), site, name, file, structureKey, checkOnly, createMissing);
                System.out.printf("%s\t%s\t%s%n", result.status, name, result.id);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "sync-templates", aliases = {"st-all", "template-sync-all"},
        mixinStandardHelpOptions = true,
        hidden = true,
        description = {
            "Sincroniza todos los templates FTL del directorio del site.",
            "Usa --structure-key para filtrar solo los templates de una estructura.",
            "No existe --key-prefix; filtra por estructura con --structure-key.",
            "Requiere --continue-on-error para tolerar templates inexistentes en sites heredados."
        }
    )
    public static class TemplateSyncAll extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = "--check-only", defaultValue = "false")
        boolean checkOnly;

        @Option(names = "--continue-on-error", defaultValue = "false")
        boolean continueOnError;

        @Option(names = "--structure-key")
        String structureKey;

        @Option(names = "--bulk", defaultValue = "false", description = "Confirmación explícita para operación bulk")
        boolean bulk;

        @Override
        public Integer call() {
            int processed = 0;
            int created = 0;
            int updated = 0;
            int checked = 0;
            int failed = 0;
            try {
                requireBulkConfirmation("sync-templates", bulk);
                var token = token(root());
                String normalizedSite = normalizeSite(site);
                Path baseDir = resolveTemplateSyncDir(root(), token.accessToken(), normalizedSite);
                if (!Files.isDirectory(baseDir)) {
                    System.out.printf(
                        "processed=%d created=%d updated=%d checked=%d failed=%d%n",
                        0,
                        0,
                        0,
                        0,
                        0
                    );
                    return 0;
                }
                Set<String> excludedSiteTokens = "/global".equals(normalizedSite)
                    ? listSiteTokens(root(), token.accessToken())
                    : Set.of();
                List<Path> files = filterSiteScopedFiles(findFiles(baseDir, ".ftl"), baseDir, excludedSiteTokens);
                if (!isBlank(structureKey)) {
                    JsonNode siteNode = resolveSite(root(), token.accessToken(), normalizedSite);
                    long siteId = siteNode.path("id").asLong(-1L);
                    long companyId = resolveCompanyId(root(), token.accessToken(), siteNode);
                    JsonNode structure = fetchStructureByKey(root(), token, normalizedSite, structureKey);
                    String structureId = structure.path("id").asText("");
                    JsonNode allTemplates = listJournalTemplates(root(), token.accessToken(), siteId, companyId);
                    Set<String> structureTemplateNames = new HashSet<>();
                    for (JsonNode t : allTemplates) {
                        if (structureId.equals(t.path("classPK").asText(""))) {
                            structureTemplateNames.add(t.path("templateKey").asText(""));
                            structureTemplateNames.add(t.path("templateId").asText(""));
                            structureTemplateNames.add(t.path("externalReferenceCode").asText(""));
                            structureTemplateNames.add(t.path("nameCurrentValue").asText(""));
                            structureTemplateNames.add(t.path("name").asText(""));
                        }
                    }
                    structureTemplateNames.remove("");
                    files = files.stream().filter(f -> structureTemplateNames.contains(fileStem(f))).toList();
                }
                for (Path templateFile : files) {
                    String name = fileStem(templateFile);
                    processed++;
                    try {
                        SyncContext result = syncTemplate(
                            root(),
                            site,
                            name,
                            templateFile.toString(),
                            structureKey,
                            checkOnly,
                            false
                        );
                        switch (result.status) {
                            case "created" -> created++;
                            case "updated" -> updated++;
                            default -> checked++;
                        }
                        System.out.printf("%s\t%s\t%s%n", result.status, name, result.id);
                    }
                    catch (Exception fileEx) {
                        failed++;
                        System.err.printf("[ERROR] template sync-all fallida en %s: %s%n", name, fileEx.getMessage());
                        if (!continueOnError) {
                            return 1;
                        }
                    }
                }
                System.out.printf(
                    "processed=%d created=%d updated=%d checked=%d failed=%d%n",
                    processed,
                    created,
                    updated,
                    checked,
                    failed
                );
                return failed > 0 && !continueOnError ? 1 : 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "delete-template", aliases = {"dt", "template-delete"}, mixinStandardHelpOptions = true, description = "Elimina un template")
    public static class TemplateDelete extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = {"--name", "--key"})
        String name;

        @Option(names = "--id")
        String id;

        @Option(names = "--yes", defaultValue = "false")
        boolean yes;

        @Override
        public Integer call() {
            if (!yes) {
                System.err.println("RESOURCE_ERROR: falta --yes");
                return 1;
            }
            try {
                var token = token(root());
                String lookup = !isBlank(id) ? id : name;
                if (isBlank(lookup)) {
                    throw new IllegalArgumentException("template-delete requiere --name o --id");
                }
                JsonNode template = findTemplate(root(), token, normalizeSite(site), lookup, 200);
                String templateKey = template.path("id").asText("");
                long siteId = resolveSiteId(root(), token.accessToken(), normalizeSite(site));
                long ddmClassId = fetchClassNameId(root(), token.accessToken(), DDM_STRUCTURE_CLASS_NAME);
                JsonNode ddmTemplate = getJson(
                    root(),
                    token.accessToken(),
                    "/api/jsonws/ddm.ddmtemplate/get-template?groupId=" + siteId +
                        "&classNameId=" + ddmClassId +
                        "&templateKey=" + urlEncode(templateKey),
                    "template-delete lookup"
                );
                String templateId = ddmTemplate.path("templateId").asText("");
                if (isBlank(templateId)) {
                    throw new IllegalStateException("No se pudo resolver templateId DDM");
                }
                postForm(root(), token.accessToken(), "/api/jsonws/ddm.ddmtemplate/delete-template", Map.of(
                    "templateId", templateId
                ), "template-delete");
                System.out.printf("deleted\t%s\t%s%n", isBlank(name) ? templateKey : name, templateId);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "adt-get", mixinStandardHelpOptions = true, description = "Obtiene un ADT")
    public static class AdtGet extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = {"--name", "--key"})
        String name;

        @Option(names = "--widget-type")
        String widgetType;

        @Option(names = "--id")
        String id;

        @Override
        public Integer call() {
            try {
                if (isBlank(name) && isBlank(id)) {
                    throw new IllegalArgumentException("adt-get requiere --name o --id");
                }
                String normalizedWidget = normalizeWidgetType(widgetType);
                var token = token(root());
                List<JsonNode> list = listAdts(root(), token.accessToken(), normalizeSite(site), normalizedWidget, true);
                List<JsonNode> matches = new ArrayList<>();
                for (JsonNode item : list) {
                    if (!isBlank(id) && Objects.equals(item.path("templateId").asText(""), id)) {
                        matches.add(item);
                    }
                    if (!isBlank(name)) {
                        String key = item.path("templateKey").asText("");
                        String adtName = item.path("adtName").asText("");
                        String displayName = item.path("displayName").asText("");
                        if (name.equals(key) || name.equals(adtName) || name.equals(displayName)) {
                            matches.add(item);
                        }
                    }
                }
                if (matches.isEmpty()) {
                    throw new IllegalStateException("ADT no encontrada");
                }
                if (matches.size() > 1) {
                    throw new IllegalStateException("ADT ambigua (" + matches.size() + " resultados)");
                }
                System.out.println(root().mapper().writeValueAsString(matches.get(0)));
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "sync-adt",
        aliases = {"sa", "adt-sync"},
        mixinStandardHelpOptions = true,
        description = {
            "Sincroniza un ADT concreto.",
            "",
            "Ruta recomendada:",
            "  1. Resuelve el ADT desde inventory/page con: resource resolve-adt --display-style ddmTemplate_<ID>",
            "  2. Sincroniza el fichero concreto con --file",
            "",
            "Ejemplos:",
            "  task liferay -- resource sync-adt --file liferay/resources/templates/application_display/global/search_result_summary/UB_ADT_ACTIVIDADES_SEARCH.ftl --widget-type search-result-summary --site /global",
            "  task liferay -- resource sync-adt --file liferay/resources/templates/application_display/actualitat/search_result_summary/UB_ADT_ACTIVIDADES_SEARCH.ftl --widget-type search-result-summary --site /actualitat --check-only"
        }
    )
    public static class AdtSync extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = {"--name", "--key"})
        String name;

        @Option(names = "--widget-type")
        String widgetType;

        @Option(names = "--file")
        String file;

        @Option(names = "--check-only", defaultValue = "false")
        boolean checkOnly;

        @Option(names = "--create-missing", defaultValue = "false")
        boolean createMissing;

        @Override
        public Integer call() {
            try {
                SyncContext result = syncAdt(root(), site, name, widgetType, file, checkOnly, createMissing);
                System.out.printf("%s\t%s\t%s\t%s%n", result.status, result.extra, result.name, result.id);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "sync-adts",
        aliases = {"sa-all", "adt-sync-all"},
        mixinStandardHelpOptions = true,
        hidden = true,
        description = "Sincroniza todos los ADT"
    )
    public static class AdtSyncAll extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = "--widget-type")
        String widgetType;

        @Option(names = "--check-only", defaultValue = "false")
        boolean checkOnly;

        @Option(names = "--continue-on-error", defaultValue = "false")
        boolean continueOnError;

        @Option(names = "--bulk", defaultValue = "false", description = "Confirmación explícita para operación bulk")
        boolean bulk;

        @Override
        public Integer call() {
            int processed = 0;
            int created = 0;
            int updated = 0;
            int checked = 0;
            int failed = 0;
            try {
                requireBulkConfirmation("sync-adts", bulk);
                var token = token(root());
                String filterWidget = normalizeWidgetType(widgetType);
                String normalizedSite = normalizeSite(site);
                Path baseDir = resolveAdtSyncDir(root(), token.accessToken(), normalizedSite);
                if (!Files.isDirectory(baseDir)) {
                    System.out.printf(
                        "processed=%d created=%d updated=%d checked=%d failed=%d%n",
                        0,
                        0,
                        0,
                        0,
                        0
                    );
                    return 0;
                }
                Set<String> excludedSiteTokens = "/global".equals(normalizedSite)
                    ? listSiteTokens(root(), token.accessToken())
                    : Set.of();
                List<Path> files = filterSiteScopedFiles(findFiles(baseDir, ".ftl"), baseDir, excludedSiteTokens);
                for (Path adtFile : files) {
                    String currentWidget = inferAdtWidgetFromPath(adtFile);
                    if (!isBlank(filterWidget) && !filterWidget.equals(currentWidget)) {
                        continue;
                    }
                    String adtName = fileStem(adtFile);
                    processed++;
                    try {
                        SyncContext result = syncAdt(
                            root(),
                            site,
                            adtName,
                            currentWidget,
                            adtFile.toString(),
                            checkOnly,
                            false
                        );
                        switch (result.status) {
                            case "created" -> created++;
                            case "updated" -> updated++;
                            default -> checked++;
                        }
                        System.out.printf("%s\t%s\t%s\t%s%n", result.status, result.extra, result.name, result.id);
                    }
                    catch (Exception fileEx) {
                        failed++;
                        System.err.printf("[ERROR] adt sync-all fallida en %s: %s%n", adtName, fileEx.getMessage());
                        if (!continueOnError) {
                            return 1;
                        }
                    }
                }
                System.out.printf(
                    "processed=%d created=%d updated=%d checked=%d failed=%d%n",
                    processed,
                    created,
                    updated,
                    checked,
                    failed
                );
                return failed > 0 && !continueOnError ? 1 : 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "delete-adt", aliases = {"da", "adt-delete"}, mixinStandardHelpOptions = true, description = "Elimina un ADT")
    public static class AdtDelete extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = {"--name", "--key"})
        String name;

        @Option(names = "--widget-type")
        String widgetType;

        @Option(names = "--id")
        String id;

        @Option(names = "--yes", defaultValue = "false")
        boolean yes;

        @Override
        public Integer call() {
            if (!yes) {
                System.err.println("RESOURCE_ERROR: falta --yes");
                return 1;
            }
            try {
                var token = token(root());
                String templateId = id;
                if (isBlank(templateId)) {
                    if (isBlank(name) || isBlank(widgetType)) {
                        throw new IllegalArgumentException("adt-delete requiere --id o (--name + --widget-type)");
                    }
                    List<JsonNode> list = listAdts(root(), token.accessToken(), normalizeSite(site), normalizeWidgetType(widgetType), false);
                    JsonNode found = null;
                    for (JsonNode item : list) {
                        String key = item.path("templateKey").asText("");
                        String adtName = item.path("adtName").asText("");
                        String displayName = item.path("displayName").asText("");
                        if (name.equals(key) || name.equals(adtName) || name.equals(displayName)) {
                            found = item;
                            break;
                        }
                    }
                    if (found == null) {
                        throw new IllegalStateException("No se pudo resolver templateId de ADT");
                    }
                    templateId = found.path("templateId").asText("");
                }
                postForm(root(), token.accessToken(), "/api/jsonws/ddm.ddmtemplate/delete-template", Map.of(
                    "templateId", templateId
                ), "adt-delete");
                System.out.printf("deleted\t%s\t%s%n", isBlank(name) ? "adt" : name, templateId);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "export-fragments", aliases = {"ef", "fragments-export"}, mixinStandardHelpOptions = true, description = "Exporta fragments de site(s)")
    public static class FragmentsExport extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = "--group-id")
        String groupId;

        @Option(names = "--all-sites", defaultValue = "false")
        boolean allSites;

        @Option(names = "--dir")
        String dir;

        @Option(names = "--output")
        String output;

        @Override
        public Integer call() {
            try {
                var token = token(root());
                if (allSites) {
                    return exportFragmentsAllSites(root(), token.accessToken(), dir, output);
                }
                SiteInfo siteInfo = resolveSiteInfo(root(), token.accessToken(), site, groupId);
                FragmentExportResult result = exportFragmentsSingleSite(root(), token.accessToken(), siteInfo, dir);
                if (!isBlank(output)) {
                    Path outputFile = resolveOutputPath(root(), output);
                    writePrettyJson(root(), result.payload, outputFile);
                }
                System.out.printf(
                    "collections=%d fragments=%d errors=%d dir=%s%n",
                    result.collectionCount,
                    result.fragmentCount,
                    result.errors,
                    result.projectDir
                );
                return result.errors > 0 ? 1 : 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "sync-fragments", aliases = {"sf", "fragments-sync"}, mixinStandardHelpOptions = true, description = "Sincroniza fragments de site(s)")
    public static class FragmentsSync extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = "--group-id")
        String groupId;

        @Option(names = "--all-sites", defaultValue = "false")
        boolean allSites;

        @Option(names = "--dir")
        String dir;

        @Option(names = "--fragment")
        String fragment;

        @Option(names = "--bulk", defaultValue = "false", description = "Confirmación explícita para operación bulk")
        boolean bulk;

        @Override
        public Integer call() {
            try {
                var token = token(root());
                if (allSites && !isBlank(fragment)) {
                    throw new IllegalArgumentException("--fragment requiere --site o --group-id");
                }
                if (allSites) {
                    requireBulkConfirmation("sync-fragments --all-sites", bulk);
                    int imported = 0;
                    int errors = 0;
                    int sites = 0;
                    for (SiteInfo siteInfo : listSitesIncludingGlobal(root(), token.accessToken())) {
                        Path projectDir = resolveFragmentsProjectDir(root(), dir, siteInfo.siteToken, false);
                        if (!Files.isDirectory(projectDir.resolve("src"))) {
                            continue;
                        }
                        JsonNode result = runFragmentsImport(root(), token.accessToken(), siteInfo.groupId, projectDir, "");
                        imported += result.path("summary").path("importedFragments").asInt(0);
                        errors += result.path("summary").path("errors").asInt(0);
                        sites++;
                    }
                    System.out.printf("sites=%d imported=%d errors=%d mode=all-sites%n", sites, imported, errors);
                    return errors > 0 ? 1 : 0;
                }

                SiteInfo siteInfo = resolveSiteInfo(root(), token.accessToken(), site, groupId);
                Path projectDir = resolveFragmentsProjectDir(root(), dir, siteInfo.siteToken, false);
                JsonNode result = runFragmentsImport(root(), token.accessToken(), siteInfo.groupId, projectDir, fragment);
                int imported = result.path("summary").path("importedFragments").asInt(0);
                int errors = result.path("summary").path("errors").asInt(0);
                System.out.printf("imported=%d errors=%d%n", imported, errors);
                return errors > 0 ? 1 : 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "export-structures", aliases = {"es", "structure-export-all"}, mixinStandardHelpOptions = true, description = "Exporta estructuras de un site. Con --key exporta solo una estructura concreta.")
    public static class StructureExportAll extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = "--dir")
        String dir;

        @Option(names = "--all-sites", defaultValue = "false")
        boolean allSites;

        @Option(names = "--check-only", defaultValue = "false")
        boolean checkOnly;

        @Option(names = {"--key", "--name"}, description = "Exportar solo la estructura con esta key (ej. UB_STR_BANNER)")
        String key;

        @Override
        public Integer call() {
            try {
                var token = token(root());
                String normalizedSite = normalizeSite(site);
                Path outputDir = resolveStructureExportDir(root(), normalizedSite, dir);

                if (!isBlank(key)) {
                    JsonNode exported = fetchStructureByKey(root(), token, normalizedSite, key, true);
                    JsonNode normalized = normalizeStructureExport(root(), exported);
                    Path target = outputDir.resolve(key + ".json");
                    if (checkOnly) {
                        boolean differs = structureDiffers(root(), normalized, target);
                        System.out.printf("CHECK_ONLY key=%s diff=%s%n", key, differs);
                    } else {
                        Files.createDirectories(outputDir);
                        writePrettyJson(root(), normalized, target);
                        System.out.printf("EXPORTED key=%s file=%s%n", key, target.getFileName());
                    }
                    return 0;
                }

                if (allSites) {
                    int scannedSites = 0;
                    int totalProcessed = 0;
                    int totalDiffs = 0;
                    for (SiteInfo siteInfo : listSitesIncludingGlobal(root(), token.accessToken())) {
                        scannedSites++;
                        String ns = normalizeSite(siteInfo.siteFriendly());
                        Path od = resolveStructureExportDir(root(), ns, dir);
                        ExportStats stats = exportStructuresForSite(root(), token, ns, od, checkOnly);
                        totalProcessed += stats.processed();
                        totalDiffs += stats.diffs();
                    }
                    if (checkOnly) {
                        System.out.printf("CHECK_ONLY mode=all-sites scanned=%d diffs=%d%n", scannedSites, totalDiffs);
                    } else {
                        System.out.printf("EXPORTED mode=all-sites scanned=%d count=%d%n", scannedSites, totalProcessed);
                    }
                    return 0;
                }

                exportStructuresForSite(root(), token, normalizedSite, outputDir, checkOnly);
                return 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "export-templates", aliases = {"et", "template-export-all"}, mixinStandardHelpOptions = true, description = "Exporta templates de Journal")
    public static class TemplateExportAll extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = "--all-sites", defaultValue = "false")
        boolean allSites;

        @Option(names = {"--output-dir", "--dir"})
        String outputDir;

        @Option(names = {"--continue-on-error", "--continue"}, defaultValue = "false")
        boolean continueOnError;

        @Override
        public Integer call() {
            try {
                var token = token(root());
                Path baseDir = resolveTemplateExportDir(root(), outputDir);
                if (allSites) {
                    int scannedSites = 0;
                    int totalExported = 0;
                    int totalFailed = 0;
                    for (SiteInfo siteInfo : listSitesIncludingGlobal(root(), token.accessToken())) {
                        scannedSites++;
                        String normalizedSite = normalizeSite(siteInfo.siteFriendly());
                        Path targetDir = resolveResourceExportSiteDir(baseDir, normalizedSite, siteInfo.siteToken());
                        ExportStats stats = exportTemplatesForSite(
                            root(),
                            token.accessToken(),
                            normalizedSite,
                            targetDir,
                            continueOnError
                        );
                        totalExported += stats.processed();
                        totalFailed += stats.failed();
                    }
                    cleanupLegacyTemplateRootFiles(baseDir);
                    System.out.printf(
                        "EXPORTED mode=all-sites scanned=%d exported=%d failed=%d dir=%s%n",
                        scannedSites,
                        totalExported,
                        totalFailed,
                        baseDir
                    );
                    return totalFailed > 0 ? 1 : 0;
                }

                String normalizedSite = normalizeSite(site);
                String siteToken = resolveExportSiteToken(root(), token.accessToken(), normalizedSite);
                Path targetDir = resolveResourceExportSiteDir(baseDir, normalizedSite, siteToken);
                ExportStats stats = exportTemplatesForSite(
                    root(),
                    token.accessToken(),
                    normalizedSite,
                    targetDir,
                    continueOnError
                );
                if ("/global".equals(normalizedSite)) {
                    cleanupLegacyTemplateRootFiles(baseDir);
                }
                return stats.failed() > 0 ? 1 : 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "export-adts", aliases = {"ea", "adt-export-all"}, mixinStandardHelpOptions = true, description = "Exporta ADTs por site/widget")
    public static class AdtExportAll extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = "--all-sites", defaultValue = "false")
        boolean allSites;

        @Option(names = {"--output-dir", "--dir"})
        String outputDir;

        @Option(names = {"--widget", "--widget-type"}, defaultValue = "")
        String widgetType;

        @Option(names = {"--continue-on-error", "--continue"}, defaultValue = "false")
        boolean continueOnError;

        @Override
        public Integer call() {
            try {
                var token = token(root());
                String widgetFilter = normalizeWidgetType(widgetType);
                Path baseDir = resolveAdtExportDir(root(), outputDir);
                if (allSites) {
                    int scannedSites = 0;
                    int totalExported = 0;
                    int totalFailed = 0;
                    for (SiteInfo siteInfo : listSitesIncludingGlobal(root(), token.accessToken())) {
                        scannedSites++;
                        String normalizedSite = normalizeSite(siteInfo.siteFriendly());
                        Path targetDir = resolveResourceExportSiteDir(baseDir, normalizedSite, siteInfo.siteToken());
                        ExportStats stats = exportAdtsForSite(
                            root(),
                            token.accessToken(),
                            normalizedSite,
                            widgetFilter,
                            targetDir,
                            continueOnError
                        );
                        totalExported += stats.processed();
                        totalFailed += stats.failed();
                    }
                    System.out.printf(
                        "EXPORTED mode=all-sites scanned=%d exported=%d failed=%d dir=%s%n",
                        scannedSites,
                        totalExported,
                        totalFailed,
                        baseDir
                    );
                    return totalFailed > 0 ? 1 : 0;
                }

                String normalizedSite = normalizeSite(site);
                String siteToken = resolveExportSiteToken(root(), token.accessToken(), normalizedSite);
                Path targetDir = resolveResourceExportSiteDir(baseDir, normalizedSite, siteToken);
                ExportStats stats = exportAdtsForSite(
                    root(),
                    token.accessToken(),
                    normalizedSite,
                    widgetFilter,
                    targetDir,
                    continueOnError
                );
                return stats.failed() > 0 ? 1 : 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "structure-with-templates-sync",
        mixinStandardHelpOptions = true,
        description = {
            "Sincroniza una estructura y todos sus templates en un solo paso.",
            "Equivale a: structure-sync --key KEY + template-sync-all --structure-key KEY --continue-on-error.",
            "Util al modificar FTL/JSON en el repo sin deploy de modulos Java.",
            "Ejemplo: ub resource structure-with-templates-sync --key UB_STR_ACTIVIDAD --site /global"
        }
    )
    public static class StructureWithTemplatesSync extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global", description = "Site por friendly URL o ID (default: /global)")
        String site;

        @Option(names = {"--key", "--name"}, required = true, description = "DataDefinition key de la estructura")
        String key;

        @Option(names = "--check-only", defaultValue = "false", description = "Preview sin aplicar cambios")
        boolean checkOnly;

        @Option(names = "--skip-templates", defaultValue = "false", description = "Sincroniza solo la estructura, omite templates")
        boolean skipTemplates;

        @Override
        public Integer call() {
            try {
                // 1. Sync structure
                System.out.println("[INFO] structure-with-templates-sync: sincronizando estructura " + key + " en " + site);
                SyncContext structCtx = syncStructure(
                    root(), site, key, null, checkOnly, false, false, null, "", false, false, false
                );
                System.out.printf("%s\t%s\t%s%n", structCtx.status, key, structCtx.id);

                if (skipTemplates) {
                    return 0;
                }

                // 2. Sync all templates for this structure
                System.out.println("[INFO] structure-with-templates-sync: sincronizando templates de " + key);
                int result = new TemplateSyncAll() {{
                    parent = StructureWithTemplatesSync.this.parent;
                    this.site = StructureWithTemplatesSync.this.site;
                    this.structureKey = key;
                    this.checkOnly = StructureWithTemplatesSync.this.checkOnly;
                    this.continueOnError = true;
                }}.call();
                return result;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "sync-all",
        aliases = {"all", "export-and-sync"},
        mixinStandardHelpOptions = true,
        hidden = true,
        description = "Exporta y sincroniza recursos"
    )
    public static class ExportAndSync extends BaseResourceOp {
        @Option(names = "--site", defaultValue = "/global")
        String site;

        @Option(names = "--all-sites", defaultValue = "false")
        boolean allSites;

        @Option(names = "--check-only", defaultValue = "false")
        boolean checkOnly;

        @Option(names = "--bulk", defaultValue = "false", description = "Confirmación explícita para operación bulk")
        boolean bulk;

        @Override
        public Integer call() {
            int failures = 0;
            try {
                requireBulkConfirmation("sync-all", bulk);
                List<String> sites = new ArrayList<>();
                if (allSites) {
                    var token = token(root());
                    for (SiteInfo siteInfo : listSitesIncludingGlobal(root(), token.accessToken())) {
                        sites.add(normalizeSite(siteInfo.siteFriendly()));
                    }
                    if (sites.isEmpty()) {
                        throw new IllegalStateException("No se han encontrado sites para --all-sites");
                    }
                }
                else {
                    sites.add(normalizeSite(site));
                }

                for (String normalizedSite : sites) {
                    System.out.println("[INFO] export-and-sync: site=" + normalizedSite);
                    if (!checkOnly) {
                        System.out.println("[INFO] export-and-sync: exportando structures...");
                        failures += new StructureExportAll() {{
                            parent = ExportAndSync.this.parent;
                            this.site = normalizedSite;
                            this.dir = null;
                            this.checkOnly = false;
                        }}.call();
                        System.out.println("[INFO] export-and-sync: exportando templates...");
                        failures += new TemplateExportAll() {{
                            parent = ExportAndSync.this.parent;
                            this.site = normalizedSite;
                            this.outputDir = null;
                            this.continueOnError = true;
                        }}.call();
                        System.out.println("[INFO] export-and-sync: exportando ADTs...");
                        failures += new AdtExportAll() {{
                            parent = ExportAndSync.this.parent;
                            this.site = normalizedSite;
                            this.outputDir = null;
                            this.widgetType = "";
                            this.continueOnError = true;
                        }}.call();
                    }
                    else {
                        System.out.println("[INFO] export-and-sync --check-only: se omite export");
                    }

                    System.out.println("[INFO] export-and-sync: sincronizando structures...");
                    failures += new StructureSyncAll() {{
                        parent = ExportAndSync.this.parent;
                        this.site = normalizedSite;
                        this.dir = null;
                        this.checkOnly = checkOnly;
                        this.continueOnError = true;
                        this.createMissing = false;
                    }}.call();
                    System.out.println("[INFO] export-and-sync: sincronizando templates...");
                    failures += new TemplateSyncAll() {{
                        parent = ExportAndSync.this.parent;
                        this.site = normalizedSite;
                        this.structureKey = null;
                        this.checkOnly = checkOnly;
                        this.continueOnError = true;
                    }}.call();
                    System.out.println("[INFO] export-and-sync: sincronizando ADTs...");
                    failures += new AdtSyncAll() {{
                        parent = ExportAndSync.this.parent;
                        this.site = normalizedSite;
                        this.widgetType = "";
                        this.checkOnly = checkOnly;
                        this.continueOnError = true;
                    }}.call();
                }
                return failures > 0 ? 1 : 0;
            }
            catch (Exception ex) {
                System.err.println("RESOURCE_ERROR: " + ex.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "resource-op-base")
    abstract public static class BaseResourceOp implements Callable<Integer> {
        @CommandLine.ParentCommand
        ResourceCommand parent;

        protected LiferayCLIMain.RootCommand root() {
            return parent.root();
        }
    }

    private static SyncContext syncStructure(
        LiferayCLIMain.RootCommand root,
        String site,
        String name,
        String file,
        boolean checkOnly,
        boolean createMissing,
        boolean skipUpdate,
        String migrationPlan,
        String migrationPhase,
        boolean migrationDryRun,
        boolean cleanupMigration,
        boolean allowBreakingChange
    ) throws Exception {
        var token = token(root);
        String normalizedSite = normalizeSite(site);
        long siteId = resolveSiteId(root, token.accessToken(), normalizedSite);
        Path structureFile = resolveStructureFile(root, name, file);
        JsonNode payloadNode = root.mapper().readTree(Files.readString(structureFile));

        String phase = isBlank(migrationPhase) ? "" : migrationPhase.toLowerCase(Locale.ROOT);
        if (!isBlank(migrationPlan) && ("pre".equals(phase) || "both".equals(phase))) {
            runStructureMigration(root, normalizedSite, name, migrationPlan, migrationDryRun, cleanupMigration);
        }

        JsonNode existing = fetchStructureByKey(root, token, normalizedSite, name, false);
        String existingId = existing.path("id").asText("");
        if (isBlank(existingId)) {
            if (!createMissing) {
                throw new IllegalStateException("Structure '" + name + "' no existe y create-missing no esta activo");
            }
            if (checkOnly) {
                return new SyncContext("checked_missing", "", name, "");
            }

            ObjectNode createPayload = payloadNode.deepCopy();
            createPayload.remove("externalReferenceCode");
            JsonNode created = postJson(
                root,
                token.accessToken(),
                "/o/data-engine/v2.0/sites/" + siteId + "/data-definitions/by-content-type/journal",
                root.mapper().writeValueAsString(createPayload),
                "structure-create"
            );
            String createdId = created.path("id").asText("");

            if (!isBlank(migrationPlan) && ("post".equals(phase) || "both".equals(phase) || isBlank(phase))) {
                runStructureMigration(root, normalizedSite, name, migrationPlan, migrationDryRun, cleanupMigration);
            }
            return new SyncContext("created", createdId, name, "");
        }

        Set<String> removedRefs = new HashSet<>(collectFieldReferencesFromDefinition(existing));
        removedRefs.removeAll(collectFieldReferencesFromDefinition(payloadNode));
        if (!removedRefs.isEmpty() && isBlank(migrationPlan) && !allowBreakingChange) {
            throw new IllegalStateException(
                "Cambio bloqueado: la estructura elimina " + removedRefs.size() + " campo(s) "
                    + String.join(", ", new TreeSet<>(removedRefs))
                    + ". Define --migration-plan o usa --allow-breaking-change."
            );
        }

        if (checkOnly || skipUpdate) {
            if (!isBlank(migrationPlan) && ("post".equals(phase) || "both".equals(phase) || isBlank(phase))) {
                runStructureMigration(root, normalizedSite, name, migrationPlan, migrationDryRun, cleanupMigration);
            }
            return new SyncContext("checked", existingId, name, "");
        }

        boolean postMigrationRequested =
            !isBlank(migrationPlan) && ("post".equals(phase) || "both".equals(phase) || isBlank(phase));
        boolean autoTransition =
            postMigrationRequested
                && "post".equals(phase)
                && !removedRefs.isEmpty();
        String structureWriteToken = token.accessToken();
        if (autoTransition) {
            structureWriteToken = token(root).accessToken();
            JsonNode transitionPayload = buildTransitionPayload(existing, payloadNode);
            putJson(
                root,
                structureWriteToken,
                "/o/data-engine/v2.0/data-definitions/" + existingId,
                root.mapper().writeValueAsString(transitionPayload),
                "structure-update auto-transition"
            );
            runStructureMigration(root, normalizedSite, name, migrationPlan, migrationDryRun, cleanupMigration);
            structureWriteToken = token(root).accessToken();
        }

        JsonNode updated = putJson(
            root,
            structureWriteToken,
            "/o/data-engine/v2.0/data-definitions/" + existingId,
            root.mapper().writeValueAsString(payloadNode),
            "structure-update"
        );
        String updatedId = updated.path("id").asText(existingId);
        if (postMigrationRequested && !autoTransition) {
            runStructureMigration(root, normalizedSite, name, migrationPlan, migrationDryRun, cleanupMigration);
        }
        return new SyncContext("updated", updatedId, name, "");
    }

    private static SyncContext syncTemplate(
        LiferayCLIMain.RootCommand root,
        String site,
        String name,
        String file,
        String structureKey,
        boolean checkOnly,
        boolean createMissing
    ) throws Exception {
        var token = token(root);
        String normalizedSite = normalizeSite(site);
        JsonNode siteNode = resolveSite(root, token.accessToken(), normalizedSite);
        long siteId = siteNode.path("id").asLong(-1L);
        long companyId = resolveCompanyId(root, token.accessToken(), siteNode);
        Path templateFile = resolveTemplateFile(root, normalizedSite, name, file);
        String script = Files.readString(templateFile);
        String localSha = sha256(script);

        JsonNode templates = listJournalTemplates(root, token.accessToken(), siteId, companyId);
        String structureIdFilter = "";
        if (!isBlank(structureKey)) {
            JsonNode structure = fetchStructureByKey(root, token, normalizedSite, structureKey);
            structureIdFilter = structure.path("id").asText("");
        }

        JsonNode existing = null;
        for (JsonNode item : templates) {
            String templateId = item.path("templateId").asText("");
            String templateKey = item.path("templateKey").asText("");
            String nameCurrentValue = item.path("nameCurrentValue").asText("");
            String runtimeName = item.path("name").asText("");
            String externalReferenceCode = item.path("externalReferenceCode").asText("");
            if (!name.equals(templateId)
                && !name.equals(templateKey)
                && !name.equals(nameCurrentValue)
                && !name.equals(runtimeName)
                && !name.equals(externalReferenceCode)) {
                continue;
            }
            if (!isBlank(structureIdFilter)
                && !Objects.equals(item.path("classPK").asText(""), structureIdFilter)) {
                continue;
            }
            existing = item;
            break;
        }

        long ddmStructureClassNameId = fetchClassNameId(root, token.accessToken(), DDM_STRUCTURE_CLASS_NAME);
        long journalArticleClassNameId = fetchClassNameId(root, token.accessToken(), JOURNAL_ARTICLE_CLASS_NAME);

        if (existing == null) {
            if (!createMissing) {
                throw new IllegalStateException("Template '" + name + "' no existe y create-missing no esta activo");
            }
            if (checkOnly) {
                return new SyncContext("checked_missing", "", name, "");
            }
            if (isBlank(structureIdFilter)) {
                throw new IllegalArgumentException("Para crear template usa --structure-key");
            }

            Map<String, String> form = new LinkedHashMap<>();
            form.put("externalReferenceCode", name);
            form.put("groupId", String.valueOf(siteId));
            form.put("classNameId", String.valueOf(ddmStructureClassNameId));
            form.put("classPK", structureIdFilter);
            form.put("resourceClassNameId", String.valueOf(journalArticleClassNameId));
            form.put("nameMap", localizedMap(root, name));
            form.put("descriptionMap", localizedMap(root, ""));
            form.put("type", "display");
            form.put("mode", "");
            form.put("language", "ftl");
            form.put("script", script);
            JsonNode created = postForm(root, token.accessToken(), "/api/jsonws/ddm.ddmtemplate/add-template", form, "template-create");
            String templateKey = created.path("templateKey").asText(created.path("templateId").asText(""));
            return new SyncContext("created", templateKey, name, "");
        }

        String existingKey = existing.path("templateKey").asText(existing.path("templateId").asText(""));
        JsonNode ddmTemplate = getJson(
            root,
            token.accessToken(),
            "/api/jsonws/ddm.ddmtemplate/get-template?groupId=" + siteId +
                "&classNameId=" + ddmStructureClassNameId +
                "&templateKey=" + urlEncode(existingKey),
            "template-ddm-get"
        );
        String templateId = ddmTemplate.path("templateId").asText("");
        String classPk = ddmTemplate.path("classPK").asText("0");

        if (!checkOnly) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("templateId", templateId);
            form.put("classPK", classPk);
            form.put("nameMap", localizedMap(root, name));
            form.put("descriptionMap", localizedMap(root, ""));
            form.put("type", "display");
            form.put("mode", "");
            form.put("language", "ftl");
            form.put("script", script);
            form.put("cacheable", "false");
            postForm(root, token.accessToken(), "/api/jsonws/ddm.ddmtemplate/update-template", form, "template-update");
        }

        JsonNode after = findTemplate(root, token, normalizedSite, existingKey, 200);
        String runtimeScript = after.path("templateScript").asText(after.path("script").asText(""));
        if (!runtimeScript.isBlank() && !Objects.equals(localSha, sha256(runtimeScript))) {
            throw new IllegalStateException("Hash mismatch template '" + name + "'");
        }

        return new SyncContext(checkOnly ? "checked" : "updated", existingKey, name, "");
    }

    private static SyncContext syncAdt(
        LiferayCLIMain.RootCommand root,
        String site,
        String name,
        String widgetType,
        String file,
        boolean checkOnly,
        boolean createMissing
    ) throws Exception {
        Path adtFile = resolveAdtFile(root, name, widgetType, file);
        String resolvedName = isBlank(name) ? fileStem(adtFile) : name;
        String resolvedWidget = normalizeWidgetType(isBlank(widgetType) ? inferAdtWidgetFromPath(adtFile) : widgetType);
        if (!ADT_CLASS_BY_WIDGET.containsKey(resolvedWidget)) {
            throw new IllegalArgumentException("widget-type ADT no soportado: " + resolvedWidget);
        }

        var token = token(root);
        String normalizedSite = normalizeSite(site);
        long siteId = resolveSiteId(root, token.accessToken(), normalizedSite);
        String script = Files.readString(adtFile);
        String localSha = sha256(script);

        List<JsonNode> list = listAdts(root, token.accessToken(), normalizedSite, resolvedWidget, true);
        JsonNode existing = null;
        for (JsonNode item : list) {
            String key = item.path("templateKey").asText("");
            String adtName = item.path("adtName").asText("");
            String displayName = item.path("displayName").asText("");
            if (resolvedName.equals(key) || resolvedName.equals(adtName) || resolvedName.equals(displayName)) {
                existing = item;
                break;
            }
        }

        long classNameId = fetchClassNameId(root, token.accessToken(), ADT_CLASS_BY_WIDGET.get(resolvedWidget));
        long resourceClassNameId = fetchClassNameId(root, token.accessToken(), ADT_RESOURCE_CLASS_NAME);

        String templateId;
        String templateKey;
        String status;

        if (existing == null) {
            if (!createMissing) {
                throw new IllegalStateException("ADT '" + resolvedName + "' no existe y create-missing no esta activo");
            }
            if (checkOnly) {
                return new SyncContext("checked_missing", "", resolvedName, resolvedWidget);
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("externalReferenceCode", resolvedName);
            form.put("groupId", String.valueOf(siteId));
            form.put("classNameId", String.valueOf(classNameId));
            form.put("classPK", "0");
            form.put("resourceClassNameId", String.valueOf(resourceClassNameId));
            form.put("nameMap", localizedMap(root, resolvedName));
            form.put("descriptionMap", localizedMap(root, ""));
            form.put("type", "display");
            form.put("mode", "");
            form.put("language", "ftl");
            form.put("script", script);
            JsonNode created = postForm(root, token.accessToken(), "/api/jsonws/ddm.ddmtemplate/add-template", form, "adt-create");
            templateId = created.path("templateId").asText("");
            templateKey = created.path("templateKey").asText(templateId);
            status = "created";
        }
        else {
            templateId = existing.path("templateId").asText("");
            templateKey = existing.path("templateKey").asText(templateId);
            if (!checkOnly) {
                JsonNode ddmTemplate = getJson(
                    root,
                    token.accessToken(),
                    "/api/jsonws/ddm.ddmtemplate/get-template?templateId=" + templateId,
                    "adt-ddm-get"
                );
                String classPk = ddmTemplate.path("classPK").asText("0");
                Map<String, String> form = new LinkedHashMap<>();
                form.put("templateId", templateId);
                form.put("classPK", classPk);
                form.put("nameMap", localizedMap(root, resolvedName));
                form.put("descriptionMap", localizedMap(root, ""));
                form.put("type", "display");
                form.put("mode", "");
                form.put("language", "ftl");
                form.put("script", script);
                form.put("cacheable", "false");
                postForm(root, token.accessToken(), "/api/jsonws/ddm.ddmtemplate/update-template", form, "adt-update");
            }
            status = checkOnly ? "checked" : "updated";
        }

        JsonNode runtime = getJson(
            root,
            token.accessToken(),
            "/api/jsonws/ddm.ddmtemplate/get-template?templateId=" + templateId,
            "adt-runtime"
        );
        String runtimeScript = runtime.path("script").asText("");
        if (!runtimeScript.isBlank() && !Objects.equals(localSha, sha256(runtimeScript))) {
            throw new IllegalStateException("Hash mismatch ADT '" + resolvedName + "'");
        }

        return new SyncContext(status, templateKey, resolvedName, resolvedWidget);
    }

    private static int runMigrationDescriptor(
        LiferayCLIMain.RootCommand root,
        String migrationFile,
        boolean checkOnly,
        boolean migrationDryRun,
        boolean skipUpdate
    ) throws Exception {
        MigrationDescriptor descriptor = readMigrationDescriptor(root, migrationFile);
        System.out.printf(
            "[migration-run] descriptor site=%s structure=%s checkOnly=%s dryRun=%s skipUpdate=%s%n",
            descriptor.site(),
            descriptor.structureKey(),
            checkOnly,
            migrationDryRun,
            skipUpdate
        );
        Path planFile = Files.createTempFile("ub-structure-migration-plan-", ".json");
        try {
            root.mapper().writeValue(planFile.toFile(), descriptor.planNode());
            boolean effectiveDryRun = checkOnly || migrationDryRun;
            long syncStartMs = System.currentTimeMillis();
            System.out.printf("[migration-run] sync-structure start ts=%d%n", syncStartMs);
            SyncContext result = syncStructure(
                root,
                descriptor.site(),
                descriptor.structureKey(),
                descriptor.structureFile(),
                checkOnly,
                false,
                skipUpdate,
                planFile.toString(),
                descriptor.migrationPhase(),
                effectiveDryRun,
                false,
                descriptor.allowBreakingChange()
            );
            long syncElapsedMs = System.currentTimeMillis() - syncStartMs;
            System.out.printf(
                "[migration-run] sync-structure done status=%s id=%s elapsed=%s%n",
                result.status,
                result.id,
                formatDuration(syncElapsedMs)
            );
            System.out.printf("%s\t%s\t%s%n", result.status, descriptor.structureKey(), result.id);
            return 0;
        }
        finally {
            Files.deleteIfExists(planFile);
        }
    }

    private static int runMigrationPipeline(
        LiferayCLIMain.RootCommand root,
        String migrationFile,
        boolean checkOnly,
        boolean migrationDryRun,
        boolean runCleanup,
        String cleanupFile,
        boolean skipValidation,
        boolean createMissingTemplates
    ) throws Exception {
        Path descriptorPath = resolveExistingFile(repoRoot(root), migrationFile, root);
        JsonNode descriptorNode = root.mapper().readTree(Files.readString(descriptorPath));
        MigrationDescriptor descriptor = parseMigrationDescriptor(root, descriptorNode);

        System.out.println("[1/5] Sync de fieldsets/estructuras globales reutilizables (si aplica)");
        boolean hasMissingGlobal = false;
        for (String globalKey : parseStringList(descriptorNode.path("globalStructures"))) {
            SyncContext check = syncStructure(
                root,
                "/global",
                globalKey,
                null,
                true,
                true,
                false,
                null,
                "",
                false,
                false,
                false
            );
            if ("checked_missing".equals(check.status)) {
                hasMissingGlobal = true;
                if (!checkOnly) {
                    SyncContext created = syncStructure(
                        root,
                        "/global",
                        globalKey,
                        null,
                        false,
                        true,
                        false,
                        null,
                        "",
                        false,
                        false,
                        false
                    );
                    System.out.printf("%s\t%s\t%s%n", created.status, globalKey, created.id);
                }
            }
        }
        if (checkOnly && hasMissingGlobal) {
            System.err.println(
                "CHECK_ONLY incompleto: falta crear al menos un fieldset global referenciado. " +
                    "Ejecuta primero sin --check-only y repite validación."
            );
            return 2;
        }

        System.out.println("[2/5] Sync de estructura + migración de contenidos");
        int runStatus = runMigrationDescriptor(root, migrationFile, checkOnly, migrationDryRun, false);
        if (runStatus != 0) {
            return runStatus;
        }

        List<String> templateNames = descriptorTemplateNames(descriptorNode);
        System.out.println("[3/5] Sync de plantillas");
        System.out.printf(
            "[3/5] mode checkOnly=%s createMissingTemplates=%s%n",
            checkOnly,
            createMissingTemplates
        );
        if (!templateNames.isEmpty()) {
            for (String templateName : templateNames) {
                SyncContext synced;
                try {
                    synced = syncTemplate(
                        root,
                        descriptor.site(),
                        templateName,
                        null,
                        null,
                        checkOnly,
                        false
                    );
                } catch (Exception templateEx) {
                    String message = templateEx.getMessage() == null ? "" : templateEx.getMessage();
                    boolean missingTemplate = message.contains("no existe");
                    if (!createMissingTemplates || !missingTemplate) {
                        throw templateEx;
                    }
                    synced = syncTemplate(
                        root,
                        descriptor.site(),
                        templateName,
                        null,
                        descriptor.structureKey(),
                        checkOnly,
                        true
                    );
                }
                System.out.printf("%s\t%s\t%s%n", synced.status, templateName, synced.id);
            }
        }
        else {
            Path templatesDir = resolveTemplateExportDir(root, null);
            for (Path templateFile : findFiles(templatesDir, ".ftl")) {
                String templateName = fileStem(templateFile);
                SyncContext synced;
                try {
                    synced = syncTemplate(
                        root,
                        descriptor.site(),
                        templateName,
                        templateFile.toString(),
                        null,
                        checkOnly,
                        false
                    );
                } catch (Exception templateEx) {
                    String message = templateEx.getMessage() == null ? "" : templateEx.getMessage();
                    boolean missingTemplate = message.contains("no existe");
                    if (!createMissingTemplates || !missingTemplate) {
                        throw templateEx;
                    }
                    synced = syncTemplate(
                        root,
                        descriptor.site(),
                        templateName,
                        templateFile.toString(),
                        descriptor.structureKey(),
                        checkOnly,
                        true
                    );
                }
                System.out.printf("%s\t%s\t%s%n", synced.status, templateName, synced.id);
            }
        }

        if (!skipValidation) {
            System.out.println("[4/5] Validación de consistencia en CHECK_ONLY");
            String validationStructureFile = descriptor.structureFile();
            if (runCleanup) {
                String cleanupStructureFile = descriptorNode.path("cleanup").path("structureFile").asText("");
                if (!cleanupStructureFile.isBlank()) {
                    validationStructureFile = cleanupStructureFile;
                }
            }
            SyncContext validatedStructure = syncStructure(
                root,
                descriptor.site(),
                descriptor.structureKey(),
                validationStructureFile,
                true,
                false,
                false,
                null,
                "",
                false,
                false,
                descriptor.allowBreakingChange()
            );
            System.out.printf("%s\t%s\t%s%n", validatedStructure.status, descriptor.structureKey(), validatedStructure.id);
            for (String templateName : templateNames) {
                SyncContext validatedTemplate = syncTemplate(
                    root,
                    descriptor.site(),
                    templateName,
                    null,
                    null,
                    true,
                    false
                );
                System.out.printf("%s\t%s\t%s%n", validatedTemplate.status, templateName, validatedTemplate.id);
            }
        }
        else {
            System.out.println("[4/5] Validación omitida (--skip-validation)");
        }

        if (!runCleanup) {
            System.out.println("[5/5] Cleanup no ejecutado (--run-cleanup no definido)");
            return 0;
        }

        System.out.println("[5/5] Cleanup de campos origen (segunda fase)");
        CleanupDescriptor cleanupDescriptor = resolveCleanupDescriptor(
            root,
            descriptorNode,
            descriptorPath,
            cleanupFile
        );
        try {
            return runMigrationDescriptor(
                root,
                cleanupDescriptor.migrationFile(),
                checkOnly,
                migrationDryRun,
                false
            );
        }
        finally {
            if (cleanupDescriptor.deleteAfterUse()) {
                Files.deleteIfExists(Path.of(cleanupDescriptor.migrationFile()));
            }
        }
    }

    private static ExportStats exportStructuresForSite(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        String normalizedSite,
        Path outputDir,
        boolean checkOnly
    ) throws Exception {
        long siteId = resolveSiteId(root, token.accessToken(), normalizedSite);
        JsonNode structures = fetchPagedItems(
            root,
            token.accessToken(),
            "/o/data-engine/v2.0/sites/" + siteId + "/data-definitions/by-content-type/journal",
            200
        );

        int processed = 0;
        int diffs = 0;
        for (JsonNode structureRow : structures) {
            String key = structureRow.path("dataDefinitionKey").asText("");
            if (isBlank(key)) {
                continue;
            }
            JsonNode exported = fetchStructureByKey(root, token, normalizedSite, key, true);
            JsonNode normalized = normalizeStructureExport(root, exported);
            Path target = outputDir.resolve(key + ".json");
            processed++;
            if (checkOnly) {
                if (structureDiffers(root, normalized, target)) {
                    System.out.println("DIFF " + target.getFileName());
                    diffs++;
                }
                continue;
            }
            writePrettyJson(root, normalized, target);
        }

        if (checkOnly) {
            System.out.printf("CHECK_ONLY site=%s diffs=%d%n", normalizedSite, diffs);
        } else {
            if (processed == 0 && Files.exists(outputDir)) {
                deleteRecursively(outputDir);
            }
            System.out.printf("EXPORTED site=%s count=%d dir=%s%n", normalizedSite, processed, outputDir);
        }
        return new ExportStats(processed, diffs, 0);
    }

    private static ExportStats exportTemplatesForSite(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String normalizedSite,
        Path targetDir,
        boolean continueOnError
    ) throws Exception {
        int exported = 0;
        int failed = 0;
        JsonNode siteNode = resolveSite(root, accessToken, normalizedSite);
        long siteId = siteNode.path("id").asLong(-1L);
        long companyId = resolveCompanyId(root, accessToken, siteNode);
        JsonNode templates = listJournalTemplates(root, accessToken, siteId, companyId);

        if (templates.isArray()) {
            for (JsonNode row : templates) {
                try {
                    String templateId = row.path("templateId").asText("");
                    String templateName = resolveTemplateExportName(row);
                    String script = row.path("script").asText("");
                    if (isBlank(script) && !isBlank(templateId)) {
                        JsonNode full = getJson(
                            root,
                            accessToken,
                            "/api/jsonws/ddm.ddmtemplate/get-template?templateId=" + urlEncode(templateId),
                            "template-get"
                        );
                        script = full.path("script").asText("");
                    }
                    if (isBlank(script)) {
                        throw new IllegalStateException("templateScript vacio");
                    }
                    Path target = targetDir.resolve(sanitizeFileToken(templateName) + ".ftl");
                    writeTextFile(target, script);
                    exported++;
                }
                catch (Exception itemEx) {
                    failed++;
                    if (!continueOnError) {
                        throw itemEx;
                    }
                    System.err.println("[ERROR] template export: " + itemEx.getMessage());
                }
            }
        }
        if (exported == 0 && failed == 0 && Files.exists(targetDir)) {
            deleteRecursively(targetDir);
        }
        System.out.printf("EXPORTED site=%s exported=%d failed=%d dir=%s%n", normalizedSite, exported, failed, targetDir);
        return new ExportStats(exported, 0, failed);
    }

    private static ExportStats exportAdtsForSite(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String normalizedSite,
        String widgetFilter,
        Path targetDir,
        boolean continueOnError
    ) throws Exception {
        int exported = 0;
        int failed = 0;
        List<JsonNode> adts = listAdts(root, accessToken, normalizedSite, widgetFilter, true);
        for (JsonNode adt : adts) {
            try {
                String widget = normalizeWidgetType(adt.path("widgetType").asText(""));
                if (isBlank(widget)) {
                    throw new IllegalStateException("widgetType vacio");
                }
                String name = resolveAdtExportName(adt);
                String script = adt.path("script").asText("");
                if (isBlank(script)) {
                    throw new IllegalStateException("script vacio para ADT " + name);
                }
                String widgetDir = widgetTypeDir(widget);
                Path target = targetDir.resolve(widgetDir).resolve(sanitizeFileToken(name) + ".ftl");
                writeTextFile(target, script);
                exported++;
            }
            catch (Exception itemEx) {
                failed++;
                if (!continueOnError) {
                    throw itemEx;
                }
                System.err.println("[ERROR] adt export: " + itemEx.getMessage());
            }
        }
        if (exported == 0 && failed == 0 && Files.exists(targetDir)) {
            deleteRecursively(targetDir);
        }
        System.out.printf("EXPORTED site=%s exported=%d failed=%d dir=%s%n", normalizedSite, exported, failed, targetDir);
        return new ExportStats(exported, 0, failed);
    }

    private static MigrationDescriptor readMigrationDescriptor(LiferayCLIMain.RootCommand root, String migrationFile) throws Exception {
        Path descriptorPath = resolveExistingFile(repoRoot(root), migrationFile, root);
        JsonNode descriptorNode = root.mapper().readTree(Files.readString(descriptorPath));
        return parseMigrationDescriptor(root, descriptorNode);
    }

    private static MigrationDescriptor parseMigrationDescriptor(LiferayCLIMain.RootCommand root, JsonNode descriptorNode) {
        String site = normalizeSite(descriptorNode.path("site").asText("/global"));
        String structureKey = descriptorNode.path("structureKey").asText("");
        if (structureKey.isBlank()) {
            throw new IllegalArgumentException("Descriptor inválido: falta structureKey");
        }
        String structureFile = descriptorNode.path("structureFile").asText("");
        if (structureFile.isBlank()) {
            structureFile = null;
        }
        JsonNode migrationNode = descriptorNode.path("migration");
        String phase = migrationNode.path("phase").asText("post");
        boolean allowBreakingChange = descriptorNode.path("allowBreakingChange").asBoolean(false);

        JsonNode planNode = migrationNode.path("plan");
        if (planNode.isMissingNode() || planNode.isNull()) {
            planNode = descriptorNode.path("plan");
        }
        if (planNode.isMissingNode() || planNode.isNull()) {
            throw new IllegalArgumentException("Descriptor inválido: falta migration.plan o plan");
        }
        JsonNode effectivePlan = planNode.deepCopy();
        if (!effectivePlan.has("mappings") && effectivePlan.path("plan").has("mappings")) {
            effectivePlan = effectivePlan.path("plan");
        }
        if (!effectivePlan.path("mappings").isArray() || effectivePlan.path("mappings").isEmpty()) {
            throw new IllegalArgumentException("Descriptor inválido: mappings[] vacío");
        }

        return new MigrationDescriptor(site, structureKey, structureFile, phase, allowBreakingChange, effectivePlan);
    }

    private static List<String> descriptorTemplateNames(JsonNode descriptorNode) {
        JsonNode templatesNode = descriptorNode.path("templates");
        if (templatesNode.isArray()) {
            return parseStringList(templatesNode);
        }
        if (templatesNode.isObject()) {
            return parseStringList(templatesNode.path("items"));
        }
        return List.of();
    }

    private static CleanupDescriptor resolveCleanupDescriptor(
        LiferayCLIMain.RootCommand root,
        JsonNode descriptorNode,
        Path descriptorPath,
        String cleanupFileOpt
    ) throws Exception {
        if (!isBlank(cleanupFileOpt)) {
            Path cleanupPath = resolveExistingFile(repoRoot(root), cleanupFileOpt, root);
            return new CleanupDescriptor(cleanupPath.toString(), false);
        }

        String cleanupDescriptor = descriptorNode.path("cleanupDescriptor").asText("");
        if (!cleanupDescriptor.isBlank()) {
            Path cleanupPath = resolveExistingFile(repoRoot(root), cleanupDescriptor, root);
            return new CleanupDescriptor(cleanupPath.toString(), false);
        }

        JsonNode cleanupNode = descriptorNode.path("cleanup");
        if (!cleanupNode.isObject()) {
            throw new IllegalArgumentException(
                "Falta descriptor de cleanup. Usa --cleanup-file o define cleanupDescriptor/cleanup en el descriptor"
            );
        }

        ObjectNode generated = root.mapper().createObjectNode();
        generated.put("site", descriptorNode.path("site").asText("/global"));
        generated.put("structureKey", descriptorNode.path("structureKey").asText(""));
        generated.put("allowBreakingChange", descriptorNode.path("allowBreakingChange").asBoolean(false));

        String cleanupStructureFile = cleanupNode.path("structureFile").asText("");
        if (cleanupStructureFile.isBlank()) {
            cleanupStructureFile = descriptorNode.path("structureFile").asText("");
        }
        if (!cleanupStructureFile.isBlank()) {
            generated.put("structureFile", cleanupStructureFile);
        }

        if (cleanupNode.path("plan").isObject()) {
            generated.set("migration", cleanupNode.deepCopy());
        }
        else {
            JsonNode sourcePlan = descriptorNode.path("plan");
            if (!sourcePlan.isObject()) {
                sourcePlan = descriptorNode.path("migration").path("plan");
            }
            if (!sourcePlan.isObject()) {
                sourcePlan = root.mapper().createObjectNode();
            }
            ObjectNode mergedPlan = sourcePlan.deepCopy();
            mergedPlan.put("cleanupSource", true);
            if (cleanupNode.has("persistThreads")) {
                mergedPlan.set("persistThreads", cleanupNode.path("persistThreads").deepCopy());
            }
            if (cleanupNode.has("patchDelayMs")) {
                mergedPlan.set("patchDelayMs", cleanupNode.path("patchDelayMs").deepCopy());
            }
            ObjectNode migration = root.mapper().createObjectNode();
            migration.put("phase", "pre");
            migration.set("plan", mergedPlan);
            generated.set("migration", migration);
        }

        Path generatedPath = Files.createTempFile("ub-structure-cleanup-", ".json");
        root.mapper().writeValue(generatedPath.toFile(), generated);
        return new CleanupDescriptor(generatedPath.toString(), true);
    }

    private static MigrationStats runStructureMigration(
        LiferayCLIMain.RootCommand root,
        String site,
        String structureKey,
        String migrationPlanPath,
        boolean dryRun,
        boolean cleanupSource
    ) throws Exception {
        var token = token(root);
        JsonNode structure = fetchStructureByKey(root, token, normalizeSite(site), structureKey);
        String structureId = structure.path("id").asText("");
        if (isBlank(structureId)) {
            throw new IllegalStateException("No se pudo resolver estructura " + structureKey + " en " + site);
        }

        Path planFile = resolveExistingFile(repoRoot(root), migrationPlanPath, root);
        JsonNode planRoot = root.mapper().readTree(Files.readString(planFile));
        JsonNode effectivePlan = planRoot.has("plan") ? planRoot.path("plan") : planRoot;
        List<MappingRule> mappings = parseMappings(effectivePlan.path("mappings"));
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("Migration plan invalido: falta mappings[]");
        }
        List<Long> rootFolderIds = parseLongList(effectivePlan.path("rootFolderIds"));
        List<String> articleIds = parseStringList(effectivePlan.path("articleIds"));
        String accessToken = token.accessToken();
        long siteId = resolveSiteId(root, accessToken, normalizeSite(site));
        Set<Long> scopedFolderIds = null;
        List<JsonNode> allItems = List.of();
        boolean listed = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                scopedFolderIds = expandRootFolderScope(root, accessToken, siteId, rootFolderIds);
                if (scopedFolderIds == null || scopedFolderIds.isEmpty()) {
                    allItems = listStructureContents(root, accessToken, structureId);
                }
                else {
                    allItems = listStructureContentsByFolders(root, accessToken, scopedFolderIds, structureId);
                }
                listed = true;
                break;
            }
            catch (Exception ex) {
                if (attempt == 0 && isHttp401(ex)) {
                    accessToken = token(root).accessToken();
                    continue;
                }
                throw ex;
            }
        }
        if (!listed) {
            throw new IllegalStateException("No se pudo listar contenidos para migracion de estructura " + structureKey);
        }

        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode item : allItems) {
            long folderId = item.path("structuredContentFolderId").asLong(Long.MIN_VALUE);
            String key = item.path("key").asText("");
            boolean folderOk = scopedFolderIds == null || scopedFolderIds.contains(folderId);
            boolean articleOk = articleIds.isEmpty() || articleIds.contains(key);
            if (folderOk && articleOk) {
                selected.add(item);
            }
        }

        System.out.printf(
            "[migration] start site=%s structure=%s structureId=%s listed=%d selected=%d mappings=%d dryRun=%s cleanupSource=%s%n",
            site,
            structureKey,
            structureId,
            allItems.size(),
            selected.size(),
            mappings.size(),
            dryRun,
            cleanupSource
        );

        MigrationStats stats = new MigrationStats();
        long migrationStartMs = System.currentTimeMillis();
        for (JsonNode item : selected) {
            stats.scanned++;
            try {
                String contentId = item.path("id").asText("");
                String contentKey = item.path("key").asText(contentId);
                boolean migratedOrChecked = false;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        ObjectNode before = fetchStructuredContentForMigration(root, accessToken, contentId).deepCopy();
                        ObjectNode after = before.deepCopy();
                        boolean changed = applyMappings(after, mappings, cleanupSource);
                        if (!changed || contentFieldsEqual(before, after)) {
                            stats.unchanged++;
                            migratedOrChecked = true;
                            break;
                        }
                        if (dryRun) {
                            stats.migrated++;
                            migratedOrChecked = true;
                            break;
                        }

                        ObjectNode updatePayload = root.mapper().createObjectNode();
                        copyIfPresent(after, updatePayload, "contentStructureId");
                        copyIfPresent(after, updatePayload, "structuredContentFolderId");
                        copyIfPresent(after, updatePayload, "friendlyUrlPath");
                        copyIfPresent(after, updatePayload, "title");
                        copyIfPresent(after, updatePayload, "contentFields");

                        putJson(
                            root,
                            accessToken,
                            "/o/headless-delivery/v1.0/structured-contents/" + contentId,
                            root.mapper().writeValueAsString(updatePayload),
                            "structure-migrate update"
                        );
                        stats.migrated++;
                        migratedOrChecked = true;
                        break;
                    }
                    catch (Exception ex) {
                        if (attempt == 0 && isHttp401(ex)) {
                            accessToken = token(root).accessToken();
                            continue;
                        }
                        throw ex;
                    }
                }
                if (!migratedOrChecked) {
                    throw new IllegalStateException("No se pudo procesar contenido " + contentKey);
                }
                logMigrationProgress(stats, selected.size(), effectivePlan, migrationStartMs);
            }
            catch (Exception ex) {
                stats.failed++;
                System.err.println("[migration] error key=" + item.path("key").asText("") + " msg=" + ex.getMessage());
                logMigrationProgress(stats, selected.size(), effectivePlan, migrationStartMs);
            }
        }
        long migrationElapsedMs = System.currentTimeMillis() - migrationStartMs;
        System.out.printf(
            "[migration] done scanned=%d migrated=%d unchanged=%d failed=%d elapsed=%s%n",
            stats.scanned,
            stats.migrated,
            stats.unchanged,
            stats.failed,
            formatDuration(migrationElapsedMs)
        );
        return stats;
    }

    private static boolean isHttp401(Exception ex) {
        String message = ex.getMessage();
        return message != null && message.contains("status=401");
    }

    private static Set<Long> expandRootFolderScope(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long groupId,
        List<Long> rootFolderIds
    ) throws Exception {
        if (rootFolderIds.isEmpty()) {
            return null;
        }
        Deque<Long> queue = new ArrayDeque<>(rootFolderIds);
        Set<Long> scopedIds = new HashSet<>();
        while (!queue.isEmpty()) {
            long folderId = queue.removeFirst();
            if (!scopedIds.add(folderId)) {
                continue;
            }
            for (long child : listChildFolderIds(root, accessToken, groupId, folderId)) {
                if (!scopedIds.contains(child)) {
                    queue.addLast(child);
                }
            }
        }
        return scopedIds;
    }

    private static List<Long> listChildFolderIds(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long groupId,
        long parentFolderId
    ) throws Exception {
        JsonNode payload = getJson(
            root,
            accessToken,
            "/api/jsonws/journal.journalfolder/get-folders?groupId=" + groupId + "&parentFolderId=" + parentFolderId,
            "journal-folder-get-folders"
        );
        List<Long> folderIds = new ArrayList<>();
        if (!payload.isArray()) {
            return folderIds;
        }
        for (JsonNode row : payload) {
            long folderId = row.path("folderId").asLong(-1L);
            if (folderId > 0) {
                folderIds.add(folderId);
            }
        }
        return folderIds;
    }

    private static MigrationScope resolveMigrationScope(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long siteId,
        String structureId,
        Set<Long> scopedFolderIds
    ) throws Exception {
        List<JsonNode> rows = listJournalArticlesByStructure(root, accessToken, siteId, structureId);
        Map<String, JsonNode> latestByArticleId = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String articleId = row.path("articleId").asText("");
            if (articleId.isBlank()) {
                continue;
            }
            JsonNode existing = latestByArticleId.get(articleId);
            if (existing == null || compareVersion(row.path("version"), existing.path("version")) > 0) {
                latestByArticleId.put(articleId, row);
            }
        }
        Set<String> articleKeys = new HashSet<>();
        Set<Long> resourcePrimKeys = new HashSet<>();
        for (JsonNode row : latestByArticleId.values()) {
            long folderId = row.path("folderId").asLong(Long.MIN_VALUE);
            String treePath = row.path("treePath").asText("");
            boolean inScope = scopedFolderIds.contains(folderId) || containsAnyFolderInTree(treePath, scopedFolderIds);
            if (!inScope) {
                continue;
            }
            String articleId = row.path("articleId").asText("");
            if (!articleId.isBlank()) {
                articleKeys.add(articleId);
            }
            long resourcePrimKey = row.path("resourcePrimKey").asLong(Long.MIN_VALUE);
            if (resourcePrimKey > 0) {
                resourcePrimKeys.add(resourcePrimKey);
            }
        }
        return new MigrationScope(articleKeys, resourcePrimKeys);
    }

    private static int compareVersion(JsonNode left, JsonNode right) {
        String l = left == null ? "0" : left.asText("0");
        String r = right == null ? "0" : right.asText("0");
        try {
            return new BigDecimal(l).compareTo(new BigDecimal(r));
        }
        catch (NumberFormatException ignored) {
            return l.compareTo(r);
        }
    }

    private static boolean containsAnyFolderInTree(String treePath, Set<Long> folderIds) {
        if (treePath == null || treePath.isBlank() || folderIds.isEmpty()) {
            return false;
        }
        for (Long folderId : folderIds) {
            if (folderId != null && treePath.contains("/" + folderId + "/")) {
                return true;
            }
        }
        return false;
    }

    private static List<JsonNode> listJournalArticlesByStructure(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long siteId,
        String structureId
    ) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        long structureIdLong = Long.parseLong(structureId);
        int start = 0;
        int pageSize = 200;
        while (true) {
            ObjectNode args = root.mapper().createObjectNode();
            args.put("groupId", siteId);
            args.put("ddmStructureId", structureIdLong);
            args.put("status", -1);
            args.put("start", start);
            args.put("end", start + pageSize);
            args.putNull("orderByComparator");
            ObjectNode cmd = root.mapper().createObjectNode();
            cmd.set("/journal.journalarticle/get-articles-by-structure-id", args);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("cmd", root.mapper().writeValueAsString(cmd));
            JsonNode page = postForm(
                root,
                accessToken,
                "/api/jsonws/invoke",
                form,
                "journal-article-get-by-structure"
            );
            if (!page.isArray() || page.isEmpty()) {
                break;
            }
            page.forEach(rows::add);
            if (page.size() < pageSize) {
                break;
            }
            start += pageSize;
        }
        return rows;
    }

    private static void logMigrationProgress(
        MigrationStats stats,
        int totalSelected,
        JsonNode effectivePlan,
        long migrationStartMs
    ) {
        int every = Math.max(1, effectivePlan.path("progressEvery").asInt(25));
        if (stats.scanned % every != 0 && stats.scanned != totalSelected) {
            return;
        }
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - migrationStartMs);
        long ratePerMinute = (stats.scanned * 60_000L) / elapsedMs;
        long remaining = Math.max(0, totalSelected - stats.scanned);
        long etaMs = ratePerMinute <= 0 ? -1L : (remaining * 60_000L) / ratePerMinute;
        System.out.printf(
            "[migration] progress %d/%d migrated=%d unchanged=%d failed=%d elapsed=%s rate=%d/min eta=%s%n",
            stats.scanned,
            totalSelected,
            stats.migrated,
            stats.unchanged,
            stats.failed,
            formatDuration(elapsedMs),
            ratePerMinute,
            etaMs < 0 ? "n/a" : formatDuration(etaMs)
        );
    }

    private static boolean applyMappings(ObjectNode item, List<MappingRule> mappings, boolean cleanupSource) {
        boolean changed = false;
        for (MappingRule mapping : mappings) {
            JsonNode value = firstSourceValue(item.path("contentFields"), mapping.source);
            if (value == null || isEmptyValue(value)) {
                continue;
            }
            if (setTargetValue(item, mapping.target, value.deepCopy())) {
                changed = true;
            }
            if (cleanupSource || mapping.cleanupSource) {
                if (cleanupSourceField(item, mapping.source)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static JsonNode firstSourceValue(JsonNode contentFields, String source) {
        if (!contentFields.isArray()) {
            return null;
        }
        for (JsonNode field : contentFields) {
            JsonNode fieldName = field.path("name");
            if (source.equals(fieldName.asText(""))) {
                JsonNode value = field.path("contentFieldValue");
                if (!isEmptyValue(value)) {
                    return value;
                }
            }
            JsonNode nested = firstSourceValue(field.path("nestedContentFields"), source);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static boolean setTargetValue(ObjectNode item, String target, JsonNode value) {
        if (target.contains("[].")) {
            String[] parts = target.split("\\[\\]\\.", 2);
            if (parts.length != 2) {
                return false;
            }
            return setFieldsetTarget(item, parts[0], parts[1], value);
        }
        return setSimpleTarget(item, target, value);
    }

    private static boolean setSimpleTarget(ObjectNode item, String target, JsonNode value) {
        ArrayNode fields = ensureArray(item, "contentFields");
        boolean changed = false;
        for (JsonNode node : fields) {
            if (target.equals(node.path("name").asText(""))) {
                ObjectNode field = (ObjectNode) node;
                JsonNode current = field.path("contentFieldValue");
                if (isEmptyValue(current)) {
                    field.set("contentFieldValue", value.deepCopy());
                    return true;
                }
                return false;
            }
        }
        ObjectNode newField = item.objectNode();
        newField.put("name", target);
        newField.set("contentFieldValue", value.deepCopy());
        fields.add(newField);
        changed = true;
        return changed;
    }

    private static boolean setFieldsetTarget(ObjectNode item, String base, String child, JsonNode value) {
        ArrayNode fields = ensureArray(item, "contentFields");
        for (JsonNode node : fields) {
            if (base.equals(node.path("name").asText(""))) {
                ObjectNode fieldset = (ObjectNode) node;
                ArrayNode nested = ensureArray(fieldset, "nestedContentFields");
                for (JsonNode nestedNode : nested) {
                    if (child.equals(nestedNode.path("name").asText(""))) {
                        ObjectNode nestedField = (ObjectNode) nestedNode;
                        JsonNode current = nestedField.path("contentFieldValue");
                        if (isEmptyValue(current)) {
                            nestedField.set("contentFieldValue", value.deepCopy());
                            return true;
                        }
                        return false;
                    }
                }
                ObjectNode createdNested = item.objectNode();
                createdNested.put("name", child);
                createdNested.set("contentFieldValue", value.deepCopy());
                nested.add(createdNested);
                return true;
            }
        }

        ObjectNode newFieldset = item.objectNode();
        newFieldset.put("name", base);
        newFieldset.set("contentFieldValue", item.objectNode());
        ArrayNode nested = item.arrayNode();
        ObjectNode createdNested = item.objectNode();
        createdNested.put("name", child);
        createdNested.set("contentFieldValue", value.deepCopy());
        nested.add(createdNested);
        newFieldset.set("nestedContentFields", nested);
        fields.add(newFieldset);
        return true;
    }

    private static boolean cleanupSourceField(ObjectNode item, String source) {
        return cleanupSourceInArray(ensureArray(item, "contentFields"), source);
    }

    private static boolean cleanupSourceInArray(ArrayNode fields, String source) {
        boolean changed = false;
        for (JsonNode node : fields) {
            ObjectNode field = (ObjectNode) node;
            if (source.equals(field.path("name").asText(""))) {
                ObjectNode cleaned = field.objectNode();
                cleaned.put("data", "");
                field.set("contentFieldValue", cleaned);
                changed = true;
            }
            ArrayNode nested = ensureArray(field, "nestedContentFields");
            if (cleanupSourceInArray(nested, source)) {
                changed = true;
            }
        }
        return changed;
    }

    private static boolean contentFieldsEqual(ObjectNode left, ObjectNode right) {
        JsonNode leftFields = left.path("contentFields");
        JsonNode rightFields = right.path("contentFields");
        return Objects.equals(leftFields, rightFields);
    }

    private static Set<String> collectFieldReferencesFromDefinition(JsonNode definition) {
        Set<String> refs = new HashSet<>();
        collectFieldReferencesRecursive(definition.path("dataDefinitionFields"), refs);
        return refs;
    }

    private static void collectFieldReferencesRecursive(JsonNode fields, Set<String> refs) {
        if (!fields.isArray()) {
            return;
        }
        for (JsonNode field : fields) {
            String name = field.path("name").asText("").trim();
            if (!name.isBlank()) {
                refs.add(name);
            }
            String fieldReference = field.path("customProperties").path("fieldReference").asText("").trim();
            if (!fieldReference.isBlank()) {
                refs.add(fieldReference);
            }
            collectFieldReferencesRecursive(field.path("nestedDataDefinitionFields"), refs);
        }
    }

    private static JsonNode buildTransitionPayload(JsonNode runtimeDefinition, JsonNode finalPayload) {
        ObjectNode transition = finalPayload.deepCopy();
        JsonNode runtimeFieldsNode = runtimeDefinition.path("dataDefinitionFields");
        JsonNode finalFieldsNode = transition.path("dataDefinitionFields");
        if (!runtimeFieldsNode.isArray() || !finalFieldsNode.isArray()) {
            return transition;
        }

        ArrayNode runtimeFields = ((ArrayNode) runtimeFieldsNode).deepCopy();
        ArrayNode finalFields = (ArrayNode) finalFieldsNode;
        Set<String> runtimeIds = new HashSet<>();
        for (JsonNode field : runtimeFields) {
            runtimeIds.add(fieldIdentity(field));
        }
        for (JsonNode field : finalFields) {
            String identity = fieldIdentity(field);
            if (!runtimeIds.contains(identity)) {
                runtimeFields.add(field.deepCopy());
                runtimeIds.add(identity);
            }
        }
        transition.set("dataDefinitionFields", runtimeFields);
        return transition;
    }

    private static String fieldIdentity(JsonNode field) {
        if (field == null || field.isMissingNode() || field.isNull()) {
            return "";
        }
        String fieldReference = field.path("customProperties").path("fieldReference").asText("").trim();
        if (!fieldReference.isBlank()) {
            return fieldReference;
        }
        return field.path("name").asText("").trim();
    }

    private static ArrayNode ensureArray(ObjectNode node, String field) {
        JsonNode current = node.get(field);
        if (current instanceof ArrayNode array) {
            return array;
        }
        ArrayNode created = node.arrayNode();
        node.set(field, created);
        return created;
    }

    private static boolean isEmptyValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return true;
        }
        if (value.isObject()) {
            if (value.size() == 0) {
                return true;
            }
            if (value.size() == 1 && value.has("data")) {
                return value.path("data").asText("").isBlank();
            }
        }
        return false;
    }

    private static void copyIfPresent(ObjectNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) {
            target.set(field, value.deepCopy());
        }
    }

    private static List<JsonNode> listStructureContents(LiferayCLIMain.RootCommand root, String accessToken, String structureId)
        throws Exception {
        List<JsonNode> items = new ArrayList<>();
        int page = 1;
        int lastPage = 1;
        long listStartMs = System.currentTimeMillis();
        System.out.printf("[migration] listado start structureId=%s%n", structureId);
        String fields = urlEncode("id,key,contentStructureId,structuredContentFolderId,friendlyUrlPath,title");
        do {
            JsonNode pagePayload = getJson(
                root,
                accessToken,
                "/o/headless-delivery/v1.0/content-structures/" + structureId +
                    "/structured-contents?page=" + page + "&pageSize=200&fields=" + fields,
                "structure-migrate list"
            );
            JsonNode pageItems = pagePayload.path("items");
            if (pageItems.isArray()) {
                pageItems.forEach(items::add);
            }
            lastPage = pagePayload.path("lastPage").asInt(1);
            if (page == 1 || page == lastPage || page % 10 == 0) {
                long elapsedMs = Math.max(1L, System.currentTimeMillis() - listStartMs);
                System.out.printf(
                    "[migration] listado progress page=%d/%d acumulado=%d elapsed=%s%n",
                    page,
                    lastPage,
                    items.size(),
                    formatDuration(elapsedMs)
                );
            }
            page++;
        }
        while (page <= lastPage);
        System.out.printf(
            "[migration] listado done total=%d elapsed=%s%n",
            items.size(),
            formatDuration(System.currentTimeMillis() - listStartMs)
        );
        return items;
    }

    private static List<JsonNode> listStructureContentsByFolders(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        Set<Long> folderIds,
        String structureId
    ) throws Exception {
        Map<Long, JsonNode> dedupedById = new LinkedHashMap<>();
        List<Long> sortedFolders = new ArrayList<>(folderIds);
        sortedFolders.sort(Comparator.naturalOrder());
        String fields = urlEncode("id,key,contentStructureId,structuredContentFolderId,friendlyUrlPath,title");
        long startMs = System.currentTimeMillis();
        int doneFolders = 0;

        for (Long folderId : sortedFolders) {
            if (folderId == null || folderId <= 0) {
                continue;
            }
            int page = 1;
            int lastPage = 1;
            do {
                JsonNode pagePayload = getJson(
                    root,
                    accessToken,
                    "/o/headless-delivery/v1.0/structured-content-folders/" + folderId
                        + "/structured-contents?page=" + page
                        + "&pageSize=200&fields=" + fields,
                    "structure-migrate list-by-folder"
                );
                JsonNode pageItems = pagePayload.path("items");
                if (pageItems.isArray()) {
                    for (JsonNode item : pageItems) {
                        String itemStructureId = item.path("contentStructureId").asText("");
                        if (!Objects.equals(itemStructureId, structureId)) {
                            continue;
                        }
                        long id = item.path("id").asLong(-1L);
                        if (id > 0) {
                            dedupedById.putIfAbsent(id, item);
                        }
                    }
                }
                lastPage = pagePayload.path("lastPage").asInt(1);
                page++;
            }
            while (page <= lastPage);

            doneFolders++;
            if (doneFolders == 1 || doneFolders == sortedFolders.size() || doneFolders % 10 == 0) {
                long elapsedMs = Math.max(1L, System.currentTimeMillis() - startMs);
                System.out.printf(
                    "[migration] listado-folder progress folder=%d/%d acumulado=%d elapsed=%s%n",
                    doneFolders,
                    sortedFolders.size(),
                    dedupedById.size(),
                    formatDuration(elapsedMs)
                );
            }
        }

        System.out.printf(
            "[migration] listado-folder done total=%d folders=%d elapsed=%s%n",
            dedupedById.size(),
            sortedFolders.size(),
            formatDuration(System.currentTimeMillis() - startMs)
        );
        return new ArrayList<>(dedupedById.values());
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%dm %02ds", minutes, seconds);
    }

    private static ObjectNode fetchStructuredContentForMigration(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String contentId
    ) throws Exception {
        String fields = urlEncode("id,key,contentStructureId,structuredContentFolderId,friendlyUrlPath,title,contentFields");
        JsonNode payload = getJson(
            root,
            accessToken,
            "/o/headless-delivery/v1.0/structured-contents/" + urlEncode(contentId) + "?fields=" + fields,
            "structure-migrate get"
        );
        return payload.deepCopy();
    }

    private static List<MappingRule> parseMappings(JsonNode node) {
        List<MappingRule> mappings = new ArrayList<>();
        if (!node.isArray()) {
            return mappings;
        }
        for (JsonNode row : node) {
            String source = row.path("source").asText(row.path("from").asText(""));
            String target = row.path("target").asText(row.path("to").asText(""));
            boolean cleanupSource = row.path("cleanupSource").asBoolean(false);
            if (!source.isBlank() && !target.isBlank()) {
                mappings.add(new MappingRule(source, target, cleanupSource));
            }
        }
        return mappings;
    }

    private static List<Long> parseLongList(JsonNode node) {
        List<Long> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item.canConvertToLong()) {
                values.add(item.asLong());
            }
        }
        return values;
    }

    private static List<String> parseStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static List<JsonNode> listAdts(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String site,
        String widgetType,
        boolean includeScript
    ) throws Exception {
        JsonNode siteNode = resolveSite(root, accessToken, site);
        long siteId = siteNode.path("id").asLong(-1L);
        long companyId = resolveCompanyId(root, accessToken, siteNode);
        if (siteId <= 0 || companyId <= 0) {
            throw new IllegalStateException("site sin id/companyId valido: " + site);
        }

        long resourceClassNameId = fetchClassNameId(root, accessToken, ADT_RESOURCE_CLASS_NAME);
        List<JsonNode> rows = new ArrayList<>();

        for (Map.Entry<String, String> entry : ADT_CLASS_BY_WIDGET.entrySet()) {
            String currentWidgetType = entry.getKey();
            if (!isBlank(widgetType) && !Objects.equals(widgetType, currentWidgetType)) {
                continue;
            }
            long classNameId = fetchClassNameId(root, accessToken, entry.getValue());
            JsonNode templates = getJson(
                root,
                accessToken,
                "/api/jsonws/ddm.ddmtemplate/get-templates?companyId=" + companyId +
                    "&groupId=" + siteId +
                    "&classNameId=" + classNameId +
                    "&resourceClassNameId=" + resourceClassNameId +
                    "&status=0",
                "adt-list"
            );
            if (!templates.isArray()) {
                continue;
            }
            for (JsonNode item : templates) {
                ObjectNode row = root.mapper().createObjectNode();
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

        return rows;
    }

    private static JsonNode listJournalTemplates(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long siteId,
        long companyId
    ) throws Exception {
        if (siteId <= 0 || companyId <= 0) {
            throw new IllegalStateException("site sin id/companyId valido para templates");
        }
        JsonNode items = fetchPagedItems(
            root,
            accessToken,
            "/o/headless-delivery/v1.0/sites/" + siteId + "/content-templates",
            200
        );
        ArrayNode mapped = root.mapper().createArrayNode();
        if (!items.isArray()) {
            return mapped;
        }
        for (JsonNode row : items) {
            ObjectNode item = root.mapper().createObjectNode();
            String key = row.path("id").asText("");
            String name = row.path("name").asText("");
            String script = row.path("templateScript").asText("");
            String structureId = row.path("contentStructureId").asText("");
            item.put("templateId", key);
            item.put("templateKey", key);
            item.put("externalReferenceCode", row.path("externalReferenceCode").asText(key));
            item.put("nameCurrentValue", name);
            item.put("name", name);
            item.put("script", script);
            item.put("classPK", structureId);
            mapped.add(item);
        }
        return mapped;
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
        JsonNode companies = getJson(root, accessToken, "/api/jsonws/company/get-companies", "company-list");
        if (companies.isArray() && !companies.isEmpty()) {
            return companies.get(0).path("companyId").asLong(-1L);
        }
        return -1L;
    }

    private static SyncContext newSyncContext(String status, String id, String name, String extra) {
        return new SyncContext(status, id, name, extra);
    }

    private static Path resolveStructureBaseDir(LiferayCLIMain.RootCommand root, String site, String dirOpt) {
        if (!isBlank(dirOpt)) {
            Path dir = Path.of(dirOpt);
            if (!dir.isAbsolute()) {
                dir = repoRoot(root).resolve(dir);
            }
            return dir.normalize();
        }
        String sitePath = normalizeSite(site).replaceFirst("^/", "");
        String configPath = root.settings().paths().getOrDefault("structures", "liferay/resources/journal/structures");
        return repoRoot(root).resolve(configPath).resolve(sitePath).normalize();
    }

    private static Path resolveStructureFile(LiferayCLIMain.RootCommand root, String name, String fileOpt) {
        if (!isBlank(fileOpt)) {
            Path resolved = resolveExistingFile(repoRoot(root), fileOpt, root);
            return resolved;
        }
        String configPath = root.settings().paths().getOrDefault("structures", "liferay/resources/journal/structures");
        Path structuresDir = repoRoot(root).resolve(configPath).normalize();
        try (Stream<Path> stream = Files.walk(structuresDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals(name + ".json"))
                .sorted(Comparator.comparing(Path::toString))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Structure file no encontrado para " + name + " en " + structuresDir + " (usa --file)"));
        }
        catch (IOException e) {
            throw new IllegalStateException("No se pudo buscar estructura", e);
        }
    }

    private static Path resolveTemplateFile(LiferayCLIMain.RootCommand root, String site, String name, String fileOpt) {
        if (!isBlank(fileOpt)) {
            return resolveExistingFile(repoRoot(root), fileOpt, root);
        }
        String configPath = root.settings().paths().getOrDefault("templates", "liferay/resources/journal/templates");
        Path templatesDir = repoRoot(root).resolve(configPath).normalize();
        String normalizedSite = normalizeSite(site);
        String siteToken = normalizedSite.replaceFirst("^/", "");
        if (siteToken.isBlank()) {
            siteToken = "global";
        }

        List<Path> candidates = new ArrayList<>();
        candidates.add(templatesDir.resolve(siteToken).resolve(name + ".ftl").normalize());
        if (!"global".equals(siteToken)) {
            candidates.add(templatesDir.resolve("global").resolve(name + ".ftl").normalize());
        }
        candidates.add(templatesDir.resolve(name + ".ftl").normalize());

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        try (Stream<Path> stream = Files.walk(templatesDir)) {
            List<Path> matches = stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals(name + ".ftl"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                throw new IllegalStateException(
                    "Template file ambiguo para " + name + ": " + matches.stream().map(Path::toString).toList()
                );
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("No se pudo buscar template file para " + name, e);
        }

        throw new IllegalStateException(
            "Template file no encontrado para " + name + " en site " + normalizedSite + " en " + templatesDir + " (usa --file)"
        );
    }

    private static Path resolveAdtFile(LiferayCLIMain.RootCommand root, String name, String widgetType, String fileOpt) {
        if (!isBlank(fileOpt)) {
            return resolveExistingFile(repoRoot(root), fileOpt, root);
        }
        if (isBlank(name) || isBlank(widgetType)) {
            throw new IllegalArgumentException(
                "ADT requiere --file o (--name y --widget-type). " +
                    "Tip: usa 'resource resolve-adt --display-style ddmTemplate_<ID>' para resolverlo desde inventory page."
            );
        }
        String widgetDir = widgetTypeDir(normalizeWidgetType(widgetType));
        String configPath = root.settings().paths().getOrDefault("adts", "liferay/resources/templates/application_display");
        Path adtsDir = repoRoot(root).resolve(configPath).normalize();
        Path defaultFile = adtsDir.resolve(widgetDir).resolve(name + ".ftl");
        if (Files.isRegularFile(defaultFile)) {
            return defaultFile;
        }
        try (Stream<Path> stream = Files.walk(adtsDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith("/" + widgetDir + "/" + name + ".ftl"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("ADT file no encontrado para " + name + " (" + widgetType + ") en " + adtsDir));
        }
        catch (IOException e) {
            throw new IllegalStateException("No se pudo buscar ADT", e);
        }
    }

    private static String inferAdtWidgetFromPath(Path filePath) {
        String parent = Optional.ofNullable(filePath.getParent())
            .map(Path::getFileName)
            .map(Path::toString)
            .orElse("");
        String widget = ADT_WIDGET_BY_DIR.get(parent);
        if (isBlank(widget)) {
            throw new IllegalStateException("No se pudo inferir widget-type ADT desde ruta: " + filePath);
        }
        return widget;
    }

    private static String normalizeSite(String site) {
        if (isBlank(site)) {
            return "/global";
        }
        if (site.matches("^\\d+$")) {
            return site;
        }
        return site.startsWith("/") ? site : "/" + site;
    }

    private static String normalizeWidgetType(String widgetType) {
        if (widgetType == null) {
            return "";
        }
        String normalized = widgetType.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("search-results".equals(normalized)) {
            return "search-result-summary";
        }
        return normalized;
    }

    private static String widgetTypeDir(String widgetType) {
        if ("similar-results".equals(widgetType)) {
            return "search_results";
        }
        return widgetType.replace('-', '_');
    }

    private static Path repoRoot(LiferayCLIMain.RootCommand root) {
        Path current = Path.of(".").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("docker/docker-compose.yml")) && Files.isDirectory(current.resolve("liferay"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("No se pudo resolver repo root desde cwd actual");
    }

    private static Path resolveExistingFile(Path repoRoot, String candidate, LiferayCLIMain.RootCommand root) {
        Path direct = Path.of(candidate);
        if (direct.isAbsolute() && Files.isRegularFile(direct)) {
            return direct;
        }
        if (Files.isRegularFile(direct)) {
            return direct.toAbsolutePath().normalize();
        }

        Path relativeToRepo = repoRoot.resolve(candidate).normalize();
        if (Files.isRegularFile(relativeToRepo)) {
            return relativeToRepo;
        }

        String structuresPath = root.settings().paths().getOrDefault("structures", "liferay/resources/journal/structures");
        Path structures = repoRoot.resolve(structuresPath).resolve(candidate).normalize();
        if (Files.isRegularFile(structures)) {
            return structures;
        }

        String templatesPath = root.settings().paths().getOrDefault("templates", "liferay/resources/journal/templates");
        Path templates = repoRoot.resolve(templatesPath).resolve(candidate).normalize();
        if (Files.isRegularFile(templates)) {
            return templates;
        }

        String adtsPath = root.settings().paths().getOrDefault("adts", "liferay/resources/templates/application_display");
        Path adts = repoRoot.resolve(adtsPath).resolve(candidate).normalize();
        if (Files.isRegularFile(adts)) {
            return adts;
        }

        throw new IllegalStateException("File no encontrado: " + candidate);
    }

    private static List<Path> findFiles(Path baseDir, String extension) {
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalStateException("Directorio no encontrado: " + baseDir);
        }
        try (Stream<Path> stream = Files.walk(baseDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(extension))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
        catch (IOException e) {
            throw new IllegalStateException("No se pudo listar ficheros en " + baseDir, e);
        }
    }

    private static String fileStem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String sanitizeFileToken(String value) {
        if (value == null) {
            return "unnamed";
        }
        String normalized = value.trim().replace('\\', '_').replace('/', '_');
        normalized = normalized.replaceAll("[^A-Za-z0-9_.-]", "_");
        normalized = normalized.replaceAll("_+", "_");
        if (normalized.isBlank()) {
            return "unnamed";
        }
        return normalized;
    }

    private static Path resolveStructureExportDir(LiferayCLIMain.RootCommand root, String site, String dirOpt) {
        if (!isBlank(dirOpt)) {
            Path custom = Path.of(dirOpt);
            return custom.isAbsolute() ? custom.normalize() : repoRoot(root).resolve(custom).normalize();
        }
        String sitePath = site.replaceFirst("^/", "");
        if (sitePath.isBlank()) {
            sitePath = "global";
        }
        return repoRoot(root).resolve("liferay/resources/journal/structures").resolve(sitePath).normalize();
    }

    private static Path resolveTemplateExportDir(LiferayCLIMain.RootCommand root, String dirOpt) {
        if (!isBlank(dirOpt)) {
            Path custom = Path.of(dirOpt);
            return custom.isAbsolute() ? custom.normalize() : repoRoot(root).resolve(custom).normalize();
        }
        return repoRoot(root).resolve("liferay/resources/journal/templates").normalize();
    }

    private static Path resolveResourceExportSiteDir(Path baseDir, String normalizedSite, String siteToken) {
        String token = siteToken;
        if (isBlank(token)) {
            token = "/global".equals(normalizedSite) ? "global" : normalizedSite.replaceFirst("^/", "");
        }
        if (isBlank(token)) {
            token = "global";
        }
        return baseDir.resolve(token).normalize();
    }

    private static String resolveExportSiteToken(LiferayCLIMain.RootCommand root, String accessToken, String normalizedSite)
        throws Exception {
        if ("/global".equals(normalizedSite)) {
            return "global";
        }
        return resolveSiteInfo(root, accessToken, normalizedSite, null).siteToken();
    }

    private static Path resolveTemplateSyncDir(LiferayCLIMain.RootCommand root, String accessToken, String normalizedSite)
        throws Exception {
        Path baseDir = repoRoot(root).resolve("liferay/resources/journal/templates").normalize();
        SiteInfo siteInfo = resolveSiteInfo(root, accessToken, normalizedSite, null);
        Path scoped = baseDir.resolve(siteInfo.siteToken()).normalize();
        if (Files.isDirectory(scoped)) {
            return scoped;
        }
        if ("/global".equals(normalizedSite)) {
            return baseDir;
        }
        return scoped;
    }

    private static Path resolveAdtExportDir(LiferayCLIMain.RootCommand root, String dirOpt) {
        if (!isBlank(dirOpt)) {
            Path custom = Path.of(dirOpt);
            return custom.isAbsolute() ? custom.normalize() : repoRoot(root).resolve(custom).normalize();
        }
        return repoRoot(root).resolve("liferay/resources/templates/application_display").normalize();
    }

    private static void cleanupLegacyTemplateRootFiles(Path baseDir) throws Exception {
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(baseDir)) {
            List<Path> legacy = stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".ftl"))
                .toList();
            for (Path file : legacy) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static Path resolveAdtSyncDir(LiferayCLIMain.RootCommand root, String accessToken, String normalizedSite)
        throws Exception {
        Path baseDir = repoRoot(root).resolve("liferay/resources/templates/application_display").normalize();
        SiteInfo siteInfo = resolveSiteInfo(root, accessToken, normalizedSite, null);
        Path scoped = baseDir.resolve(siteInfo.siteToken()).normalize();
        if (Files.isDirectory(scoped)) {
            return scoped;
        }
        if ("/global".equals(normalizedSite)) {
            return baseDir;
        }
        return scoped;
    }

    private static Set<String> listSiteTokens(LiferayCLIMain.RootCommand root, String accessToken) throws Exception {
        Set<String> tokens = new HashSet<>();
        tokens.add("global");
        for (SiteInfo siteInfo : listSites(root, accessToken)) {
            tokens.add(siteInfo.siteToken());
        }
        return tokens;
    }

    private static List<SiteInfo> listSitesIncludingGlobal(LiferayCLIMain.RootCommand root, String accessToken) throws Exception {
        Map<String, SiteInfo> bySite = new LinkedHashMap<>();
        SiteInfo globalSite = resolveSiteInfo(root, accessToken, "/global", null);
        bySite.put("/global", new SiteInfo(globalSite.groupId(), "global", "/global", globalSite.siteName()));
        for (SiteInfo siteInfo : listSites(root, accessToken)) {
            String normalizedFriendly = normalizeSite(siteInfo.siteFriendly());
            bySite.putIfAbsent(
                normalizedFriendly,
                new SiteInfo(siteInfo.groupId(), siteInfo.siteToken(), normalizedFriendly, siteInfo.siteName())
            );
        }
        return new ArrayList<>(bySite.values());
    }

    private static List<Path> filterSiteScopedFiles(List<Path> files, Path baseDir, Set<String> excludedSiteTokens) {
        if (excludedSiteTokens.isEmpty()) {
            return files;
        }
        List<Path> filtered = new ArrayList<>();
        for (Path file : files) {
            Path relative = baseDir.relativize(file);
            if (relative.getNameCount() > 1) {
                String firstSegment = relative.getName(0).toString();
                if (excludedSiteTokens.contains(firstSegment)) {
                    continue;
                }
            }
            filtered.add(file);
        }
        return filtered;
    }

    private static String resolveTemplateExportName(JsonNode row) {
        String name = row.path("nameCurrentValue").asText("");
        if (!isBlank(name)) {
            return name;
        }
        String key = row.path("templateKey").asText("");
        if (!isBlank(key)) {
            return key;
        }
        String id = row.path("templateId").asText("");
        return isBlank(id) ? "template" : "template-" + id;
    }

    private static String resolveAdtExportName(JsonNode row) {
        String key = row.path("templateKey").asText("");
        if (!isBlank(key)) {
            return key;
        }
        String name = row.path("adtName").asText("");
        if (!isBlank(name)) {
            return name;
        }
        String id = row.path("templateId").asText("");
        return isBlank(id) ? "adt" : "adt-" + id;
    }

    private static JsonNode normalizeStructureExport(LiferayCLIMain.RootCommand root, JsonNode payload) {
        ObjectNode normalized = payload.deepCopy();
        normalized.remove(List.of("id", "siteId", "userId", "dateCreated", "dateModified"));
        JsonNode defaultLayout = normalized.path("defaultDataLayout");
        if (defaultLayout.isObject()) {
            ((ObjectNode) defaultLayout).remove(
                List.of("id", "siteId", "userId", "dateCreated", "dateModified", "dataDefinitionId", "dataLayoutKey")
            );
        }
        return normalized;
    }

    private static boolean structureDiffers(LiferayCLIMain.RootCommand root, JsonNode expected, Path currentFile) throws Exception {
        if (!Files.isRegularFile(currentFile)) {
            return true;
        }
        JsonNode current = root.mapper().readTree(Files.readString(currentFile));
        String left = root.mapper().writeValueAsString(current);
        String right = root.mapper().writeValueAsString(expected);
        return !Objects.equals(left, right);
    }

    private static void writePrettyJson(LiferayCLIMain.RootCommand root, JsonNode payload, Path output) throws Exception {
        Path target = output.toAbsolutePath().normalize();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        String pretty = root.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        Files.writeString(target, pretty + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static void writeTextFile(Path output, String content) throws Exception {
        Path target = output.toAbsolutePath().normalize();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        }
        catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular SHA-256", e);
        }
    }

    private static String localizedMap(LiferayCLIMain.RootCommand root, String text) throws Exception {
        ObjectNode map = root.mapper().createObjectNode();
        map.put("ca_ES", text);
        map.put("es_ES", text);
        map.put("en_US", text);
        return root.mapper().writeValueAsString(map);
    }

    private static OAuthTokenClient.TokenResponse token(LiferayCLIMain.RootCommand root) throws Exception {
        return root.tokenClient().fetchClientCredentialsToken(root.settings());
    }

    private static long resolveSiteId(LiferayCLIMain.RootCommand root, String accessToken, String site) throws Exception {
        JsonNode node = resolveSite(root, accessToken, site);
        long id = node.path("id").asLong(-1L);
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
        String encoded = urlEncode(normalized);
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
        String site,
        String key
    ) throws Exception {
        return fetchStructureByKey(root, token, site, key, true);
    }

    private static JsonNode fetchStructureByKey(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        String site,
        String key,
        boolean failIfMissing
    ) throws Exception {
        long siteId = resolveSiteId(root, token.accessToken(), site);
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            "/o/data-engine/v2.0/sites/" + siteId +
                "/data-definitions/by-content-type/journal/by-data-definition-key/" + urlEncode(key),
            token.accessToken(),
            root.settings().timeoutSeconds()
        );
        if (response.statusCode() == 404 && !failIfMissing) {
            return root.mapper().createObjectNode();
        }
        ensure2xx(response, "structure-get");
        return root.mapper().readTree(response.body());
    }

    private static JsonNode findTemplate(
        LiferayCLIMain.RootCommand root,
        OAuthTokenClient.TokenResponse token,
        String site,
        String id,
        int ignoredPageSize
    ) throws Exception {
        JsonNode siteNode = resolveSite(root, token.accessToken(), site);
        long siteId = siteNode.path("id").asLong(-1L);
        long companyId = resolveCompanyId(root, token.accessToken(), siteNode);
        JsonNode items = listJournalTemplates(root, token.accessToken(), siteId, companyId);
        if (items.isArray()) {
            for (JsonNode item : items) {
                String templateId = item.path("templateId").asText("");
                String templateKey = item.path("templateKey").asText("");
                String erc = item.path("externalReferenceCode").asText("");
                String name = item.path("nameCurrentValue").asText("");
                if (id.equals(templateId) || id.equals(templateKey) || id.equals(erc) || id.equals(name)) {
                    ObjectNode payload = root.mapper().createObjectNode();
                    payload.put("id", templateKey);
                    payload.put("templateId", templateId);
                    payload.put("templateKey", templateKey);
                    payload.put("externalReferenceCode", erc.isBlank() ? templateKey : erc);
                    payload.put("name", name.isBlank() ? templateKey : name);
                    payload.put("contentStructureId", item.path("classPK").asLong(-1L));
                    payload.put("templateScript", item.path("script").asText(""));
                    payload.set("raw", item.deepCopy());
                    return payload;
                }
            }
        }
        throw new IllegalStateException("template no encontrado: " + id);
    }

    private static JsonNode fetchPagedItems(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String basePath,
        int pageSize
    ) throws Exception {
        ArrayNode out = root.mapper().createArrayNode();
        int page = 1;
        int lastPage = 1;
        do {
            JsonNode response = getJson(
                root,
                accessToken,
                basePath + "?page=" + page + "&pageSize=" + pageSize,
                "paged-list"
            );
            JsonNode items = response.path("items");
            if (items.isArray()) {
                items.forEach(out::add);
            }
            lastPage = response.path("lastPage").asInt(1);
            page++;
        }
        while (page <= lastPage);
        return out;
    }

    private static long fetchClassNameId(LiferayCLIMain.RootCommand root, String accessToken, String className) throws Exception {
        JsonNode payload = getJson(
            root,
            accessToken,
            "/api/jsonws/classname/fetch-class-name?value=" + urlEncode(className),
            "classname-fetch"
        );
        long classNameId = payload.path("classNameId").asLong(-1L);
        if (classNameId <= 0) {
            throw new IllegalStateException("classNameId no resuelto para " + className);
        }
        return classNameId;
    }

    private static JsonNode getJson(LiferayCLIMain.RootCommand root, String accessToken, String path, String op) throws Exception {
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            path,
            accessToken,
            root.settings().timeoutSeconds()
        );
        ensure2xx(response, op);
        return root.mapper().readTree(response.body());
    }

    private static JsonNode postForm(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String path,
        Map<String, String> form,
        String op
    ) throws Exception {
        LiferayApiClient.ApiResponse response = root.apiClient().postForm(
            root.settings().baseUrl(),
            path,
            accessToken,
            root.settings().timeoutSeconds(),
            form
        );
        ensure2xx(response, op);
        return parseJson(root.mapper(), response.body());
    }

    private static JsonNode postJson(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String path,
        String payload,
        String op
    ) throws Exception {
        LiferayApiClient.ApiResponse response = root.apiClient().postJson(
            root.settings().baseUrl(),
            path,
            accessToken,
            root.settings().timeoutSeconds(),
            payload
        );
        ensure2xx(response, op);
        return parseJson(root.mapper(), response.body());
    }

    private static JsonNode putJson(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String path,
        String payload,
        String op
    ) throws Exception {
        LiferayApiClient.ApiResponse response = root.apiClient().putJson(
            root.settings().baseUrl(),
            path,
            accessToken,
            root.settings().timeoutSeconds(),
            payload
        );
        ensure2xx(response, op);
        return parseJson(root.mapper(), response.body());
    }

    private static SiteInfo resolveSiteInfo(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String site,
        String groupIdOpt
    ) throws Exception {
        JsonNode siteNode;
        if (!isBlank(groupIdOpt)) {
            siteNode = resolveSite(root, accessToken, groupIdOpt.trim());
        } else {
            siteNode = resolveSite(root, accessToken, normalizeSite(site));
        }
        long groupId = siteNode.path("id").asLong(-1L);
        if (groupId <= 0) {
            throw new IllegalStateException("groupId no resuelto");
        }
        String friendly = siteNode.path("friendlyUrlPath").asText("");
        if (isBlank(friendly)) {
            friendly = "/site-" + groupId;
        } else if (!friendly.startsWith("/")) {
            friendly = "/" + friendly;
        }
        String siteName = siteNode.path("name").asText("");
        String token = sanitizeFileToken(friendly.replaceFirst("^/", ""));
        if (isBlank(token)) {
            token = "site-" + groupId;
        }
        return new SiteInfo(groupId, token, friendly, siteName);
    }

    private static List<SiteInfo> listSites(LiferayCLIMain.RootCommand root, String accessToken) throws Exception {
        List<SiteInfo> rows = new ArrayList<>();
        JsonNode companies = getJson(root, accessToken, "/api/jsonws/company/get-companies", "company/get-companies");
        for (JsonNode company : companies) {
            long companyId = company.path("companyId").asLong(0L);
            if (companyId <= 0) {
                continue;
            }
            int total = fetchGroupSearchCount(root, accessToken, companyId);
            for (int start = 0; start < total; start += 200) {
                JsonNode page = getJson(
                    root,
                    accessToken,
                    "/api/jsonws/group/search?companyId=" + companyId +
                        "&name=&description=&params=%7B%7D&start=" + start + "&end=" + (start + 200),
                    "group/search"
                );
                if (!page.isArray()) {
                    continue;
                }
                for (JsonNode row : page) {
                    if (!row.path("site").asBoolean(false)) {
                        continue;
                    }
                    long groupId = row.path("groupId").asLong(-1L);
                    if (groupId <= 0) {
                        continue;
                    }
                    String friendly = row.path("friendlyURL").asText("");
                    if (isBlank(friendly)) {
                        friendly = "/site-" + groupId;
                    } else if (!friendly.startsWith("/")) {
                        friendly = "/" + friendly;
                    }
                    String name = row.path("nameCurrentValue").asText(row.path("name").asText(""));
                    String token = sanitizeFileToken(friendly.replaceFirst("^/", ""));
                    if (isBlank(token)) {
                        token = "site-" + groupId;
                    }
                    rows.add(new SiteInfo(groupId, token, friendly, name));
                }
            }
        }
        Set<Long> seen = new TreeSet<>();
        List<SiteInfo> unique = new ArrayList<>();
        for (SiteInfo row : rows) {
            if (seen.add(row.groupId)) {
                unique.add(row);
            }
        }
        return unique;
    }

    private static int fetchGroupSearchCount(LiferayCLIMain.RootCommand root, String accessToken, long companyId)
        throws Exception {
        LiferayApiClient.ApiResponse response = root.apiClient().get(
            root.settings().baseUrl(),
            "/api/jsonws/group/search-count?companyId=" + companyId + "&name=&description=&params=%7B%7D",
            accessToken,
            root.settings().timeoutSeconds()
        );
        ensure2xx(response, "group/search-count");
        String body = response.body() == null ? "" : response.body().trim();
        if (body.startsWith("\"") && body.endsWith("\"") && body.length() >= 2) {
            body = body.substring(1, body.length() - 1);
        }
        return Integer.parseInt(body);
    }

    private static int exportFragmentsAllSites(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String dirOpt,
        String outputOpt
    ) throws Exception {
        int scanned = 0;
        int sites = 0;
        int collections = 0;
        int fragments = 0;
        int errors = 0;
        ArrayNode siteSummaries = root.mapper().createArrayNode();
        for (SiteInfo siteInfo : listSitesIncludingGlobal(root, accessToken)) {
            scanned++;
            FragmentExportResult result = exportFragmentsSingleSite(root, accessToken, siteInfo, dirOpt);
            if (result.collectionCount == 0 && result.fragmentCount == 0) {
                continue;
            }
            sites++;
            collections += result.collectionCount;
            fragments += result.fragmentCount;
            errors += result.errors;
            siteSummaries.add(result.payload);
        }
        ObjectNode payload = root.mapper().createObjectNode();
        payload.put("ok", errors == 0);
        payload.put("domain", "fragments");
        payload.put("action", "export");
        ObjectNode data = payload.putObject("data");
        data.put("mode", "all-sites");
        data.put("scannedSites", scanned);
        data.put("siteCount", sites);
        data.put("collectionCount", collections);
        data.put("fragmentCount", fragments);
        data.put("errors", errors);
        data.set("sites", siteSummaries);
        if (!isBlank(outputOpt)) {
            writePrettyJson(root, payload, resolveOutputPath(root, outputOpt));
        }
        System.out.printf(
            "scanned=%d sites=%d collections=%d fragments=%d errors=%d mode=all-sites%n",
            scanned,
            sites,
            collections,
            fragments,
            errors
        );
        return errors > 0 ? 1 : 0;
    }

    private static FragmentExportResult exportFragmentsSingleSite(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        SiteInfo siteInfo,
        String dirOpt
    ) throws Exception {
        Path projectDir = resolveFragmentsProjectDir(root, dirOpt, siteInfo.siteToken, true);
        JsonNode json = runFragmentsExport(root, accessToken, siteInfo.groupId, projectDir);
        int collectionCount = json.path("summary").path("collectionCount").asInt(0);
        int fragmentCount = json.path("summary").path("fragmentCount").asInt(0);
        int errors = json.path("summary").path("errors").asInt(0);
        if (fragmentCount > 0) {
            ensureFragmentProjectScaffold(root, projectDir);
        } else if (Files.exists(projectDir)) {
            deleteRecursively(projectDir);
        }
        ObjectNode payload = root.mapper().createObjectNode();
        payload.put("groupId", siteInfo.groupId);
        payload.put("site", siteInfo.siteFriendly);
        payload.put("siteName", siteInfo.siteName);
        payload.put("projectDir", projectDir.toString());
        payload.set("summary", json.path("summary"));
        return new FragmentExportResult(collectionCount, fragmentCount, errors, projectDir, payload);
    }

    private static Path resolveFragmentsProjectDir(
        LiferayCLIMain.RootCommand root,
        String dirOpt,
        String siteToken,
        boolean exportMode
    ) {
        if (isBlank(dirOpt)) {
            String fragmentsDir = root.settings().paths().getOrDefault("fragments", "liferay/fragments");
            return repoRoot(root).resolve(fragmentsDir + "/sites").resolve(siteToken).normalize();
        }
        Path configured = Path.of(dirOpt);
        if (!configured.isAbsolute()) {
            configured = repoRoot(root).resolve(configured).normalize();
        }
        if (!exportMode && Files.isDirectory(configured.resolve("src"))) {
            return configured;
        }
        return configured.resolve(siteToken).normalize();
    }

    private static void ensureFragmentProjectScaffold(LiferayCLIMain.RootCommand root, Path projectDir) throws Exception {
        String fragmentsDir = root.settings().paths().getOrDefault("fragments", "liferay/fragments");
        Path base = repoRoot(root).resolve(fragmentsDir);
        Files.createDirectories(projectDir.resolve("src"));
        List<String> seeds = List.of(
            ".editorconfig",
            ".gitignore",
            "README.md",
            "liferay-npm-bundler.config.js",
            "package.json",
            "yarn.lock"
        );
        for (String seed : seeds) {
            Path source = base.resolve(seed);
            Path target = projectDir.resolve(seed);
            if (!Files.exists(target) && Files.exists(source)) {
                Files.copy(source, target);
            }
        }
    }

    private static JsonNode runFragmentsExport(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long groupId,
        Path projectDir
    ) throws Exception {
        Path srcDir = projectDir.resolve("src");
        Files.createDirectories(srcDir);
        try (Stream<Path> stream = Files.list(srcDir)) {
            stream.forEach(path -> {
                try {
                    if (!path.getFileName().toString().startsWith(".")) {
                        if (Files.isDirectory(path)) {
                            deleteRecursively(path);
                        } else {
                            Files.deleteIfExists(path);
                        }
                    }
                }
                catch (Exception ignored) {
                }
            });
        }

        JsonNode collections = getJson(
            root,
            accessToken,
            "/api/jsonws/fragment.fragmentcollection/get-fragment-collections?groupId=" + groupId,
            "fragments-collections"
        );

        int collectionCount = 0;
        int fragmentCount = 0;
        for (JsonNode collection : collections) {
            long collectionId = collection.path("fragmentCollectionId").asLong(-1L);
            if (collectionId <= 0) {
                continue;
            }
            String collectionKey = collection.path("fragmentCollectionKey").asText("");
            String collectionName = collection.path("name").asText("");
            String collectionDesc = collection.path("description").asText("");
            if (isBlank(collectionKey)) {
                collectionKey = sanitizeFileToken(collectionName);
            }
            if (isBlank(collectionKey)) {
                collectionKey = "collection-" + collectionId;
            }
            JsonNode fragments = getJson(
                root,
                accessToken,
                "/api/jsonws/fragment.fragmententry/get-fragment-entries?fragmentCollectionId=" + collectionId,
                "fragments-list"
            );
            Path collectionDir = null;
            for (JsonNode fragment : fragments) {
                long fragmentId = fragment.path("fragmentEntryId").asLong(-1L);
                String fragmentKey = fragment.path("fragmentEntryKey").asText("");
                String fragmentName = fragment.path("name").asText("");
                String icon = fragment.path("icon").asText("code");
                int type = fragment.path("type").asInt(0);
                if (isBlank(fragmentKey)) {
                    fragmentKey = sanitizeFileToken(fragmentName);
                }
                if (isBlank(fragmentKey)) {
                    fragmentKey = "fragment-" + fragmentId;
                }
                if (collectionDir == null) {
                    collectionDir = srcDir.resolve(collectionKey);
                    Files.createDirectories(collectionDir.resolve("fragments"));
                    ObjectNode collectionJson = root.mapper().createObjectNode();
                    collectionJson.put("name", collectionName);
                    collectionJson.put("description", collectionDesc);
                    writePrettyJson(root, collectionJson, collectionDir.resolve("collection.json"));
                }

                Path fragmentDir = collectionDir.resolve("fragments").resolve(fragmentKey);
                Files.createDirectories(fragmentDir);
                writeTextFile(fragmentDir.resolve("index.html"), fragment.path("html").asText(""));
                writeTextFile(fragmentDir.resolve("index.css"), fragment.path("css").asText(""));
                writeTextFile(fragmentDir.resolve("index.js"), fragment.path("js").asText(""));

                String rawConfig = fragment.path("configuration").asText("");
                if (!isBlank(rawConfig)) {
                    try {
                        JsonNode config = root.mapper().readTree(rawConfig);
                        writePrettyJson(root, config, fragmentDir.resolve("configuration.json"));
                    }
                    catch (Exception ex) {
                        writePrettyJson(root, root.mapper().createObjectNode(), fragmentDir.resolve("configuration.json"));
                    }
                } else {
                    writePrettyJson(root, root.mapper().createObjectNode(), fragmentDir.resolve("configuration.json"));
                }

                ObjectNode fragmentJson = root.mapper().createObjectNode();
                fragmentJson.put("configurationPath", "configuration.json");
                fragmentJson.put("jsPath", "index.js");
                fragmentJson.put("htmlPath", "index.html");
                fragmentJson.put("cssPath", "index.css");
                fragmentJson.put("icon", icon);
                fragmentJson.put("name", fragmentName);
                fragmentJson.put("type", type == 1 ? "section" : "component");
                writePrettyJson(root, fragmentJson, fragmentDir.resolve("fragment.json"));
                fragmentCount++;
            }
            if (collectionDir != null) {
                collectionCount++;
            }
        }

        ObjectNode payload = root.mapper().createObjectNode();
        ObjectNode summary = payload.putObject("summary");
        summary.put("groupId", groupId);
        summary.put("collectionCount", collectionCount);
        summary.put("fragmentCount", fragmentCount);
        summary.put("compositionCount", 0);
        summary.put("pageTemplateCount", 0);
        summary.put("errors", 0);
        summary.put("mode", "oauth-jsonws-export");
        payload.put("projectDir", projectDir.toString());
        payload.set("collections", root.mapper().createArrayNode());
        return payload;
    }

    private static JsonNode runFragmentsImport(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long groupId,
        Path projectDir,
        String fragmentFilter
    ) throws Exception {
        LocalFragmentsProject project = readLocalFragmentsProject(root, projectDir, fragmentFilter);
        JsonNode collections = getJson(
            root,
            accessToken,
            "/api/jsonws/fragment.fragmentcollection/get-fragment-collections?groupId=" + groupId,
            "fragments-collections"
        );
        Map<String, JsonNode> collectionByKey = new LinkedHashMap<>();
        for (JsonNode collection : collections) {
            String key = collection.path("fragmentCollectionKey").asText("");
            String name = collection.path("name").asText("");
            if (!isBlank(key)) {
                collectionByKey.put(key.toLowerCase(Locale.ROOT), collection);
            }
            if (!isBlank(name)) {
                collectionByKey.putIfAbsent(sanitizeFileToken(name).toLowerCase(Locale.ROOT), collection);
            }
        }

        int imported = 0;
        int errors = 0;
        ArrayNode fragmentResults = root.mapper().createArrayNode();
        ArrayNode pageTemplateResults = root.mapper().createArrayNode();

        for (LocalFragmentCollection localCollection : project.collections()) {
            try {
                JsonNode collection = collectionByKey.get(localCollection.slug().toLowerCase(Locale.ROOT));
                if (collection == null) {
                    collection = createFragmentCollection(root, accessToken, groupId, localCollection);
                    collectionByKey.put(localCollection.slug().toLowerCase(Locale.ROOT), collection);
                } else {
                    updateFragmentCollection(root, accessToken, collection.path("fragmentCollectionId").asLong(-1L), localCollection);
                }
                long collectionId = collection.path("fragmentCollectionId").asLong(-1L);
                if (collectionId <= 0) {
                    throw new IllegalStateException("fragmentCollectionId inválido para " + localCollection.slug());
                }

                JsonNode runtimeFragments = getJson(
                    root,
                    accessToken,
                    "/api/jsonws/fragment.fragmententry/get-fragment-entries?fragmentCollectionId=" + collectionId,
                    "fragments-list"
                );
                Map<String, JsonNode> runtimeByKey = new LinkedHashMap<>();
                for (JsonNode runtimeFragment : runtimeFragments) {
                    String runtimeKey = runtimeFragment.path("fragmentEntryKey").asText("");
                    String runtimeName = runtimeFragment.path("name").asText("");
                    if (!isBlank(runtimeKey)) {
                        runtimeByKey.put(runtimeKey.toLowerCase(Locale.ROOT), runtimeFragment);
                    }
                    if (!isBlank(runtimeName)) {
                        runtimeByKey.putIfAbsent(sanitizeFileToken(runtimeName).toLowerCase(Locale.ROOT), runtimeFragment);
                    }
                }

                for (LocalFragment localFragment : localCollection.fragments()) {
                    ObjectNode result = root.mapper().createObjectNode();
                    result.put("collection", localCollection.slug());
                    result.put("fragment", localFragment.slug());
                    try {
                        JsonNode runtimeFragment = runtimeByKey.get(localFragment.slug().toLowerCase(Locale.ROOT));
                        JsonNode syncedFragment;
                        if (runtimeFragment == null) {
                            syncedFragment = createFragmentEntry(
                                root,
                                accessToken,
                                groupId,
                                collectionId,
                                localFragment
                            );
                        } else {
                            long fragmentEntryId = runtimeFragment.path("fragmentEntryId").asLong(-1L);
                            if (fragmentEntryId <= 0) {
                                throw new IllegalStateException("fragmentEntryId inválido para " + localFragment.slug());
                            }
                            syncedFragment = updateFragmentEntry(root, accessToken, fragmentEntryId, localFragment);
                        }
                        result.put("status", "imported");
                        result.put("fragmentEntryId", syncedFragment.path("fragmentEntryId").asLong(-1L));
                        imported++;
                    }
                    catch (Exception fragmentEx) {
                        result.put("status", "error");
                        result.put("error", fragmentEx.getMessage());
                        errors++;
                    }
                    fragmentResults.add(result);
                }
            }
            catch (Exception collectionEx) {
                for (LocalFragment localFragment : localCollection.fragments()) {
                    ObjectNode result = root.mapper().createObjectNode();
                    result.put("collection", localCollection.slug());
                    result.put("fragment", localFragment.slug());
                    result.put("status", "error");
                    result.put("error", collectionEx.getMessage());
                    fragmentResults.add(result);
                    errors++;
                }
            }
        }

        ObjectNode payload = root.mapper().createObjectNode();
        ObjectNode summary = payload.putObject("summary");
        summary.put("importedFragments", imported);
        summary.put("fragmentResults", fragmentResults.size());
        summary.put("pageTemplateResults", 0);
        summary.put("errors", errors);
        summary.put("mode", "oauth-jsonws-import");
        payload.set("fragmentResults", fragmentResults);
        payload.set("pageTemplateResults", pageTemplateResults);
        return payload;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                }
                catch (IOException ignored) {
                }
            });
        }
    }

    private static LocalFragmentsProject readLocalFragmentsProject(
        LiferayCLIMain.RootCommand root,
        Path projectDir,
        String fragmentFilter
    ) throws Exception {
        Path srcDir = projectDir.resolve("src");
        if (!Files.isDirectory(srcDir)) {
            throw new IllegalStateException("No existe directorio src en " + projectDir);
        }
        List<LocalFragmentCollection> collections = new ArrayList<>();
        String filter = fragmentFilter == null ? "" : fragmentFilter.trim();
        try (Stream<Path> collectionDirs = Files.list(srcDir)) {
            for (Path collectionDir : collectionDirs.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (!Files.isDirectory(collectionDir)) {
                    continue;
                }
                Path fragmentsDir = collectionDir.resolve("fragments");
                if (!Files.isDirectory(fragmentsDir)) {
                    continue;
                }
                String collectionSlug = collectionDir.getFileName().toString();
                JsonNode collectionMeta = readJsonIfExists(root, collectionDir.resolve("collection.json"));
                String collectionName = collectionMeta.path("name").asText(collectionSlug);
                String collectionDescription = collectionMeta.path("description").asText("");
                List<LocalFragment> fragments = new ArrayList<>();
                try (Stream<Path> fragmentDirs = Files.list(fragmentsDir)) {
                    for (Path fragmentDir : fragmentDirs.sorted(Comparator.comparing(Path::toString)).toList()) {
                        if (!Files.isDirectory(fragmentDir)) {
                            continue;
                        }
                        LocalFragment fragment = readLocalFragment(root, collectionSlug, fragmentDir);
                        if (!isBlank(filter) && !fragmentMatchesFilter(fragment, filter)) {
                            continue;
                        }
                        fragments.add(fragment);
                    }
                }
                if (!fragments.isEmpty()) {
                    collections.add(new LocalFragmentCollection(collectionSlug, collectionName, collectionDescription, fragments));
                }
            }
        }
        if (collections.isEmpty()) {
            if (!isBlank(filter)) {
                throw new IllegalStateException("Fragment '" + filter + "' no encontrado en " + projectDir);
            }
            throw new IllegalStateException("No se encontraron fragments para importar en " + projectDir);
        }
        return new LocalFragmentsProject(projectDir, collections);
    }

    private static LocalFragment readLocalFragment(LiferayCLIMain.RootCommand root, String collectionSlug, Path fragmentDir)
        throws Exception {
        String slug = fragmentDir.getFileName().toString();
        JsonNode fragmentJson = readJsonIfExists(root, fragmentDir.resolve("fragment.json"));
        String name = fragmentJson.path("name").asText(slug);
        String icon = fragmentJson.path("icon").asText("code");
        String typeLabel = fragmentJson.path("type").asText("component");
        int type = "section".equalsIgnoreCase(typeLabel) ? 1 : 0;

        String htmlPath = fragmentJson.path("htmlPath").asText("index.html");
        String cssPath = fragmentJson.path("cssPath").asText("index.css");
        String jsPath = fragmentJson.path("jsPath").asText("index.js");
        String configPath = fragmentJson.path("configurationPath").asText("configuration.json");

        String html = readTextIfExists(fragmentDir.resolve(htmlPath));
        String css = readTextIfExists(fragmentDir.resolve(cssPath));
        String js = readTextIfExists(fragmentDir.resolve(jsPath));
        String configuration = readTextIfExists(fragmentDir.resolve(configPath));
        if (isBlank(configuration)) {
            configuration = "{}";
        }

        String directoryPath = collectionSlug + "/fragments/" + slug;
        return new LocalFragment(slug, name, icon, type, html, css, js, configuration, directoryPath);
    }

    private static boolean fragmentMatchesFilter(LocalFragment fragment, String filter) {
        String normalized = filter.toLowerCase(Locale.ROOT);
        if (fragment.slug().equalsIgnoreCase(normalized)) {
            return true;
        }
        if (fragment.directoryPath().equalsIgnoreCase(normalized)) {
            return true;
        }
        return fragment.name().equalsIgnoreCase(normalized);
    }

    private static JsonNode createFragmentCollection(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long groupId,
        LocalFragmentCollection collection
    ) throws Exception {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("groupId", String.valueOf(groupId));
        base.put("name", collection.name());
        base.put("description", collection.description());

        List<Map<String, String>> candidates = new ArrayList<>();
        Map<String, String> withKey = new LinkedHashMap<>(base);
        withKey.put("fragmentCollectionKey", collection.slug());
        withKey.put("serviceContext", "{}");
        candidates.add(withKey);

        Map<String, String> withKeyNoCtx = new LinkedHashMap<>(base);
        withKeyNoCtx.put("fragmentCollectionKey", collection.slug());
        candidates.add(withKeyNoCtx);

        Map<String, String> noKey = new LinkedHashMap<>(base);
        noKey.put("serviceContext", "{}");
        candidates.add(noKey);

        return postFormCandidates(
            root,
            accessToken,
            "/api/jsonws/fragment.fragmentcollection/add-fragment-collection",
            candidates,
            "fragment-collection-create"
        );
    }

    private static void updateFragmentCollection(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long fragmentCollectionId,
        LocalFragmentCollection collection
    ) {
        if (fragmentCollectionId <= 0) {
            return;
        }
        List<Map<String, String>> candidates = new ArrayList<>();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("fragmentCollectionId", String.valueOf(fragmentCollectionId));
        form.put("name", collection.name());
        form.put("description", collection.description());
        candidates.add(form);

        Map<String, String> withCtx = new LinkedHashMap<>(form);
        withCtx.put("serviceContext", "{}");
        candidates.add(withCtx);

        try {
            postFormCandidates(
                root,
                accessToken,
                "/api/jsonws/fragment.fragmentcollection/update-fragment-collection",
                candidates,
                "fragment-collection-update"
            );
        }
        catch (Exception ignored) {
        }
    }

    private static JsonNode createFragmentEntry(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long groupId,
        long fragmentCollectionId,
        LocalFragment fragment
    ) throws Exception {
        List<Map<String, String>> candidates = new ArrayList<>();
        Map<String, String> base = fragmentEntryBaseForm(groupId, fragmentCollectionId, fragment);

        Map<String, String> full = new LinkedHashMap<>(base);
        full.put("serviceContext", "{}");
        full.put("cacheable", "false");
        full.put("readOnly", "false");
        full.put("typeOptions", "{}");
        candidates.add(full);

        Map<String, String> minimal = new LinkedHashMap<>(base);
        candidates.add(minimal);

        return postFormCandidates(
            root,
            accessToken,
            "/api/jsonws/fragment.fragmententry/add-fragment-entry",
            candidates,
            "fragment-entry-create"
        );
    }

    private static JsonNode updateFragmentEntry(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        long fragmentEntryId,
        LocalFragment fragment
    ) throws Exception {
        List<Map<String, String>> candidates = new ArrayList<>();
        Map<String, String> base = new LinkedHashMap<>();
        base.put("fragmentEntryId", String.valueOf(fragmentEntryId));
        base.put("name", fragment.name());
        base.put("css", fragment.css());
        base.put("html", fragment.html());
        base.put("js", fragment.js());
        base.put("configuration", fragment.configuration());
        base.put("icon", fragment.icon());
        base.put("type", String.valueOf(fragment.type()));

        Map<String, String> withCtx = new LinkedHashMap<>(base);
        withCtx.put("serviceContext", "{}");
        withCtx.put("cacheable", "false");
        withCtx.put("readOnly", "false");
        candidates.add(withCtx);
        candidates.add(base);

        return postFormCandidates(
            root,
            accessToken,
            "/api/jsonws/fragment.fragmententry/update-fragment-entry",
            candidates,
            "fragment-entry-update"
        );
    }

    private static Map<String, String> fragmentEntryBaseForm(long groupId, long fragmentCollectionId, LocalFragment fragment) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("groupId", String.valueOf(groupId));
        form.put("fragmentCollectionId", String.valueOf(fragmentCollectionId));
        form.put("fragmentEntryKey", fragment.slug());
        form.put("name", fragment.name());
        form.put("css", fragment.css());
        form.put("html", fragment.html());
        form.put("js", fragment.js());
        form.put("configuration", fragment.configuration());
        form.put("icon", fragment.icon());
        form.put("type", String.valueOf(fragment.type()));
        return form;
    }

    private static JsonNode postFormCandidates(
        LiferayCLIMain.RootCommand root,
        String accessToken,
        String path,
        List<Map<String, String>> candidates,
        String op
    ) throws Exception {
        List<String> errors = new ArrayList<>();
        for (Map<String, String> form : candidates) {
            LiferayApiClient.ApiResponse response = root.apiClient().postForm(
                root.settings().baseUrl(),
                path,
                accessToken,
                root.settings().timeoutSeconds(),
                form
            );
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return parseJson(root.mapper(), response.body());
            }
            errors.add("status=" + response.statusCode() + " body=" + response.body());
        }
        throw new IllegalStateException(op + " falló en " + path + " (" + String.join(" | ", errors) + ")");
    }

    private static JsonNode readJsonIfExists(LiferayCLIMain.RootCommand root, Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            return root.mapper().createObjectNode();
        }
        String raw = Files.readString(file);
        if (raw.isBlank()) {
            return root.mapper().createObjectNode();
        }
        return root.mapper().readTree(raw);
    }

    private static String readTextIfExists(Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        return Files.readString(file);
    }

    private static Path resolveOutputPath(LiferayCLIMain.RootCommand root, String outputOpt) {
        Path path = Path.of(outputOpt);
        if (!path.isAbsolute()) {
            path = repoRoot(root).resolve(path).normalize();
        }
        return path;
    }

    private static JsonNode parseJson(ObjectMapper mapper, String body) throws Exception {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(body);
    }

    private static void ensure2xx(LiferayApiClient.ApiResponse response, String op) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(op + " status=" + response.statusCode() + " body=" + response.body());
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void requireBulkConfirmation(String commandName, boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException(
                commandName + " es una operación bulk y no forma parte del camino normal. " +
                    "Si realmente la necesitas, vuelve a lanzarla con --bulk."
            );
        }
    }

    private record MappingRule(String source, String target, boolean cleanupSource) {
    }

    private record SiteInfo(long groupId, String siteToken, String siteFriendly, String siteName) {
    }

    private record FragmentExportResult(int collectionCount, int fragmentCount, int errors, Path projectDir, ObjectNode payload) {
    }

    private record LocalFragmentsProject(Path projectDir, List<LocalFragmentCollection> collections) {
    }

    private record LocalFragmentCollection(String slug, String name, String description, List<LocalFragment> fragments) {
    }

    private record LocalFragment(
        String slug,
        String name,
        String icon,
        int type,
        String html,
        String css,
        String js,
        String configuration,
        String directoryPath
    ) {
    }

    private record MigrationDescriptor(
        String site,
        String structureKey,
        String structureFile,
        String migrationPhase,
        boolean allowBreakingChange,
        JsonNode planNode
    ) {
    }

    private record MigrationScope(Set<String> articleKeys, Set<Long> resourcePrimKeys) {
    }

    private record CleanupDescriptor(String migrationFile, boolean deleteAfterUse) {
    }

    private record ExportStats(int processed, int diffs, int failed) {
    }

    private static final class MigrationStats {
        int scanned;
        int migrated;
        int unchanged;
        int failed;
    }

    private record SyncContext(String status, String id, String name, String extra) {
    }
}
