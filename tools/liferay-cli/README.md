# tools/liferay-cli

CLI Java (picocli) portable para gestionar recursos Liferay DXP via Headless API.
Independiente del proyecto UB — funciona con cualquier portal Liferay.

## Requisitos

- Java 21
- Gradle 8.5+ (wrapper incluido: `./gradlew`)

## Compilar

```bash
cd tools/liferay-cli

# Fat JAR portable (para CI/Jenkins/uso standalone)
./gradlew uberJar
# Genera: build/libs/liferay-cli-all.jar

# Tests unitarios
./gradlew test

# Ejecutar sin compilar JAR (desarrollo)
./gradlew liferayCli -PliferayCliArgs="--help"
```

## Ejecutar

```bash
# Via Taskfile (punto de entrada principal)
task liferay -- --help
task liferay -- inventory structures --site /global
task deploy:prepare    # solo compilar JAR
```

# Directo con JAR (para Jenkins/CI)
java -jar tools/liferay-cli/build/libs/liferay-cli-all.jar --help
```

## Configuración

Prioridad: variable de entorno > `.liferay-cli.yml` > defaults.
| Variable | Default | Descripción |
|---|---|---|
| `LIFERAY_CLI_URL` | `http://localhost:8080` | URL del portal |
| `LIFERAY_CLI_OAUTH2_CLIENT_ID` | — | Client ID OAuth2 |
| `LIFERAY_CLI_OAUTH2_CLIENT_SECRET` | — | Client secret OAuth2 |
| `LIFERAY_CLI_HTTP_TIMEOUT_SECONDS` | `30` | Timeout HTTP |

Backward compat: acepta también `UB_LIFERAY_URL`, `LIFERAY_CLI_OAUTH2_CLIENT_ID/SECRET`.

Perfil YAML (`.liferay-cli.yml` en raíz del repo):
```yaml
liferay:
  url: http://localhost:8080
  oauth2:
    # Generado dinámicamente: task osgi:liferaycli-creds
    clientId: ...
    clientSecret: ...
```

paths:
  structures: liferay/resources/journal/structures
  templates: liferay/resources/journal/templates
  fragments: liferay/ub-fragments/sites
  adts: liferay/resources/templates/application_display
```

## Uso en Jenkins

```groovy
// ci/Jenkinsfile-before-all o pipeline personalizado
sh './gradlew -p tools/liferay-cli uberJar --console=plain'
sh "java -jar tools/liferay-cli/build/libs/liferay-cli-all.jar inventory structures --site /global"
```

O via Taskfile si `task` está instalado en el agente Jenkins:
```groovy
sh 'task liferay-build'
sh 'task liferay -- audit --output-format json > audit.json'
```

## Comandos disponibles

| Comando | Descripción |
|---|---|
| `auth check` | Verificar autenticación OAuth2 |
| `auth token` | Obtener token OAuth2 |
| `health` | Estado del portal |
| `inventory structures` | Listar estructuras DDM |
| `inventory templates` | Listar plantillas DDM |
| `page-layout export` | Exportar `pageDefinition` y metadatos útiles de una content page |
| `page-layout diff` | Comparar una content page live contra un export o contra otra página usando `pageDefinition` |
| `resource structure-sync` | Sincronizar estructura desde fichero JSON |
| `resource template-sync` | Sincronizar plantilla desde fichero FTL |
| `resource fragments-sync` | Sincronizar fragment sets |
| `theme check` | Verificar iconos del tema |
| `audit` | Auditoría de recursos del portal |

### Exit codes de `page-layout diff`

- `0`: sin diferencias
- `1`: con diferencias
- `2`: error real de ejecución

## Estructura del código

```
src/main/java/dev/mordonez/liferaycli/
  LiferayCLIMain.java          ← entrypoint picocli
  config/RuntimeConfig.java    ← configuración + perfiles YAML
  commands/                    ← subcomandos (AuthCommand, InventoryCommand, ...)
  http/                        ← LiferayApiClient, OAuthTokenClient
src/test/java/dev/mordonez/liferaycli/
  config/RuntimeConfigTest.java
  commands/CommandSmokeTest.java
```
