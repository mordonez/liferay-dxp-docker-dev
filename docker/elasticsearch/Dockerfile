FROM elasticsearch:8.17.4

# Plugins requeridos por Liferay DXP para análisis multilingüe
# Mismos plugins que incluye el sidecar embebido de Liferay
RUN elasticsearch-plugin install --batch \
    analysis-icu \
    analysis-kuromoji \
    analysis-smartcn \
    analysis-stempel \
    analysis-ukrainian
