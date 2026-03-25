#!/usr/bin/env bash
# reindex.sh — control del proceso de reindexado en Elasticsearch
# Variables requeridas: DOCKER_DIR (para resolve_es_url)

_SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${_SELF_DIR}/../lib/common.sh"

resolve_es_url() {
    local env_file="${DOCKER_DIR}/.env"
    local bind_ip es_port
    bind_ip="$(read_env_value BIND_IP "${env_file}")"; bind_ip="${bind_ip:-localhost}"
    es_port="$(read_env_value ES_HTTP_PORT "${env_file}")"; es_port="${es_port:-9200}"
    printf 'http://%s:%s\n' "${bind_ip}" "${es_port}"
}

run_reindex() {
    local subcommand="${1:-progress}"
    shift || true
    local es_url
    es_url="$(resolve_es_url)"

    case "${subcommand}" in
    -h|--help)
        echo "Uso: task <reindex|reindex-watch|reindex-speedup-on|reindex-speedup-off>"
        return 0
        ;;
    progress)
        local rows
        rows="$(curl -sf --max-time 5 "${es_url}/_cat/indices?h=health,status,index,docs.count" | grep -iE "journal|liferay" || true)"
        if [ -n "${rows}" ]; then
            printf '%s\n' "${rows}"
        else
            echo "Sin índices journal/liferay visibles en ${es_url}"
        fi
        ;;
    watch)
        local interval="${1:-5}"
        local iterations="${2:-60}"
        local i=0
        while [ "${i}" -lt "${iterations}" ]; do
            printf '[%s/%s] ' "$((i + 1))" "${iterations}"
            curl -sf --max-time 5 "${es_url}/_cat/indices?h=health,status,index,docs.count" | grep -iE "journal|liferay" || true
            sleep "${interval}"
            i=$((i + 1))
        done
        ;;
    speedup-on)
        curl -sf --max-time 5 -X PUT "${es_url}/_all/_settings" -H "Content-Type: application/json" \
            -d '{"index":{"refresh_interval":"-1"}}' >/dev/null
        echo "Reindex speedup ON (refresh_interval=-1)"
        ;;
    speedup-off)
        curl -sf --max-time 5 -X PUT "${es_url}/_all/_settings" -H "Content-Type: application/json" \
            -d '{"index":{"refresh_interval":"1s"}}' >/dev/null
        curl -sf --max-time 5 -X POST "${es_url}/_refresh" >/dev/null
        echo "Reindex speedup OFF (refresh_interval=1s + refresh)"
        ;;
    tasks)
        # Consulta backgroundtask en BD: muestra tareas de reindex activas/pendientes
        local db_svc pg_user pg_db compose_dir
        db_svc="postgres"
        pg_user="$(read_env_value POSTGRES_USER "${DOCKER_DIR}/.env")"; pg_user="${pg_user:-ub}"
        pg_db="$(read_env_value POSTGRES_DB "${DOCKER_DIR}/.env")"; pg_db="${pg_db:-ub}"
        compose_dir="${DOCKER_DIR}"
        docker compose -f "${compose_dir}/docker-compose.yml" exec -T "${db_svc}" \
            psql -U "${pg_user}" -d "${pg_db}" -x -c "
                SELECT backgroundtaskid,
                       CASE status WHEN 0 THEN 'PENDING' WHEN 1 THEN 'RUNNING' WHEN 2 THEN 'SUCCESSFUL' WHEN 3 THEN 'FAILED' ELSE status::text END AS status,
                       createdate,
                       completiondate,
                       taskexecutorclassname
                FROM backgroundtask
                WHERE taskexecutorclassname LIKE '%Reindex%'
                  AND status IN (0, 1)
                ORDER BY createdate DESC
                LIMIT 20;"
        ;;
    *)
        echo "[ERROR] Subcomando reindex no soportado: ${subcommand}" >&2
        echo "Usa: reindex progress|watch [interval iterations]|speedup-on|speedup-off|tasks" >&2
        exit 1
        ;;
    esac
}

# Dispatcher standalone (solo cuando se ejecuta directamente, no cuando se hace source)
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    # Dependencias: DOCKER_DIR (para resolve_es_url)
    DOCKER_DIR="${DOCKER_DIR:-${_PLUGIN_REPO_ROOT}/docker}"
    cmd="${1:-progress}"; shift || true
    run_reindex "${cmd}" "$@"
fi
