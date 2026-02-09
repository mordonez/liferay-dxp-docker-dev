# Liferay DXP Docker Dev

Simple Docker Compose setup for a fresh Liferay DXP plus a workspace for modules and themes.

## Requirements
- Docker Desktop

## Quick start
```bash
cp .env.example .env
make start
```
Open `http://localhost:8080`.

## Develop
Workspace is in `liferay/`:
- `liferay/modules/`
- `liferay/themes/`
- `liferay/configs/`

Build and hot-deploy:
```bash
make deploy
```
Artifacts land in `liferay/build/docker/deploy` and Liferay picks them up.

## Scripts
`liferay/scripts/` is mounted to `/usr/local/liferay/scripts` for startup hooks.

## Useful commands
- `make up`
- `make down`
- `make logs`
- `make shell`
- `make gogo`
- `make db`

## Troubleshooting
If `/mnt/liferay/deploy` is empty inside the container but the host has files, check Docker Desktop File Sharing and restart Docker.

## Reset
```bash
docker compose down -v
```

## Docs
Official Liferay Docker references:

- https://learn.liferay.com/w/dxp/self-hosted-installation-and-upgrades/using-liferay-docker-images
- https://learn.liferay.com/w/dxp/self-hosted-installation-and-upgrades/using-liferay-docker-images/configuring-containers
- https://learn.liferay.com/w/dxp/self-hosted-installation-and-upgrades/using-liferay-docker-images/providing-files-to-the-container
- https://learn.liferay.com/w/dxp/self-hosted-installation-and-upgrades/using-liferay-docker-images/installing-apps-and-other-artifacts-to-containers
- https://learn.liferay.com/w/dxp/self-hosted-installation-and-upgrades/using-liferay-docker-images/running-scripts-in-containers
- https://learn.liferay.com/w/dxp/self-hosted-installation-and-upgrades/using-liferay-docker-images/patching-dxp-in-docker
- https://learn.liferay.com/w/dxp/self-hosted-installation-and-upgrades/using-liferay-docker-images/container-lifecycle-and-api
- https://learn.liferay.com/w/dxp/self-hosted-installation-and-upgrades/using-liferay-docker-images/maintenance-and-troubleshooting-in-docker
- https://hub.docker.com/r/liferay/dxp

