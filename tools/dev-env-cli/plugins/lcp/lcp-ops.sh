#!/usr/bin/env bash
# lcp/lcp-ops.sh — Operaciones de Liferay Cloud Platform: backups de BD y Document Library.
# Requiere: lcp CLI autenticado, acceso al proyecto LCP.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="${SELF_DIR}/../lib"

# shellcheck source=../lib/common.sh
source "${LIB_DIR}/common.sh"

REPO_ROOT="${REPO_ROOT:-${_PLUGIN_REPO_ROOT}}"
DOCKER_DIR="${LOCAL_OPS_DOCKER_DIR:-${REPO_ROOT}/docker}"
DOCKER_DIR="$(cd "${DOCKER_DIR}" && pwd)"
DEFAULT_LCP_PROJECT="my-lcp-project"
DEFAULT_LCP_ENVIRONMENT="prd"

usage() {
    cat <<'USAGE'
lcp-ops.sh — operaciones de Liferay Cloud Platform (LCP)

Uso:
  lcp-ops.sh db-import --file FILE [--skip-adapt]
  lcp-ops.sh db-download [--environment ENV] [--backup-id ID] [--download-doclib] [--doclib-only] [--doclib-dest DIR]
  lcp-ops.sh doclib-mount [--path DIR]
  lcp-ops.sh doclib-detect [--base-dir DIR] [--timeout SEC]
USAGE
}

cmd_db_import() {
    guardrail_primary_checkout_feature_branch "importar backups en local" || exit 1
    local file=""
    local skip_adapt=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --file) shift; require_arg "${1:-}" "valor para --file"; file="$1" ;;
        --skip-adapt) skip_adapt=1 ;;
        --environment) shift ;; # compatibilidad, sin uso en bash-native
        *) echo "[ERROR] Opción no soportada para db-import: $1" >&2; exit 1 ;;
        esac
        shift || true
    done
    if [ -z "${file}" ]; then
        file="$(find "${DOCKER_DIR}/backups" -name "doclib" -prune -o -type f \( -name "*.gz" -o -name "*.sql" -o -name "*.dump" \) -print0 2>/dev/null \
            | xargs -0 ls -t 2>/dev/null | head -n1 || true)"
        if [ -z "${file}" ]; then
            echo "[ERROR] No se encontró ningún backup en docker/backups/. Usa FILE=ruta/al/fichero.gz" >&2
            exit 1
        fi
        echo "[INFO] Autodetectado backup: ${file}"
    fi

    local env_file="${DOCKER_DIR}/.env"
    local db_user db_name pg_data_dir
    db_user="$(read_env_value POSTGRES_USER "${env_file}")"; db_user="${db_user:-ub}"
    db_name="$(read_env_value POSTGRES_DB "${env_file}")"; db_name="${db_name:-ub}"
    pg_data_dir="$(resolve_env_data_root_for_docker_dir "${DOCKER_DIR}")/postgres-data"

    if [ -d "${pg_data_dir}" ] && [ -n "$(ls -A "${pg_data_dir}" 2>/dev/null)" ]; then
        echo "[ERROR] El directorio de datos de PostgreSQL ya tiene datos: ${pg_data_dir}" >&2
        echo "[ERROR] Ejecuta 'task env:clean' antes de importar para partir de cero." >&2
        exit 1
    fi

    docker_compose_in "${DOCKER_DIR}" up -d postgres >/dev/null
    echo "[INFO] Esperando a que PostgreSQL esté listo..."
    local retry_count=0
    while [ "${retry_count}" -lt 30 ]; do
        if docker_compose_in "${DOCKER_DIR}" exec -T postgres psql -U "${db_user}" -d "${db_name}" -c "SELECT 1" >/dev/null 2>&1; then
            break
        fi
        sleep 1
        retry_count=$((retry_count + 1))
    done
    if [ "${retry_count}" -ge 30 ]; then
        echo "[ERROR] PostgreSQL no respondió en tiempo límite" >&2
        exit 1
    fi

    local file_size t_start
    file_size=$(du -sh "${file}" 2>/dev/null | cut -f1 || echo "?")
    t_start=$(date +%s)
    echo "[INFO] Importando BD (${file_size}) — esto puede tardar varios minutos..."

    local import_pid
    if [[ "${file}" == *.gz ]]; then
        gunzip -c "${file}" | docker_compose_in "${DOCKER_DIR}" exec -T postgres psql -U "${db_user}" -d "${db_name}" >/dev/null &
    else
        cat "${file}" | docker_compose_in "${DOCKER_DIR}" exec -T postgres psql -U "${db_user}" -d "${db_name}" >/dev/null &
    fi
    import_pid=$!
    while kill -0 "${import_pid}" 2>/dev/null; do
        printf '.'
        sleep 5
    done
    wait "${import_pid}"
    printf '\n'
    echo "[INFO] Import completado en $(( $(date +%s) - t_start ))s"

    if [ "${skip_adapt}" -eq 0 ] && [ -f "${DOCKER_DIR}/sql/adapt-local-db.sql" ]; then
        echo "[INFO] Aplicando adapt-local-db.sql..."
        docker_compose_in "${DOCKER_DIR}" exec -T postgres psql -U "${db_user}" -d "${db_name}" < "${DOCKER_DIR}/sql/adapt-local-db.sql"
    fi
    echo "DB import OK: ${file}"
}

