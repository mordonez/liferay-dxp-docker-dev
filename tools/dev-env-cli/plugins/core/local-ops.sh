#!/usr/bin/env bash
# core/local-ops.sh — Operaciones genéricas de infraestructura local: setup, worktrees, env, btrfs.
# Portable: se puede copiar a cualquier proyecto Docker con esta convención de directorios.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="${SELF_DIR}/../lib"
LCP_OPS_SH="${SELF_DIR}/../lcp/lcp-ops.sh"

# shellcheck source=../lib/common.sh
source "${LIB_DIR}/common.sh"

REPO_ROOT="${REPO_ROOT:-${_PLUGIN_REPO_ROOT}}"
DOCKER_DIR="${LOCAL_OPS_DOCKER_DIR:-${REPO_ROOT}/docker}"
DOCKER_DIR="$(cd "${DOCKER_DIR}" && pwd)"

usage() {
    cat <<'USAGE'
core/local-ops.sh — operaciones genéricas de infraestructura local

Uso:
  core/local-ops.sh setup [flags]
  core/local-ops.sh env-init
  core/local-ops.sh env-start
  core/local-ops.sh worktree-setup [NAME] [--name NAME] [--issue NUM] [--base REF] [--with-env]
  core/local-ops.sh worktree-start NAME
  core/local-ops.sh worktree-env [--clone-volumes]
  core/local-ops.sh worktree-restore [NAME]
  core/local-ops.sh worktree-deploy-cache-update [--clean]
  core/local-ops.sh btrfs-setup [--apply] [--confirm-token BTRFS] [--size-gb N] [--mount-point DIR] [--loop-file FILE]
  core/local-ops.sh worktree-clean NAME [--apply] [--keep-branch] [--allow-unreviewed-commits]
  core/local-ops.sh worktree-gc [--days N] [--clean-stale]
USAGE
}

ensure_main_env_layout() {
    local env_file="${DOCKER_DIR}/.env"
    local env_example="${DOCKER_DIR}/.env.example"

    if [ ! -f "${env_file}" ] && [ -f "${env_example}" ]; then
        seed_env_file_from_source "${env_file}" "${env_example}"
    fi

    [ -z "$(current_worktree_name)" ] || return 0
    is_linux_host || return 0
    btrfs_layout_ready_for_docker_dir "${DOCKER_DIR}" || return 0

    # No escribir vars btrfs ni ENV_DATA_ROOT automáticamente.
    # El usuario debe configurarlas explícitamente en su .env si quiere usar btrfs.
    # Consultar .env.example para los valores recomendados.
    local main_env_root
    main_env_root="$(main_btrfs_data_root_for_docker_dir "${DOCKER_DIR}")"
    ensure_host_dir_owned_by_current_user "${main_env_root}" 2>/dev/null || true
}

ensure_repo_git_hooks_installed() {
    local templates_dir common_git_dir hooks_dir current_hooks_path installed=0

    templates_dir="${REPO_ROOT}/.githooks"
    [ -d "${templates_dir}" ] || return 0

    common_git_dir="$(git -C "${REPO_ROOT}" rev-parse --git-common-dir)"
    case "${common_git_dir}" in
    /*) ;;
    *) common_git_dir="${REPO_ROOT}/${common_git_dir}" ;;
    esac

    hooks_dir="${common_git_dir}/hooks"
    mkdir -p "${hooks_dir}"

    for hook_name in _guardrails.sh post-checkout pre-commit; do
        if ! cmp -s "${templates_dir}/${hook_name}" "${hooks_dir}/${hook_name}" 2>/dev/null; then
            cp "${templates_dir}/${hook_name}" "${hooks_dir}/${hook_name}"
            installed=1
        fi
    done
    chmod +x "${hooks_dir}/post-checkout" "${hooks_dir}/pre-commit" >/dev/null 2>&1 || true

    current_hooks_path="$(git config --get core.hooksPath 2>/dev/null || true)"
    case "${current_hooks_path}" in
    */.githooks)
        git config --unset core.hooksPath >/dev/null 2>&1 || true
        installed=1
        ;;
    esac

    if [ "${installed}" -eq 1 ]; then
        echo "[INFO] Git hooks del repo instalados en ${hooks_dir}"
    fi
}

ensure_playwright_cli_installed() {
    if ! command -v node >/dev/null 2>&1; then
        echo "  playwright-cli: NO COMPROBADO (Node.js no está disponible)"
        return 0
    fi

    if command -v playwright-cli >/dev/null 2>&1; then
        echo "  playwright-cli: OK"
        return 0
    fi

    if ! command -v npm >/dev/null 2>&1; then
        echo "  playwright-cli: NO INSTALADO (npm no está disponible)"
        return 0
    fi

    echo "[INFO] Instalando @playwright/cli globalmente para asegurar playwright-cli..."
    npm install -g @playwright/cli >/dev/null 2>&1 || {
        echo "[WARN] No se pudo instalar @playwright/cli automáticamente; instala manualmente con 'npm install -g @playwright/cli'" >&2
        return 0
    }

    if command -v playwright-cli >/dev/null 2>&1; then
        echo "  playwright-cli: INSTALADO"
    else
        echo "[WARN] @playwright/cli se instaló pero playwright-cli no quedó en PATH" >&2
    fi
}

ensure_playwright_cli_runtime() {
    if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
        echo "  playwright chromium: NO COMPROBADO (faltan Node.js/npm)"
        return 0
    fi

    local npm_global_root playwright_cli_js
    npm_global_root="$(npm root -g 2>/dev/null || true)"
    playwright_cli_js="${npm_global_root}/@playwright/cli/node_modules/playwright/cli.js"

    if [ ! -f "${playwright_cli_js}" ]; then
        echo "  playwright chromium: NO COMPROBADO (no se encontró cli.js del runtime global)"
        return 0
    fi

    echo "[INFO] Verificando runtime chromium de playwright-cli..."
    if node "${playwright_cli_js}" install chromium >/dev/null 2>&1; then
        echo "  playwright chromium: OK"
    else
        echo "[WARN] No se pudo verificar/instalar chromium para playwright-cli automáticamente" >&2
    fi
}

normalize_build_deploy_permissions() {
    local build_dir="$1"
    local deploy_dir="${build_dir}/deploy"
    [ -d "${deploy_dir}" ] || return 0

    local uid gid
    uid="$(id -u)"
    gid="$(id -g)"

    if [ -w "${deploy_dir}" ]; then
        chmod -R u+rwX "${deploy_dir}" >/dev/null 2>&1 || true
        return 0
    fi

    docker run --rm -v "${deploy_dir}:/target" alpine sh -lc \
        "chown -R ${uid}:${gid} /target && chmod -R u+rwX /target" >/dev/null 2>&1 || true
}

read_prepare_commit() {
    local base_dir="$1"
    local marker_file="${base_dir}/.prepare-commit"

    [ -f "${marker_file}" ] || return 1
    tr -d '\r\n' < "${marker_file}"
}

cache_matches_target_commit() {
    local cache_dir="$1"
    local target_commit="$2"
    local cache_commit=""

    cache_commit="$(read_prepare_commit "${cache_dir}" 2>/dev/null || true)"
    [ -n "${cache_commit}" ] || return 1
    [ "${cache_commit}" = "${target_commit}" ]
}

