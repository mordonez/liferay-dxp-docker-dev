# tools/dev-env-cli

Plugins bash para operaciones del entorno de desarrollo Liferay DXP.
Invocados desde el Taskfile raíz — no ejecutar directamente.

## Estructura

```
plugins/liferay/
  ops.sh        ← gogo, bundle-status, bundle-diag, thread-dump, pipeline-check, info
  deploy.sh     ← deploy-module, deploy-theme, prepare-build
  reindex.sh    ← reindex start/progress/watch/speedup
  liferay-cli.sh ← wrapper para ejecutar liferay-cli JAR
  resource.sh   ← export/sync de structures, templates, fragments
```

## Uso

```bash
task osgi:gogo
task deploy:module -- ub-config
task liferay -- inventory structures --site /global
```

Ver `task --list` para todos los comandos disponibles.
