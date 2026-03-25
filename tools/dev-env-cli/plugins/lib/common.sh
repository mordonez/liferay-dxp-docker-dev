#!/usr/bin/env bash
# lib/common.sh — Utilidades compartidas entre plugins core y lcp
# Sourcea este fichero al inicio de cada plugin: source "${SELF_DIR}/../lib/common.sh"
# No ejecutar directamente.

# Repo root calculado UNA sola vez desde la ubicación conocida de common.sh.
# Todos los scripts del plugin deben usar _PLUGIN_REPO_ROOT en lugar de
# calcular su propia profundidad (../../..) para evitar regresiones al reorganizar.
_COMMON_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Cuando el vendor está en vendor/liferay-tooling/, los 4 niveles apuntan al vendor,
# no al repo del proyecto. Usar REPO_ROOT (exportado por Taskfile.yml) como fuente
# canónica; el cálculo relativo queda como fallback para ejecución directa en tests.
_PLUGIN_REPO_ROOT="${REPO_ROOT:-$(cd "${_COMMON_SH_DIR}/../../../.." && pwd)}"

# Obtiene mtime en segundos epoch — portable entre GNU stat (-c %Y) y BSD stat (-f %m)
file_mtime() {
    stat -c %Y "$1" 2>/dev/null || stat -f %m "$1"
}

# Wrapper portable para timeout (macOS no tiene GNU timeout; usa gtimeout si está disponible)
run_timeout() {
    local secs="$1"; shift
    if command -v timeout >/dev/null 2>&1; then
        timeout "${secs}" "$@"
    elif command -v gtimeout >/dev/null 2>&1; then
        gtimeout "${secs}" "$@"
    else
        "$@"
    fi
}

require_arg() {
    local value="${1:-}"
    local name="${2:-arg}"
    if [ -z "${value}" ]; then
        echo "[ERROR] Falta ${name}" >&2
        exit 1
    fi
}

read_env_value() {
    local key="$1"
    local env_file="${2:-${DOCKER_DIR}/.env}"
    if [ ! -f "${env_file}" ]; then
        return 0
    fi
    awk -F= -v k="${key}" '$1==k{print substr($0,index($0,$2)); exit}' "${env_file}" | tr -d '\r'
}

read_config_value() {
    local key="$1"
    local default_value="${2:-}"
    local env_file="${3:-${DOCKER_DIR}/.env}"
    local from_process="${!key:-}"
    if [ -n "${from_process}" ]; then
        printf '%s\n' "${from_process}"
        return 0
    fi
    local from_file
    from_file="$(read_env_value "${key}" "${env_file}")"
    if [ -n "${from_file}" ]; then
        printf '%s\n' "${from_file}"
        return 0
    fi
    printf '%s\n' "${default_value}"
}

