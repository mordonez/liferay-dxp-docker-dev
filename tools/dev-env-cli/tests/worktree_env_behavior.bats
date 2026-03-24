#!/usr/bin/env bats

# Tests para local-ops.sh worktree-env — validar que funciona
# dentro de un worktree y falla apropiadamente fuera de uno.

setup() {
    REPO_ROOT="$(cd "${BATS_TEST_DIRNAME}/../../.." && pwd)"
    LOCAL_OPS="${REPO_ROOT}/tools/dev-env-cli/plugins/core/local-ops.sh"

    # Crear estructura temporal de worktree
    TEST_TEMP="$(mktemp -d)"
    TEST_MAIN_ROOT="${TEST_TEMP}/main-repo"
    TEST_WT_ROOT="${TEST_MAIN_ROOT}/.worktrees/test-feature-$$"
    mkdir -p "${TEST_MAIN_ROOT}/docker"
    mkdir -p "${TEST_WT_ROOT}/docker"
    mkdir -p "${TEST_MAIN_ROOT}/liferay/build/docker"
    touch "${TEST_WT_ROOT}/docker/.env"
    touch "${TEST_MAIN_ROOT}/docker/.env"
}

teardown() {
    rm -rf "${TEST_TEMP}" >/dev/null 2>&1 || true
}

# Test 1: worktree-env falla con error claro fuera de un worktree
@test "worktree-env: fail con error claro fuera de worktree" {
    run env REPO_ROOT="${TEST_MAIN_ROOT}" \
        LOCAL_OPS_DOCKER_DIR="${TEST_MAIN_ROOT}/docker" \
        "${LOCAL_OPS}" worktree-env
    [ "$status" -ne 0 ]
    [[ "$output" == *"dentro de un worktree"* ]]
}

# Test 2: worktree-env --help muestra uso sin fallar
@test "worktree-env: help muestra uso (valida comando existe)" {
    # Nota: local-ops.sh no tiene --help para subcomandos, pero validamos
    # que el comando en sí es reconocido (este test documenta comportamiento)
    run env REPO_ROOT="${TEST_WT_ROOT}" \
        LOCAL_OPS_DOCKER_DIR="${TEST_WT_ROOT}/docker" \
        "${LOCAL_OPS}" help
    [ "$status" -eq 0 ]
    [[ "$output" == *"worktree-env"* ]]
}