clone_build_docker_cache() {
    local source_build_dir="$1"
    local target_build_dir="$2"
    local source_commit="$3"
    local target_commit="$4"
    local source_marker="${source_build_dir}/.prepare-commit"
    local source_deploy_dir="${source_build_dir}/deploy"

    if [ ! -d "${source_build_dir}" ] || [ ! -d "${source_deploy_dir}" ] || [ ! -f "${source_build_dir}/configs/dockerenv/portal-ext.properties" ]; then
        return 1
    fi
    if [ -f "${source_marker}" ]; then
        local marker_commit
        marker_commit="$(tr -d '\r\n' < "${source_marker}")"
        if [ -n "${marker_commit}" ] && [ "${marker_commit}" != "${source_commit}" ]; then
            return 1
        fi
    fi
    if [ "${source_commit}" != "${target_commit}" ]; then
        return 1
    fi

    mkdir -p "$(dirname "${target_build_dir}")"
    delete_tree_with_helper "${target_build_dir}" || true
    rm -rf "${target_build_dir}" || true
    mkdir -p "${target_build_dir}"
    if ! cp -a --reflink=always "${source_build_dir}/." "${target_build_dir}/" >/dev/null 2>&1; then
        if [[ "$(uname -s)" == "Darwin" ]]; then
            cp -cR "${source_build_dir}/." "${target_build_dir}/" 2>/dev/null || \
                cp -R "${source_build_dir}/." "${target_build_dir}/"
        else
            docker run --rm -v "${source_build_dir}:/source:ro" -v "${target_build_dir}:/target" alpine sh -lc "cp -a /source/. /target/"
        fi
    fi
    normalize_build_deploy_permissions "${target_build_dir}"
    printf '%s\n' "${target_commit}" > "${target_build_dir}/.prepare-commit"
    return 0
}

restore_build_deploy_from_env_cache() {
    local docker_dir="$1"
    local build_dir="$2"
    local target_commit="$3"
    local env_file="${docker_dir}/.env"
    local env_data_root cache_dir deploy_dir

    env_data_root="$(read_env_value ENV_DATA_ROOT "${env_file}")"
    [ -n "${env_data_root}" ] || return 1
    env_data_root="$(resolve_path_from_env_file "${env_data_root}" "${env_file}")"
    cache_dir="${env_data_root}/liferay-deploy-cache"
    [ -d "${cache_dir}" ] || return 1
    if ! cache_matches_target_commit "${cache_dir}" "${target_commit}"; then
        echo "[INFO] Se omite restore de deploy-cache del entorno: commit cache != commit worktree (${target_commit})"
        return 1
    fi

    deploy_dir="${build_dir}/deploy"
    if [ -d "${deploy_dir}" ] && find "${deploy_dir}" -maxdepth 1 -type f \( -name '*.jar' -o -name '*.war' \) | grep -q .; then
        return 1
    fi

    mkdir -p "${deploy_dir}"
    find "${cache_dir}" -maxdepth 1 -type f \( -name '*.jar' -o -name '*.war' \) -print0 | while IFS= read -r -d '' artifact; do
        if cp -a --reflink=always "${artifact}" "${deploy_dir}/" >/dev/null 2>&1; then
            :
        else
            cp -a "${artifact}" "${deploy_dir}/"
        fi
    done

    if find "${deploy_dir}" -maxdepth 1 -type f \( -name '*.jar' -o -name '*.war' \) | grep -q .; then
        normalize_build_deploy_permissions "${build_dir}"
        return 0
    fi
    return 1
}

seed_build_docker_configs() {
    local worktree_root="$1"
    local build_dir="$2"
    local source_cfg_dir="${worktree_root}/liferay/configs/dockerenv"
    local target_cfg_dir="${build_dir}/configs/dockerenv"

    [ -d "${source_cfg_dir}" ] || return 1
    mkdir -p "${target_cfg_dir}"
    cp -a "${source_cfg_dir}/." "${target_cfg_dir}/"

    [ -f "${target_cfg_dir}/portal-ext.properties" ]
}

ensure_portal_ext_local_override() {
    local worktree_root="$1"
    local build_dir="$2"
    local http_port="$3"
    local source_cfg_dir="${worktree_root}/liferay/configs/dockerenv"
    local build_cfg_dir="${build_dir}/configs/dockerenv"
    local content="web.server.http.port=${http_port}"

    mkdir -p "${source_cfg_dir}" "${build_cfg_dir}"
    printf '%s\n' "${content}" > "${source_cfg_dir}/portal-ext.local.properties"
    printf '%s\n' "${content}" > "${build_cfg_dir}/portal-ext.local.properties"
}

clone_env_data_root_btrfs() {
    local src_root="$1"
    local dst_root="$2"
    local clone_deploy_cache="${3:-1}"
    local can_use_btrfs_snapshot=0
    if command -v btrfs >/dev/null 2>&1 && command -v sudo >/dev/null 2>&1 && \
       [ -d "${src_root}/postgres-data" ] && \
       sudo -n /usr/bin/btrfs subvolume show "${src_root}/postgres-data" >/dev/null 2>&1; then
        can_use_btrfs_snapshot=1
    fi
    if [ "${can_use_btrfs_snapshot}" -eq 0 ]; then
        echo "[WARN] Sin sudo no interactivo para Btrfs; usando copia local sin snapshot."
    fi
    if [ -e "${dst_root}" ] && [ ! -w "${dst_root}" ]; then
        delete_tree_with_helper "${dst_root}" || true
    fi
    mkdir -p "${dst_root}"
    local copied=0
    for subdir in postgres-data liferay-data liferay-osgi-state liferay-deploy-cache elasticsearch-data; do
        if [ "${subdir}" = "liferay-deploy-cache" ] && [ "${clone_deploy_cache}" != "1" ]; then
            continue
        fi
        if [ -d "${src_root}/${subdir}" ]; then
            local src_subdir="${src_root}/${subdir}"
            local dst_subdir="${dst_root}/${subdir}"
            if [ -e "${dst_subdir}" ]; then
                if [ "${can_use_btrfs_snapshot}" -eq 1 ]; then
                    sudo -n /usr/bin/btrfs subvolume delete "${dst_subdir}" >/dev/null 2>&1 || true
                fi
                delete_tree_with_helper "${dst_subdir}" || true
            fi
            mkdir -p "$(dirname "${dst_subdir}")"
            if [ "${can_use_btrfs_snapshot}" -eq 1 ] && sudo -n /usr/bin/btrfs subvolume snapshot "${src_subdir}" "${dst_subdir}" >/dev/null 2>&1; then
                :
            else
                mkdir -p "${dst_subdir}"
                if ! cp -a --reflink=always "${src_subdir}/." "${dst_subdir}/" >/dev/null 2>&1; then
                    if [[ "$(uname -s)" == "Darwin" ]]; then
                        # macOS: cp -cR usa APFS clonefile (copy-on-write, instantáneo en mismo volumen)
                        cp -cR "${src_subdir}/." "${dst_subdir}/" 2>/dev/null || \
                            cp -R "${src_subdir}/." "${dst_subdir}/"
                    else
                        docker run --rm -v "${src_subdir}:/source:ro" -v "${dst_subdir}:/target" alpine sh -lc "cp -a /source/. /target/"
                    fi
                fi
            fi
            copied=1
        fi
    done
    if [ ! -d "${dst_root}/elasticsearch-data" ]; then
        mkdir -p "${dst_root}/elasticsearch-data"
        chmod 0777 "${dst_root}/elasticsearch-data" || true
    fi
    if [ "${copied}" -eq 0 ]; then
        echo "[WARN] clone-env-data-root: no se encontraron subdirectorios base en ${src_root}"
    fi
}

has_deploy_cache_artifacts() {
    local dir="$1"
    [ -d "${dir}" ] || return 1
    find "${dir}" -maxdepth 1 -type f \( -name '*.jar' -o -name '*.war' -o -name '*.xml' \) | grep -q .
}

worktree_envs_root() {
    printf '%s\n' "${WORKTREE_BTRFS_ENVS_ROOT:-/mnt/docker-btrfs/envs}"
}

remove_btrfs_env_dir() {
    local name="$1"
    local envs_root
    envs_root="$(worktree_envs_root)"
    local env_dir="${envs_root}/${name}"
    [ -e "${env_dir}" ] || return 0

    if command -v btrfs >/dev/null 2>&1; then
        if sudo -n /usr/bin/btrfs subvolume show "${env_dir}" >/dev/null 2>&1; then
            sudo -n /usr/bin/btrfs subvolume delete "${env_dir}" >/dev/null 2>&1 || true
        fi
    fi

    if ! delete_tree_with_helper "${env_dir}"; then
        echo "[ERROR] No se pudo eliminar env Btrfs huérfano: ${env_dir}" >&2
        return 1
    fi
    return 0
}

worktree_exists_in_git() {
    local name="$1"
    local main_root
    main_root="$(main_repo_root)"
    git -C "${main_root}" worktree list --porcelain | awk '/^worktree /{print $2}' | sed -n 's#.*/\.worktrees/\([^/]*\)$#\1#p' | grep -Fxq "${name}"
}

