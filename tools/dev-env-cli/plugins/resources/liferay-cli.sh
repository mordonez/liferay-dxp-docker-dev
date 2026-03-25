#!/usr/bin/env bash
# liferay-cli.sh — wrapper para invocar liferay-cli (fat JAR) con build automático
# Variables requeridas: REPO_ROOT

run_ub() {
    local ub_jar="${REPO_ROOT}/tools/liferay-cli/build/libs/liferay-cli-all.jar"
    local ub_lock="${REPO_ROOT}/.tmp/dev-cli-ub.lock"
    local ub_lock_wait_seconds="${UB_LOCK_WAIT_SECONDS:-5}"
    local skip_build="${UB_SKIP_BUILD:-auto}"
    local http_timeout_seconds="${LIFERAY_CLI_HTTP_TIMEOUT_SECONDS:-${UB_CLI_HTTP_TIMEOUT_SECONDS:-300}}"
    local needs_lock="0"
    local previous_token=""
    local token
    local ub_args=()
    local should_build="0"

    ub_has_help_flag() {
        local arg=""
        for arg in "$@"; do
            if [ "${arg}" = "-h" ] || [ "${arg}" = "--help" ]; then
                return 0
            fi
        done
        return 1
    }

    ub_requires_lock() {
        local first="${1:-}"
        local second="${2:-}"
        if [ "${UB_DISABLE_LOCK:-0}" = "1" ]; then
            return 1
        fi
        if ub_has_help_flag "$@"; then
            return 1
        fi
        case "${first}" in
        ""|-h|--help|-V|--version|help|health|inventory|audit)
            return 1
            ;;
        resource)
            case "${second}" in
            structure-sync|structure-sync-all|structure-migrate-content|structure-migration-run|structure-migration-pipeline|\
            template-sync|template-sync-all|adt-sync|adt-sync-all|fragments-sync|\
            structure-export-all|template-export-all|adt-export-all|fragments-export|\
            export-and-sync)
                return 0
                ;;
            *)
                return 1
                ;;
            esac
            ;;
        *)
            return 1
            ;;
        esac
    }

    if [ "$#" -eq 0 ]; then
        ub_args+=(--help)
    else
        for token in "$@"; do
            if [ "${previous_token}" = "--output" ] && [[ "${token}" != /* ]]; then
                token="${REPO_ROOT}/${token#./}"
            fi
            ub_args+=("${token}")
            previous_token="${token}"
        done
    fi
    if ub_requires_lock "${ub_args[@]}"; then
        needs_lock="1"
    fi

    (
        cd "${REPO_ROOT}/liferay"
        mkdir -p "${REPO_ROOT}/.tmp"
        if [ "${needs_lock}" = "1" ] && command -v flock >/dev/null 2>&1; then
            exec 9>"${ub_lock}"
            if ! flock -w "${ub_lock_wait_seconds}" 9; then
                echo "[ERROR] ub-cli bloqueado: otro proceso mantiene ${ub_lock}." >&2
                echo "        Ajusta espera con UB_LOCK_WAIT_SECONDS=<segundos> o espera a que termine el proceso en curso." >&2
                echo "        Usa UB_DISABLE_LOCK=1 para omitir lock (bajo tu responsabilidad)." >&2
                if command -v lsof >/dev/null 2>&1; then
                    lsof "${ub_lock}" 2>/dev/null | sed 's/^/        /' >&2 || true
                fi
                exit 1
            fi
        fi
        if [ "${skip_build}" = "auto" ]; then
            if [ ! -f "${ub_jar}" ]; then
                should_build="1"
            elif find ../tools/liferay-cli/src ../tools/liferay-cli/build.gradle ../tools/liferay-cli/settings.gradle -type f -newer "${ub_jar}" -print -quit 2>/dev/null | grep -q .; then
                should_build="1"
            fi
        elif [ "${skip_build}" = "0" ]; then
            should_build="1"
        fi
        if [ "${should_build}" = "1" ]; then
            ./gradlew -p ../tools/liferay-cli uberJar --console=plain 1>&2
        elif [ ! -f "${ub_jar}" ]; then
            echo "[ERROR] No se encuentra ${ub_jar}. Ejecuta sin UB_SKIP_BUILD=1 para compilar." >&2
            exit 1
        fi
        LIFERAY_CLI_HTTP_TIMEOUT_SECONDS="${http_timeout_seconds}" java -jar "${ub_jar}" "${ub_args[@]}"
    )
}

# Dispatcher standalone (solo cuando se ejecuta directamente, no cuando se hace source)
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    # Dependencias: REPO_ROOT
    REPO_ROOT="${REPO_ROOT:-${_PLUGIN_REPO_ROOT}}"
    run_ub "$@"
fi
