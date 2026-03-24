# Contributing to liferay-cli

liferay-cli is a portable Java CLI (fat JAR) for interacting with Liferay DXP instances.
It is designed to be extracted as an independent repository.

## Requirements

- Java 21 (Azul Zulu or Eclipse Temurin recommended)
- Gradle wrapper included (`./gradlew`)

## Build

```bash
./gradlew uberJar --console=plain
# Output: build/libs/liferay-cli-all.jar
```

## Test

```bash
./gradlew test --console=plain
```

## Run

```bash
java -jar build/libs/liferay-cli-all.jar --help
```

## Code conventions

- Package prefix: `io.github.liferaycli.*`
- No hardcoded UB-specific credentials or project references in code logic
- Configuration is loaded from environment variables and `.env` files at runtime
- Configuration file paths (e.g. OSGi config filenames) may reference project-specific names
  as string constants — this is acceptable and expected
- Use `RuntimeConfig` for all connection/auth settings — never hardcode base URLs

## Environment variables

| Variable | Description |
|---|---|
| `LIFERAY_CLI_URL` | Liferay instance URL (overrides all other sources) |
| `LIFERAY_CLI_OAUTH2_CLIENT_ID` | OAuth2 client ID |
| `LIFERAY_CLI_OAUTH2_CLIENT_SECRET` | OAuth2 client secret |
| `LIFERAY_CLI_HTTP_TIMEOUT_SECONDS` | HTTP timeout in seconds (default: 30) |
| `LIFERAY_CLI_PROFILE_PATH` | Path to `.liferay-cli.yml` profile file |
| `LIFERAY_CLI_REPO_ROOT` | Explicit repo root path. Skips heuristic `docker/docker-compose.yml` scan. Use when running outside the monorepo. |

## Profile file (`.liferay-cli.yml`)

Place in the repo root or current directory:

```yaml
liferay:
  url: http://localhost:8080
  oauth2:
    clientId: my-client
    clientSecret: my-secret
    timeoutSeconds: 60
```

## Design decisions

### URL resolution priority (`RuntimeConfig`)

The base URL is resolved in this order (first non-blank wins):

1. `LIFERAY_CLI_URL` / `UB_LIFERAY_URL` / `LIFERAY_HOST` — process env vars
2. Same keys from `docker/.env`
3. `BIND_IP` from `docker/.env` — **only if it is a routable (non-loopback, non-wildcard) IP**
   (e.g. `100.115.222.80`). Loopback (`127.0.0.1`, `localhost`) and wildcard (`0.0.0.0`) are skipped
   so they don't shadow a profile URL that may point to a different host/port.
4. `liferay.url` from `.liferay-cli.yml` profile
5. `http://<BIND_IP>:<LIFERAY_HTTP_PORT>` fallback (from `docker/.env`, defaulting to `localhost:8080`)
6. `http://localhost:8080` hard default

**Why**: in server environments where Docker binds to a specific IP (not localhost),
`docker/.env` is the single source of truth. The profile YAML provides user-level defaults
for developer laptops where Docker binds to localhost.

### Why headless-delivery for templates (not JSONWS)

`GET /api/jsonws/ddm.ddmtemplate/get-templates` returns an empty array in Liferay DXP 2025.Q1
despite records existing in the `ddmtemplate` table. The `ddmtemplate` table no longer has a
`status` column; the JSONWS method's internal query silently returns nothing.

The correct endpoint for Liferay 2025.Q1+ is:
```
GET /o/headless-delivery/v1.0/sites/{siteId}/content-templates
```
This is a stable REST API that returns all Journal Article templates for the site.

### Why `resolveRepoRoot` can return `null`

The tool is designed to run both inside the monorepo (where `docker/docker-compose.yml`
and `liferay/` are present) and as a standalone JAR outside of it. When run outside:
- `resolveRepoRoot` returns `null`
- File-based config (`docker/.env`, OSGi bootstrap config) is skipped
- Connection is configured via `LIFERAY_CLI_REPO_ROOT`, `LIFERAY_CLI_URL`, or `.liferay-cli.yml`

## Adding new commands

1. Create a new `@Command`-annotated class under the appropriate package
2. Register it in the main `LiferayCli` command class
3. Add unit tests in `src/test/java/`
4. Update `--help` output documentation

## CI

GitHub Actions runs on every push to `main` and on pull requests:
- `./gradlew test` — unit tests
- `./gradlew uberJar` — fat JAR build
- Artifact uploaded as `liferay-cli-jar` (retention: 30 days)