seed_liferaycli_creds_to_env() {
    local docker_dir="${1:-${DOCKER_DIR}}"
    local row
    row="$(fetch_liferaycli_creds "${docker_dir}")"
    if [ -z "${row}" ]; then
        echo "[WARN] OAuth2 app 'liferay-cli' no encontrada en BD — credenciales no escritas en .env" >&2
        return 0
    fi
    local env_file="${docker_dir}/.env"
    upsert_env_value LIFERAY_CLI_OAUTH2_CLIENT_ID "${row%%|*}" "${env_file}"
    upsert_env_value LIFERAY_CLI_OAUTH2_CLIENT_SECRET "${row#*|}" "${env_file}"
    echo "[INFO] Credenciales liferay-cli escritas en .env (LIFERAY_CLI_OAUTH2_CLIENT_ID=${row%%|*})"
}

worktree_env_has_state() {
    local env_root="$1"
    [ -d "${env_root}" ] || return 1

    local subdir
    for subdir in postgres-data liferay-data liferay-osgi-state liferay-deploy-cache elasticsearch-data; do
        [ -d "${env_root}/${subdir}" ] && return 0
    done
    return 1
}

ensure_worktree_env_ready() {
    local wt_dir="$1"
    local wt_docker_dir="$2"
    local env_file="${wt_docker_dir}/.env"
    local clone_volumes=0

    if [ ! -f "${env_file}" ]; then
        clone_volumes=1
    else
        local env_data_root=""
        env_data_root="$(resolve_env_data_root_for_docker_dir "${wt_docker_dir}" 2>/dev/null || true)"
        if [ -z "${env_data_root}" ] || ! worktree_env_has_state "${env_data_root}"; then
            clone_volumes=1
        fi
    fi

    if [ "${clone_volumes}" -eq 1 ]; then
        echo "[INFO] Inicializando entorno aislado del worktree desde base"
        (cd "${wt_docker_dir}" && REPO_ROOT="${wt_dir}" LOCAL_OPS_DOCKER_DIR="${wt_docker_dir}" bash "${SELF_DIR}/local-ops.sh" worktree-env --clone-volumes)
    else
        echo "[INFO] Reutilizando configuracion/volumenes existentes del worktree"
        (cd "${wt_docker_dir}" && REPO_ROOT="${wt_dir}" LOCAL_OPS_DOCKER_DIR="${wt_docker_dir}" bash "${SELF_DIR}/local-ops.sh" worktree-env)
    fi
}

ensure_main_env_ready() {
    local env_file="${DOCKER_DIR}/.env"
    local env_example="${DOCKER_DIR}/.env.example"
    local build_dir="${REPO_ROOT}/liferay/build/docker"
    local deploy_dir="${build_dir}/deploy"
    local target_commit cache_restored=0

    if [ ! -f "${env_file}" ] && [ -f "${env_example}" ]; then
        echo "[INFO] Creando ${env_file} desde .env.example"
        seed_env_file_from_source "${env_file}" "${env_example}"
    fi

    ensure_main_env_layout

    bash "${SELF_DIR}/local-ops.sh" setup --auto --skip-pull --skip-db-import

    target_commit="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
    if restore_build_deploy_from_env_cache "${DOCKER_DIR}" "${build_dir}" "${target_commit}"; then
        echo "[INFO] Cache de deploy restaurada desde ENV_DATA_ROOT/liferay-deploy-cache"
        cache_restored=1
    fi
    if seed_build_docker_configs "${REPO_ROOT}" "${build_dir}"; then
        echo "[INFO] Configuracion dockerenv restaurada en build/docker/configs"
    fi

    if ! has_deploy_cache_artifacts "${deploy_dir}"; then
        echo "[INFO] Artefactos ausentes en build/docker/deploy; ejecutando prepare local"
        REPO_ROOT="${REPO_ROOT}" bash "${SELF_DIR}/../liferay/deploy.sh" run_prepare_build
        bash "${SELF_DIR}/local-ops.sh" worktree-deploy-cache-update
    elif [ "${cache_restored}" -eq 1 ]; then
        echo "[INFO] Se reutilizan artefactos restaurados desde cache persistente"
    else
        echo "[INFO] Se reutilizan artefactos existentes en build/docker/deploy"
    fi
}

cmd_env_init() {
    if [ -n "$(current_worktree_name)" ]; then
        cmd_worktree_env "$@"
        return 0
    fi

    ensure_main_env_layout
    echo "Main env OK: $(resolve_env_data_root_for_docker_dir "${DOCKER_DIR}")"
}

start_worktree_runtime() {
    local wt_dir="$1"
    local wt_docker_dir="$2"
    local main_root
    main_root="$(main_repo_root)"

    ensure_worktree_env_ready "${wt_dir}" "${wt_docker_dir}"

    (cd "${wt_dir}" && REPO_ROOT="${wt_dir}" task --taskfile "${wt_dir}/Taskfile.yml" env:setup -- --auto --skip-pull --skip-db-import)
    local main_build_dir="${main_root}/liferay/build/docker"
    local source_build_dir="${REPO_ROOT}/liferay/build/docker"
    local target_build_dir="${wt_dir}/liferay/build/docker"
    local source_commit target_commit cache_restored=0
    target_commit="$(git -C "${wt_dir}" rev-parse HEAD)"
    if [ "${source_build_dir}" != "${target_build_dir}" ]; then
        source_commit="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
        if clone_build_docker_cache "${source_build_dir}" "${target_build_dir}" "${source_commit}" "${target_commit}"; then
            echo "[INFO] Reutilizando artefactos build/docker desde origen actual (commit ${target_commit})"
            cache_restored=1
        fi
    fi
    if [ "${cache_restored}" -eq 0 ]; then
        source_commit="$(git -C "${main_root}" rev-parse HEAD)"
        if clone_build_docker_cache "${main_build_dir}" "${target_build_dir}" "${source_commit}" "${target_commit}"; then
            echo "[INFO] Reutilizando artefactos build/docker desde main (commit ${target_commit})"
            cache_restored=1
        fi
    fi
    if restore_build_deploy_from_env_cache "${wt_docker_dir}" "${target_build_dir}" "${target_commit}"; then
        echo "[INFO] Cache de deploy restaurada desde ENV_DATA_ROOT/liferay-deploy-cache"
    fi

    local wt_deploy_dir="${target_build_dir}/deploy"
    if ! has_deploy_cache_artifacts "${wt_deploy_dir}"; then
        local main_env_data_root
        main_env_data_root="$(resolve_env_data_root_for_docker_dir "${main_root}/docker" 2>/dev/null || true)"
        main_env_data_root="${main_env_data_root:-${main_root}/docker/data/default}"
        local main_deploy_cache="${main_env_data_root}/liferay-deploy-cache"
        if has_deploy_cache_artifacts "${main_deploy_cache}" && cache_matches_target_commit "${main_deploy_cache}" "${target_commit}"; then
            mkdir -p "${wt_deploy_dir}"
            find "${main_deploy_cache}" -maxdepth 1 -type f \( -name '*.jar' -o -name '*.war' -o -name '*.xml' \) -exec cp -f {} "${wt_deploy_dir}/" \;
            echo "[INFO] Deploy dir poblado desde deploy-cache del entorno principal"
        elif has_deploy_cache_artifacts "${main_deploy_cache}"; then
            echo "[INFO] Se omite deploy-cache del entorno principal: commit cache != commit worktree (${target_commit})"
        fi
    fi

    if seed_build_docker_configs "${wt_dir}" "${target_build_dir}"; then
        echo "[INFO] Configuracion dockerenv restaurada en build/docker/configs"
    fi
    local worktree_http_port
    worktree_http_port="$(read_env_value LIFERAY_HTTP_PORT "${wt_docker_dir}/.env")"
    worktree_http_port="${worktree_http_port:-8080}"
    ensure_portal_ext_local_override "${wt_dir}" "${target_build_dir}" "${worktree_http_port}"
    echo "[INFO] portal-ext.local.properties actualizado (web.server.http.port=${worktree_http_port})"

    if has_deploy_cache_artifacts "${wt_deploy_dir}"; then
        (cd "${wt_docker_dir}" && REPO_ROOT="${wt_dir}" LOCAL_OPS_DOCKER_DIR="${wt_docker_dir}" bash "${SELF_DIR}/local-ops.sh" worktree-deploy-cache-update)
    else
        echo "[INFO] Deploy dir vacío; ejecutando task deploy:prepare completo en el worktree (primera vez)"
        (cd "${wt_dir}" && REPO_ROOT="${wt_dir}" task --taskfile "${wt_dir}/Taskfile.yml" deploy:prepare)
    fi

    (cd "${wt_docker_dir}" && docker compose up -d)
    wait_for_compose_service_health "${wt_docker_dir}" liferay 250
    seed_liferaycli_creds_to_env "${wt_docker_dir}"

    local http_port bind_ip
    http_port="$(read_env_value LIFERAY_HTTP_PORT "${wt_docker_dir}/.env")"
    bind_ip="$(read_env_value BIND_IP "${wt_docker_dir}/.env")"
    http_port="${http_port:-8080}"
    bind_ip="${bind_ip:-localhost}"
    echo "Worktree listo en: http://${bind_ip}:${http_port}"
    echo "Siguiente paso: cd ${wt_dir}/docker"
}

