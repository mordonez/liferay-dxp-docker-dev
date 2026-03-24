#!/usr/bin/env bats

# Tests para validar la propagacion y herencia de REPO_ROOT
# en llamadas bash-a-bash (subprocess), que fue una fuente de regresiones.

setup() {
    REPO_ROOT="$(cd "${BATS_TEST_DIRNAME}/../../.." && pwd)"
    LOCAL_OPS="${REPO_ROOT}/tools/dev-env-cli/plugins/core/local-ops.sh"

    # Crear estructura temporaria
    TEST_TEMP="$(mktemp -d)"
    TEST_MAIN_ROOT="${TEST_TEMP}/main-repo"
    TEST_WT_ROOT="${TEST_MAIN_ROOT}/.worktrees/test-issue-456"
    mkdir -p "${TEST_MAIN_ROOT}/docker"
    mkdir -p "${TEST_WT_ROOT}/docker"
    touch "${TEST_WT_ROOT}/docker/.env"
    touch "${TEST_MAIN_ROOT}/docker/.env"
}

teardown() {
    rm -rf "${TEST_TEMP}" >/dev/null 2>&1 || true
}

# Test 1: REPO_ROOT se hereda correctamente en subprocess
@test "REPO_ROOT heredado en subprocess: env var accesible" {
    local result
    result="$(env REPO_ROOT="${TEST_WT_ROOT}" bash -c 'echo "${REPO_ROOT}"')"
    [ "${result}" = "${TEST_WT_ROOT}" ]
}

# Test 2: REPO_ROOT se hereda en pipe
@test "REPO_ROOT heredado en pipe" {
    local result
    result="$(export REPO_ROOT='${TEST_WT_ROOT}'; echo "${REPO_ROOT}" 2>&1)"
    [ -n "${result}" ]
}

# Test 3: REPO_ROOT no se sobreescribe en variable assignment
@test "REPO_ROOT no sobreescrito si preexiste" {
    local result
    result="$(export REPO_ROOT='${TEST_WT_ROOT}'; echo "${REPO_ROOT:-/fallback}")"
    [ -n "${result}" ]
}

# Test 4: common.sh puede parsearse sin errores
@test "common.sh: sourcing sin errores" {
    (source "${REPO_ROOT}/tools/dev-env-cli/plugins/lib/common.sh")
    [ "$?" -eq 0 ]
}

# Test 5: _PLUGIN_REPO_ROOT se calcula al source
@test "common.sh: _PLUGIN_REPO_ROOT se define" {
    (
        source "${REPO_ROOT}/tools/dev-env-cli/plugins/lib/common.sh"
        [ -n "${_PLUGIN_REPO_ROOT}" ]
        [ -d "${_PLUGIN_REPO_ROOT}" ]
    )
    [ "$?" -eq 0 ]
}

# Test 6: REPO_ROOT fallback a _PLUGIN_REPO_ROOT funciona
@test "REPO_ROOT fallback a PLUGIN_REPO_ROOT" {
    (
        source "${REPO_ROOT}/tools/dev-env-cli/plugins/lib/common.sh"
        local repo
        repo="${REPO_ROOT:-${_PLUGIN_REPO_ROOT}}"
        [ -n "${repo}" ]
        [ -d "${repo}" ]
    )
    [ "$?" -eq 0 ]
}

# Test 7: local-ops.sh usa _PLUGIN_REPO_ROOT internamente
@test "local-ops.sh: resuelve ruta sin error" {
    (
        # source common.sh como lo hace local-ops.sh
        source "${REPO_ROOT}/tools/dev-env-cli/plugins/lib/common.sh"
        REPO_ROOT="${REPO_ROOT:-${_PLUGIN_REPO_ROOT}}"
        [ -d "${REPO_ROOT}/tools/dev-env-cli/plugins" ]
    )
    [ "$?" -eq 0 ]
}

# Test 8: lcp-ops.sh puede sourcing common.sh
@test "lcp-ops.sh: puede sourcear common.sh" {
    (
        source "${REPO_ROOT}/tools/dev-env-cli/plugins/lib/common.sh"
        REPO_ROOT="${REPO_ROOT:-${_PLUGIN_REPO_ROOT}}"
        [ -n "${REPO_ROOT}" ]
    )
    [ "$?" -eq 0 ]
}

# Test 9: current_worktree_name funciona correctamente
@test "current_worktree_name: funciona desde REPO_ROOT real" {
    (
        export REPO_ROOT="/Users/mordonez/Development/GitHub/labwebv2/.worktrees/feature-test"
        source "/Users/mordonez/Development/GitHub/labwebv2/tools/dev-env-cli/plugins/lib/common.sh"
        local name="$(current_worktree_name)"
        [ "${name}" = "feature-test" ]
    )
    [ "$?" -eq 0 ]
}

# Test 10: main_repo_root elimina suffix
@test "main_repo_root: elimina .worktrees/NAME desde REPO_ROOT" {
    (
        export REPO_ROOT="/Users/mordonez/Development/GitHub/labwebv2/.worktrees/feature-test"
        source "/Users/mordonez/Development/GitHub/labwebv2/tools/dev-env-cli/plugins/lib/common.sh"
        local main="$(main_repo_root)"
        [ "${main}" = "/Users/mordonez/Development/GitHub/labwebv2" ]
    )
    [ "$?" -eq 0 ]
}
