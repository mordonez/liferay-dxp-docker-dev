#!/usr/bin/env bats

# Tests para lib/common.sh — validar que las funciones de cálculo de rutas
# se resuelven correctamente incluso cuando los scripts se mueven.

setup() {
    REPO_ROOT="$(cd "${BATS_TEST_DIRNAME}/../../.." && pwd)"
    COMMON_SH="${REPO_ROOT}/tools/dev-env-cli/plugins/lib/common.sh"

    # Crear directorios temporales para simular worktrees
    TEST_TEMP="$(mktemp -d)"
    TEST_MAIN_ROOT="${TEST_TEMP}/main-repo"
    TEST_WT_ROOT="${TEST_MAIN_ROOT}/.worktrees/test-issue-123"
    mkdir -p "${TEST_WT_ROOT}"
}

teardown() {
    rm -rf "${TEST_TEMP}" >/dev/null 2>&1 || true
}

# Test 1: _PLUGIN_REPO_ROOT se calcula desde la ubicacion de common.sh
@test "common.sh: PLUGIN_REPO_ROOT apunta a raiz del repo" {
    # Sourcing común en un subshell para validar el cálculo
    (
        source "${COMMON_SH}"
        # _PLUGIN_REPO_ROOT debe ser la raíz (contener tools/dev-env-cli/plugins)
        [ -d "${_PLUGIN_REPO_ROOT}/tools/dev-env-cli/plugins" ]
        # No debe terminar en "tools"
        [[ "${_PLUGIN_REPO_ROOT}" != */tools ]]
        [[ "${_PLUGIN_REPO_ROOT}" != */plugins ]]
    )
    [ "$?" -eq 0 ]
}

# Test 2: REPO_ROOT se respeta si está preestablecido
@test "common.sh: REPO_ROOT exportado no se sobreescribe" {
    (
        export REPO_ROOT="${TEST_MAIN_ROOT}"
        source "${COMMON_SH}"
        # REPO_ROOT ya estaba set, debe conservarse
        [ "${REPO_ROOT}" = "${TEST_MAIN_ROOT}" ]
    )
    [ "$?" -eq 0 ]
}

# Test 3: current_worktree_name extrae nombre desde REPO_ROOT con .worktrees
@test "common.sh: current_worktree_name extrae nombre desde /.worktrees/" {
    (
        export REPO_ROOT="${TEST_WT_ROOT}"
        source "${COMMON_SH}"
        local name
        name="$(current_worktree_name)"
        [ "${name}" = "test-issue-123" ]
    )
    [ "$?" -eq 0 ]
}

# Test 4: current_worktree_name retorna vacio si no esta en worktree
@test "common.sh: current_worktree_name retorna vacio fuera de worktree" {
    (
        export REPO_ROOT="${TEST_MAIN_ROOT}"
        source "${COMMON_SH}"
        local name
        name="$(current_worktree_name)"
        [ -z "${name}" ]
    )
    [ "$?" -eq 0 ]
}

# Test 5: main_repo_root elimina suffix .worktrees/NAME
@test "common.sh: main_repo_root remove .worktrees/NAME" {
    (
        export REPO_ROOT="${TEST_WT_ROOT}"
        source "${COMMON_SH}"
        local main
        main="$(main_repo_root)"
        [ "${main}" = "${TEST_MAIN_ROOT}" ]
    )
    [ "$?" -eq 0 ]
}

# Test 6: main_repo_root retorna REPO_ROOT sin cambios si no tiene .worktrees
@test "common.sh: main_repo_root identity si no está en worktree" {
    (
        export REPO_ROOT="${TEST_MAIN_ROOT}"
        source "${COMMON_SH}"
        local main
        main="$(main_repo_root)"
        [ "${main}" = "${TEST_MAIN_ROOT}" ]
    )
    [ "$?" -eq 0 ]
}

# Test 7: current_worktree_name con nested paths
@test "common.sh: current_worktree_name con path anidado" {
    (
        # Simular: /repo/.worktrees/my-feature/some/nested/path
        export REPO_ROOT="${TEST_MAIN_ROOT}/.worktrees/my-feature/some/nested/path"
        source "${COMMON_SH}"
        local name
        name="$(current_worktree_name)"
        # Debe extraer solo "my-feature"
        [ "${name}" = "my-feature" ]
    )
    [ "$?" -eq 0 ]
}

# Test 8: Validar que read_env_value está disponible
@test "common.sh: read_env_value function exists" {
    (
        source "${COMMON_SH}"
        declare -f read_env_value >/dev/null 2>&1
    )
    [ "$?" -eq 0 ]
}

# Test 9: Validar que upsert_env_value está disponible
@test "common.sh: upsert_env_value function exists" {
    (
        source "${COMMON_SH}"
        declare -f upsert_env_value >/dev/null 2>&1
    )
    [ "$?" -eq 0 ]
}

# Test 10: Validar que require_arg está disponible
@test "common.sh: require_arg function exists" {
    (
        source "${COMMON_SH}"
        declare -f require_arg >/dev/null 2>&1
    )
    [ "$?" -eq 0 ]
}

# Test 11: ensure_host_dir_owned_by_current_user crea el directorio si falta
@test "common.sh: ensure_host_dir_owned_by_current_user crea directorio ausente" {
    local target="${TEST_MAIN_ROOT}/docker/data/default"
    (
        source "${COMMON_SH}"
        ensure_host_dir_owned_by_current_user "${target}"
        [ -d "${target}" ]
        [ -w "${target}" ]
    )
    [ "$?" -eq 0 ]
}

# Test 12: ensure_host_dir_owned_by_current_user no falla si el directorio ya existe
@test "common.sh: ensure_host_dir_owned_by_current_user tolera directorio existente" {
    local target="${TEST_MAIN_ROOT}/docker/data/default"
    mkdir -p "${target}"
    (
        source "${COMMON_SH}"
        ensure_host_dir_owned_by_current_user "${target}"
        [ -d "${target}" ]
        [ -w "${target}" ]
    )
    [ "$?" -eq 0 ]
}

@test "common.sh: resolve_env_data_root_for_docker_dir resuelve ruta relativa desde docker/.env" {
    mkdir -p "${TEST_MAIN_ROOT}/docker"
    cat > "${TEST_MAIN_ROOT}/docker/.env" <<EOF
ENV_DATA_ROOT=./data/default
EOF

    run bash -c "
        source \"${COMMON_SH}\"
        resolve_env_data_root_for_docker_dir \"${TEST_MAIN_ROOT}/docker\"
    "

    [ "$status" -eq 0 ]
    [ "$output" = "${TEST_MAIN_ROOT}/docker/./data/default" ]
}

@test "common.sh: resolve_env_data_root_for_docker_dir preserva ruta absoluta" {
    mkdir -p "${TEST_MAIN_ROOT}/docker"
    cat > "${TEST_MAIN_ROOT}/docker/.env" <<EOF
ENV_DATA_ROOT=/mnt/docker-btrfs/main
EOF

    run bash -c "
        source \"${COMMON_SH}\"
        resolve_env_data_root_for_docker_dir \"${TEST_MAIN_ROOT}/docker\"
    "

    [ "$status" -eq 0 ]
    [ "$output" = "/mnt/docker-btrfs/main" ]
}