cmd_post_start() {
    wait_for_compose_service_health "${DOCKER_DIR}" liferay 250
    seed_liferaycli_creds_to_env "${DOCKER_DIR}"
}

cmd_env_start() {
    local wt_name
    wt_name="$(current_worktree_name)"
    ensure_repo_git_hooks_installed
    guardrail_primary_checkout_feature_branch "arrancar el entorno" || exit 1

    if [ -n "${wt_name}" ]; then
        start_worktree_runtime "${REPO_ROOT}" "${DOCKER_DIR}"
        return 0
    fi

    ensure_main_env_ready
    bash "${LCP_OPS_SH}" doclib-mount || true
    docker_compose_in "${DOCKER_DIR}" up -d
}

cmd_worktree_setup() {
    local name=""
    local with_env=0
    local base_ref="${WORKTREE_SOURCE_REF:-HEAD}"
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --name)
            shift || true
            name="${1:-}"
            require_arg "${name}" "NAME"
            ;;
        --issue)
            shift || true
            local issue_num="${1:-}"
            require_arg "${issue_num}" "ISSUE"
            name="issue-${issue_num}"
            ;;
        --base)
            shift || true
            base_ref="${1:-}"
            require_arg "${base_ref}" "BASE"
            ;;
        --with-env) with_env=1 ;;
        --no-env) ;;
        -h|--help)
            cat <<'EOF'
Uso: local-ops.sh worktree-setup [NAME] [--name NAME] [--issue NUM] [--base REF] [--with-env]

Ejemplos:
  local-ops.sh worktree-setup issue-587
  local-ops.sh worktree-setup --issue 587 --base fix/page-layout-cli
  local-ops.sh worktree-setup --name issue-587-page-layout --base fix/page-layout-cli --with-env
EOF
            return 0
            ;;
        *)
            if [[ -z "${name}" && ! "$1" =~ ^- ]]; then
                name="$1"
            else
                echo "[ERROR] Opción no soportada para worktree-setup: $1" >&2
                exit 1
            fi
            ;;
        esac
        shift || true
    done
    require_arg "${name}" "NAME"
    local main_root
    main_root="$(main_repo_root)"
    local wt_dir="${main_root}/.worktrees/${name}"
    local wt_docker_dir="${wt_dir}/docker"
    local branch="fix/${name}"
    local start_ref="${base_ref}"
    local reused_worktree=0

    ensure_repo_git_hooks_installed
    guardrail_primary_checkout_feature_branch "crear otro worktree desde una raíz incorrecta" || exit 1

    if [ -d "${wt_dir}" ]; then
        if git -C "${main_root}" worktree list --porcelain | grep -Fq "worktree ${wt_dir}"; then
            echo "Worktree reutilizado: ${wt_dir}"
            reused_worktree=1
        else
            echo "[ERROR] El path existe pero no es un git worktree registrado: ${wt_dir}" >&2
            exit 1
        fi
    else
        if git -C "${main_root}" show-ref --verify --quiet "refs/heads/${branch}"; then
            git -C "${main_root}" worktree add "${wt_dir}" "${branch}"
        else
            git -C "${main_root}" worktree add -b "${branch}" "${wt_dir}" "${start_ref}"
        fi
    fi

    if [ "${with_env}" -eq 0 ]; then
        echo "Worktree listo sin entorno: ${wt_dir}"
        echo "Para levantar Docker/BD/doclib más tarde: cd ${wt_dir} && task env:start"
        echo "Atajo desde la raíz principal: task worktree:start -- ${name}"
        echo "Siguiente paso: cd ${wt_dir}"
        return 0
    fi

    start_worktree_runtime "${wt_dir}" "${wt_docker_dir}"
}

cmd_worktree_start() {
    local name="${1:-}"
    require_arg "${name}" "NAME"
    guardrail_primary_checkout_feature_branch "arrancar un worktree desde una raíz incorrecta" || exit 1
    local main_root
    main_root="$(main_repo_root)"
    local wt_dir="${main_root}/.worktrees/${name}"
    local wt_docker_dir="${wt_dir}/docker"

    if [ ! -d "${wt_dir}" ] || [ ! -d "${wt_docker_dir}" ]; then
        echo "[ERROR] Worktree no encontrado: ${wt_dir}" >&2
        echo "[ERROR] Crea primero el worktree con 'task worktree:new -- ${name}'." >&2
        exit 1
    fi
    if ! git -C "${main_root}" worktree list --porcelain | grep -Fq "worktree ${wt_dir}"; then
        echo "[ERROR] El path existe pero no es un git worktree registrado: ${wt_dir}" >&2
        exit 1
    fi

    start_worktree_runtime "${wt_dir}" "${wt_docker_dir}"
}

