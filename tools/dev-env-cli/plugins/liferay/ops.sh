#!/usr/bin/env bash
# ops.sh — operaciones de runtime de Liferay: gogo, bundle, logs, diagnóstico
# Variables requeridas: DOCKER_DIR, REPO_ROOT

_SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${_SELF_DIR}/../lib/common.sh"

json_string_or_null() {
    local value="${1:-}"
    if [ -z "${value}" ]; then
        printf 'null'
        return 0
    fi
    value="${value//\\/\\\\}"
    value="${value//\"/\\\"}"
    printf '"%s"' "${value}"
}

run_ops() {
    local subcommand="${1:-}"
    shift || true

    case "${subcommand}" in
    -h|--help|"")
        echo "Uso: task <info|status|wait|is-healthy|logs|shell|gogo|bundle-status|ops-bundle-diag|ops-thread-dump|ops-heap-dump>"
        return 0
        ;;
    status)
        local url bind_ip http_port container_id lifecycle state_status health_status portal_healthy=false
        if [ -n "${LIFERAY_CLI_URL:-}" ]; then
            url="${LIFERAY_CLI_URL}"
        elif [ -f "${DOCKER_DIR}/.env" ]; then
            bind_ip="$(read_env_value BIND_IP "${DOCKER_DIR}/.env")"; bind_ip="${bind_ip:-localhost}"
            http_port="$(read_env_value LIFERAY_HTTP_PORT "${DOCKER_DIR}/.env")"; http_port="${http_port:-8080}"
            url="http://${bind_ip}:${http_port}"
        else
            url="http://localhost:8080"
        fi

        if curl -sf --max-time 5 "${url}/c/portal/layout" -o /dev/null 2>/dev/null; then
            portal_healthy=true
        fi

        container_id="$(run_compose ps -q liferay 2>/dev/null | head -n1 || true)"
        if [ -n "${container_id}" ]; then
            lifecycle="$(run_timeout 6 docker exec "${container_id}" sh -lc 'cat /opt/liferay/container_status' 2>/dev/null || true)"
            state_status="$(docker inspect -f '{{.State.Status}}' "${container_id}" 2>/dev/null || true)"
            health_status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${container_id}" 2>/dev/null || true)"
            if [ -z "${lifecycle:-}" ]; then
                lifecycle="state=${state_status:-unknown}${health_status:+ health=${health_status}}"
            fi
        fi

        cat <<EOF
{
  "ok": true,
  "url": "${url}",
  "portalHealthy": ${portal_healthy},
  "containerId": $(json_string_or_null "${container_id}"),
  "stateStatus": $(json_string_or_null "${state_status}"),
  "healthStatus": $(json_string_or_null "${health_status}"),
  "lifecycle": $(json_string_or_null "${lifecycle}")
}
EOF
        ;;
    logs)
        local since="${1:-}"
        if [ -n "${since}" ]; then
            run_compose logs -f --tail=200 --since "${since}" liferay
        else
            run_compose logs -f --tail=200 liferay
        fi
        ;;
    info)
        local status_json url portal_healthy lifecycle
        status_json="$(run_ops status)"
        url="$(printf '%s\n' "${status_json}" | awk -F'"' '/"url"/{print $4; exit}')"
        portal_healthy="$(printf '%s\n' "${status_json}" | awk '/"portalHealthy"/{gsub(/[ ,]/,"",$2); print $2; exit}')"
        lifecycle="$(printf '%s\n' "${status_json}" | awk -F'"' '/"lifecycle"/{print $4; exit}')"

        echo "Docker services:"
        run_compose ps
        echo ""
        echo "Portal URL: ${url}"

        if [ "${portal_healthy}" = "true" ]; then
            echo "Portal: HEALTHY"
        else
            echo "Portal: UNREACHABLE"
        fi
        if [ -n "${lifecycle}" ]; then
            echo "Lifecycle: ${lifecycle}"
        fi
        return 0
        ;;
    is-healthy)
        if run_ops status | grep -q '"portalHealthy": true'; then
            return 0
        fi
        return 1
        ;;
    wait)
        local timeout_secs=600
        local poll_secs=10
        while [ "$#" -gt 0 ]; do
            case "$1" in
            --timeout) shift; timeout_secs="${1:-600}" ;;
            --poll) shift; poll_secs="${1:-10}" ;;
            *)
                echo "[ERROR] Opción no soportada para wait: $1" >&2
                exit 1
                ;;
            esac
            shift || true
        done
        local deadline=$((SECONDS + timeout_secs))
        while [ "${SECONDS}" -lt "${deadline}" ]; do
            if run_ops is-healthy; then
                run_ops status
                return 0
            fi
            sleep "${poll_secs}"
        done
        run_ops status
        return 1
        ;;
    shell)
        run_compose exec liferay bash
        ;;
    gogo)
        echo "Connecting to Gogo shell (Ctrl+C to exit)..."
        run_compose exec liferay telnet localhost 11311
        ;;
    gogo-cmd)
        local cmd_arg="${1:-}"
        if [ -z "${cmd_arg}" ]; then
            echo 'Uso: task ops-gogo (interactivo) o bash tools/dev-env-cli/plugins/liferay/ops.sh gogo-cmd "lb | grep ub-config"' >&2
            exit 1
        fi
        (printf '%s\n' "${cmd_arg}"; sleep 1; printf 'disconnect\n') | run_compose exec -T liferay sh -c 'telnet localhost 11311 2>/dev/null'
        ;;
    bundle-status)
        local bundle="${1:-}"
        if [ -z "${bundle}" ]; then
            echo "Uso: task bundle-status BUNDLE=<symbolic-name>" >&2
            exit 1
        fi
        run_ops gogo-cmd "lb | grep ${bundle}"
        ;;
    bundle-diag)
        local bundle="${1:-}"
        if [ -z "${bundle}" ]; then
            echo "Uso: task ops-bundle-diag BUNDLE=<symbolic-name>" >&2
            exit 1
        fi
        local status_output bundle_id
        status_output="$(run_ops bundle-status "${bundle}" || true)"
        bundle_id="$(printf '%s\n' "${status_output}" | grep -E '^[[:space:]]*[0-9]+\|' | awk -F'|' '{gsub(/[[:space:]]/, "", $1); print $1; exit}')"
        if [ -z "${bundle_id}" ]; then
            echo "Bundle no encontrado: ${bundle}" >&2
            exit 1
        fi
        run_ops gogo-cmd "diag ${bundle_id}"
        ;;
    thread-dump)
        local count="${1:-6}"
        local interval="${2:-3}"
        run_compose exec liferay generate_thread_dump.sh -d /opt/liferay/dumps -n "${count}" -s "${interval}"
        echo "Thread dumps saved to ./dumps/"
        ;;
    heap-dump)
        run_compose exec liferay generate_heap_dump.sh -d /opt/liferay/dumps
        echo "Heap dump saved to ./dumps/"
        ;;
    liferaycli-creds)
        local row
        local oauth2_erc
        oauth2_erc="$(resolve_liferaycli_oauth2_external_reference_code)"
        row="$(fetch_liferaycli_creds "${DOCKER_DIR}")"
        if [ -z "${row}" ]; then
            echo "[ERROR] App OAuth2 '${oauth2_erc}' no encontrada. ¿Ha arrancado Liferay y ejecutado el bootstrap?" >&2
            exit 1
        fi
        printf 'LIFERAY_CLI_OAUTH2_CLIENT_ID=%s\nLIFERAY_CLI_OAUTH2_CLIENT_SECRET=%s\n' "${row%%|*}" "${row#*|}"
        echo ""
        echo "Añade estas variables a tu shell o a docker/.env para usar liferay-cli."

        local row_ro
        row_ro="$(fetch_liferaycli_readonly_creds "${DOCKER_DIR}")"
        if [ -n "${row_ro}" ]; then
            echo ""
            echo "--- App read-only (solo lectura) ---"
            printf 'LIFERAY_CLI_OAUTH2_CLIENT_ID=%s\nLIFERAY_CLI_OAUTH2_CLIENT_SECRET=%s\n' "${row_ro%%|*}" "${row_ro#*|}"
            echo ""
            echo "Usa estas variables cuando solo necesites acceso de consulta (sin escritura)."
        fi
        ;;
    *)
        echo "[ERROR] Subcomando ops no soportado: ${subcommand}" >&2
        exit 1
        ;;
    esac
}

# Dispatcher standalone (solo cuando se ejecuta directamente, no cuando se hace source)
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    # Dependencias: necesita DOCKER_DIR y run_compose definidos por el entorno padre,
    # o bien local-ops.sh como coordinador
    REPO_ROOT="${REPO_ROOT:-${_PLUGIN_REPO_ROOT}}"
    DOCKER_DIR="${DOCKER_DIR:-${REPO_ROOT}/docker}"
    run_compose() { (cd "${DOCKER_DIR}" && docker compose "$@"); }
    cmd="${1:-}"; shift || true
    if [ -z "${cmd}" ]; then
        echo "Uso: ops.sh <subcomando> [args...]"
        echo "Subcomandos: info, status, wait, is-healthy, logs, shell, gogo, gogo-cmd, bundle-status, bundle-diag, thread-dump, heap-dump"
        exit 0
    fi
    run_ops "${cmd}" "$@"
fi
