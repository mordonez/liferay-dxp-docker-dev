# Getting Started

## Prerequisitos

- Docker + Docker Compose v2
- Java 21
- Node.js
- [Task](https://taskfile.dev) >= 3.28
- `gh` CLI (para descargar backups desde Liferay Cloud)

---

## Caso 1 — Proyecto nuevo desde cero

```bash
git clone git@github.com:mordonez/liferay-dxp-docker-dev.git
cd liferay-dxp-docker-dev
task project:init -- --name mi-proyecto --dir ~/projects/mi-proyecto
```

Crea la estructura completa (docker/, liferay/, Taskfile.yml, módulo OAuth2) y
añade el vendor como subtree.

---

## Caso 2 — Proyecto existente con Docker

```bash
git clone git@github.com:mordonez/liferay-dxp-docker-dev.git
cd liferay-dxp-docker-dev
task project:add -- --target ~/projects/mi-proyecto
```

Añade el vendor, el Taskfile.yml y el módulo OAuth2 al proyecto existente.
El docker-compose.yml y la estructura liferay/ se mantienen intactos.

---

## Caso 3 — Proyecto con Liferay Community Edition

```bash
git clone git@github.com:mordonez/liferay-dxp-docker-dev.git
cd liferay-dxp-docker-dev
task project:add-community -- --target ~/projects/mi-proyecto
```

Igual que el caso 2, pero también crea docker/ desde la plantilla del vendor
si no existe.

---

## Después de la integración

```bash
cd ~/projects/mi-proyecto

# 1. Ajustar docker/.env (COMPOSE_PROJECT_NAME, puertos)
# 2. Ajustar .liferay-cli.yml (paths.theme con el nombre de tu tema)

task env:setup                              # verifica prerequisitos
task db:sync -- --environment prd           # importa BD desde Liferay Cloud
# o: task db:import FILE=ruta/backup.gz    # desde backup local
task env:start                              # arranca el entorno
task osgi:liferaycli-creds                  # configura OAuth2
task liferay -- inventory sites             # valida que todo funciona
```

---

## Mantener el vendor actualizado

```bash
task tooling:sync   # trae últimas mejoras de liferay-dxp-docker-dev
```
