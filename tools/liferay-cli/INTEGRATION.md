# liferay-cli — Guía de Integración

`liferay-cli` es un CLI Java portable para gestionar recursos Liferay DXP via Headless API.
Está desacoplado de cualquier proyecto específico y funciona con cualquier portal Liferay.

## Prerequisitos

- Java 21
- Gradle 8.5+ (Gradle Wrapper incluido: `./gradlew`)
- Portal Liferay DXP con OAuth2 habilitado (ver sección OAuth2 más abajo)

## Opciones de integración

### Opción A — Como subdirectorio del monorepo (recomendado)

Añadir como subtree de git:

```bash
git subtree add \
  --prefix tools/liferay-cli \
  https://github.com/mordonez/liferay-cli \
  main --squash
```

Actualizar más adelante:

```bash
git subtree pull \
  --prefix tools/liferay-cli \
  https://github.com/mordonez/liferay-cli \
  main --squash
```

### Opción B — Standalone (sin monorepo)

```bash
git clone https://github.com/mordonez/liferay-cli
cd liferay-cli
./gradlew uberJar
java -jar build/libs/liferay-cli-all.jar --help
```

### Opción C — Solo el JAR (CI/CD)

```bash
# En el pipeline CI, compilar el JAR y usarlo directamente
./gradlew -p tools/liferay-cli uberJar --console=plain
java -jar tools/liferay-cli/build/libs/liferay-cli-all.jar inventory structures --site /global
```

## Configuración

### Variables de entorno

| Variable | Default | Descripción |
|---|---|---|
| `LIFERAY_CLI_URL` | `http://localhost:8080` | URL del portal |
| `LIFERAY_CLI_OAUTH2_CLIENT_ID` | — | Client ID OAuth2 |
| `LIFERAY_CLI_OAUTH2_CLIENT_SECRET` | — | Client secret OAuth2 |
| `LIFERAY_CLI_HTTP_TIMEOUT_SECONDS` | `30` | Timeout HTTP en segundos |
### Perfil YAML (`.liferay-cli.yml` en raíz del proyecto)

```yaml
liferay:
  url: http://localhost:8080
  oauth2:
    # Las credenciales se generan aleatoriamente en el primer arranque.
    # Ejecuta 'task osgi:liferaycli-creds' para obtenerlas.
    clientId: ...
    clientSecret: ...
    timeoutSeconds: 60
```

paths:
  structures: liferay/resources/journal/structures
  templates: liferay/resources/journal/templates
  fragments: liferay/ub-fragments/sites
  adts: liferay/resources/templates/application_display
```

Prioridad de resolución: `LIFERAY_CLI_URL` (env) > `docker/.env` > `.liferay-cli.yml` > `http://localhost:8080`.

## Integración con Taskfile

Copia `Taskfile.liferay-cli.yml` (incluido en este directorio) a la raíz de tu proyecto e inclúyelo en tu `Taskfile.yml`:

```yaml
# Taskfile.yml de tu proyecto
version: '3'
includes:
  resources:
    taskfile: ./Taskfile.liferay-cli.yml
    flatten: true
```

O si prefieres sin namespace, usa directamente el fichero como `Taskfile.yml`.

Comandos disponibles tras la integración:

```bash
task liferay-build                                           # Compilar JAR
task liferay -- health                                       # Estado del portal
task liferay -- inventory structures --site /global          # Listar estructuras
task liferay -- resource structure-sync --key MY_STR --site /global
task liferay -- resource fragments-sync --site /global
```

## OAuth2 — Configuración en Liferay

El CLI usa OAuth2 Client Credentials para autenticarse. Necesitas crear una aplicación OAuth2 en Liferay:

1. Panel de Control → OAuth 2 Administration → Añadir aplicación
2. Tipo de cliente: **Confidencial**
3. Método de autenticación: **Client Credentials**
4. Scopes mínimos: `Liferay.Headless.Admin.Content.everything`, `Liferay.Headless.Delivery.everything`
5. Copiar Client ID y Client Secret al `.liferay-cli.yml` o variables de entorno

Para entornos locales con el módulo `liferay-cli-bootstrap`:
- **Recuperación**: Ejecuta `task osgi:liferaycli-creds` desde la raíz del monorepo.
- **Configuración**: El módulo genera credenciales aleatorias seguras y las persiste en el portal local.

## Comandos disponibles

| Comando | Descripción |
|---|---|
| `auth check` | Verificar autenticación OAuth2 |
| `auth token` | Obtener token OAuth2 (útil para debugging) |
| `health` | Estado del portal |
| `inventory structures [--site SITE]` | Listar estructuras DDM |
| `inventory templates [--site SITE]` | Listar plantillas DDM |
| `resource structure-sync --key KEY --site SITE` | Sincronizar estructura desde JSON |
| `resource structure-with-templates-sync --key KEY --site SITE` | Estructura + plantillas en 1 paso |
| `resource template-sync --structure-key KEY --site SITE` | Sincronizar plantilla desde FTL |
| `resource resolve-adt --display-style ddmTemplate_<ID>` | Resolver ADT a widgetType y fichero local |
| `resource template-sync-all --site SITE --bulk` | Bulk sync de plantillas; solo para casos excepcionales |
| `resource fragments-sync [--site SITE]` | Sincronizar fragment sets |
| `resource adt-sync --site SITE` | Sincronizar Application Display Templates |
| `theme check` | Verificar iconos Clay del tema |
| `audit [--site SITE]` | Auditoría completa de recursos del portal |

## Uso en Jenkins

```groovy
// Opción 1: Compilar y ejecutar JAR directamente
sh './gradlew -p tools/liferay-cli uberJar --console=plain'
sh 'java -jar tools/liferay-cli/build/libs/liferay-cli-all.jar audit --output-format json > audit.json'

// Opción 2: Via Taskfile (si task está instalado en el agente)
sh 'task liferay-build'
sh 'task liferay -- audit --output-format json > audit.json'
```