cmd_db_download() {
    guardrail_primary_checkout_feature_branch "descargar datos de LCP" || exit 1
    local environment=""
    local backup_id=""
    local download_doclib=0
    local doclib_only=0
    local doclib_dest=""
    local background=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --environment) shift; require_arg "${1:-}" "valor para --environment"; environment="$1" ;;
        --backup-id) shift; require_arg "${1:-}" "valor para --backup-id"; backup_id="$1" ;;
        --download-doclib) download_doclib=1 ;;
        --doclib-only) doclib_only=1 ;;
        --doclib-dest) shift; require_arg "${1:-}" "valor para --doclib-dest"; doclib_dest="$1" ;;
        --background) background=1 ;;
        *) echo "[ERROR] Opción no soportada para db-download: $1" >&2; exit 1 ;;
        esac
        shift || true
    done
    if [ "${doclib_only}" -eq 1 ] && [ "${download_doclib}" -eq 0 ]; then
        echo "[ERROR] --doclib-only requiere --download-doclib." >&2
        exit 1
    fi
    if ! command -v lcp >/dev/null 2>&1; then
        echo "[ERROR] lcp no disponible. Instala LCP CLI o usa un backup local con db-import." >&2
        exit 1
    fi
    if ! lcp version >/dev/null 2>&1; then
        echo "[ERROR] lcp no está autenticado o no responde. Ejecuta login en LCP CLI." >&2
        exit 1
    fi

    local project resolved_environment
    project="$(read_config_value LCP_PROJECT "${DEFAULT_LCP_PROJECT}")"
    resolved_environment="${environment:-$(read_config_value LCP_ENVIRONMENT "${DEFAULT_LCP_ENVIRONMENT}")}"

    if [ -z "${backup_id}" ]; then
        local list_output
        if ! list_output="$(lcp backup list --project "${project}" --environment "${resolved_environment}" --statuses success --max-items 1 2>&1)"; then
            echo "[ERROR] No se pudieron listar backups de ${project}/${resolved_environment}: ${list_output}" >&2
            exit 1
        fi
        backup_id="$(
            printf '%s\n' "${list_output}" |
                awk 'NF && $1 !~ /^(Backup|ID|---)$/ && $1 !~ /^#/ {print $1; exit}'
        )"
        if [ -z "${backup_id}" ]; then
            echo "[ERROR] No se encontró backup exitoso en ${project}/${resolved_environment}" >&2
            exit 1
        fi
    fi

    local backup_dir
    backup_dir="${DOCKER_DIR}/backups"
    mkdir -p "${backup_dir}"

    if [ "${doclib_only}" -eq 0 ]; then
        local existing_db_backup=""
        existing_db_backup="$(find "${backup_dir}" -maxdepth 3 -type f \( -name "*.gz" -o -name "*.sql" -o -name "*.dump" \) | grep -F "${backup_id}" | head -n1 || true)"
        if [ -n "${existing_db_backup}" ]; then
            echo "[INFO] Backup de BD ya presente en local: ${existing_db_backup}"
        else
            lcp backup download --project "${project}" --environment "${resolved_environment}" --backupId "${backup_id}" --database --dest "${backup_dir}"
            echo "[INFO] Backup de BD descargado en ${backup_dir}"
        fi
    fi

    if [ "${download_doclib}" -eq 1 ]; then
        local target_doclib_dir actual_doclib
        target_doclib_dir="${doclib_dest:-${backup_dir}/doclib}"
        mkdir -p "${target_doclib_dir}"

        if [ "${background}" -eq 1 ]; then
            local log_file="${DOCKER_DIR}/backups/doclib-download.log"
            local pid_file="${DOCKER_DIR}/backups/doclib-download.pid"
            mkdir -p "${DOCKER_DIR}/backups"

            # Si hay un proceso previo corriendo, informar y salir
            if [ -f "${pid_file}" ]; then
                local existing_pid
                existing_pid="$(cat "${pid_file}")"
                if kill -0 "${existing_pid}" 2>/dev/null; then
                    echo "[INFO] Ya hay una descarga en curso (PID: ${existing_pid})"
                    echo "[INFO] Log: ${log_file}"
                    echo "[INFO] Para cancelarla: kill ${existing_pid} && rm ${pid_file}"
                    return 0
                else
                    # Proceso ya terminó — limpiar pid file residual
                    rm -f "${pid_file}"
                    echo "[INFO] Descarga anterior ya completada. Iniciando nueva descarga..."
                fi
            fi

            echo "[INFO] Iniciando descarga de doclib en segundo plano..."
            nohup lcp backup download --project "${project}" --environment "${resolved_environment}" --backupId "${backup_id}" --doclib --dest "${target_doclib_dir}" >"${log_file}" 2>&1 &
            local lcp_pid=$!
            disown "${lcp_pid}"
            echo "${lcp_pid}" > "${pid_file}"

            # Esperar a que se cree el directorio dxpcloud-*/doclib (max 120s)
            local wait_count=0
            while [ "${wait_count}" -lt 120 ]; do
                local doclib_dir
                doclib_dir="$(find "${target_doclib_dir}" -maxdepth 3 -type d -name "doclib" 2>/dev/null | head -1 || true)"
                if [ -n "${doclib_dir}" ]; then
                    actual_doclib="$(resolve_doclib_root "${doclib_dir}")"
                    upsert_env_value "DOCLIB_PATH" "${actual_doclib}" "${DOCKER_DIR}/.env"
                    echo "[INFO] ✓ Doclib comenzando a descargarse en: ${actual_doclib}"
                    echo "[INFO] Para ver progreso:  tail -f ${log_file}"
                    echo "[INFO] Para cancelar:      kill ${lcp_pid} && rm ${pid_file}"
                    echo "[INFO] Cuando termine ejecuta: task db:files-mount"
                    return 0
                fi
                # Si el proceso murió antes de crear el directorio, falló
                if ! kill -0 "${lcp_pid}" 2>/dev/null; then
                    rm -f "${pid_file}"
                    echo "[ERROR] La descarga falló al arrancar. Revisa: ${log_file}" >&2
                    exit 1
                fi
                sleep 1
                wait_count=$((wait_count + 1))
            done
            # Timeout esperando el directorio
            kill "${lcp_pid}" 2>/dev/null || true
            rm -f "${pid_file}"
            echo "[ERROR] Descarga de doclib no comenzó en tiempo límite. Revisa: ${log_file}" >&2
            exit 1
        else
            lcp backup download --project "${project}" --environment "${resolved_environment}" --backupId "${backup_id}" --doclib --dest "${target_doclib_dir}"
            # lcp crea un subdirectorio dxpcloud-XXX/doclib dentro del destino; buscar y guardar el path real
            local doclib_dir
            doclib_dir="$(find "${target_doclib_dir}" -maxdepth 3 -type d -name "doclib" | head -1 || true)"
            if [ -n "${doclib_dir}" ]; then
                actual_doclib="$(resolve_doclib_root "${doclib_dir}")"
                upsert_env_value "DOCLIB_PATH" "${actual_doclib}" "${DOCKER_DIR}/.env"
                echo "[INFO] Doclib descargada en ${actual_doclib}"
                echo "[INFO] DOCLIB_PATH guardado en .env — ejecuta 'task doclib-mount' para montar"
            else
                echo "[INFO] Doclib descargada en ${target_doclib_dir}"
            fi
        fi
    fi

    echo "db-download OK: backupId=${backup_id} environment=${resolved_environment} project=${project}"
}