cmd_worktree_env() {
    local clone_volumes=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --clone-volumes) clone_volumes=1 ;;
        *) echo "[ERROR] Opción no soportada para worktree-env: $1" >&2; exit 1 ;;
        esac
        shift || true
    done
    local wt_name
    wt_name="$(current_worktree_name)"
    if [ -z "${wt_name}" ]; then
        echo "[ERROR] worktree-env debe ejecutarse dentro de un worktree." >&2
        exit 1
    fi

    local env_file="${DOCKER_DIR}/.env"
    local main_root source_env=""
    main_root="$(main_repo_root)"
    if [ -f "${main_root}/docker/.env" ] && [ "${DOCKER_DIR}" != "${main_root}/docker" ]; then
        source_env="${main_root}/docker/.env"
    elif [ -f "${DOCKER_DIR}/.env.example" ]; then
        source_env="${DOCKER_DIR}/.env.example"
    fi
    if [ -n "${source_env}" ]; then
        seed_env_file_from_source "${env_file}" "${source_env}" || true
    fi

    local bind_ip
    bind_ip="$(read_env_value BIND_IP "${env_file}")"; bind_ip="${bind_ip:-127.0.0.1}"
    local hash
    hash="$(printf '%s' "${wt_name}" | cksum | awk '{print $1}')"
    local offset=$((hash % 800))
    local http_port=$((8100 + offset))
    local debug_port=$((9000 + offset))
    local gogo_port=$((12000 + offset))
    local pg_port=$((5400 + offset))
    local es_port=$((9201 + offset))
    # Btrfs: solo activar si está explícitamente configurado en el .env del main.
    # Sin configuración explícita no activar aunque /mnt/docker-btrfs exista en el host.
    local btrfs_root="" btrfs_base="" btrfs_envs="" use_btrfs_snapshots="" env_data_root=""
    btrfs_root="$(read_env_value BTRFS_ROOT "${env_file}")"
    use_btrfs_snapshots="$(read_env_value USE_BTRFS_SNAPSHOTS "${env_file}")"
    if [ -n "${btrfs_root}" ] && [ -n "${use_btrfs_snapshots}" ] && [ "${use_btrfs_snapshots}" != "false" ]; then
        btrfs_base="$(read_env_value BTRFS_BASE "${env_file}")"; btrfs_base="${btrfs_base:-${btrfs_root}/base}"
        btrfs_envs="$(read_env_value BTRFS_ENVS "${env_file}")"; btrfs_envs="${btrfs_envs:-${btrfs_root}/envs}"
        if [ -d "${btrfs_root}" ] && [ -d "${btrfs_base}" ] && [ -d "${btrfs_envs}" ]; then
            env_data_root="${btrfs_envs}/${wt_name}"
        else
            env_data_root="${DOCKER_DIR}/data/envs/${wt_name}"
        fi
    else
        env_data_root="${DOCKER_DIR}/data/envs/${wt_name}"
    fi

    # Leer COMPOSE_PROJECT_NAME siempre del main para evitar que re-ejecuciones
    # de cmd_worktree_env dupliquen el sufijo (liferay-test2 → liferay-test2-test2).
    local main_compose_project
    local main_env_for_project="${main_root}/docker/.env"
    if [ -f "${main_env_for_project}" ]; then
        main_compose_project="$(read_env_value COMPOSE_PROJECT_NAME "${main_env_for_project}")"
    fi
    main_compose_project="${main_compose_project:-liferay}"

    upsert_env_value BIND_IP "${bind_ip}" "${env_file}"
    upsert_env_value LIFERAY_CLI_URL "http://${bind_ip}:${http_port}" "${env_file}"
    upsert_env_value COMPOSE_PROJECT_NAME "${main_compose_project}-${wt_name}" "${env_file}"
    upsert_env_value VOLUME_PREFIX "${main_compose_project}-${wt_name}" "${env_file}"
    upsert_env_value DOCLIB_VOLUME_NAME "${main_compose_project}-${wt_name}-doclib" "${env_file}"
    upsert_env_value LIFERAY_HTTP_PORT "${http_port}" "${env_file}"
    upsert_env_value LIFERAY_DEBUG_PORT "${debug_port}" "${env_file}"
    upsert_env_value GOGO_PORT "${gogo_port}" "${env_file}"
    upsert_env_value POSTGRES_PORT "${pg_port}" "${env_file}"
    upsert_env_value ES_HTTP_PORT "${es_port}" "${env_file}"
    upsert_env_value ENV_DATA_ROOT "${env_data_root}" "${env_file}"
    if [ -n "${btrfs_root}" ] && [ -n "${use_btrfs_snapshots}" ] && [ "${use_btrfs_snapshots}" != "false" ]; then
        upsert_env_value BTRFS_ROOT "${btrfs_root}" "${env_file}"
        upsert_env_value BTRFS_BASE "${btrfs_base}" "${env_file}"
        upsert_env_value BTRFS_ENVS "${btrfs_envs}" "${env_file}"
        upsert_env_value USE_BTRFS_SNAPSHOTS "${use_btrfs_snapshots}" "${env_file}"
    fi

    ensure_portal_ext_local_override "${REPO_ROOT}" "${REPO_ROOT}/liferay/build/docker" "${http_port}"

    if [ "${clone_volumes}" -eq 1 ]; then
        local src_data dst_data main_env_data_root
        main_env_data_root="$(read_env_value ENV_DATA_ROOT "${main_root}/docker/.env")"
        if [ -n "${main_env_data_root}" ]; then
            main_env_data_root="$(resolve_path_from_env_file "${main_env_data_root}" "${main_root}/docker/.env")"
        fi
        src_data="${main_env_data_root:-${main_root}/docker/data/default}"
        if [ -d "${btrfs_base}" ] && [ "${use_btrfs_snapshots}" != "false" ]; then
            src_data="${btrfs_base}"
        fi
        dst_data="${env_data_root}"
        if [ -d "${src_data}" ]; then
            clone_env_data_root_btrfs "${src_data}" "${dst_data}"
            normalize_env_data_permissions "${dst_data}"
        fi
    fi
    ensure_doclib_volume >/dev/null
    echo "Worktree env OK: ${wt_name} (${http_port})"
}

cmd_worktree_restore() {
    local name="${1:-}"
    local main_root wt_root wt_name
    main_root="$(main_repo_root)"
    if [ -n "${name}" ]; then
        wt_root="${main_root}/.worktrees/${name}"
    else
        wt_root="${REPO_ROOT}"
    fi
    if [ ! -d "${wt_root}/docker" ]; then
        echo "[ERROR] Worktree no encontrado: ${wt_root}" >&2
        exit 1
    fi
    wt_name="$(basename "${wt_root}")"
    docker_compose_in "${wt_root}/docker" down || true
    local wt_env_file="${wt_root}/docker/.env"
    local btrfs_base use_btrfs_snapshots src_data dst_data main_env_data_root
    btrfs_base="$(read_env_value BTRFS_BASE "${wt_env_file}")"; btrfs_base="${btrfs_base:-/mnt/docker-btrfs/base}"
    use_btrfs_snapshots="$(read_env_value USE_BTRFS_SNAPSHOTS "${wt_env_file}")"; use_btrfs_snapshots="${use_btrfs_snapshots:-auto}"
    main_env_data_root="$(read_env_value ENV_DATA_ROOT "${main_root}/docker/.env")"
    if [ -n "${main_env_data_root}" ]; then
        main_env_data_root="$(resolve_path_from_env_file "${main_env_data_root}" "${main_root}/docker/.env")"
    fi
    src_data="${main_env_data_root:-${main_root}/docker/data/default}"
    if [ -d "${btrfs_base}" ] && [ "${use_btrfs_snapshots}" != "false" ]; then
        src_data="${btrfs_base}"
    fi
    dst_data="$(resolve_env_data_root_for_docker_dir "${wt_root}/docker" 2>/dev/null || true)"
    dst_data="${dst_data:-${wt_root}/docker/data/envs/${wt_name}}"
    local clone_deploy_cache=1
    local dst_deploy_cache="${dst_data}/liferay-deploy-cache"
    if has_deploy_cache_artifacts "${dst_deploy_cache}"; then
        clone_deploy_cache=0
        echo "[INFO] Preservando deploy-cache existente en ${dst_deploy_cache}"
    fi
    if [ -d "${src_data}" ]; then
        clone_env_data_root_btrfs "${src_data}" "${dst_data}" "${clone_deploy_cache}"
        normalize_env_data_permissions "${dst_data}"
    else
        echo "[WARN] No existe source data root: ${src_data}"
    fi
    echo "Worktree restore OK: ${wt_name}"
}

cmd_worktree_deploy_cache_update() {
    local clean=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --clean) clean=1 ;;
        -h|--help)
            echo "Uso: local-ops.sh worktree-deploy-cache-update [--clean]"
            return 0
            ;;
        *)
            echo "[ERROR] Opción no soportada para worktree-deploy-cache-update: $1" >&2
            exit 1
            ;;
        esac
        shift || true
    done

    local env_file env_data_root source_dir cache_dir compose_project source_commit
    env_file="${DOCKER_DIR}/.env"
    env_data_root="$(read_env_value ENV_DATA_ROOT "${env_file}")"
    if [ -z "${env_data_root}" ]; then
        echo "[ERROR] Falta ENV_DATA_ROOT en ${env_file}" >&2
        exit 1
    fi
    env_data_root="$(resolve_path_from_env_file "${env_data_root}" "${env_file}")"
    compose_project="$(read_env_value COMPOSE_PROJECT_NAME "${env_file}")"
    compose_project="${compose_project:-liferay}"
    if docker ps --format '{{.Names}}' | grep -q "^${compose_project}-liferay$"; then
        echo "[WARN] Se recomienda ejecutar con entorno parado para evitar auto-deploy durante el copiado." >&2
        echo "[WARN] Flujo recomendado: task stop && task deploy:prepare && task worktree-deploy-cache-update --clean && task start" >&2
    fi

    source_dir="${REPO_ROOT}/liferay/build/docker/deploy"
    cache_dir="${env_data_root}/liferay-deploy-cache"
    if [ ! -d "${source_dir}" ]; then
        echo "[ERROR] No existe ${source_dir}. Ejecuta 'task deploy:prepare' primero." >&2
        exit 1
    fi

    local -a artifacts=()
    while IFS= read -r -d '' artifact; do
        artifacts+=("${artifact}")
    done < <(find "${source_dir}" -maxdepth 1 -type f \( -name '*.jar' -o -name '*.war' -o -name '*.xml' \) -print0)

    if [ "${#artifacts[@]}" -eq 0 ]; then
        echo "[ERROR] No hay artefactos en ${source_dir}. Ejecuta 'task deploy:prepare'." >&2
        exit 1
    fi

    mkdir -p "${cache_dir}"
    if [ "${clean}" -eq 1 ]; then
        find "${cache_dir}" -maxdepth 1 -type f \( -name '*.jar' -o -name '*.war' -o -name '*.xml' \) -delete
    fi

    local copied=0
    for artifact in "${artifacts[@]}"; do
        if cp -a --reflink=always "${artifact}" "${cache_dir}/" >/dev/null 2>&1; then
            :
        else
            cp -a "${artifact}" "${cache_dir}/"
        fi
        copied=$((copied + 1))
    done

    source_commit="$(read_prepare_commit "${REPO_ROOT}/liferay/build/docker" 2>/dev/null || git -C "${REPO_ROOT}" rev-parse HEAD)"
    printf '%s\n' "${source_commit}" > "${cache_dir}/.prepare-commit"

    echo "Deploy cache update OK: source=${source_dir} cache=${cache_dir} copied=${copied} clean=${clean} commit=${source_commit}"
}

