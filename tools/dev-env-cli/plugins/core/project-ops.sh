#!/usr/bin/env bash
# project-ops.sh — Inicializar o integrar tooling en un proyecto Liferay
# Uso: project-ops.sh <init|add|add-community> [--name NAME] [--dir DIR] [--target TARGET]
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENDOR_ROOT="$(cd "${SELF_DIR}/../../../.." && pwd)"   # raíz de liferay-dxp-docker-dev
SCAFFOLD_DIR="${VENDOR_ROOT}/scaffold"

# URL remota de este repo (para añadirla como remote en el proyecto destino)
TOOLING_REMOTE="$(git -C "${VENDOR_ROOT}" remote get-url origin 2>/dev/null || echo "")"

usage() {
    cat <<'USAGE'
Uso:
  project-ops.sh init --name NOMBRE --dir DIRECTORIO
      Crea un nuevo proyecto Liferay con tooling desde cero.

  project-ops.sh add --target DIRECTORIO
      Añade el tooling a un proyecto existente que ya tiene Docker y repo git.

  project-ops.sh add-community --target DIRECTORIO
      Añade el tooling + docker-compose a un proyecto con Liferay Community Edition.
USAGE
}

# --- helpers -----------------------------------------------------------------

add_vendor_subtree() {
    local target_dir="$1"
    if [ -z "${TOOLING_REMOTE}" ]; then
        echo "[ERROR] No se puede determinar la URL remota de liferay-dxp-docker-dev." >&2
        echo "[ERROR] Asegúrate de que este repo tiene un remote 'origin' configurado." >&2
        exit 1
    fi

    cd "${target_dir}"

    if git remote get-url liferay-tooling &>/dev/null; then
        echo "[INFO] Remote 'liferay-tooling' ya existe, omitiendo."
    else
        git remote add liferay-tooling "${TOOLING_REMOTE}"
        echo "[INFO] Remote 'liferay-tooling' añadido: ${TOOLING_REMOTE}"
    fi

    if [ -d "vendor/liferay-tooling" ]; then
        echo "[INFO] vendor/liferay-tooling ya existe, omitiendo subtree add."
    else
        git subtree add --prefix=vendor/liferay-tooling liferay-tooling main --squash
        echo "[INFO] Vendor integrado en vendor/liferay-tooling"
    fi
}

copy_scaffold_files() {
    local target_dir="$1"
    cd "${target_dir}"

    [ -f "Taskfile.yml" ] || cp "${SCAFFOLD_DIR}/Taskfile.yml" .
    [ -f ".liferay-cli.yml" ] || cp "${SCAFFOLD_DIR}/.liferay-cli.yml" .
    [ -f ".gitignore" ] || cp "${SCAFFOLD_DIR}/.gitignore" .
    echo "[INFO] Ficheros de configuración copiados."
}

copy_docker_scaffold() {
    local target_dir="$1"
    cd "${target_dir}"

    if [ ! -d "docker" ]; then
        cp -r "${VENDOR_ROOT}/docker" .
        cp docker/.env.example docker/.env
        echo "[INFO] Directorio docker/ creado desde plantilla."
        echo "[AVISO] Edita docker/.env antes de arrancar (COMPOSE_PROJECT_NAME, puertos, etc.)"
    else
        echo "[INFO] docker/ ya existe, omitiendo."
    fi
}

copy_liferay_scaffold() {
    local target_dir="$1"
    cd "${target_dir}"

    if [ ! -d "liferay" ]; then
        cp -r "${VENDOR_ROOT}/liferay" .
        echo "[INFO] Directorio liferay/ creado desde plantilla."
    else
        echo "[INFO] liferay/ ya existe, omitiendo."
    fi
}

copy_oauth2_module() {
    local target_dir="$1"
    local modules_dir="${target_dir}/liferay/modules/liferay-cli-bootstrap"

    if [ ! -d "${modules_dir}" ]; then
        mkdir -p "${target_dir}/liferay/modules"
        cp -r "${VENDOR_ROOT}/modules" "${modules_dir}"
        echo "[INFO] Módulo liferay-cli-bootstrap copiado."
    else
        echo "[INFO] liferay-cli-bootstrap ya existe, omitiendo."
    fi
}