# Dada la carpeta "doclib/" descargada por LCP, resuelve el directorio raíz
# real del document_library de Liferay.
# LCP descarga con estructura: doclib/{uuid}/20098/... — el directorio raíz
# real es el que contiene los IDs de empresa numéricos (e.g. 20098).
resolve_doclib_root() {
    local doclib_dir="$1"
    [ -d "${doclib_dir}" ] || return 1
    # Buscar un subdirectorio que contenga carpetas numéricas (company IDs)
    local subdir
    for subdir in "${doclib_dir}"/*/; do
        [ -d "${subdir}" ] || continue
        if ls "${subdir}" 2>/dev/null | grep -qE '^[0-9]+$'; then
            echo "${subdir%/}"
            return 0
        fi
    done
    # Fallback: usar el propio doclib/ si ya contiene company IDs
    echo "${doclib_dir}"
}

cmd_doclib_detect() {
    local base_dir="${HOME}"
    local timeout="20"
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --base-dir) shift; require_arg "${1:-}" "valor para --base-dir"; base_dir="$1" ;;
        --timeout) shift; require_arg "${1:-}" "valor para --timeout"; timeout="$1" ;;
        *) echo "[ERROR] Opción no soportada para doclib-detect: $1" >&2; exit 1 ;;
        esac
        shift || true
    done
    local detected
    detected="$(run_timeout "${timeout}" find "${base_dir}" -type d -name document_library 2>/dev/null | head -n1 || true)"
    if [ -z "${detected}" ]; then
        echo "[ERROR] No se encontró document_library en ${base_dir}" >&2
        exit 1
    fi
    upsert_env_value DOCLIB_PATH "${detected}" "${DOCKER_DIR}/.env"
    echo "DOCLIB_PATH=${detected}"
}

cmd_doclib_mount() {
    local path=""
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --path) shift; require_arg "${1:-}" "valor para --path"; path="$1" ;;
        *) echo "[ERROR] Opción no soportada para doclib-mount: $1" >&2; exit 1 ;;
        esac
        shift || true
    done

    local volume
    volume="$(ensure_doclib_volume)"
    if [ -z "${path}" ]; then
        path="$(read_env_value DOCLIB_PATH "${DOCKER_DIR}/.env")"
    fi
    local nas_ip nas_share nas_user nas_pass nas_port
    nas_ip="$(read_env_value DOCLIB_NAS_IP "${DOCKER_DIR}/.env")"
    nas_share="$(read_env_value DOCLIB_NAS_SHARE "${DOCKER_DIR}/.env")"
    nas_user="$(read_env_value DOCLIB_NAS_USER "${DOCKER_DIR}/.env")"
    nas_pass="$(read_env_value DOCLIB_NAS_PASS "${DOCKER_DIR}/.env")"
    nas_port="$(read_env_value DOCLIB_NAS_PORT "${DOCKER_DIR}/.env")"
    nas_port="${nas_port:-10445}"

    if [ -n "${path}" ] && [ -d "${path}" ]; then
        recreate_docker_volume "${volume}"
        docker volume create \
            --driver local \
            --opt type=none \
            --opt "device=$(realpath "${path}")" \
            --opt o=bind \
            "${volume}" >/dev/null
        upsert_env_value "DOCLIB_PATH" "${path}" "${DOCKER_DIR}/.env"
        echo "Doclib volume ${volume} montado desde ruta local: ${path}"
        return 0
    fi

    if [ -n "${nas_ip}" ]; then
        if [ -z "${nas_share}" ] || [ -z "${nas_user}" ]; then
            echo "[ERROR] Con DOCLIB_NAS_IP se requieren DOCLIB_NAS_SHARE y DOCLIB_NAS_USER." >&2
            exit 1
        fi
        recreate_docker_volume "${volume}"
        docker volume create \
            --driver local \
            --opt type=cifs \
            --opt "device=//${nas_ip}/${nas_share}" \
            --opt "o=username=${nas_user},password=${nas_pass},uid=1000,gid=1000,vers=3.0,port=${nas_port}" \
            "${volume}" >/dev/null
        echo "Doclib volume ${volume} montado desde NAS: ${nas_ip}/${nas_share}"
        return 0
    fi

    # No path or NAS configured — ensure volume is a plain empty volume (no stale bind mount)
    recreate_docker_volume "${volume}"
    docker volume create "${volume}" >/dev/null
    echo "Doclib volume ${volume} listo (sin import de path externo)"
}

cmd_doclib_download_bg() {
    local doclib_dest=""
    while [ "$#" -gt 0 ]; do
        case "$1" in
        --doclib-dest) shift; require_arg "${1:-}" "valor para --doclib-dest"; doclib_dest="$1" ;;
        *) echo "[ERROR] Opción no soportada para doclib-download-bg: $1" >&2; exit 1 ;;
        esac
        shift || true
    done
    if [ -z "${doclib_dest}" ]; then
        echo "[INFO] DOCLIB_DEST no especificado — se omite descarga de doclib"
        return 0
    fi
    cmd_db_download --download-doclib --doclib-only --doclib-dest "${doclib_dest}" --background
}

main() {
    local cmd="${1:-}"
    shift || true
    case "${cmd}" in
    db-import|db-download|doclib-download-bg|doclib-mount|doclib-detect)
        guardrail_primary_checkout_feature_branch "operar con datos locales/LCP" || exit 1
        ;;
    esac
    case "${cmd}" in
    db-import) cmd_db_import "$@" ;;
    db-download) cmd_db_download "$@" ;;
    doclib-download-bg) cmd_doclib_download_bg "$@" ;;
    doclib-mount) cmd_doclib_mount "$@" ;;
    doclib-detect) cmd_doclib_detect "$@" ;;
    help|-h|--help|"") usage ;;
    *)
        echo "[ERROR] Comando no soportado: ${cmd}" >&2
        usage
        exit 1
        ;;
    esac
}

main "$@"
