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
# 3. Añadir la activation key de Liferay DXP (ver sección siguiente)

task env:setup                              # verifica prerequisitos
task db:sync -- --environment prd           # importa BD desde Liferay Cloud
# o: task db:import FILE=ruta/backup.gz    # desde backup local
task env:start                              # arranca el entorno
task osgi:liferaycli-creds                  # configura OAuth2
task liferay -- inventory sites             # valida que todo funciona
```

---

## Activation key de Liferay DXP

> **Obligatorio antes del primer arranque.** Sin activation key, Liferay DXP
> arranca en modo de prueba con una licencia de corta duración y algunas APIs
> quedan restringidas (p.ej. `inventory sites` devuelve 403).

### Obtener la key

1. Accede a [customer.liferay.com](https://customer.liferay.com) con tu cuenta de suscripción.
2. Descarga la activation key para tu versión de Liferay DXP.

### Instalar la key

Copia el fichero `.xml` a:

```
liferay/configs/dockerenv/osgi/modules/activation-key-*.xml
```

El fichero está en `.gitignore` — nunca se versiona.

### Arrancar

```bash
task env:start
```

En los logs verás:
```
[activation-key] Deploying: activation-key-*.xml
```

La key se recarga automáticamente en cada arranque. Si no hay ninguna key,
el script avisa con un `WARNING` pero el portal arranca igualmente en modo trial.

---

## Mantener el vendor actualizado

```bash
task tooling:sync   # trae últimas mejoras de liferay-dxp-docker-dev
```
