---
name: deploying-liferay
description: "Especialista en despliegue y verificación de componentes Liferay DXP. Usar cuando el cambio ya está hecho y el foco es compilar, hacer hot-deploy o confirmar que un bundle está Active. El punto de entrada recomendado para tareas técnicas Liferay es /liferay-expert."
---

# Despliegue de Módulos Liferay (Operaciones de Despliegue)

> Para tareas técnicas Liferay, el entrypoint recomendado es `/liferay-expert`. Esta skill es la especialista de dominio para compilar, desplegar y verificar el estado del runtime.

Esta skill se centra en la "Ejecución" y "Validación" de las tareas de despliegue de Liferay. Asegura que el portal refleje correctamente los cambios del código fuente.

## 🔄 El Ciclo de Vida del Despliegue (Obligatorio)

### 1. Investigación y Análisis
- **Identificar el Objetivo**: Localiza el módulo, tema o juego de fragmentos específico.
- **Consultar Requisitos**: Revisa `build.gradle` o `package.json` para las dependencias.

### 2. Estrategia y Planificación
- **Determinar la Ruta de Despliegue**: Elige entre el completo `deploy:all`, `deploy:module` o `sync-fragments` para el ciclo de feedback más rápido.

### 3. Ejecución (Compilación y Despliegue)
- **Compilar Componente**: Usa los comandos `task deploy:...`.
- **Monitorizar la Compilación**: Revisa la salida estándar para "BUILD SUCCESSFUL" o "Import Successful".

### 4. Validación y Verificación
- **Verificación OSGi**: Usa `task osgi:diag -- <bundle>` para asegurar que esté en estado `Active`.
- **Vigilancia de Logs**: Ejecuta `task env:logs SINCE=2m` para capturar excepciones de despliegue.

---

## Build y despliegue local

```bash
task deploy:prepare
task env:start
task deploy:module -- <module-name>
task deploy:theme
task liferay -- resource sync-fragments --site /<site>
task osgi:status -- <com.example.bundle.name>
task osgi:diag -- <com.example.bundle.name>
task env:logs SINCE=2m
```

Para ver todas las opciones y subcomandos disponibles:
```bash
task help
task liferay -- --help
task liferay -- resource --help
```

## Flujo mínimo de despliegue

1. Identificar tipo de cambio (`module`, `theme`, `fragments`, `config`).
2. Ejecutar el despliegue más pequeño posible (`deploy:module`, `deploy:theme`, `liferay -- resource sync-fragments`).
3. Verificar el estado OSGi (`osgi:status` y `osgi:diag` si no está `ACTIVE`).
4. Verificar el runtime en el portal y revisar logs recientes (`task env:logs SINCE=2m`).

## Despliegues por tipo

### Compilación completa del proyecto

```bash
task deploy:prepare
task env:start
```

Usar para el primer arranque o cuando hay cambios amplios.

### Módulo OSGi (hot-deploy)

```bash
task deploy:module -- <module-name>
```

`deploy:module` detecta:
- Módulos simples: compila y despliega.
- Service Builder (módulos con `service.xml`): ejecuta `buildService`, despliega API+Service y restaura `service.properties` sin dejar cambios persistentes en el worktree.
- Tema vía módulo: `MODULE=<tema>`.

### Tema (cambios CSS/FTL/JS)

```bash
task deploy:theme
```

### Fragmentos del sitio (sin import manual en la UI)

```bash
task liferay -- resource sync-fragments --site /<site>
task liferay -- resource sync-fragments --group-id <group-id>
task liferay -- resource sync-fragments --site /<site> --fragment <FRAGMENT_KEY>
```

## Verificación post-despliegue (obligatoria)

```bash
task osgi:status -- <com.example.bundle.name>
task osgi:diag -- <com.example.bundle.name>
task env:logs SINCE=2m
```

Validar también el comportamiento en runtime (`http://localhost:8080` o host/puerto del worktree).

## Diagnóstico OSGi con Gogo

No interactivo (preferido para agentes):
```bash
task osgi:gogo -- "lb | grep -i <module-name>"
task osgi:gogo -- "diag <bundle-id>"
task osgi:gogo -- "refresh"
```

Interactivo:
```bash
task osgi:gogo
```

## CRUD de recursos Liferay (estructuras, plantillas, ADTs, fragmentos)

Para crear, actualizar, exportar o borrar recursos vía `task liferay -- resource`.
Incluye: convenciones de ficheros, flags completos, flujos de trabajo habituales,
escenario típico de estructura+plantilla+contenido real.

Atajos de uso frecuente:
```bash
task liferay -- inventory structures --site /<site>                              # listar
task liferay -- resource sync-structure --key <STRUCTURE_KEY> --site /<site>    # upsert
task liferay -- resource sync-template --id <TEMPLATE_ID> --site /<site>
task liferay -- resource export-and-sync --site /<site>                         # export+sync todo en 1 JVM
task liferay -- resource export-and-sync --site /<site> --check-only            # verificar sin escribir
```

## Worktrees y errores frecuentes

Si el despliegue falla solo en el worktree, cargar:
- [references/worktree-pitfalls.md](references/worktree-pitfalls.md)

Casos cubiertos en la referencia:
- `Exporting an empty package` por el `.gitignore`.
- `-Xmx4g: command not found` por `LIFERAY_JVM_OPTS` sin comillas.
- Recursos CSS/JS apuntando al puerto equivocado por `web.server.http.port`.

## Reglas de seguridad

- No usar despliegues más amplios de lo necesario.
- No asumir `ACTIVE` sin verificar con `task osgi:status`.
- No dar por correcto `task liferay -- resource sync-fragments` sin revisar el resumen/logs.
- Revisar siempre los logs de errores tras cada despliegue.