print_next_steps() {
    local target_dir="$1"
    cat <<EOF

========================================
  Proyecto listo en: ${target_dir}
========================================

Próximos pasos:
  1. Edita docker/.env          → ajusta COMPOSE_PROJECT_NAME, puertos
  2. Edita .liferay-cli.yml     → ajusta paths.theme con el nombre de tu tema
  3. cd ${target_dir}
  4. task env:setup              → verifica prerequisitos e inicializa imágenes
  5. task db:sync                → descarga e importa BD desde Liferay Cloud
     (o task db:import FILE=ruta/backup.gz  para backup local)
  6. task env:start              → arranca el entorno
  7. task osgi:liferaycli-creds  → configura credenciales OAuth2

EOF
}

# --- comandos ----------------------------------------------------------------

cmd_init() {
    local name="" dir=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --name) name="$2"; shift 2 ;;
            --dir)  dir="$2";  shift 2 ;;
            *) echo "[ERROR] Argumento desconocido: $1" >&2; usage; exit 1 ;;
        esac
    done

    [ -n "${name}" ] || { echo "[ERROR] --name es obligatorio" >&2; usage; exit 1; }
    [ -n "${dir}" ]  || { echo "[ERROR] --dir es obligatorio" >&2; usage; exit 1; }

    dir="$(eval echo "${dir}")"   # expandir ~ si es necesario

    echo "[INFO] Creando proyecto '${name}' en ${dir}..."
    mkdir -p "${dir}"

    if [ ! -d "${dir}/.git" ]; then
        git -C "${dir}" init
        echo "[INFO] Repositorio git inicializado."
    fi

    copy_docker_scaffold "${dir}"
    copy_liferay_scaffold "${dir}"
    copy_scaffold_files "${dir}"
    copy_oauth2_module "${dir}"

    # Commit inicial necesario antes de git subtree add (requiere HEAD y working tree limpio)
    cd "${dir}"
    git add -A
    git commit -m "chore: scaffold inicial del proyecto Liferay"

    add_vendor_subtree "${dir}"

    print_next_steps "${dir}"
}

cmd_add() {
    local target=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --target) target="$2"; shift 2 ;;
            *) echo "[ERROR] Argumento desconocido: $1" >&2; usage; exit 1 ;;
        esac
    done

    [ -n "${target}" ] || { echo "[ERROR] --target es obligatorio" >&2; usage; exit 1; }
    target="$(eval echo "${target}")"
    [ -d "${target}/.git" ] || { echo "[ERROR] ${target} no es un repositorio git." >&2; exit 1; }

    echo "[INFO] Añadiendo tooling a ${target}..."
    copy_scaffold_files "${target}"
    copy_oauth2_module "${target}"

    cd "${target}"
    git add -A
    git diff --cached --quiet || git commit -m "chore: añadir ficheros de configuración del tooling"

    add_vendor_subtree "${target}"

    print_next_steps "${target}"
}

cmd_add_community() {
    local target=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --target) target="$2"; shift 2 ;;
            *) echo "[ERROR] Argumento desconocido: $1" >&2; usage; exit 1 ;;
        esac
    done

    [ -n "${target}" ] || { echo "[ERROR] --target es obligatorio" >&2; usage; exit 1; }
    target="$(eval echo "${target}")"
    [ -d "${target}/.git" ] || { echo "[ERROR] ${target} no es un repositorio git." >&2; exit 1; }

    echo "[INFO] Añadiendo tooling (Community Edition) a ${target}..."
    copy_docker_scaffold "${target}"
    copy_liferay_scaffold "${target}"
    copy_scaffold_files "${target}"
    copy_oauth2_module "${target}"

    cd "${target}"
    git add -A
    git diff --cached --quiet || git commit -m "chore: añadir scaffold y ficheros de configuración del tooling"

    add_vendor_subtree "${target}"

    print_next_steps "${target}"
}

# --- main --------------------------------------------------------------------

case "${1:-}" in
    init)            shift; cmd_init "$@" ;;
    add)             shift; cmd_add "$@" ;;
    add-community)   shift; cmd_add_community "$@" ;;
    *)               usage; exit 1 ;;
esac
