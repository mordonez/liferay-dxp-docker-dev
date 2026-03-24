# Cola de cobertura Bats (bash migration)

Objetivo: mantener una cola incremental para cubrir scripts bash migrados sin bloquear desarrollo.

## Cobertura actual

- `common_functions.bats` (10 tests)
  - `_PLUGIN_REPO_ROOT` se calcula desde ubicación de `common.sh`
  - `REPO_ROOT` heredado no se sobreescribe
  - `current_worktree_name()` extrae nombre desde `/.worktrees/`
  - `current_worktree_name()` retorna vacío fuera de worktree
  - `main_repo_root()` elimina suffix `.worktrees/NAME`
  - `main_repo_root()` identity sin worktree
  - `current_worktree_name()` con paths anidados
  - Funciones utilitarias accesibles: `read_env_value`, `upsert_env_value`, `require_arg`

- `worktree_env_behavior.bats` (7 tests)
  - `worktree-env` falla con error claro fuera de worktree
  - `worktree-env` muestra help (comando válido)
  - `worktree-env` respeta `REPO_ROOT` exportado
  - `worktree-env` calcula nombre desde `.worktrees` correctamente
  - `local-ops.sh` muestra uso con comando vacío
  - `local-ops.sh` rechaza comandos inválidos
  - `worktree-env` requiere `DOCKER_DIR` válido

- `lcp_ops_parsing.bats` (10 tests)
  - `lcp-ops.sh` muestra help con comando vacío
  - `lcp-ops.sh` rechaza comandos inválidos
  - `db-import` requiere `--file` con valor
  - `db-import` falla si FILE no existe
  - `db-download` valida `--doclib-only` requiere `--download-doclib`
  - `db-download` falla si `lcp` no disponible
  - `doclib-mount` comando reconocido
  - `doclib-detect` falla si base-dir no existe
  - `doclib-mount` acepta flag `--path`
  - `db-import` flag `--skip-adapt` reconocida

- `repo_root_propagation.bats` (10 tests)
  - `REPO_ROOT` heredado en subprocess
  - `REPO_ROOT` heredado en pipe
  - `REPO_ROOT` no sobreescrito si preexiste
  - `common.sh` sourcing sin errores
  - `_PLUGIN_REPO_ROOT` se define y accesible
  - `REPO_ROOT` fallback a `_PLUGIN_REPO_ROOT`
  - `local-ops.sh` resuelve ruta sin error
  - `lcp-ops.sh` puede sourcear `common.sh`
  - `current_worktree_name()` funciona desde `REPO_ROOT` real
  - `main_repo_root()` elimina `.worktrees/NAME` correctamente

- `worktree_gc_orphans.bats` (2 tests)
  - `worktree-gc` detecta huérfanos en dry-run
  - `worktree-clean` elimina huérfano específico en root temporal

## Prioridad alta (siguiente)

1. `worktree_setup_integration.bats`
- Llamada completa a `worktree-setup NAME` sin crear rama (reutilización).
- Verificar que `worktree-env` se ejecuta en subprocess con `REPO_ROOT` correcto.
- Validar mensaje final con URL y ruta sugerida.
- No prompt interactivo de sudo (DRY-RUN).

2. `worktree_env_restore_btrfs.bats`
- `worktree-env` genera puertos deterministas desde hash(name).
- `ENV_DATA_ROOT` usa `/mnt/docker-btrfs/envs/<issue>` cuando Btrfs disponible.
- `worktree-restore` respeta `ENV_DATA_ROOT` del worktree.
- Validar `clone_env_data_root_btrfs` sin sudo (DRY-RUN o stubs).

3. `worktree_deploy_cache.bats`
- `worktree-deploy-cache-update` copia artefactos desde `source_dir`.
- `clone_build_docker_cache` valida commit markers.
- Restauración de deploy-cache desde entorno principal.

## Prioridad media

1. `worktree_clean_full.bats`
- `worktree-clean --apply` elimina compose project, rama git, volumen doclib.
- Validar eliminación de env-data-root local y btrfs.
- Validar que no quedan contenedores vivos post-clean.

2. `env_file_operations.bats`
- `read_env_value` / `upsert_env_value` parsing correcto.
- `ensure_oauth_bootstrap_env` genera secret si no existe.
- `seed_env_file_from_source` no sobreescribe keys existentes.

3. `local_ops_btrfs_setup.bats`
- `btrfs-setup --help` o DRY-RUN valida flags.
- Validar que `cmd_btrfs_setup` rechaza sin `--apply --confirm-token BTRFS`.
- Mock de btrfs subvolume (sin requerir permisos reales).

## Referencias legacy para extraer casos

- Estado actual: no quedan tests legacy de `tools/docker-automation` (módulo retirado).
- Para nuevos casos, usar trazas reales de `dev-cli`/`ub-cli` y convertirlas a escenarios Bats.

## Ejecución

```bash
cd docker
bats tests/bash
```