resolve_path_from_env_file() {
    local path_value="$1"
    local env_file="${2:-${DOCKER_DIR}/.env}"

    [ -n "${path_value}" ] || return 1
    if [[ "${path_value}" == /* ]]; then
        printf '%s\n' "${path_value}"
        return 0
    fi

    local base_dir
    base_dir="$(cd "$(dirname "${env_file}")" && pwd)"
    printf '%s/%s\n' "${base_dir}" "${path_value}"
}

resolve_env_data_root_for_docker_dir() {
    local docker_dir="${1:-${DOCKER_DIR}}"
    local env_file="${docker_dir}/.env"
    local env_data_root

    env_data_root="$(read_env_value ENV_DATA_ROOT "${env_file}")"
    env_data_root="${env_data_root:-./data/default}"
    resolve_path_from_env_file "${env_data_root}" "${env_file}"
}

is_linux_host() {
    [ "$(uname -s)" = "Linux" ]
}

configured_btrfs_root_for_docker_dir() {
    local docker_dir="${1:-${DOCKER_DIR}}"
    local env_file="${docker_dir}/.env"
    local btrfs_root

    btrfs_root="$(read_env_value BTRFS_ROOT "${env_file}")"
    if [ -n "${btrfs_root}" ]; then
        resolve_path_from_env_file "${btrfs_root}" "${env_file}"
        return 0
    fi

    # Auto-detección solo si USE_BTRFS_SNAPSHOTS está explícitamente configurado.
    # Sin configuración explícita no activar Btrfs aunque el directorio exista en el host.
    local use_btrfs
    use_btrfs="$(read_env_value USE_BTRFS_SNAPSHOTS "${env_file}")"
    if [ -n "${use_btrfs}" ] && [ "${use_btrfs}" != "false" ] \
        && [ -d "/mnt/docker-btrfs/base" ] && [ -d "/mnt/docker-btrfs/envs" ]; then
        printf '%s\n' "/mnt/docker-btrfs"
    fi
}

btrfs_layout_ready_for_docker_dir() {
    local docker_dir="${1:-${DOCKER_DIR}}"
    local btrfs_root

    btrfs_root="$(configured_btrfs_root_for_docker_dir "${docker_dir}" 2>/dev/null || true)"
    [ -n "${btrfs_root}" ] || return 1
    [ -d "${btrfs_root}" ] || return 1
    [ -d "${btrfs_root}/base" ] || return 1
    [ -d "${btrfs_root}/envs" ] || return 1
}

main_btrfs_data_root_for_docker_dir() {
    local docker_dir="${1:-${DOCKER_DIR}}"
    local btrfs_root

    btrfs_root="$(configured_btrfs_root_for_docker_dir "${docker_dir}" 2>/dev/null || true)"
    [ -n "${btrfs_root}" ] || return 1
    printf '%s\n' "${btrfs_root}/main"
}

upsert_env_value() {
    local key="$1"
    local value="$2"
    local env_file="${3:-${DOCKER_DIR}/.env}"
    touch "${env_file}"
    # Remove all existing occurrences (handles duplicates) then append — portable macOS/Linux
    local tmp
    tmp=$(mktemp)
    grep -v "^${key}=" "${env_file}" > "${tmp}" || true
    printf '%s=%s\n' "${key}" "${value}" >> "${tmp}"
    mv "${tmp}" "${env_file}"
}

fetch_oauth2_app_creds() {
    local erc="${1}"
    local docker_dir="${2:-${DOCKER_DIR}}"
    local pg_user pg_db
    local escaped_erc
    pg_user="$(read_env_value POSTGRES_USER "${docker_dir}/.env")"; pg_user="${pg_user:-liferay}"
    pg_db="$(read_env_value POSTGRES_DB "${docker_dir}/.env")"; pg_db="${pg_db:-liferay}"
    escaped_erc="$(printf "%s" "${erc}" | sed "s/'/''/g")"
    docker_compose_in "${docker_dir}" exec -T postgres \
        psql -U "${pg_user}" -d "${pg_db}" -t -A -c \
        "SELECT clientid || '|' || clientsecret FROM OAuth2Application WHERE externalreferencecode = '${escaped_erc}' LIMIT 1;" \
        2>/dev/null || true
}

resolve_liferaycli_oauth2_external_reference_code() {
    local config_value="${LIFERAY_CLI_OAUTH2_EXTERNAL_REFERENCE_CODE:-}"
    local config_file

    for config_file in \
        "${_PLUGIN_REPO_ROOT}/liferay/configs/dockerenv/osgi/configs/dev.mordonez.liferay.cli.bootstrap.configuration.LiferayCliOAuth2BootstrapConfiguration.config" \
        "${_PLUGIN_REPO_ROOT}/liferay/configs/local/osgi/configs/dev.mordonez.liferay.cli.bootstrap.configuration.LiferayCliOAuth2BootstrapConfiguration.config" \
        "${_PLUGIN_REPO_ROOT}/liferay/configs/common/osgi/configs/dev.mordonez.liferay.cli.bootstrap.configuration.LiferayCliOAuth2BootstrapConfiguration.config"
    do
        if [ -f "${config_file}" ]; then
            local from_file
            from_file="$(awk -F= '$1=="externalReferenceCode"{print substr($0,index($0,$2)); exit}' "${config_file}" | tr -d '\r"')"
            if [ -n "${from_file}" ]; then
                config_value="${from_file}"
                break
            fi
        fi
    done

    printf '%s\n' "${config_value:-liferay-cli}"
}

fetch_liferaycli_creds() {
    fetch_oauth2_app_creds "$(resolve_liferaycli_oauth2_external_reference_code)" "${1:-${DOCKER_DIR}}"
}

fetch_liferaycli_readonly_creds() {
    local erc
    erc="$(resolve_liferaycli_oauth2_external_reference_code)"
    fetch_oauth2_app_creds "${erc}-readonly" "${1:-${DOCKER_DIR}}"
}

seed_env_file_from_source() {
    local env_file="$1"
    local source_env="$2"
    [ -f "${source_env}" ] || return 1

    if [ ! -f "${env_file}" ]; then
        cp "${source_env}" "${env_file}"
        return 0
    fi

    while IFS= read -r line || [ -n "${line}" ]; do
        case "${line}" in
        ""|\#*) continue ;;
        esac
        local key="${line%%=*}"
        [ -n "${key}" ] || continue
        if ! grep -q "^${key}=" "${env_file}" 2>/dev/null; then
            printf '%s\n' "${line}" >> "${env_file}"
        fi
    done < "${source_env}"
    return 0
}

docker_compose_in() {
    local dir="$1"
    shift
    (cd "${dir}" && docker compose "$@")
}

wait_for_compose_service_health() {
    local dir="$1"
    local service="$2"
    local timeout_secs="${3:-600}"
    local deadline=$((SECONDS + timeout_secs))
    local container_id="" health_status="" state_status=""

    while [ "${SECONDS}" -lt "${deadline}" ]; do
        container_id="$(docker_compose_in "${dir}" ps -q "${service}" | head -n1)"
        if [ -n "${container_id}" ]; then
            health_status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${container_id}" 2>/dev/null || true)"
            state_status="$(docker inspect -f '{{.State.Status}}' "${container_id}" 2>/dev/null || true)"
            if [ -z "${health_status}" ] && [ "${state_status}" = "running" ]; then
                return 0
            fi
            if [ "${health_status}" = "healthy" ]; then
                return 0
            fi
            if [ "${state_status}" = "exited" ] || [ "${state_status}" = "dead" ]; then
                echo "[ERROR] Servicio ${service} no pudo arrancar (state=${state_status}, health=${health_status:-n/a})" >&2
                return 1
            fi
        fi
        sleep 5
    done

    echo "[ERROR] Timeout esperando ${service} healthy/running (state=${state_status:-unknown}, health=${health_status:-n/a})" >&2
    return 1
}

delete_path_with_helper() {
    local path="$1"
    [ -e "${path}" ] || return 0
    docker run --rm -v "${path}:/target" alpine sh -lc 'find /target -mindepth 1 -delete' >/dev/null 2>&1 || true
    rmdir "${path}" >/dev/null 2>&1 || true
}

delete_tree_with_helper() {
    local path="$1"
    [ -e "${path}" ] || return 0
    local parent base
    parent="$(dirname "${path}")"
    base="$(basename "${path}")"
    docker run --rm -v "${parent}:/parent" alpine sh -lc "rm -rf \"/parent/${base}\"" >/dev/null 2>&1 || true
    rm -rf "${path}" >/dev/null 2>&1 || true
    [ ! -e "${path}" ]
}

current_uid() {
    id -u
}

current_gid() {
    id -g
}

ensure_host_dir_owned_by_current_user() {
    local target="$1"
    local uid gid
    uid="$(current_uid)"
    gid="$(current_gid)"

    if [ -d "${target}" ]; then
        if [ -w "${target}" ]; then
            chmod -R u+rwX "${target}" 2>/dev/null || true
            return 0
        fi

        docker run --rm -v "${target}:/target" alpine sh -c \
            "chown -R \"${uid}:${gid}\" /target && chmod -R u+rwX /target" >/dev/null 2>&1 || true

        if [ -w "${target}" ]; then
            return 0
        fi
    else
        if mkdir -p "${target}" 2>/dev/null; then
            chmod -R u+rwX "${target}" 2>/dev/null || true
            return 0
        fi

        local parent
        parent="$(dirname "${target}")"
        docker run --rm -v "${parent}:/parent" alpine sh -c \
            "mkdir -p \"/parent/$(basename "${target}")\" && chown -R \"${uid}:${gid}\" \"/parent/$(basename "${target}")\" && chmod -R u+rwX \"/parent/$(basename "${target}")\"" >/dev/null 2>&1 || true

        if [ -d "${target}" ] && [ -w "${target}" ]; then
            return 0
        fi
    fi

    echo "[ERROR] No se pudo preparar la ruta con permisos de usuario: ${target}" >&2
    return 1
}

normalize_env_data_permissions() {
    local env_root="$1"
    local uid gid
    uid="$(current_uid)"
    gid="$(current_gid)"

    for subdir in liferay-data liferay-osgi-state liferay-deploy-cache patching dumps; do
        local target="${env_root}/${subdir}"
        [ -d "${target}" ] || continue
        if [[ "$(uname -s)" == "Darwin" ]]; then
            # macOS: los archivos ya son del usuario actual; ajuste nativo sin Docker
            chown -R "${uid}:${gid}" "${target}" 2>/dev/null || true
            chmod -R u+rwX "${target}" 2>/dev/null || true
        else
            docker run --rm -v "${target}:/target" alpine sh -c \
                "chown -R \"${uid}:${gid}\" /target && chmod -R u+rwX /target" >/dev/null
        fi
    done

    local postgres_dir="${env_root}/postgres-data"
    if [ -d "${postgres_dir}" ]; then
        # UID 70 (postgres) requiere Docker en ambas plataformas
        docker run --rm -v "${postgres_dir}:/target" alpine sh -c \
            'chown -R 70:70 /target && chmod 700 /target' >/dev/null
    fi

    # Elasticsearch corre como UID 1000 en el contenedor; en Linux los bind mounts aplican
    # permisos reales del host, así que el directorio debe pertenecer a UID 1000
    local es_dir="${env_root}/elasticsearch-data"
    if [ -d "${es_dir}" ]; then
        if [[ "$(uname -s)" == "Darwin" ]]; then
            # En macOS/Docker Desktop VirtioFS el UID del host no importa; basta con 0777
            chmod -R 0777 "${es_dir}" 2>/dev/null || true
        else
            docker run --rm -v "${es_dir}:/target" alpine sh -c \
                'chown -R 1000:1000 /target' >/dev/null
        fi
    fi
}

ensure_doclib_volume() {
    local env_file="${DOCKER_DIR}/.env"
    local volume
    volume="$(read_env_value DOCLIB_VOLUME_NAME "${env_file}")"
    if [ -z "${volume}" ]; then
        local compose_project
        compose_project="$(read_env_value COMPOSE_PROJECT_NAME "${env_file}")"
        volume="${compose_project:-liferay}-doclib"
        upsert_env_value DOCLIB_VOLUME_NAME "${volume}" "${env_file}"
    fi

    # Si ya es CIFS/NAS, no tocar.
    local existing_type=""
    existing_type="$(docker volume inspect "${volume}" --format '{{index .Options "type"}}' 2>/dev/null || true)"
    if [ "${existing_type}" = "cifs" ]; then
        printf '%s\n' "${volume}"
        return 0
    fi

    # Crear (o recrear si era plain) como bind-device apuntando a ENV_DATA_ROOT/liferay-doclib.
    # Así los datos persisten entre reinicios y se clonan con el resto del entorno en worktrees Btrfs.
    local doclib_dir
    doclib_dir="$(resolve_env_data_root_for_docker_dir "${DOCKER_DIR}")/liferay-doclib"
    mkdir -p "${doclib_dir}"
    # Solo recrear si el volumen no apunta ya al directorio correcto
    local existing_device=""
    existing_device="$(docker volume inspect "${volume}" --format '{{index .Options "device"}}' 2>/dev/null || true)"
    if [ "${existing_device}" != "$(realpath "${doclib_dir}")" ]; then
        docker volume rm "${volume}" >/dev/null 2>&1 || true
        docker volume create \
            --driver local \
            --opt type=none \
            --opt "device=$(realpath "${doclib_dir}")" \
            --opt o=bind \
            "${volume}" >/dev/null
    fi
    printf '%s\n' "${volume}"
}

recreate_docker_volume() {
    local volume="$1"
    docker volume rm "${volume}" >/dev/null 2>&1 || true
}

main_repo_root() {
    case "${REPO_ROOT}" in
    */.worktrees/*)
        printf '%s\n' "${REPO_ROOT%%/.worktrees/*}"
        ;;
    *)
        printf '%s\n' "${REPO_ROOT}"
        ;;
    esac
}

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

current_branch_name() {
    git -C "${REPO_ROOT}" branch --show-current 2>/dev/null || true
}

is_primary_checkout_non_main_branch() {
    local branch_name main_root
    branch_name="$(current_branch_name)"
    [ -n "${branch_name}" ] || return 1

    main_root="$(main_repo_root)"
    [ "${REPO_ROOT}" = "${main_root}" ] || return 1
    [ "${branch_name}" != "main" ] || return 1
    [ "${branch_name}" != "master" ] || return 1
}

guardrail_primary_checkout_feature_branch() {
    local action_hint="${1:-continuar}"
    local branch_name suggested_name

    if ! is_primary_checkout_non_main_branch; then
        return 0
    fi

    branch_name="$(current_branch_name)"
    suggested_name="${branch_name##*/}"

    cat >&2 <<EOF
[GUARDRAIL] Operación bloqueada: la raíz principal del repo no debe usarse en ramas de trabajo.
[GUARDRAIL] Checkout actual: ${REPO_ROOT} (${branch_name})
[GUARDRAIL] Antes de ${action_hint}, mueve esta rama a un worktree:
  1. git switch main
  2. task worktree:new -- ${suggested_name}
  3. cd .worktrees/${suggested_name}
  4. task env:start
EOF
    return 1
}
