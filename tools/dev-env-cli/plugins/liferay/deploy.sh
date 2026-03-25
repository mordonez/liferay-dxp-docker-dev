#!/usr/bin/env bash
# deploy.sh — compilación y despliegue de módulos y tema
# Variables requeridas: REPO_ROOT

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="${SELF_DIR}/../lib"

# shellcheck source=../lib/common.sh
source "${LIB_DIR}/common.sh"

read_env_value_from_file() {
    local key="$1"
    local file="$2"

    [ -f "${file}" ] || return 1
    sed -n "s/^${key}=//p" "${file}" | tail -n 1
}

current_artifact_commit() {
    local marker_file="${REPO_ROOT}/liferay/build/docker/.prepare-commit"

    if [ -f "${marker_file}" ]; then
        tr -d '\r\n' < "${marker_file}"
        return 0
    fi

    git -C "${REPO_ROOT}" rev-parse HEAD
}

resolve_deploy_cache_dir() {
    local env_file="${REPO_ROOT}/docker/.env"
    local env_data_root=""

    env_data_root="$(read_env_value_from_file ENV_DATA_ROOT "${env_file}" 2>/dev/null || true)"
    [ -n "${env_data_root}" ] || return 1

    if [[ "${env_data_root}" != /* ]]; then
        env_data_root="${REPO_ROOT}/docker/${env_data_root}"
    fi

    printf '%s\n' "${env_data_root}/liferay-deploy-cache"
}

run_prepare_build() {
    guardrail_primary_checkout_feature_branch "preparar artefactos de despliegue" || return 1
    local build_dir="${REPO_ROOT}/liferay/build/docker"
    local cfg_dir="${build_dir}/configs/dockerenv"
    local source_cfg_dir="${REPO_ROOT}/liferay/configs/dockerenv"

    if [ -n "${FORCE_PREPARE:-}" ]; then
        echo "FORCE_PREPARE=1 -> ejecutando prepare completo (buildService + dockerDeploy)"
        (cd "${REPO_ROOT}/liferay" && ./gradlew --console=plain buildService -q) || { echo "[ERROR] buildService falló (FORCE_PREPARE)" >&2; return 1; }
        (cd "${REPO_ROOT}" && git ls-files liferay/modules | grep 'service\.properties$' | xargs git checkout -- 2>/dev/null || true)
    elif ! find "${REPO_ROOT}/liferay/modules" -maxdepth 5 -path "*/build/libs/*.jar" | grep -q .; then
        # Clone fresco: no hay JARs compilados en modules/*/build/libs/ → buildService necesario
        echo "Clone fresco detectado -> generando Service Builder y compilando"
        (cd "${REPO_ROOT}/liferay" && ./gradlew --console=plain buildService -q) || { echo "[ERROR] buildService falló" >&2; return 1; }
        # buildService regenera service.properties con build.number/build.date nuevo — restaurar para no generar ruido en git
        (cd "${REPO_ROOT}" && git ls-files liferay/modules | grep 'service\.properties$' | xargs git checkout -- 2>/dev/null || true)
    fi
    # dockerDeploy es incremental: solo re-copia JARs ausentes en build/docker/deploy/
    # (Liferay los consume al desplegar, por lo que siempre hay que repoblar el directorio)
    (cd "${REPO_ROOT}/liferay" && ./gradlew --console=plain dockerDeploy -Pliferay.workspace.environment=dockerenv -q) || { echo "[ERROR] dockerDeploy falló — ejecuta 'task prepare' manualmente para ver el error completo" >&2; return 1; }

    if [ -d "${source_cfg_dir}" ]; then
        mkdir -p "${cfg_dir}"
        cp -a "${source_cfg_dir}/." "${cfg_dir}/"
    fi
}

run_deploy() {
    guardrail_primary_checkout_feature_branch "desplegar artefactos" || return 1
    (cd "${REPO_ROOT}/liferay" && ./gradlew --console=plain dockerDeploy -Pliferay.workspace.environment=dockerenv)
}

copy_artifacts_to_build_deploy() {
    local build_deploy_dir="${REPO_ROOT}/liferay/build/docker/deploy"

    mkdir -p "${build_deploy_dir}"

    local copied=0 artifact
    for artifact in "$@"; do
        [ -f "${artifact}" ] || continue
        cp -f "${artifact}" "${build_deploy_dir}/$(basename "${artifact}")"
        copied=$((copied + 1))
    done

    [ "${copied}" -gt 0 ]
}

copy_artifacts_to_deploy_cache() {
    local cache_dir=""
    local artifact_commit=""

    cache_dir="$(resolve_deploy_cache_dir 2>/dev/null || true)"
    [ -n "${cache_dir}" ] || return 1

    mkdir -p "${cache_dir}"

    local copied=0 artifact
    for artifact in "$@"; do
        [ -f "${artifact}" ] || continue
        cp -f "${artifact}" "${cache_dir}/$(basename "${artifact}")"
        copied=$((copied + 1))
    done

    [ "${copied}" -gt 0 ] || return 1

    artifact_commit="$(current_artifact_commit)"
    printf '%s\n' "${artifact_commit}" > "${cache_dir}/.prepare-commit"
}

collect_module_artifacts() {
    local module="$1"

    if [ -d "${REPO_ROOT}/liferay/themes/${module}/dist" ]; then
        find "${REPO_ROOT}/liferay/themes/${module}/dist" -maxdepth 1 -type f -name '*.war'
        return 0
    fi

    if [ -d "${REPO_ROOT}/liferay/modules/${module}/${module}-api/build/libs" ]; then
        find "${REPO_ROOT}/liferay/modules/${module}/${module}-api/build/libs" -maxdepth 1 -type f -name '*.jar'
    fi

    if [ -d "${REPO_ROOT}/liferay/modules/${module}/${module}-service/build/libs" ]; then
        find "${REPO_ROOT}/liferay/modules/${module}/${module}-service/build/libs" -maxdepth 1 -type f -name '*.jar'
    fi

    if [ -d "${REPO_ROOT}/liferay/modules/${module}/build/libs" ]; then
        find "${REPO_ROOT}/liferay/modules/${module}/build/libs" -maxdepth 1 -type f -name '*.jar'
    fi
}

run_deploy_module() {
    local module="${1:-}"
    local -a artifacts=()
    local artifact
    guardrail_primary_checkout_feature_branch "desplegar un módulo" || return 1
    require_arg "${module}" "MODULE (ej: ub-config)"
    if [ -d "${REPO_ROOT}/liferay/themes/${module}" ]; then
        (cd "${REPO_ROOT}/liferay" && ./gradlew --console=plain ":themes:${module}:dockerDeploy" -q)
    elif [ -d "${REPO_ROOT}/liferay/modules/${module}/${module}-api" ] && [ -d "${REPO_ROOT}/liferay/modules/${module}/${module}-service" ]; then
        (cd "${REPO_ROOT}/liferay" && ./gradlew --console=plain ":modules:${module}:${module}-api:dockerDeploy" -Pliferay.workspace.environment=dockerenv)
        (cd "${REPO_ROOT}/liferay" && ./gradlew --console=plain ":modules:${module}:${module}-service:dockerDeploy" -Pliferay.workspace.environment=dockerenv)
    else
        (cd "${REPO_ROOT}/liferay" && ./gradlew --console=plain ":modules:${module}:dockerDeploy" -Pliferay.workspace.environment=dockerenv)
    fi

    while IFS= read -r artifact; do
        [ -n "${artifact}" ] || continue
        artifacts+=("${artifact}")
    done < <(collect_module_artifacts "${module}")

    copy_artifacts_to_build_deploy "${artifacts[@]}" || {
        echo "[ERROR] No se pudo sincronizar el artefacto de ${module} en liferay/build/docker/deploy" >&2
        return 1
    }
    copy_artifacts_to_deploy_cache "${artifacts[@]}" || {
        echo "[ERROR] No se pudo sincronizar el artefacto de ${module} en ENV_DATA_ROOT/liferay-deploy-cache" >&2
        return 1
    }
}

sync_theme_artifact_to_build_deploy() {
    local theme_war="${REPO_ROOT}/liferay/themes/ub-theme/dist/ub-theme.war"

    copy_artifacts_to_build_deploy "${theme_war}"
}

run_deploy_theme() {
    guardrail_primary_checkout_feature_branch "desplegar el tema" || return 1
    (cd "${REPO_ROOT}/liferay" && ./gradlew --console=plain :themes:ub-theme:dockerDeploy -q)
    sync_theme_artifact_to_build_deploy || {
        echo "[ERROR] No se pudo sincronizar ub-theme.war en liferay/build/docker/deploy" >&2
        return 1
    }
    copy_artifacts_to_deploy_cache "${REPO_ROOT}/liferay/themes/ub-theme/dist/ub-theme.war" || {
        echo "[ERROR] No se pudo sincronizar ub-theme.war en ENV_DATA_ROOT/liferay-deploy-cache" >&2
        return 1
    }
}

# Dispatcher standalone (solo cuando se ejecuta directamente, no cuando se hace source)
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    # Dependencias: REPO_ROOT (se resuelve desde ubicación del script)
    REPO_ROOT="${REPO_ROOT:-${_PLUGIN_REPO_ROOT}}"
    require_arg() { [ -n "${1:-}" ] || { echo "[ERROR] Falta ${2:-arg}" >&2; exit 1; }; }
    cmd="${1:-}"; shift || true
    case "${cmd}" in
    ""|-h|--help|help)
        echo "Uso: deploy.sh <funcion> [args...]"
        echo "Funciones:"
        echo "  run_prepare_build          — buildService (si clone fresco) + dockerDeploy + cache"
        echo "  run_deploy                 — dockerDeploy completo"
        echo "  run_deploy_module MODULE   — desplegar un módulo OSGi"
        echo "  run_deploy_theme           — desplegar el tema ub-theme"
        exit 0 ;;
    *)
        "${cmd}" "$@" ;;
    esac
fi
