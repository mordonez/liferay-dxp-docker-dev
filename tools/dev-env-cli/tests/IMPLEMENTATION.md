# Implementación de Tests BATS para Plugin Bash

## Resumen ejecutivo

Se han implementado **39 tests BATS** que cubren la validación de regresiones en el plugin `tools/dev-env-cli/plugins/` tras la migración de scripts bash.

**Status**: ✅ Todos los tests pasan (39/39 en verde)

**Ejecución**:
```bash
cd docker
bats tests/bash/*.bats
```

---

## Riesgos de regresión históricos cubiertos

### 1. REPO_ROOT no heredado en subprocesses

**Problema**: Cuando `cmd_worktree_setup` llamaba a `worktree-env` en un subprocess, el `REPO_ROOT` no se heredaba.

**Tests que lo validan**:
- `repo_root_propagation.bats`: Test 1-3, 6
- `worktree_env_behavior.bats`: Test 3

**Resultado**: ✅ `REPO_ROOT` se hereda correctamente en `bash -c` y pipes.

---

### 2. _PLUGIN_REPO_ROOT se recalcula mal tras movimiento de archivos

**Problema**: Al reorganizar scripts de `docker/scripts/` a `tools/dev-env-cli/plugins/`, cada script calculaba su propia profundidad (`../../..`), causando fallos silenciosos.

**Solución**: `_PLUGIN_REPO_ROOT` se calcula UNA sola vez en `lib/common.sh` desde su ubicación conocida:
```bash
_COMMON_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_PLUGIN_REPO_ROOT="$(cd "${_COMMON_SH_DIR}/../../../.." && pwd)"
```

**Tests que lo validan**:
- `common_functions.bats`: Test 1, 5
- `repo_root_propagation.bats`: Test 5, 7

**Resultado**: ✅ `_PLUGIN_REPO_ROOT` apunta a la raíz del repo correctamente.

---

### 3. worktree-env no detecta si se ejecuta fuera de un worktree

**Problema**: Sin validación de `/.worktrees/` en `REPO_ROOT`, los comandos pueden ejecutarse en contexto incorrecto.

**Validación**: `current_worktree_name()` extrae el nombre desde `REPO_ROOT` si contiene `/.worktrees/`:
```bash
current_worktree_name() {
    case "${REPO_ROOT}" in
    */.worktrees/*)
        local rest="${REPO_ROOT##*/.worktrees/}"
        printf '%s\n' "${rest%%/*}"
        ;;
    *)
        printf ''
        ;;
    esac
}
```

**Tests que lo validan**:
- `common_functions.bats`: Test 3, 4, 7
- `worktree_env_behavior.bats`: Test 1, 4
- `repo_root_propagation.bats`: Test 9

**Resultado**: ✅ `worktree-env` falla con error claro fuera de worktree.

---

## Arquitectura de tests

```
tools/dev-env-cli/tests/
├── common_functions.bats          (10 tests) — Funciones de lib/common.sh
├── worktree_env_behavior.bats     (7 tests)  — Comportamiento de worktree-env
├── lcp_ops_parsing.bats           (10 tests) — Parsing de flags en lcp-ops.sh
├── repo_root_propagation.bats     (10 tests) — Herencia de REPO_ROOT
├── worktree_gc_orphans.bats       (2 tests)  — Limpieza de orphans
├── TEST_QUEUE.md                   — Cola de cobertura incremental
└── IMPLEMENTATION.md               — Este documento
```

### Diseño de tests

1. **Setup aislado**: Cada test crea su propia estructura temporal con `mktemp -d`.
2. **No destructivos**: Los tests usan `WORKTREE_BTRFS_ENVS_ROOT` y `LOCAL_OPS_DOCKER_DIR` para aislar cambios.
3. **Portables**: Usan `stat -c %Y` con fallback BSD, `grep` sin GNU flags.
4. **Deterministas**: Validación de strings exactos, códigos de salida, existencia de archivos.

---

## Casos de test detallados

### `common_functions.bats`

| Test | Escenario | Validación |
|------|-----------|-----------|
| 1 | `_PLUGIN_REPO_ROOT` se calcula desde ubicación de `common.sh` | Apunta a raíz, no a `tools/plugins` |
| 2 | `REPO_ROOT` exportado no se sobreescribe | Fallback `${REPO_ROOT:-${_PLUGIN_REPO_ROOT}}` funciona |
| 3 | `current_worktree_name` extrae desde `/.worktrees/` | Ejemplo: `/repo/.worktrees/issue-123` → `issue-123` |
| 4 | `current_worktree_name` vacío fuera de worktree | Sin `/.worktrees/` en REPO_ROOT |
| 5 | `main_repo_root` elimina suffix `.worktrees/NAME` | `/repo/.worktrees/issue` → `/repo` |
| 6 | `main_repo_root` identity sin worktree | Retorna `REPO_ROOT` si no tiene `.worktrees` |
| 7 | `current_worktree_name` con path anidado | `/repo/.worktrees/my-feature/nested/path` → `my-feature` |
| 8-10 | Funciones utilitarias accesibles | `read_env_value`, `upsert_env_value`, `require_arg` |