cmd_btrfs_setup() {
    local apply=0
    local confirm=""
    local size_gb="200"
    local mount_point="${DOCKER_DIR}/data"
    local loop_file="${DOCKER_DIR}/data/.btrfs-loop.img"
    local mount_opts="loop,compress=zstd,noatime,user_subvol_rm_allowed"
    local source_data_root="${DOCKER_DIR}/data/default"
    local skip_migration=0
    local force_migration=0
    local mount_point_explicit=0
    local loop_file_explicit=0
    local source_data_root_explicit=0
    local env_file="${DOCKER_DIR}/.env"
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --apply) apply=1 ;;
        --confirm-token) shift; require_arg "${1:-}" "valor para --confirm-token"; confirm="$1" ;;
        --size-gb) shift; require_arg "${1:-}" "valor para --size-gb"; size_gb="$1" ;;
        --mount-point) shift; require_arg "${1:-}" "valor para --mount-point"; mount_point="$1"; mount_point_explicit=1 ;;
        --loop-file) shift; require_arg "${1:-}" "valor para --loop-file"; loop_file="$1"; loop_file_explicit=1 ;;
        --mount-opts) shift; require_arg "${1:-}" "valor para --mount-opts"; mount_opts="$1" ;;
        --source-data-root) shift; require_arg "${1:-}" "valor para --source-data-root"; source_data_root="$1"; source_data_root_explicit=1 ;;
        --skip-migration) skip_migration=1 ;;
        --force-migration) force_migration=1 ;;
        *) echo "[ERROR] Opción no soportada para btrfs-setup: $1" >&2; exit 1 ;;
        esac
        shift || true
    done

    if [ "${source_data_root_explicit}" -eq 0 ]; then
        source_data_root="$(resolve_env_data_root_for_docker_dir "${DOCKER_DIR}" 2>/dev/null || true)"
        source_data_root="${source_data_root:-${DOCKER_DIR}/data/default}"
    fi

    if [ "${mount_point_explicit}" -eq 0 ]; then
        local configured_btrfs_root=""
        configured_btrfs_root="$(read_env_value BTRFS_ROOT "${env_file}")"
        if [ -n "${configured_btrfs_root}" ]; then
            mount_point="$(resolve_path_from_env_file "${configured_btrfs_root}" "${env_file}")"
        elif [ -d "/mnt/docker-btrfs/base" ] && [ -d "/mnt/docker-btrfs/envs" ]; then
            mount_point="/mnt/docker-btrfs"
        fi
    fi

    if [ "${loop_file_explicit}" -eq 0 ] && [ "${mount_point}" = "/mnt/docker-btrfs" ]; then
        loop_file="/var/lib/docker-btrfs.loop"
    fi

    if [ "${apply}" -eq 0 ] || [ "${confirm}" != "BTRFS" ]; then
        echo "DRY-RUN btrfs-setup: usar --apply --confirm-token BTRFS para ejecutar."
        echo "  size_gb=${size_gb}"
        echo "  mount_point=${mount_point}"
        echo "  loop_file=${loop_file}"
        echo "  mount_opts=${mount_opts}"
        echo "  source_data_root=${source_data_root}"
        echo "  skip_migration=${skip_migration}"
        echo "  force_migration=${force_migration}"
        return 0
    fi

    if ! command -v btrfs >/dev/null 2>&1; then
        echo "[ERROR] btrfs no disponible en el host." >&2
        exit 1
    fi

    mkdir -p "${mount_point}"
    if [ "${force_migration}" -eq 0 ] && [ ! -f "${loop_file}" ]; then
        echo "[INFO] Creando imagen btrfs de ${size_gb}GB en ${loop_file}..."
        truncate -s "${size_gb}G" "${loop_file}"
        echo "[INFO] Formateando filesystem btrfs..."
        mkfs.btrfs -f "${loop_file}"
        echo "[INFO] Montando filesystem en ${mount_point}..."
        sudo mount -o "${mount_opts}" "${loop_file}" "${mount_point}"
        echo "[INFO] Creando subvolúmenes base..."
        sudo btrfs subvolume create "${mount_point}/base"
        sudo btrfs subvolume create "${mount_point}/main"
        sudo btrfs subvolume create "${mount_point}/envs"
        for subdir in postgres-data liferay-data liferay-osgi-state elasticsearch-data liferay-deploy-cache; do
            echo "  - creando ${subdir}..."
            sudo btrfs subvolume create "${mount_point}/base/${subdir}"
            sudo btrfs subvolume create "${mount_point}/main/${subdir}"
        done
    fi

    if [ -d "${mount_point}" ]; then
        if [ ! -d "${mount_point}/base" ]; then
            sudo btrfs subvolume create "${mount_point}/base"
        fi
        if [ ! -d "${mount_point}/main" ]; then
            sudo btrfs subvolume create "${mount_point}/main"
        fi
        if [ ! -d "${mount_point}/envs" ]; then
            sudo btrfs subvolume create "${mount_point}/envs"
        fi
        for subdir in postgres-data liferay-data liferay-osgi-state elasticsearch-data liferay-deploy-cache; do
            [ -d "${mount_point}/base/${subdir}" ] || sudo btrfs subvolume create "${mount_point}/base/${subdir}"
            [ -d "${mount_point}/main/${subdir}" ] || sudo btrfs subvolume create "${mount_point}/main/${subdir}"
        done
    fi

    if [ "${skip_migration}" -eq 0 ] || [ "${force_migration}" -eq 1 ]; then
        echo "[INFO] Iniciando migración de datos (esto puede tomar varios minutos)..."
        for subdir in postgres-data liferay-data liferay-osgi-state elasticsearch-data liferay-deploy-cache; do
            if [ ! -d "${source_data_root}/${subdir}" ]; then
                continue
            fi
            echo "[INFO] Migrando ${subdir}..."
            docker run --rm \
                -v "${source_data_root}/${subdir}:/from:ro" \
                -v "${mount_point}/base/${subdir}:/to" \
                alpine sh -lc 'set -eu; mkdir -p /to; find /to -mindepth 1 -delete || true; cd /from; tar -cpf - . | tar -xpf - -C /to'
        done
        echo "[INFO] Migración de datos completada"
    fi
    echo "[INFO] btrfs-setup completado en ${mount_point}"
}

