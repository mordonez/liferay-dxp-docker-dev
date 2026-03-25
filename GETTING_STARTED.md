# Getting Started — Integrar liferay-dxp-docker-dev en un proyecto Liferay

Este runbook cubre el flujo completo desde cero: estructura mínima del proyecto,
integración del vendor, módulo OAuth2 y primera validación con liferay-cli.

---

## Prerequisitos

- Docker + Docker Compose v2
- Java 21 (Azul Zulu o Eclipse Temurin)
- Node.js (para build de tema)
- [Task](https://taskfile.dev) >= 3.28
- `gh` CLI (para `task db:sync` desde Liferay Cloud)

---

## 1. Estructura mínima del proyecto

El proyecto necesita esta estructura base antes de integrar el tooling:

```
mi-proyecto/
├── docker/
│   ├── docker-compose.yml      ← arranque del entorno local
│   ├── .env.example            ← plantilla de variables
│   └── sql/
│       └── adapt-local-db.sql  ← adaptaciones BD local (deshabilitar SAML, etc.)
└── liferay/
    ├── build.gradle
    ├── gradle.properties       ← liferay.workspace.product=dxp-2025.q1.x-lts
    ├── gradlew
    ├── settings.gradle
    ├── configs/
    │   ├── common/
    │   └── dockerenv/
    │       ├── portal-ext.properties
    │       └── portal-setup-wizard.properties
    └── modules/
```

**Referencia**: los ficheros `docker/docker-compose.yml`, `docker/.env.example` y
`liferay/configs/dockerenv/portal-ext.properties` de este repo son plantillas
de partida para un proyecto nuevo.

---

## 2. Añadir el vendor via git subtree

```bash
# Añadir el remote (solo primera vez)
git remote add liferay-tooling git@github.com:mordonez/liferay-dxp-docker-dev.git

# Incorporar el vendor
git subtree add --prefix=vendor/liferay-tooling liferay-tooling main --squash
```

---

## 3. Crear Taskfile.yml en la raíz del proyecto

```yaml
# https://taskfile.dev
version: '3'

vars:
  DOCKER_DIR:      '{{.ROOT_DIR}}/docker'
  TOOLING_DIR:     '{{.ROOT_DIR}}/vendor/liferay-tooling'
  DEV_ENV_CLI:     '{{.TOOLING_DIR}}/tools/dev-env-cli'
  LOCAL_OPS_SH:    '{{.TOOLING_DIR}}/tools/dev-env-cli/plugins/core/local-ops.sh'
  LCP_OPS_SH:      '{{.TOOLING_DIR}}/tools/dev-env-cli/plugins/lcp/lcp-ops.sh'
  LIFERAY_CLI_DIR: '{{.TOOLING_DIR}}/tools/liferay-cli'
  LIFERAY_CLI_JAR: '{{.LIFERAY_CLI_DIR}}/build/libs/liferay-cli-all.jar'

env:
  REPO_ROOT: '{{.ROOT_DIR}}'

includes:
  env:      { taskfile: ./vendor/liferay-tooling/Taskfile.env.yml }
  worktree: { taskfile: ./vendor/liferay-tooling/Taskfile.worktree.yml }
  db:       { taskfile: ./vendor/liferay-tooling/Taskfile.lcp.yml }
  deploy:   { taskfile: ./vendor/liferay-tooling/Taskfile.deploy.yml }
  osgi:     { taskfile: ./vendor/liferay-tooling/Taskfile.osgi.yml }
  reindex:  { taskfile: ./vendor/liferay-tooling/Taskfile.reindex.yml }

tasks:
  liferay:
    desc: "Ejecutar liferay-cli. Ej: task liferay -- inventory sites"
    cmds:
      - bash {{.DEV_ENV_CLI}}/plugins/resources/liferay-cli.sh {{.CLI_ARGS}}

  tooling:sync:
    desc: Actualizar vendor desde liferay-dxp-docker-dev
    cmds:
      - git subtree pull --prefix=vendor/liferay-tooling liferay-tooling main --squash

  tooling:push:
    desc: Publicar cambios del vendor a liferay-dxp-docker-dev
    cmds:
      - git subtree push --prefix=vendor/liferay-tooling liferay-tooling main

  default:
    cmds: [task --list --sort alpha]
    silent: true
```

---

## 4. Configurar `.env`

Copiar la plantilla y ajustar para el proyecto:

```bash
cp docker/.env.example docker/.env
```

Valores clave a personalizar:

```dotenv
COMPOSE_PROJECT_NAME=mi-proyecto     # Prefijo de contenedores Docker
DOCLIB_VOLUME_NAME=mi-proyecto-doclib
LIFERAY_HTTP_PORT=8080               # Cambiar si hay colisión con otro entorno
POSTGRES_PORT=5432
GOGO_PORT=11311
LIFERAY_JVM_OPTS="-Xms4g -Xmx6g ..."
ENV_DATA_ROOT=./data/default         # Bind mounts locales (NO cambiar a btrfs salvo necesidad explícita)
LCP_PROJECT=nombre-proyecto-lcp      # Para task db:sync desde Liferay Cloud
LCP_ENVIRONMENT=prd
```

> **Nota**: no añadir `BTRFS_ROOT` ni `ENV_DATA_ROOT=/mnt/docker-btrfs/...` salvo que
> se quiera usar btrfs explícitamente. Ver `.env.example` para la documentación de esas vars.

---

## 5. Configurar `.liferay-cli.yml`

Copiar la plantilla del vendor y ajustar al proyecto:

```bash
cp vendor/liferay-tooling/.liferay-cli.yml.example .liferay-cli.yml
```

Ajustar las rutas y el nombre del tema:

```yaml
liferay:
  url: http://localhost:8080   # Debe coincidir con LIFERAY_HTTP_PORT
  oauth2:
    clientId: ""
    clientSecret: ""
    timeoutSeconds: 30
paths:
  theme: mi-theme              # Nombre del tema (carpeta en liferay/themes/)
  structures: liferay/resources/journal/structures
  templates: liferay/resources/journal/templates
  fragments: liferay/fragments
  adts: liferay/resources/templates/application_display
```

> Las credenciales OAuth2 se dejan vacías — el módulo bootstrap las genera al arrancar.
> Ejecutar `task osgi:liferaycli-creds` tras el primer arranque para obtenerlas.

---

## 6. Copiar el módulo OAuth2 bootstrap

```bash
cp -r vendor/liferay-tooling/modules liferay/modules/liferay-cli-bootstrap
```

Este módulo crea automáticamente una app OAuth2 técnica al arrancar Liferay,
necesaria para que `task liferay -- ...` pueda autenticarse.

---

## 7. Configurar OSGi bootstrap (dockerenv)

Crear `liferay/configs/dockerenv/osgi/configs/dev.mordonez.liferay.cli.bootstrap.configuration.LiferayCliOAuth2BootstrapConfiguration.config`:

```properties
companyWebId="local.mi-proyecto.com"
adminUserEmail="admin@liferay.local"
```

Ajustar `companyWebId` y `adminUserEmail` al entorno local del proyecto.

---

## 8. Primera puesta en marcha

```bash
# Verificar prerequisitos e inicializar imágenes Docker
task env:setup

# Descargar e importar BD desde Liferay Cloud (requiere gh CLI autenticado con acceso LCP)
task db:sync -- --environment prd
# O importar un backup local:
task db:import FILE=ruta/al/backup.gz

# Compilar módulos y tema
task deploy:prepare

# Arrancar el entorno
task env:start

# Esperar a que Liferay esté healthy (~2-5 min)
task env:wait

# Verificar URL y estado
task env:info
```

---

## 9. Configurar credenciales OAuth2

Tras el primer arranque, obtener las credenciales generadas por el módulo bootstrap:

```bash
task osgi:liferaycli-creds
```

El comando imprime las variables de entorno a configurar. Añadirlas al shell o
exportarlas en la sesión:

```bash
export LIFERAY_CLI_OAUTH2_CLIENT_ID="..."
export LIFERAY_CLI_OAUTH2_CLIENT_SECRET="..."
```

---

## 10. Validación del tooling

```bash
# Listar sites disponibles
task liferay -- inventory sites

# Listar páginas de un site
task liferay -- inventory pages --site /global

# Verificar estado de bundles OSGi
task osgi:status
```

Si `inventory sites` devuelve resultados, el tooling está completamente operativo.

---

## Mantenimiento del vendor

```bash
# Sincronizar últimas mejoras del tooling
task tooling:sync

# Contribuir un fix al tooling
task tooling:push
```

---

## Comandos de referencia rápida

| Comando | Descripción |
|---|---|
| `task env:setup` | Inicializar entorno (primera vez) |
| `task env:start` | Arrancar Docker |
| `task env:stop` | Parar Docker |
| `task env:info` | Estado + URL del portal |
| `task env:clean` | Borrar TODOS los datos locales |
| `task db:sync` | Descargar e importar BD desde LCP |
| `task db:import` | Importar backup local |
| `task deploy:prepare` | Compilar todos los módulos y tema |
| `task deploy:module -- nombre` | Compilar y desplegar un módulo |
| `task deploy:theme` | Compilar y desplegar el tema |
| `task osgi:liferaycli-creds` | Obtener credenciales OAuth2 generadas |
| `task liferay -- inventory sites` | Listar sites via API |
| `task tooling:sync` | Actualizar vendor |
| `task worktree:new -- issue-123` | Crear entorno aislado para una issue |