# Test 3: local-ops.sh respeta REPO_ROOT si esta preestablecido
@test "worktree-env: respeta REPO_ROOT exportado" {
    # Simulamos que REPO_ROOT esta correctamente set al worktree
    run env REPO_ROOT="${TEST_WT_ROOT}" \
        LOCAL_OPS_DOCKER_DIR="${TEST_WT_ROOT}/docker" \
        bash -c "
            source \"${REPO_ROOT}/tools/dev-env-cli/plugins/lib/common.sh\"
            # Validar que REPO_ROOT se asigna correctamente
            [ \"\${REPO_ROOT}\" = \"${TEST_WT_ROOT}\" ] && echo \"OK\"
        "
    [ "$status" -eq 0 ]
    [[ "$output" == *"OK"* ]]
}

# Test 4: worktree-env dentro de worktree no fallará por ruta incorrecto
@test "worktree-env: calcula nombre desde .worktrees correctamente" {
    # Este test valida que el parsing de nombre funciona incluso
    # si REPO_ROOT se recalcula internamente
    run env REPO_ROOT="${TEST_WT_ROOT}" \
        LOCAL_OPS_DOCKER_DIR="${TEST_WT_ROOT}/docker" \
        bash -c "
            source \"${REPO_ROOT}/tools/dev-env-cli/plugins/lib/common.sh\"
            local wt_name
            wt_name=\"\$(current_worktree_name)\"
            if [ -z \"\${wt_name}\" ]; then
                echo '[ERROR] No se extrajo nombre del worktree' >&2
                exit 1
            fi
            echo \"OK: \${wt_name}\"
        "
    [ "$status" -eq 0 ]
    [[ "$output" == *"OK: test-feature-"* ]]
}

# Test 5: local-ops.sh valida estructura (help/usage)
@test "local-ops.sh: muestra uso con comando vacio" {
    run "${LOCAL_OPS}" help
    [ "$status" -eq 0 ]
    [[ "$output" == *"core/local-ops.sh"* ]]
}

# Test 6: local-ops.sh rechaza comando desconocido
@test "local-ops.sh: rechaza comando invalido con error" {
    run env REPO_ROOT="${TEST_MAIN_ROOT}" \
        LOCAL_OPS_DOCKER_DIR="${TEST_MAIN_ROOT}/docker" \
        "${LOCAL_OPS}" invalid-command
    [ "$status" -ne 0 ]
    [[ "$output" == *"soportado"* ]]
}

# Test 7: worktree-env valida DOCKER_DIR accesible
@test "worktree-env: requiere DOCKER_DIR valido" {
    # Sin docker dir set correctamente, fallará al parsear .env
    run env REPO_ROOT="${TEST_WT_ROOT}" \
        LOCAL_OPS_DOCKER_DIR="/nonexistent/path/docker" \
        "${LOCAL_OPS}" worktree-env
    # Puede fallar por varias razones, lo importante es que no crash silencioso
    [ "$status" -ne 0 ]
}

# Test 8: issue-587 no debe heredar ES 9200 del entorno principal cuando offset=0
@test "worktree-env: desplaza ES_HTTP_PORT cuando el hash cae en offset cero" {
    local issue_wt_root="${TEST_MAIN_ROOT}/.worktrees/issue-587"
    mkdir -p "${issue_wt_root}/docker"
    touch "${issue_wt_root}/docker/.env"

    run env REPO_ROOT="${issue_wt_root}" \
        LOCAL_OPS_DOCKER_DIR="${issue_wt_root}/docker" \
        "${LOCAL_OPS}" worktree-env

    [ "$status" -eq 0 ]
    grep -Fxq "LIFERAY_HTTP_PORT=8100" "${issue_wt_root}/docker/.env"
    grep -Fxq "ES_HTTP_PORT=9201" "${issue_wt_root}/docker/.env"
    grep -Fxq "LIFERAY_CLI_URL=http://127.0.0.1:8100" "${issue_wt_root}/docker/.env"
}

# Test 9: btrfs-setup resuelve por defecto la raiz Btrfs activa y el source del main
@test "btrfs-setup: dry-run usa BTRFS_ROOT y ENV_DATA_ROOT de main por defecto" {
    cat > "${TEST_MAIN_ROOT}/docker/.env" <<EOF
ENV_DATA_ROOT=./data/default
BTRFS_ROOT=/mnt/docker-btrfs
EOF

    run env REPO_ROOT="${TEST_MAIN_ROOT}" \
        LOCAL_OPS_DOCKER_DIR="${TEST_MAIN_ROOT}/docker" \
        "${LOCAL_OPS}" btrfs-setup

    [ "$status" -eq 0 ]
    [[ "$output" == *"mount_point=/mnt/docker-btrfs"* ]]
    [[ "$output" == *"loop_file=/var/lib/docker-btrfs.loop"* ]]
    [[ "$output" == *"source_data_root=${TEST_MAIN_ROOT}/docker/./data/default"* ]]
}

@test "env-init: main usa Btrfs en Linux cuando layout existe" {
    local btrfs_root="${TEST_TEMP}/mnt/docker-btrfs"
    mkdir -p "${btrfs_root}/base" "${btrfs_root}/envs"

    cat > "${TEST_MAIN_ROOT}/docker/.env" <<EOF
ENV_DATA_ROOT=./data/default
BTRFS_ROOT=${btrfs_root}
EOF

    run env REPO_ROOT="${TEST_MAIN_ROOT}" \
        LOCAL_OPS_DOCKER_DIR="${TEST_MAIN_ROOT}/docker" \
        "${LOCAL_OPS}" env-init

    [ "$status" -eq 0 ]
    grep -Fxq "ENV_DATA_ROOT=${btrfs_root}/main" "${TEST_MAIN_ROOT}/docker/.env"
    grep -Fxq "BTRFS_BASE=${btrfs_root}/base" "${TEST_MAIN_ROOT}/docker/.env"
    grep -Fxq "BTRFS_ENVS=${btrfs_root}/envs" "${TEST_MAIN_ROOT}/docker/.env"
    grep -Fxq "USE_BTRFS_SNAPSHOTS=auto" "${TEST_MAIN_ROOT}/docker/.env"
}