cmd_worktree_clean() {
    local name="${1:-}"
    require_arg "${name}" "NAME"
    shift || true
    local confirm=""
    local keep_branch=0
    local allow_unreviewed_commits=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --apply) confirm="DELETE" ;;
        --keep-branch) keep_branch=1 ;;
        --allow-unreviewed-commits) allow_unreviewed_commits=1 ;;
        *) echo "[ERROR] Opción no soportada para worktree-clean: $1" >&2; exit 1 ;;
        esac
        shift || true
    done
    local main_root
    main_root="$(main_repo_root)"
    local wt_dir="${main_root}/.worktrees/${name}"
    local wt_docker_dir="${wt_dir}/docker"
    local wt_env_file="${wt_docker_dir}/.env"
    local branch="fix/${name}"
    local main_compose_project
    main_compose_project="$(read_env_value COMPOSE_PROJECT_NAME "${main_root}/docker/.env" 2>/dev/null || true)"
    main_compose_project="${main_compose_project:-liferay}"
    local compose_project="${main_compose_project}-${name}"
    local doclib_volume=""
    if [ -f "${wt_env_file}" ]; then
        compose_project="$(read_env_value COMPOSE_PROJECT_NAME "${wt_env_file}")"
        compose_project="${compose_project:-${main_compose_project}-${name}}"
        doclib_volume="$(read_env_value DOCLIB_VOLUME_NAME "${wt_env_file}")"
    fi

    if [ "${confirm}" != "DELETE" ]; then
        echo "DRY-RUN worktree-clean: ${wt_dir} (usa --apply para ejecutar)"
        return 0
    fi

    if [ "${allow_unreviewed_commits}" -eq 0 ] && git -C "${main_root}" show-ref --verify --quiet "refs/heads/${branch}"; then
        local base_ref="refs/remotes/origin/main"
        local ahead_count
        if ! git -C "${main_root}" show-ref --verify --quiet "${base_ref}"; then
            base_ref="refs/heads/main"
        fi
        ahead_count="$(git -C "${main_root}" rev-list --count "${base_ref}..${branch}" 2>/dev/null || echo 0)"
        if [ "${ahead_count}" -gt 0 ]; then
            local pr_check_status=1
            if command -v gh >/dev/null 2>&1; then
                if (cd "${main_root}" && gh pr list --head "${branch}" --state all --json url --jq 'length' >/tmp/worktree-pr-count.$$ 2>/dev/null); then
                    local pr_count
                    pr_count="$(cat /tmp/worktree-pr-count.$$ 2>/dev/null || echo 0)"
                    rm -f /tmp/worktree-pr-count.$$ >/dev/null 2>&1 || true
                    if [ "${pr_count}" -gt 0 ]; then
                        pr_check_status=0
                    else
                        pr_check_status=1
                    fi
                else
                    rm -f /tmp/worktree-pr-count.$$ >/dev/null 2>&1 || true
                    pr_check_status=2
                fi
            else
                pr_check_status=2
            fi

            if [ "${pr_check_status}" -ne 0 ]; then
                echo "[ERROR] Refusing to remove worktree '${name}'." >&2
                echo "        Branch ${branch} has ${ahead_count} commit(s) ahead of ${base_ref}." >&2
                if [ "${pr_check_status}" -eq 1 ]; then
                    echo "        No PR was found for ${branch}." >&2
                else
                    echo "        PR status could not be verified (gh missing or not authenticated)." >&2
                fi
                echo "        Push/create the PR first, or rerun with --allow-unreviewed-commits if cleanup is intentional." >&2
                exit 1
            fi
        fi
    fi

    if [ -f "${wt_docker_dir}/docker-compose.yml" ]; then
        echo "Deteniendo contenedores de ${compose_project}..."
        if [ -f "${wt_env_file}" ]; then
            run_timeout 60 docker compose -f "${wt_docker_dir}/docker-compose.yml" --env-file "${wt_env_file}" -p "${compose_project}" down --remove-orphans || true
        else
            run_timeout 60 docker compose -f "${wt_docker_dir}/docker-compose.yml" -p "${compose_project}" down --remove-orphans || true
        fi
    fi
    local stale_containers
    stale_containers="$(docker ps -aq --filter "label=com.docker.compose.project=${compose_project}" 2>/dev/null || true)"
    [ -n "${stale_containers}" ] && echo "${stale_containers}" | xargs docker rm -f >/dev/null 2>&1 || true

    if [ -d "${wt_dir}" ]; then
        if ! git -C "${main_root}" worktree remove --force "${wt_dir}" >/dev/null 2>&1; then
            delete_tree_with_helper "${wt_dir}" || true
            if [ -d "${wt_dir}" ]; then

                echo "[ERROR] No se pudo eliminar worktree: ${wt_dir}" >&2
                exit 1
            fi
        fi
    fi
    if [ "${keep_branch}" -eq 0 ] && git -C "${main_root}" show-ref --verify --quiet "refs/heads/${branch}"; then
        git -C "${main_root}" branch -D "${branch}" || true
    fi
    local worktree_data_root="${wt_docker_dir}/data/${name}"
    local env_data_root=""
    if [ -f "${wt_env_file}" ]; then
        env_data_root="$(resolve_env_data_root_for_docker_dir "${wt_docker_dir}" 2>/dev/null || true)"
    fi
    delete_tree_with_helper "${worktree_data_root}" || true
    if [ -n "${env_data_root}" ]; then
        delete_tree_with_helper "${env_data_root}" || true
    fi
    remove_btrfs_env_dir "${name}" || exit 1
    for volume_name in "${doclib_volume}" "${compose_project}-doclib" "${main_compose_project}-${name}-doclib"; do
        [ -n "${volume_name}" ] || continue
        docker volume rm "${volume_name}" >/dev/null 2>&1 || true
    done
    if docker ps --format '{{.Names}}' | grep -q "^${compose_project}-"; then
        echo "[ERROR] Quedaron contenedores vivos para ${compose_project}" >&2
        exit 1
    fi
    echo "Worktree clean OK: ${name}"
}

cmd_worktree_gc() {
    local days="7"
    local clean_stale=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --days) shift; require_arg "${1:-}" "valor para --days"; days="$1" ;;
        --clean-stale) clean_stale=1 ;;
        *) echo "[ERROR] Opción no soportada para worktree-gc: $1" >&2; exit 1 ;;
        esac
        shift || true
    done
    local main_root
    main_root="$(main_repo_root)"
    local main_compose_project
    main_compose_project="$(read_env_value COMPOSE_PROJECT_NAME "${main_root}/docker/.env" 2>/dev/null || true)"
    main_compose_project="${main_compose_project:-liferay}"
    local current_wt
    current_wt="$(current_worktree_name)"
    local cutoff
    # date -d es GNU; en macOS (BSD date) se usa date -v-Nd
    if date -d "-${days} days" +%s >/dev/null 2>&1; then
        cutoff="$(date -d "-${days} days" +%s)"
    else
        cutoff="$(date -v-"${days}"d +%s)"
    fi

    # Si --clean-stale, elimina worktrees viejos
    if [ "${clean_stale}" -eq 1 ]; then
        for dir in "${main_root}/.worktrees/"*; do
            [ -d "${dir}" ] || continue
            local base mtime
            base="$(basename "${dir}")"
            if [ -n "${current_wt}" ] && [ "${base}" = "${current_wt}" ]; then
                continue
            fi
            if docker ps --format '{{.Names}}' | grep -q "^${main_compose_project}-${base}-"; then
                continue
            fi
            mtime="$(file_mtime "${dir}")"
            if [ "${mtime}" -gt "${cutoff}" ]; then
                continue
            fi
            cmd_worktree_clean "${base}" --apply || true
        done
    else
        # Por defecto, solo preview de worktrees viejos
        for dir in "${main_root}/.worktrees/"*; do
            [ -d "${dir}" ] || continue
            local base mtime
            base="$(basename "${dir}")"
            if [ -n "${current_wt}" ] && [ "${base}" = "${current_wt}" ]; then
                continue
            fi
            if docker ps --format '{{.Names}}' | grep -q "^${main_compose_project}-${base}-"; then
                continue
            fi
            mtime="$(file_mtime "${dir}")"
            if [ "${mtime}" -gt "${cutoff}" ]; then
                continue
            fi
            echo "DRY-RUN GC candidate (usa --clean-stale para eliminar): ${base}"
        done
    fi

    # Siempre limpia btrfs orphans automáticamente
    local envs_root
    envs_root="$(worktree_envs_root)"
    if [ -d "${envs_root}" ]; then
        for env_dir in "${envs_root}/"*; do
            [ -d "${env_dir}" ] || continue
            local env_name env_mtime
            env_name="$(basename "${env_dir}")"
            if worktree_exists_in_git "${env_name}"; then
                continue
            fi
            env_mtime="$(file_mtime "${env_dir}")"
            if [ "${env_mtime}" -gt "${cutoff}" ]; then
                continue
            fi
            echo "GC orphan env: ${env_name}"
            remove_btrfs_env_dir "${env_name}" || true
        done
    fi
}