### `worktree_env_behavior.bats`

| Test | Escenario | Validación |
|------|-----------|-----------|
| 1 | `worktree-env` fuera de worktree | Falla con "dentro de un worktree" en stderr |
| 2 | Comando `worktree-env` en help | Listado en `local-ops.sh help` |
| 3 | `REPO_ROOT` exportado respetado | Asignación correcta en subprocess |
| 4 | Parsing de nombre desde `.worktrees` | Sin fallos en cálculo de nombre |
| 5 | `local-ops.sh` sin argumentos | Muestra uso sin error fatal |
| 6 | Comando inválido rechazado | Salida contiene "soportado" |
| 7 | `DOCKER_DIR` inaccesible | Falla apropiadamente |

### `lcp_ops_parsing.bats`

| Test | Escenario | Validación |
|------|-----------|-----------|
| 1 | Help sin comando | Muestra uso |
| 2 | Comando inválido | Error de comando no soportado |
| 3 | `db-import --file` sin valor | Requiere argumento |
| 4 | `db-import FILE` no existe | Falla en ejecución (du/docker) |
| 5 | `--doclib-only` sin `--download-doclib` | Validación de precondiciones |
| 6 | `lcp` no disponible | Detección de herramienta faltante |
| 7 | `doclib-mount` reconocido | Parsing correcto de comando |
| 8 | `doclib-detect --base-dir` inválido | Falla en busqueda de path |
| 9 | `--path` reconocido | Parsing de flag sin error |
| 10 | `--skip-adapt` reconocido | Parsing correcto |

### `repo_root_propagation.bats`

Valida que `REPO_ROOT` se propaga correctamente en contextos complejos:

| Test | Escenario | Validación |
|------|-----------|-----------|
| 1-3 | Herencia en subprocess | `bash -c`, pipes, env vars |
| 4-5 | Sourcing sin errores | `common.sh` se parse correctamente |
| 6-7 | Fallback a `_PLUGIN_REPO_ROOT` | Funciona cuando `REPO_ROOT` vacío |
| 8 | `lcp-ops.sh` puede sourcear | Sin conflictos de variables |
| 9-10 | Funciones de ruta correctas | `current_worktree_name()`, `main_repo_root()` |

---

## Escenarios NO cubiertos (próximos)

### Prioridad Alta

1. **`worktree-setup` completo**: Llamada end-to-end sin crear rama.
2. **`worktree-restore` con Btrfs**: Snapshots y `ENV_DATA_ROOT`.
3. **Deploy cache**: `worktree-deploy-cache-update`, commit markers.

### Prioridad Media

1. **Operaciones de env-file**: `upsert_env_value`, OAuth2 bootstrap.
2. **Btrfs setup**: DRY-RUN, stubs de `btrfs subvolume`.
3. **Limpieza completa**: `worktree-clean` con Docker cleanup.

---

## Validación de no-regresión en cambios de código

Cuando se modifiquen scripts bash, ejecutar:

```bash
cd docker
make test-bash
# o
bats tests/bash/*.bats
```

Los tests validan:

✅ Rutas relativas no se rompen al mover scripts
✅ REPO_ROOT se hereda en llamadas bash-a-bash
✅ Funciones de parsing de rutas funcionan correctamente
✅ Errores se comunican con claridad
✅ Flags y argumentos se validan correctamente

---

## Notas técnicas

### Portabilidad

- Tests corren en macOS (BSD stat, date, grep).
- Se evitan GNU-isms: `-c %Y` con fallback `-f %m`, `date -v-Nd`.
- Sin dependencias externas salvo `bats-core`.

### Manejo de caracteres acentuados

- Nombres de tests sin acentos (POSIX ASCII).
- Descripciones en comentarios pueden tener acentos.

### Aislamiento de estado

- `setup()` crea directorio temporal único por test (`TEST_TEMP=$(mktemp -d)`).
- `teardown()` limpia recursivamente: `rm -rf "${TEST_TEMP}"`.
- Ningún test afecta sistema de archivos real.

### Stubs y mocks

- `WORKTREE_BTRFS_ENVS_ROOT` permite teste de GC sin Btrfs real.
- `LOCAL_OPS_DOCKER_DIR` aísla cambios de `.env`.
- `REPO_ROOT` en env permite inyectar rutas temporales.

---

## Referencia de comandos

```bash
# Ejecutar todos los tests
cd docker && bats tests/bash/*.bats

# Ejecutar test específico
bats tests/bash/common_functions.bats

# Con detalles de fallos
bats tests/bash/common_functions.bats --verbose

# Contar tests
bats tests/bash/*.bats 2>&1 | grep "^ok" | wc -l
```

---

## Historial de cambios

| Commit | Descripción |
|--------|------------|
| 4e78b62 | Implementación inicial: 39 tests, 4 archivos nuevos |

