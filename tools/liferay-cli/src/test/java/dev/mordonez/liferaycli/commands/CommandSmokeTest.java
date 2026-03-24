package dev.mordonez.liferaycli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mordonez.liferaycli.LiferayCLIMain;
import dev.mordonez.liferaycli.config.RuntimeConfig;
import dev.mordonez.liferaycli.http.LiferayApiClient;
import dev.mordonez.liferaycli.http.OAuthTokenClient;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandSmokeTest {

    @Test
    void authTokenRaw_printsOnlyAccessToken() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "auth", "token", "--raw");

        assertEquals(0, code);
        assertEquals("token-1234567890", out.toString().trim());
        assertEquals("", err.toString().trim());
    }

    @Test
    void auditJson_returnsCounts() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "audit", "--site", "/global", "--format", "json");

        String json = out.toString();
        assertEquals(0, code);
        assertTrue(json.contains("\"siteId\" : 20124"));
        assertTrue(json.contains("\"structureCount\" : 3"));
        assertTrue(json.contains("\"templateCount\" : 1"));
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventorySites_outputsPagesCommands() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "inventory", "sites", "--format", "json");

        String json = out.toString();
        assertEquals(0, code);
        assertTrue(json.contains("\"siteFriendlyUrl\" : \"/global\""));
        assertTrue(json.contains("\"pagesCommand\" : \"inventory pages --site /global\""));
        assertTrue(json.contains("\"siteFriendlyUrl\" : \"/ub\""));
        assertTrue(json.contains("\"pagesCommand\" : \"inventory pages --site /ub\""));
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventorySitesText_printsPagesCommands() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "inventory", "sites");

        String text = out.toString();
        assertEquals(0, code);
        assertTrue(text.contains("pages=inventory pages --site /global"));
        assertTrue(text.contains("pages=inventory pages --site /ub"));
        assertEquals("", err.toString().trim());
    }

    @Test
    void fragmentsListJson_returnsItems() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "resource", "fragments-list", "--site", "/global", "--format", "json");

        String json = out.toString();
        assertEquals(0, code);
        assertTrue(json.contains("\"fragmentId\" : 3001"));
        assertTrue(json.contains("\"fragmentKey\" : \"ub-banner\""));
        assertTrue(json.contains("\"collectionName\" : \"UB Base\""));
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPageUrl_parsesAndReturnsLayout() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // --url auto-parses /web/global/inici → site=/global, page=/inici
        int code = execute(root, out, err, "inventory", "page",
            "--url", "/web/global/inici",
            "--format", "json");

        String json = out.toString();
        assertEquals(0, code, "exit code should be 0, stderr=" + err.toString());
        assertTrue(json.contains("\"pageSubtype\" : \"content\""), "missing pageSubtype in: " + json);
        assertTrue(json.contains("\"layoutId\" : 7"), "missing layoutId in: " + json);
        assertTrue(json.contains("\"friendlyURL\" : \"/inici\""), "missing friendlyURL in: " + json);
        assertTrue(json.contains("fragmentEntryLinks"), "missing fragmentEntryLinks in: " + json);
        assertTrue(json.contains("\"fragmentKey\" : \"ub-banner\""), "missing ub-banner fragment in: " + json);
        assertTrue(json.contains("widgets"), "missing widgets in: " + json);
        assertTrue(json.contains("journalArticles"), "missing journalArticles in: " + json);
        assertTrue(json.contains("contentStructures"), "missing contentStructures in: " + json);
        assertEquals("", err.toString().trim());
    }

    @Test
    void pageLayoutExportJson_includesHeadlessPageDefinition() throws Exception {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "page-layout", "export", "--url", "/web/ub/subsites");

        String json = out.toString();
        assertEquals(0, code, "stderr=" + err);
        assertTrue(json.contains("\"kind\" : \"liferay-page-layout-export\""));
        assertTrue(json.contains("\"friendlyUrl\" : \"/subsites\""));
        assertTrue(json.contains("\"layoutType\" : \"content\""));
        assertTrue(json.contains("\"headlessSitePage\" : {"));
        assertTrue(json.contains("\"layoutStructure\" : {"));
        assertTrue(json.contains("\"storage\" : \"api-only\""));
        assertTrue(json.contains("\"displayDepth\" : \"0\""));
        assertEquals("", err.toString().trim());
    }

    @Test
    void pageLayoutDiff_againstFile_reportsDifferences() throws Exception {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        Path exportFile = Files.createTempFile("page-export-", ".json");
        Files.writeString(exportFile, """
            {
              "kind": "liferay-page-layout-export",
              "schemaVersion": 1,
              "source": {
                "url": "/web/ub/subsites"
              },
              "headlessSitePage": {
                "pageDefinition": {
                  "pageElement": {
                    "id": "root-1",
                    "type": "Root",
                    "pageElements": [
                      {
                        "id": "widget-1",
                        "type": "Widget",
                        "definition": {
                          "widgetInstance": {
                            "widgetName": "com_liferay_site_navigation_menu_web_portlet_SiteNavigationMenuPortlet",
                            "widgetConfig": {
                              "displayDepth": "9"
                            }
                          }
                        }
                      }
                    ]
                  }
                }
              }
            }
            """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "page-layout", "diff", "--url", "/web/ub/subsites", "--file", exportFile.toString());

        String json = out.toString();
        assertEquals(1, code, "stderr=" + err);
        assertTrue(json.contains("\"equal\" : false"));
        assertTrue(json.contains("\"compareMode\" : \"pageDefinition\""));
        assertTrue(json.contains("displayDepth"));
        assertEquals("", err.toString().trim());
    }

    @Test
    void pageLayoutDiff_againstSameReferenceUrl_returnsZero() throws Exception {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "page-layout", "diff", "--url", "/web/ub/subsites", "--reference-url", "/web/ub/subsites");

        String json = out.toString();
        assertEquals(0, code, "stderr=" + err);
        assertTrue(json.contains("\"equal\" : true"));
        assertTrue(json.contains("\"diffCount\" : 0"));
        assertTrue(json.contains("\"compareMode\" : \"pageDefinition\""));
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPageDisplayPage_returnsArticleAndStructure() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // Display page: /web/global/w/premis-2025
        int code = execute(root, out, err, "inventory", "page",
            "--url", "/web/global/w/premis-2025",
            "--format", "json");

        String json = out.toString();
        assertEquals(0, code, "exit code should be 0, stderr=" + err.toString());
        assertTrue(json.contains("\"pageType\" : \"displayPage\""), "missing pageType in: " + json);
        assertTrue(json.contains("\"pageSubtype\" : \"journalArticle\""), "missing pageSubtype in: " + json);
        assertTrue(json.contains("\"urlTitle\" : \"premis-2025\""), "missing urlTitle in: " + json);
        assertTrue(json.contains("\"friendlyUrlPath\" : \"premis-2025\""), "missing friendlyUrlPath in: " + json);
        assertTrue(json.contains("\"folderBreadcrumb\" : \"Noticies > 2025 > Premis\""), "missing folder breadcrumb in: " + json);
        assertTrue(json.contains("\"contentStructureId\" : 9001"), "missing contentStructureId in: " + json);
        assertTrue(json.contains("\"contentStructure\" : {"), "missing contentStructure block in: " + json);
        assertTrue(json.contains("\"key\" : \"UB_STR_BASICA\""), "missing structure key in: " + json);
        assertTrue(json.contains("\"name\" : \"UB_STR_BASICA\""), "missing structure name in: " + json);
        assertTrue(json.contains("\"id\" : \"UB_TPL_BASICA_DETALLE\""), "missing template in: " + json);
        assertTrue(json.contains("\"id\" : \"UB_TPL_BASICA_CARD\""), "missing template card in: " + json);
        assertTrue(json.contains("\"articleProperties\" : {"), "missing articleProperties in: " + json);
        assertTrue(json.contains("\"identifier\" : \"PREMIS-001\""), "missing identifier in: " + json);
        assertTrue(json.contains("\"defaultTemplateKey\" : \"UB_TPL_BASICA\""), "missing default template key in: " + json);
        assertTrue(json.contains("\"owner\" : \"Maria Owner\""), "missing owner in: " + json);
        assertTrue(json.contains("\"lastEditor\" : \"Pau Editor\""), "missing lastEditor in: " + json);
        assertTrue(json.contains("\"vocabulary\" : \"Categoria temàtica\""), "missing public category vocabulary in: " + json);
        assertTrue(json.contains("\"vocabulary\" : \"Novetat / Nota\""), "missing internal category vocabulary in: " + json);
        assertTrue(json.contains("\"featuredImage\" : \"hero-premis\""), "missing featured image in: " + json);
        assertTrue(json.contains("\"smallImage\" : \"/images/premis-small.jpg\""), "missing small image in: " + json);
        assertTrue(json.contains("\"path\" : \"Descripció\""), "missing content field path in: " + json);
        assertTrue(json.contains("\"value\" : \"Premis 2025 detall\""), "missing normalized content field value in: " + json);
        assertTrue(json.contains("\"path\" : \"Bloc de contingut > Bloc rich text\""), "missing nested content field path in: " + json);
        assertTrue(json.contains("\"key\" : \"ub_basica_detail\""), "missing displayPageTemplate key in: " + json);
        assertTrue(json.contains("\"markedAsDefault\" : true"), "missing markedAsDefault in: " + json);
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPageDisplayPage_ignoresQueryParamsInUrl() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "inventory", "page",
            "--url", "/web/global/w/premis-2025?referer=tramits-administratius",
            "--format", "json");

        String json = out.toString();
        assertEquals(0, code, "exit code should be 0, stderr=" + err.toString());
        assertTrue(json.contains("\"urlTitle\" : \"premis-2025\""), "query params should be ignored: " + json);
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPageUrl_acceptsLocalizedWebPath() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "inventory", "page",
            "--url", "/ca/web/escola-doctorat/comandament",
            "--format", "json");

        String json = out.toString();
        assertEquals(0, code, "exit code should be 0, stderr=" + err.toString());
        assertTrue(json.contains("\"pageSubtype\" : \"embedded\""), "missing embedded pageSubtype in: " + json);
        assertTrue(json.contains("\"targetUrl\" : \"https://powerbi.example/report\""), "missing embedded targetUrl in: " + json);
        assertTrue(json.contains("\"siteName\" : \"Escola de Doctorat\""), "missing site name in: " + json);
        assertTrue(json.contains("\"friendlyURL\" : \"/comandament\""), "missing normalized friendly URL in: " + json);
        assertTrue(json.contains("\"type\" : \"embedded\""), "missing embedded layout type in: " + json);
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPageUrl_acceptsAbsoluteLocalizedWebUrl() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "inventory", "page",
            "--url", "https://web.ub.edu/ca/web/escola-doctorat/comandament",
            "--format", "json");

        String json = out.toString();
        assertEquals(0, code, "exit code should be 0, stderr=" + err.toString());
        assertTrue(json.contains("\"pageSubtype\" : \"embedded\""), "missing embedded pageSubtype in: " + json);
        assertTrue(json.contains("\"targetUrl\" : \"https://powerbi.example/report\""), "missing embedded targetUrl in: " + json);
        assertTrue(json.contains("\"siteName\" : \"Escola de Doctorat\""), "missing site name in: " + json);
        assertTrue(json.contains("\"layoutId\" : 101"), "missing layoutId in: " + json);
        assertTrue(json.contains("\"type\" : \"embedded\""), "missing embedded layout type in: " + json);
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPageJson_distinguishesLayoutSubtypes() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int portletCode = execute(root, out, err, "inventory", "page",
            "--url", "/web/ub/inici",
            "--format", "json");
        String portletJson = out.toString();
        assertEquals(0, portletCode, "exit code should be 0, stderr=" + err.toString());
        assertTrue(portletJson.contains("\"pageSubtype\" : \"portlet\""), "missing portlet subtype in: " + portletJson);
        assertTrue(portletJson.contains("\"layoutTemplateId\" : \"home\""), "missing portlet layout template in: " + portletJson);
        assertFalse(portletJson.contains("\"fragmentEntryLinks\""), "portlet page should omit component inventory: " + portletJson);
        assertFalse(portletJson.contains("\"widgets\""), "portlet page should omit widgets: " + portletJson);
        assertFalse(portletJson.contains("\"journalArticles\""), "portlet page should omit journal articles: " + portletJson);
        assertFalse(portletJson.contains("\"contentStructures\""), "portlet page should omit content structures: " + portletJson);

        out.reset();
        err.reset();

        int urlCode = execute(root, out, err, "inventory", "page",
            "--url", "/web/ub/seu-electronica",
            "--format", "json");
        String urlJson = out.toString();
        assertEquals(0, urlCode, "exit code should be 0, stderr=" + err.toString());
        assertTrue(urlJson.contains("\"pageSubtype\" : \"url\""), "missing url subtype in: " + urlJson);
        assertTrue(urlJson.contains("\"targetUrl\" : \"https://seu.example.test\""), "missing redirect target in: " + urlJson);
        assertTrue(urlJson.contains("\"type\" : \"url\""), "missing layout type url in: " + urlJson);
        assertFalse(urlJson.contains("\"fragmentEntryLinks\""), "url page should omit component inventory: " + urlJson);

        out.reset();
        err.reset();

        int nodeCode = execute(root, out, err, "inventory", "page",
            "--url", "/web/ub/peu-de-p%C3%A0gina",
            "--format", "json");
        String nodeJson = out.toString();
        assertEquals(0, nodeCode, "exit code should be 0, stderr=" + err.toString());
        assertTrue(nodeJson.contains("\"pageSubtype\" : \"node\""), "missing node subtype in: " + nodeJson);
        assertTrue(nodeJson.contains("\"layoutTemplateId\" : \"interior\""), "missing node layout template in: " + nodeJson);
        assertTrue(nodeJson.contains("\"type\" : \"node\""), "missing layout type node in: " + nodeJson);
        assertFalse(nodeJson.contains("\"fragmentEntryLinks\""), "node page should omit component inventory: " + nodeJson);
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPageText_printsSummary() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "inventory", "page",
            "--site", "/global",
            "--friendly-url", "/inici");

        String text = out.toString();
        assertEquals(0, code, "exit code should be 0, stderr=" + err.toString());
        assertTrue(text.contains("REGULAR PAGE"), "missing REGULAR PAGE header in: " + text);
        assertTrue(text.contains("Layout ID: 7"), "missing Layout ID in: " + text);
        assertTrue(text.contains("Friendly URL: /inici"), "missing Friendly URL in: " + text);
        assertTrue(text.contains("FRAGMENTS ("), "missing FRAGMENTS header in: " + text);
        assertTrue(text.contains("ub-banner"), "missing ub-banner fragment in: " + text);
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPageJson_siteResolutionByNumericId() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "inventory", "page",
            "--site", "20124",
            "--friendly-url", "/inici",
            "--format", "json");

        String json = out.toString();
        assertEquals(0, code, "exit code should be 0, stderr=" + err.toString());
        assertTrue(json.contains("\"layoutId\" : 7"), "missing layoutId in: " + json);
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPageSiteRoot_listsTopLevelPages() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // URL with no page path → site root listing
        int code = execute(root, out, err, "inventory", "page",
            "--url", "/web/global",
            "--format", "json");

        String json = out.toString();
        assertEquals(0, code, "exit code should be 0, stderr=" + err.toString());
        assertTrue(json.contains("\"pageType\" : \"siteRoot\""), "missing siteRoot in: " + json);
        assertTrue(json.contains("\"pages\""), "missing pages in: " + json);
        assertTrue(json.contains("\"/inici\""), "missing /inici page in: " + json);
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPagesJson_listsHierarchyAndTargets() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "inventory", "pages",
            "--site", "/ub",
            "--format", "json");

        String json = out.toString();
        assertEquals(0, code, "exit code should be 0, stderr=" + err.toString());
        assertTrue(json.contains("\"inventoryType\" : \"pages\""), "missing inventoryType in: " + json);
        assertTrue(json.contains("\"siteName\" : \"Universitat de Barcelona\""), "missing site name in: " + json);
        assertTrue(json.contains("\"sitePathPrefix\" : \"/web/ub\""), "missing sitePathPrefix in: " + json);
        assertTrue(json.contains("\"pageCount\" : 5"), "missing total page count in: " + json);
        assertTrue(json.contains("\"pageSubtype\" : \"url\""), "missing url subtype in: " + json);
        assertTrue(json.contains("\"targetUrl\" : \"https://seu.example.test\""), "missing targetUrl in: " + json);
        assertTrue(json.contains("\"name\" : \"Cookies\""), "missing child page in hierarchy: " + json);
        assertTrue(json.contains("\"friendlyUrl\" : \"/cookies\""), "missing child friendlyUrl in: " + json);
        assertTrue(json.contains("\"fullUrl\" : \"/web/ub/cookies\""), "missing fullUrl in: " + json);
        assertTrue(json.contains("\"pageCommand\" : \"inventory page --url /web/ub/cookies\""),
            "missing pageCommand in: " + json);
        assertEquals("", err.toString().trim());
    }

    @Test
    void inventoryPagesText_printsHierarchicalTree() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "inventory", "pages",
            "--site", "/ub");

        String text = out.toString();
        assertEquals(0, code, "exit code should be 0, stderr=" + err.toString());
        assertTrue(text.contains("SITE PAGES"), "missing SITE PAGES header in: " + text);
        assertTrue(text.contains("Site Path Prefix: /web/ub"), "missing site path prefix in: " + text);
        assertTrue(text.contains("Total Pages: 5"), "missing total page count in: " + text);
        assertTrue(text.contains("Inspect Command Template: inventory page --url <fullUrl>"),
            "missing inspect command template in: " + text);
        assertTrue(text.contains("- Seu electrònica [url] /web/ub/seu-electronica -> https://seu.example.test"),
            "missing target url line in: " + text);
        assertTrue(text.contains("- Peu de pàgina [node] /web/ub/peu-de-p%C3%A0gina"), "missing node line in: " + text);
        assertTrue(text.contains("  - Cookies [portlet] /web/ub/cookies"), "missing indented child page in: " + text);
        assertEquals("", err.toString().trim());
    }

    @Test
    void resolveAdtJson_resolvesDisplayStyleToPreferredLocalFile() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(
            root,
            out,
            err,
            "resource",
            "resolve-adt",
            "--display-style",
            "ddmTemplate_19690804",
            "--site",
            "/global",
            "--format",
            "json"
        );

        String json = out.toString();
        assertEquals(0, code, "stderr=" + err);
        assertTrue(json.contains("\"siteFriendlyUrl\" : \"/global\""), "missing site in: " + json);
        assertTrue(json.contains("\"widgetType\" : \"search-result-summary\""), "missing widgetType in: " + json);
        assertTrue(json.contains("\"templateId\" : \"19690804\""), "missing templateId in: " + json);
        assertTrue(
            json.contains("\"preferredLocalFile\" : \"liferay/resources/templates/application_display/global/search_result_summary/UB_ADT_ACTIVIDADES_SEARCH.ftl\""),
            "missing preferred local file in: " + json
        );
        assertTrue(json.contains("\"syncCommand\" : \"resource sync-adt --file"), "missing sync command in: " + json);
        assertEquals("", err.toString().trim());
    }

    @Test
    void syncAdts_requiresExplicitBulkConfirmation() {
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            new FakeApiClient()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(root, out, err, "resource", "sync-adts", "--site", "/global");

        assertEquals(1, code);
        assertTrue(err.toString().contains("no forma parte del camino normal"));
    }

    @Test
    void fragmentsSync_usesJsonwsWithoutNode() throws Exception {
        FakeApiClient apiClient = new FakeApiClient();
        LiferayCLIMain.RootCommand root = new LiferayCLIMain.RootCommand(
            runtimeSettings(),
            new FakeTokenClient(),
            apiClient
        );

        Path projectDir = Files.createTempDirectory("ub-fragments-sync-test");
        Path fragmentDir = projectDir.resolve("src")
            .resolve("ub-base")
            .resolve("fragments")
            .resolve("ub-banner");
        Files.createDirectories(fragmentDir);
        Files.writeString(projectDir.resolve("src").resolve("ub-base").resolve("collection.json"), """
            {"name":"UB Base","description":""}
            """);
        Files.writeString(fragmentDir.resolve("fragment.json"), """
            {"name":"UB Banner","icon":"code","type":"component","htmlPath":"index.html","cssPath":"index.css","jsPath":"index.js","configurationPath":"configuration.json"}
            """);
        Files.writeString(fragmentDir.resolve("index.html"), "<div>banner</div>");
        Files.writeString(fragmentDir.resolve("index.css"), ".banner{}");
        Files.writeString(fragmentDir.resolve("index.js"), "console.log('banner');");
        Files.writeString(fragmentDir.resolve("configuration.json"), "{}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(
            root,
            out,
            err,
            "resource",
            "fragments-sync",
            "--group-id",
            "20124",
            "--dir",
            projectDir.toString(),
            "--fragment",
            "ub-banner"
        );

        assertEquals(0, code);
        assertTrue(out.toString().contains("imported=1 errors=0"));
        assertEquals("", err.toString().trim());
        assertTrue(apiClient.calledPostPath("/api/jsonws/fragment.fragmententry/update-fragment-entry"));
    }

    private static int execute(
        LiferayCLIMain.RootCommand root,
        ByteArrayOutputStream out,
        ByteArrayOutputStream err,
        String... args
    ) {
        PrintStream prevOut = System.out;
        PrintStream prevErr = System.err;
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));
            return new CommandLine(root).execute(args);
        }
        finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
    }

    private static RuntimeConfig runtimeSettings() {
        return new RuntimeConfig(
            "http://localhost:8080",
            "test-client-id",
            "test-client-secret",
            "scope.one,scope.two",
            30,
            Map.of()
        );
    }

    private static class FakeTokenClient extends OAuthTokenClient {
        FakeTokenClient() {
            super(HttpClient.newHttpClient());
        }

        @Override
        public TokenResponse fetchClientCredentialsToken(RuntimeConfig settings) {
            return new TokenResponse("token-1234567890", "Bearer", 3600L);
        }
    }

    private static class FakeApiClient extends LiferayApiClient {
        private final Set<String> postPaths = new LinkedHashSet<>();

        FakeApiClient() {
            super(HttpClient.newHttpClient());
        }

        @Override
        public ApiResponse get(String baseUrl, String path, String bearerToken, int timeoutSeconds) {
            if (path.startsWith("/api/jsonws/company/get-companies")) {
                return new ApiResponse(200, "[{\"companyId\":20097}]");
            }
            if (path.contains("/api/jsonws/classname/fetch-class-name?value=com.liferay.portlet.display.template.PortletDisplayTemplate")) {
                return new ApiResponse(200, "{\"classNameId\":7001}");
            }
            if (path.contains("/api/jsonws/classname/fetch-class-name?value=com.liferay.portal.search.web.internal.result.display.context.SearchResultSummaryDisplayContext")) {
                return new ApiResponse(200, "{\"classNameId\":7002}");
            }
            if (path.startsWith("/api/jsonws/classname/fetch-class-name?value=")) {
                return new ApiResponse(200, "{\"classNameId\":7999}");
            }
            if (path.startsWith("/api/jsonws/group/search-count?companyId=20097")) {
                return new ApiResponse(200, "2");
            }
            if (path.startsWith("/api/jsonws/group/search?companyId=20097")) {
                return new ApiResponse(200, "[" +
                    "{\"groupId\":20124,\"friendlyURL\":\"/global\",\"nameCurrentValue\":\"Global\",\"site\":true}," +
                    "{\"groupId\":2685349,\"friendlyURL\":\"/ub\",\"nameCurrentValue\":\"Universitat de Barcelona\",\"site\":true}" +
                    "]");
            }
            if (path.startsWith("/o/headless-admin-user/v1.0/sites/by-friendly-url-path/global")) {
                return new ApiResponse(200, "{\"id\":20124,\"companyId\":20097,\"friendlyUrlPath\":\"/global\",\"name\":\"Global\"}");
            }
            if (path.startsWith("/o/headless-admin-user/v1.0/sites/by-friendly-url-path/ub")) {
                return new ApiResponse(200, "{\"id\":2685349,\"companyId\":20097,\"friendlyUrlPath\":\"/ub\",\"name\":\"Universitat de Barcelona\"}");
            }
            if (path.startsWith("/o/headless-admin-user/v1.0/sites/by-friendly-url-path/escola-doctorat")) {
                return new ApiResponse(200, "{\"id\":7772917,\"companyId\":20097,\"friendlyUrlPath\":\"/escola-doctorat\",\"name\":\"Escola de Doctorat\"}");
            }
            if (path.startsWith("/o/headless-admin-user/v1.0/sites/20124")) {
                return new ApiResponse(200, "{\"id\":20124,\"companyId\":20097,\"friendlyUrlPath\":\"/global\",\"name\":\"Global\"}");
            }
            if (path.startsWith("/o/headless-admin-user/v1.0/sites/2685349")) {
                return new ApiResponse(200, "{\"id\":2685349,\"companyId\":20097,\"friendlyUrlPath\":\"/ub\",\"name\":\"Universitat de Barcelona\"}");
            }
            if (path.startsWith("/o/headless-admin-user/v1.0/sites/7772917")) {
                return new ApiResponse(200, "{\"id\":7772917,\"companyId\":20097,\"friendlyUrlPath\":\"/escola-doctorat\",\"name\":\"Escola de Doctorat\"}");
            }
            if (path.startsWith("/o/data-engine/v2.0/sites/20124/data-definitions/by-content-type/journal?page=1")) {
                return new ApiResponse(200, "{\"items\":[{},{},{}],\"lastPage\":1}");
            }
            if (path.startsWith("/o/headless-delivery/v1.0/sites/20124/content-templates?page=1")) {
                return new ApiResponse(200, "{\"items\":[{}],\"lastPage\":1}");
            }
            if (path.startsWith("/api/jsonws/ddm.ddmtemplate/get-templates?companyId=20097&groupId=20124&classNameId=7002&resourceClassNameId=7001&status=0")) {
                return new ApiResponse(200, "[" +
                    "{\"templateId\":19690804,\"templateKey\":\"UB_ADT_ACTIVIDADES_SEARCH\",\"externalReferenceCode\":\"UB_ADT_ACTIVIDADES_SEARCH\"," +
                    "\"nameCurrentValue\":\"UB_ADT_ACTIVIDADES_SEARCH\",\"script\":\"<#if entries?has_content>...</#if>\"}" +
                    "]");
            }
            if (path.startsWith("/api/jsonws/ddm.ddmtemplate/get-templates?companyId=20097&groupId=20124")) {
                return new ApiResponse(200, "[]");
            }
            if (path.startsWith("/api/jsonws/fragment.fragmentcollection/get-fragment-collections?groupId=20124")) {
                return new ApiResponse(200, "[{\"fragmentCollectionId\":1100,\"fragmentCollectionKey\":\"ub-base\",\"name\":\"UB Base\"}]");
            }
            if (path.startsWith("/api/jsonws/fragment.fragmententry/get-fragment-entries?fragmentCollectionId=1100")) {
                return new ApiResponse(200, "[{\"fragmentEntryId\":3001,\"fragmentEntryKey\":\"ub-banner\",\"name\":\"UB Banner\",\"fragmentCollectionId\":1100}]");
            }
            if (path.equals("/api/jsonws?discover")) {
                return new ApiResponse(200, "{\"services\":[{\"path\":\"/fragment.fragmententrylink/get-fragment-entry-links\",\"method\":\"GET\"}]}");
            }
            // Fragment entry links for plid=406 — uses new endpoint name (no -by-plid)
            if (path.startsWith("/api/jsonws/fragment.fragmententrylink/get-fragment-entry-links?groupId=20124&plid=406")) {
                String journalPortletPrefs = "{\\\"com_liferay_journal_content_web_portlet_JournalContentPortlet\\\":{" +
                    "\\\"portletPreferencesMap\\\":{" +
                    "\\\"articleId\\\":[\\\"ARTICLE-001\\\"]," +
                    "\\\"groupId\\\":[\\\"20124\\\"]," +
                    "\\\"ddmTemplateKey\\\":[\\\"UB_TPL_BASICA\\\"]}}}";
                return new ApiResponse(200, "[" +
                    "{\"fragmentEntryLinkId\":501,\"fragmentEntryKey\":\"ub-banner\",\"rendererKey\":\"ub-banner\",\"position\":0,\"editableValues\":\"{}\"}," +
                    "{\"fragmentEntryLinkId\":502,\"fragmentEntryKey\":\"\",\"rendererKey\":\"com_liferay_journal_content_web_portlet_JournalContentPortlet\",\"position\":1,\"editableValues\":\"" + journalPortletPrefs + "\"}" +
                    "]");
            }
            // JournalArticle fetch
            if (path.startsWith("/api/jsonws/journal.journalarticle/get-latest-article?groupId=20124&articleId=ARTICLE-001")) {
                return new ApiResponse(200, "{\"articleId\":\"ARTICLE-001\",\"id\":55001,\"titleCurrentValue\":\"Mi artículo de prueba\"," +
                    "\"ddmStructureKey\":\"UB_STR_BASICA\",\"resourcePrimKey\":88001}");
            }
            // Headless: structured content by id (for resolving contentStructureId)
            if (path.startsWith("/o/headless-delivery/v1.0/structured-contents/55001")) {
                return new ApiResponse(200, "{\"id\":55001,\"friendlyUrlPath\":\"article-001\",\"contentStructureId\":9001}");
            }
            if (path.startsWith("/o/headless-delivery/v1.0/structured-contents/88001")) {
                return new ApiResponse(200, "{\"id\":88001,\"friendlyUrlPath\":\"premis-2025\",\"contentStructureId\":9001," +
                    "\"datePublished\":\"2025-01-15T08:00:00Z\",\"dateModified\":\"2025-01-16T09:30:00Z\",\"priority\":1.0," +
                    "\"contentFields\":[" +
                    "{\"label\":\"Títol\",\"name\":\"titulo\",\"dataType\":\"string\",\"contentFieldValue\":{\"data\":\"Premis 2025\"},\"nestedContentFields\":[]," +
                    "\"repeatable\":false}," +
                    "{\"label\":\"Imatge destacada\",\"name\":\"imagenDestacada\",\"dataType\":\"image\",\"contentFieldValue\":{\"image\":{\"title\":\"hero-premis\"," +
                    "\"contentUrl\":\"/documents/20124/0/hero-premis.jpg\"}},\"nestedContentFields\":[],\"repeatable\":false}," +
                    "{\"label\":\"Descripció\",\"name\":\"descripcion\",\"dataType\":\"string\",\"contentFieldValue\":{\"data\":\"<p>Premis <strong>2025</strong> detall</p>\"}," +
                    "\"nestedContentFields\":[],\"repeatable\":false}," +
                    "{\"label\":\"Bloc de contingut\",\"name\":\"bloqueContenido\",\"nestedContentFields\":[" +
                    "{\"label\":\"Bloc rich text\",\"name\":\"bloqueRichText\",\"dataType\":\"string\",\"contentFieldValue\":{\"data\":\"<p>Informació ampliada</p>\"}," +
                    "\"nestedContentFields\":[],\"repeatable\":false}" +
                    "],\"repeatable\":false}" +
                    "]}");
            }
            // Headless: content structure by id
            if (path.startsWith("/o/headless-delivery/v1.0/content-structures/9001")) {
                return new ApiResponse(200, "{\"id\":9001,\"name\":\"UB_STR_BASICA\",\"siteId\":20124}");
            }
            if (path.startsWith("/o/data-engine/v2.0/data-definitions/9001")) {
                return new ApiResponse(200, "{\"id\":9001,\"dataDefinitionKey\":\"UB_STR_BASICA\",\"siteId\":20124," +
                    "\"name\":{\"ca_ES\":\"UB_STR_BASICA\"}}");
            }
            // headless-admin-content: display page templates for site
            if (path.startsWith("/o/headless-admin-content/v1.0/sites/20124/display-page-templates")) {
                return new ApiResponse(200, "{\"items\":[" +
                    "{\"displayPageTemplateKey\":\"ub_basica_detail\",\"title\":\"UB_BASICA_DETAIL\",\"markedAsDefault\":true," +
                    "\"displayPageTemplateSettings\":{\"contentAssociation\":{\"contentSubtype\":\"UB_STR_BASICA\",\"contentType\":\"StructuredContent\"}}}" +
                    "],\"lastPage\":1,\"totalCount\":1}");
            }
            // Headless: content templates for site (filtered client-side by contentStructureId)
            if (path.startsWith("/o/headless-delivery/v1.0/sites/20124/content-templates")) {
                return new ApiResponse(200, "{\"items\":[" +
                    "{\"id\":\"UB_TPL_BASICA_DETALLE\",\"name\":\"UB_TPL_BASICA_DETALLE\",\"contentStructureId\":9001}," +
                    "{\"id\":\"UB_TPL_BASICA_CARD\",\"name\":\"UB_TPL_BASICA_CARD\",\"contentStructureId\":9001}," +
                    "{\"id\":\"UB_TPL_OTHER\",\"name\":\"UB_TPL_OTHER\",\"contentStructureId\":9999}" +
                    "],\"lastPage\":1,\"totalCount\":3}");
            }
            // JSONWS: get article by urlTitle (for folderId in display page)
            if (path.contains("/api/jsonws/journal.journalarticle/get-article-by-url-title") && path.contains("premis-2025")) {
                return new ApiResponse(200, "{\"articleId\":\"PREMIS-001\",\"id_\":88001,\"resourcePrimKey\":88001," +
                    "\"folderId\":35136616,\"groupId\":20124,\"titleCurrentValue\":\"Premis 2025\"," +
                    "\"version\":1.7,\"status\":0,\"DDMTemplateKey\":\"UB_TPL_BASICA\"," +
                    "\"externalReferenceCode\":\"erc-premis-001\",\"displayDate\":1736899200000," +
                    "\"modifiedDate\":1736985600000,\"indexable\":true,\"userName\":\"Maria Owner\"," +
                    "\"statusByUserName\":\"Pau Editor\",\"smallImage\":true,\"smallImageURL\":\"/images/premis-small.jpg\"}");
            }
            if (path.startsWith("/api/jsonws/journal.journalfolder/get-folder?folderId=35136616")) {
                return new ApiResponse(200, "{\"folderId\":35136616,\"parentFolderId\":35136000,\"name\":\"Premis\"}");
            }
            if (path.startsWith("/api/jsonws/journal.journalfolder/get-folder?folderId=35136000")) {
                return new ApiResponse(200, "{\"folderId\":35136000,\"parentFolderId\":35135000,\"name\":\"2025\"}");
            }
            if (path.startsWith("/api/jsonws/journal.journalfolder/get-folder?folderId=35135000")) {
                return new ApiResponse(200, "{\"folderId\":35135000,\"parentFolderId\":0,\"name\":\"Noticies\"}");
            }
            if (path.startsWith("/api/jsonws/assetentry/get-entry?className=com.liferay.journal.model.JournalArticle&classPK=88001")) {
                return new ApiResponse(200, "{\"entryId\":90001,\"visible\":true,\"listable\":true,\"priority\":1.0,\"publishDate\":1736928000000}");
            }
            if (path.startsWith("/api/jsonws/assetcategory/get-categories?className=com.liferay.journal.model.JournalArticle&classPK=88001")) {
                return new ApiResponse(200, "[" +
                    "{\"titleCurrentValue\":\"Institucional\",\"vocabularyId\":5001}," +
                    "{\"titleCurrentValue\":\"Novetat\",\"vocabularyId\":5002}" +
                    "]");
            }
            if (path.startsWith("/api/jsonws/assetvocabulary/get-vocabulary?vocabularyId=5001")) {
                return new ApiResponse(200, "{\"titleCurrentValue\":\"Categoria temàtica\",\"visibilityType\":0}");
            }
            if (path.startsWith("/api/jsonws/assetvocabulary/get-vocabulary?vocabularyId=5002")) {
                return new ApiResponse(200, "{\"titleCurrentValue\":\"Novetat / Nota\",\"visibilityType\":1}");
            }
            if (path.startsWith("/api/jsonws/assettag/get-tags?className=com.liferay.journal.model.JournalArticle&classPK=88001")) {
                return new ApiResponse(200, "[{\"name\":\"premis\"}]");
            }
            // Headless: display page structured-contents filter by friendlyUrlPath
            if (path.contains("/o/headless-delivery/v1.0/sites/20124/structured-contents") && path.contains("premis-2025")) {
                return new ApiResponse(200, "{\"items\":[{\"id\":88001,\"key\":\"PREMIS-001\",\"friendlyUrlPath\":\"premis-2025\",\"title\":\"Premis 2025\",\"contentStructureId\":9001}],\"totalCount\":1,\"lastPage\":1}");
            }
            // Headless site-pages: regular page definition with fragments and a widget
            if (path.startsWith("/o/headless-delivery/v1.0/sites/20124/site-pages/inici")) {
                return new ApiResponse(200, "{\"friendlyUrlPath\":\"/inici\",\"pageDefinition\":{\"pageElement\":{\"type\":\"Root\",\"pageElements\":[" +
                    "{\"type\":\"Fragment\",\"definition\":{\"fragment\":{\"key\":\"ub-banner\"}},\"pageElements\":[]}," +
                    "{\"type\":\"Widget\",\"definition\":{\"widgetInstance\":{\"widgetName\":\"com_liferay_journal_content_web_portlet_JournalContentPortlet\"}},\"pageElements\":[]}" +
                    "]}}}");
            }
            if (path.startsWith("/o/headless-delivery/v1.0/sites/2685349/site-pages/subsites/experiences")) {
                return new ApiResponse(200, "{\"items\":[{\"experience\":{\"key\":\"DEFAULT\",\"name\":\"Default\"},\"pageType\":\"Pàgina de contingut\"}],\"lastPage\":1,\"totalCount\":1}");
            }
            if (path.startsWith("/o/headless-delivery/v1.0/sites/2685349/site-pages/subsites")) {
                return new ApiResponse(200, "{\"actions\":{\"get\":{\"method\":\"GET\"}},\"friendlyUrlPath\":\"/subsites\",\"id\":1720," +
                    "\"pageDefinition\":{\"pageElement\":{\"id\":\"root-1\",\"type\":\"Root\",\"pageElements\":[" +
                    "{\"id\":\"section-1\",\"type\":\"Section\",\"pageElements\":[" +
                    "{\"id\":\"widget-1\",\"type\":\"Widget\",\"definition\":{\"widgetInstance\":{\"widgetName\":\"com_liferay_site_navigation_menu_web_portlet_SiteNavigationMenuPortlet\",\"widgetConfig\":{\"displayDepth\":\"0\"}}}}" +
                    "]}" +
                    "]},\"settings\":{\"themeName\":\"ub\"},\"version\":1.1}," +
                    "\"pageType\":\"Pàgina de contingut\",\"siteId\":2685349,\"title\":\"Subsites\",\"uuid\":\"subsites-uuid\"}");
            }
            if (path.startsWith("/o/headless-delivery/v1.0/sites/7772917/site-pages/comandament")) {
                return new ApiResponse(200, "{\"friendlyUrlPath\":\"/comandament\",\"pageDefinition\":{\"pageElement\":{\"type\":\"Root\",\"pageElements\":[]}}}");
            }
            if (path.startsWith("/api/jsonws/layout/get-layouts?groupId=20124&privateLayout=false&parentLayoutId=0")) {
                return new ApiResponse(200, "[" +
                    "{\"layoutId\":7,\"friendlyURL\":\"/inici\",\"plid\":406,\"type\":\"content\",\"nameCurrentValue\":\"Actualitat UB\"}," +
                    "{\"layoutId\":9,\"friendlyURL\":\"/noticies\",\"plid\":408,\"type\":\"content\",\"nameCurrentValue\":\"Noticies\"}" +
                    "]");
            }
            if (path.startsWith("/api/jsonws/layout/get-layouts?groupId=7772917&privateLayout=false&parentLayoutId=0")) {
                return new ApiResponse(200, "[" +
                    "{\"layoutId\":101,\"friendlyURL\":\"/comandament\",\"plid\":2994,\"type\":\"embedded\",\"nameCurrentValue\":\"Quadre de comandament\"," +
                    "\"typeSettings\":\"embeddedLayoutURL=https://powerbi.example/report\\nlayout-template-id=2_columns_ii\\nlayoutUpdateable=true\\n\"}" +
                    "]");
            }
            if (path.startsWith("/api/jsonws/layout/get-layouts?groupId=2685349&privateLayout=false&parentLayoutId=0")) {
                return new ApiResponse(200, "[" +
                    "{\"layoutId\":1,\"friendlyURL\":\"/inici\",\"plid\":76,\"type\":\"portlet\",\"nameCurrentValue\":\"Inici\"," +
                    "\"typeSettings\":\"layout-template-id=home\\nlayoutUpdateable=true\\n\"}," +
                    "{\"layoutId\":119,\"friendlyURL\":\"/subsites\",\"plid\":1720,\"type\":\"content\",\"nameCurrentValue\":\"Subsites\"," +
                    "\"typeSettings\":\"layout-template-id=1_column\\nlayoutUpdateable=true\\n\"}," +
                    "{\"layoutId\":34,\"friendlyURL\":\"/seu-electronica\",\"plid\":145,\"type\":\"url\",\"nameCurrentValue\":\"Seu electrònica\"," +
                    "\"typeSettings\":\"layout-template-id=2_columns_ii\\nlayoutUpdateable=true\\nurl=https://seu.example.test\\n\"}," +
                    "{\"layoutId\":27,\"friendlyURL\":\"/peu-de-p%C3%A0gina\",\"plid\":138,\"type\":\"node\",\"nameCurrentValue\":\"Peu de pàgina\"," +
                    "\"typeSettings\":\"layout-template-id=interior\\nlayoutUpdateable=true\\n\"}" +
                    "]");
            }
            if (path.startsWith("/api/jsonws/layout/get-layouts?groupId=2685349&privateLayout=false&parentLayoutId=27")) {
                return new ApiResponse(200, "[" +
                    "{\"layoutId\":29,\"friendlyURL\":\"/cookies\",\"plid\":140,\"type\":\"portlet\",\"nameCurrentValue\":\"Cookies\"," +
                    "\"typeSettings\":\"layout-template-id=interior\\nlayoutUpdateable=true\\n\"}" +
                    "]");
            }
            if (path.startsWith("/api/jsonws/layout/get-layouts?groupId=20124&privateLayout=false&parentLayoutId=")) {
                // Child layout requests return empty
                return new ApiResponse(200, "[]");
            }
            if (path.startsWith("/api/jsonws/layout/get-layouts?groupId=2685349&privateLayout=false&parentLayoutId=")) {
                return new ApiResponse(200, "[]");
            }
            if (path.startsWith("/api/jsonws/layout/get-layouts?groupId=7772917&privateLayout=false&parentLayoutId=")) {
                return new ApiResponse(200, "[]");
            }
            return new ApiResponse(404, "{}");
        }

        @Override
        public ApiResponse postForm(
            String baseUrl,
            String path,
            String bearerToken,
            int timeoutSeconds,
            Map<String, String> form
        ) {
            postPaths.add(path);
            if (path.startsWith("/api/jsonws/fragment.fragmententry/update-fragment-entry")) {
                return new ApiResponse(200, "{\"fragmentEntryId\":3001}");
            }
            if (path.startsWith("/api/jsonws/fragment.fragmententry/add-fragment-entry")) {
                return new ApiResponse(200, "{\"fragmentEntryId\":3002}");
            }
            if (path.startsWith("/api/jsonws/fragment.fragmentcollection/add-fragment-collection")) {
                return new ApiResponse(200, "{\"fragmentCollectionId\":1100,\"fragmentCollectionKey\":\"ub-base\",\"name\":\"UB Base\"}");
            }
            if (path.startsWith("/api/jsonws/fragment.fragmentcollection/update-fragment-collection")) {
                return new ApiResponse(200, "{\"fragmentCollectionId\":1100}");
            }
            return new ApiResponse(404, "{}");
        }

        boolean calledPostPath(String expectedPath) {
            return postPaths.stream().anyMatch(path -> path.startsWith(expectedPath));
        }
    }

}
