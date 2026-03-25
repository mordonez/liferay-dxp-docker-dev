#!/bin/bash
# 00-configure-elasticsearch.sh — Escribe la config OSGi de ES7 antes de que
# arranque Tomcat, como safety net por si FileInstall no la procesa a tiempo.
#
# Se ejecuta desde /usr/local/liferay/scripts/pre-startup/ por el entrypoint
# de la imagen Liferay DXP ANTES de lanzar Tomcat.

set -euo pipefail

ES_CONFIG_DIR="/opt/liferay/osgi/configs"
ES_CONFIG_FILE="${ES_CONFIG_DIR}/com.liferay.portal.search.elasticsearch7.configuration.ElasticsearchConfiguration.config"

mkdir -p "${ES_CONFIG_DIR}"

# Solo escribir si no existe ya (respeta overrides de usuario)
if [ ! -f "${ES_CONFIG_FILE}" ]; then
    cat > "${ES_CONFIG_FILE}" << 'EOF'
operationMode="REMOTE"
networkHostAddresses=["http://elasticsearch:9200"]
authenticationEnabled=B"false"
httpSSLEnabled=B"false"
EOF
    echo "[INFO] ES7 config escrita en ${ES_CONFIG_FILE}"
else
    echo "[INFO] ES7 config ya existe, omitiendo escritura."
fi
