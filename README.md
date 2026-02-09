# Liferay Docker Dev Environment (Fresh)

Goal: boot a fresh Liferay DXP with Docker Compose and a ready-to-use Liferay Workspace for modules and themes.

## Requirements
- Docker Desktop (includes Docker Compose)
- 8+ GB of free RAM recommended

## Quick start
1. Create the environment file:

```bash
cp .env.example .env
```

2. Start services:

```bash
make start
```

3. Open Liferay at `http://localhost:8080`.

## Configuration
Variables in `.env`:
- `LIFERAY_IMAGE`: Liferay DXP image
- `LIFERAY_HTTP_PORT`, `LIFERAY_DEBUG_PORT`, `LIFERAY_GOGO_PORT`: host ports
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT`: PostgreSQL

Portal properties can also be overridden via `LIFERAY_...` environment variables. These override `portal.properties`. 

### Portal properties via files
You can provide a `portal-ext.properties` file (and other overrides) by placing it under `liferay/build/docker/configs/dockerenv/files/` which is mounted to `/mnt/liferay/files` in the container.

## Development (modules, themes, configs)
This repo includes a Liferay Workspace in `liferay/`.

Main paths:
- Modules: `liferay/modules/`
- Themes: `liferay/themes/`
- Configs: `liferay/configs/`

Build and hot-deploy:

```bash
make deploy
```

Artifacts are generated in `liferay/build/docker/deploy` and Liferay auto-detects them.
When you run `make deploy` from the workspace root, Liferay Workspace also copies Docker configuration overlays from `liferay/configs` into `liferay/build/docker`.

### Why this approach
We use Docker Compose to run the runtime container, and Gradle only to build and copy artifacts/configs into `liferay/build/docker`. This keeps the runtime orchestration simple and explicit (Compose), while still leveraging the Liferay Workspace tooling for builds.

Note: We do not use the Gradle Docker tasks (`buildDockerImage`, `createDockerContainer`, etc.). Those are an alternative workflow where Gradle manages the container lifecycle.

## Container scripts
`liferay/scripts/` is mounted at `/usr/local/liferay/scripts` for startup hooks (for example, scripts in `pre-startup`).
Scripts placed in `/mnt/liferay/scripts` run before startup. For other phases, use the phase folders under `/usr/local/liferay/scripts` (e.g., `pre-startup`, `pre-configure`, `post-shutdown`).

## DXP licensing
The DXP image may include a trial license. If you need an official license, place your activation key following the `liferay/scripts` flow (for example in `pre-startup`).

## Useful commands
- `make up`: start services
- `make down`: stop services
- `make logs`: Liferay logs
- `make shell`: shell in the Liferay container
- `make gogo`: open Gogo shell (OSGi console)
- `make db`: Postgres shell

## Troubleshooting
### Deploy folder not visible in container
If `liferay/build/docker/deploy` has files but `/mnt/liferay/deploy` is empty inside the container, it is usually a Docker Desktop File Sharing issue.

Fix:
1. Docker Desktop -> Settings -> Resources -> File Sharing
2. Add `/Users/<your-user>/Development` (or `/Users/<your-user>`)
3. Restart Docker Desktop
4. `docker compose down && docker compose up -d`

## FAQ
Q: My module JAR was built, but it is not deployed
A: Make sure the file exists in `liferay/build/docker/deploy` and that it appears inside the container at `/mnt/liferay/deploy`. If it does not, check Docker Desktop File Sharing.

Q: I changed ports in `.env` and nothing happened
A: Recreate containers: `docker compose down` and then `docker compose up -d`.

Q: Liferay takes a long time to start
A: The first boot can take several minutes to initialize the database and deploy bundles.

## Full reset (start over)

```bash
docker compose down -v
```

This removes Postgres and Liferay data volumes.

## License
See `LICENSE`.