cmd_setup() {
    local auto=0
    local skip_db_import=0
    local skip_pull=0
    local download_doclib=0
    local doclib_dest=""
    local doclib_local_path=""
    local doclib_nas_ip=""
    local doclib_nas_share=""
    local doclib_nas_user=""
    local doclib_nas_pass=""
    local doclib_nas_port=""

    while [ "$#" -gt 0 ]; do
        case "$1" in
        --auto) auto=1 ;;
        --skip-db-import) skip_db_import=1 ;;
        --skip-pull) skip_pull=1 ;;
        --download-doclib) download_doclib=1 ;;
        --doclib-dest) shift; require_arg "${1:-}" "valor para --doclib-dest"; doclib_dest="$1" ;;
        --doclib-local-path) shift; require_arg "${1:-}" "valor para --doclib-local-path"; doclib_local_path="$1" ;;
        --doclib-nas-ip) shift; require_arg "${1:-}" "valor para --doclib-nas-ip"; doclib_nas_ip="$1" ;;
        --doclib-nas-share) shift; require_arg "${1:-}" "valor para --doclib-nas-share"; doclib_nas_share="$1" ;;
        --doclib-nas-user) shift; require_arg "${1:-}" "valor para --doclib-nas-user"; doclib_nas_user="$1" ;;
        --doclib-nas-pass) shift; require_arg "${1:-}" "valor para --doclib-nas-pass"; doclib_nas_pass="$1" ;;
        --doclib-nas-port) shift; require_arg "${1:-}" "valor para --doclib-nas-port"; doclib_nas_port="$1" ;;
        *) echo "[ERROR] Flag no soportada para setup: $1" >&2; exit 1 ;;
        esac
        shift || true
    done

    ensure_repo_git_hooks_installed
    guardrail_primary_checkout_feature_branch "preparar el entorno local" || exit 1
    ensure_main_env_layout

    if [ -n "${doclib_local_path}" ]; then
        upsert_env_value DOCLIB_PATH "${doclib_local_path}" "${DOCKER_DIR}/.env"
        upsert_env_value DOCLIB_NAS_IP "" "${DOCKER_DIR}/.env"
        upsert_env_value DOCLIB_NAS_SHARE "" "${DOCKER_DIR}/.env"
        upsert_env_value DOCLIB_NAS_USER "" "${DOCKER_DIR}/.env"
        upsert_env_value DOCLIB_NAS_PASS "" "${DOCKER_DIR}/.env"
    fi
    if [ -n "${doclib_nas_ip}" ]; then
        upsert_env_value DOCLIB_PATH "" "${DOCKER_DIR}/.env"
        upsert_env_value DOCLIB_NAS_IP "${doclib_nas_ip}" "${DOCKER_DIR}/.env"
        upsert_env_value DOCLIB_NAS_SHARE "${doclib_nas_share}" "${DOCKER_DIR}/.env"
        upsert_env_value DOCLIB_NAS_USER "${doclib_nas_user}" "${DOCKER_DIR}/.env"
        upsert_env_value DOCLIB_NAS_PASS "${doclib_nas_pass}" "${DOCKER_DIR}/.env"
        upsert_env_value DOCLIB_NAS_PORT "${doclib_nas_port:-10445}" "${DOCKER_DIR}/.env"
    fi

    echo "=== Prerequisitos ==="
    docker compose version && echo "  Docker Compose: OK"
    command -v gh >/dev/null 2>&1 && echo "  gh CLI: OK" || echo "  gh CLI: NO INSTALADO (opcional para LCP)"
    command -v node >/dev/null 2>&1 && echo "  Node.js: OK" || echo "  Node.js: NO INSTALADO (necesario para deploy-theme)"
    ensure_playwright_cli_installed
    ensure_playwright_cli_runtime
    (cd "${REPO_ROOT}/liferay" && ./gradlew --version 2>/dev/null | head -1) && echo "  Gradle: OK" || echo "  Gradle: NO DISPONIBLE"
    echo ""

    if [ "${skip_pull}" -eq 0 ]; then
        # --ignore-buildable skips images that have a build: context (e.g. elasticsearch)
        # which are built locally and don't exist on Docker Hub
        docker_compose_in "${DOCKER_DIR}" pull --ignore-buildable
    fi

    if [ "${download_doclib}" -eq 1 ]; then
        if [ -n "${doclib_dest}" ]; then
            bash "${LCP_OPS_SH}" db-download --download-doclib --doclib-dest "${doclib_dest}"
        else
            bash "${LCP_OPS_SH}" db-download --download-doclib
        fi
    fi

    bash "${LCP_OPS_SH}" doclib-mount

    if [ "${skip_db_import}" -eq 0 ] && [ "${auto}" -eq 1 ]; then
        local latest
        latest="$(find "${DOCKER_DIR}/backups" -name "doclib" -prune -o -type f \( -name "*.gz" -o -name "*.sql" -o -name "*.dump" \) -print0 2>/dev/null \
            | xargs -0 ls -t 2>/dev/null | head -n1 || true)"
        if [ -n "${latest}" ]; then
            bash "${LCP_OPS_SH}" db-import --file "${latest}"
        else
            echo "[WARN] No se encontró backup .gz en docker/backups; se omite db-import automático."
        fi
    fi
    # Crear directorios de datos con permisos correctos para evitar que Docker los cree como root en Linux.
    # En Linux, Docker aplica los permisos reales del host en bind mounts.
    local data_root
    data_root="$(resolve_env_data_root_for_docker_dir "${DOCKER_DIR}")"
    ensure_host_dir_owned_by_current_user "${data_root}"

    # Directorios del host user (Liferay y logs)
    for subdir in liferay-data liferay-osgi-state liferay-deploy-cache patching dumps; do
        ensure_host_dir_owned_by_current_user "${data_root}/${subdir}"
    done

    # Directorio de PostgreSQL (UID 70)
    local pg_data_dir="${data_root}/postgres-data"
    mkdir -p "${pg_data_dir}"
    docker run --rm -v "${pg_data_dir}:/target" alpine \
        sh -c 'chown -R 70:70 /target && chmod 700 /target' >/dev/null 2>&1 || \
        chmod -R 0700 "${pg_data_dir}" 2>/dev/null || true

    # Directorio de Elasticsearch (UID 1000)
    local es_data_dir="${data_root}/elasticsearch-data"
    mkdir -p "${es_data_dir}"
    docker run --rm -v "${es_data_dir}:/target" alpine \
        sh -c 'chown -R 1000:1000 /target' >/dev/null 2>&1 || \
        chmod -R 0777 "${es_data_dir}" 2>/dev/null || true

    echo "Setup OK"
}

main() {
    local cmd="${1:-}"
    shift || true
    case "${cmd}" in
    env-init) cmd_env_init "$@" ;;
    env-start) cmd_env_start "$@" ;;
    setup) cmd_setup "$@" ;;
    post-start) cmd_post_start "$@" ;;
    worktree-setup) cmd_worktree_setup "$@" ;;
    worktree-start) cmd_worktree_start "$@" ;;
    worktree-env) cmd_worktree_env "$@" ;;
    worktree-restore) cmd_worktree_restore "$@" ;;
    worktree-deploy-cache-update) cmd_worktree_deploy_cache_update "$@" ;;
    btrfs-setup) cmd_btrfs_setup "$@" ;;
    worktree-clean) cmd_worktree_clean "$@" ;;
    worktree-gc) cmd_worktree_gc "$@" ;;
    help|-h|--help|"") usage ;;
    *)
        echo "[ERROR] Comando no soportado: ${cmd}" >&2
        usage
        exit 1
        ;;
    esac
}

main "$@"
