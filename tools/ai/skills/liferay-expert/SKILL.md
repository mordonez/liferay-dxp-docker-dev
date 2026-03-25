---
name: liferay-expert
description: "Usar cuando se va a realizar cualquier tarea técnica sobre la plataforma Liferay DXP: desarrollo, despliegue o diagnóstico. Actúa como router de dominio y delega a la skill especialista. No sustituye a issue-engineering (lifecycle de issues), ni a developing-liferay, deploying-liferay o troubleshooting-liferay."
---

# Liferay Expert — Router de Dominio Técnico

Este skill es el punto de entrada para tareas técnicas Liferay. Identifica el
dominio afectado y activa la skill correcta. No contiene playbooks propios.

**Importante:**
- Para resolver issues de GitHub (worktree, PR, lifecycle), el entrypoint es
  `/issue-engineering`, no este skill.
- Este skill no sustituye a `developing-liferay`, `deploying-liferay` ni
  `troubleshooting-liferay`. Los orquesta.

---

## Árbol de decisión

**¿Cuál es el foco principal de la tarea?**

**1. Cambiar código o contenido**
Hay que modificar SCSS, FTL, estructuras DDM, templates, fragments, módulos
OSGi, o trabajar en el Page Editor.
→ activar `developing-liferay`

**2. Compilar, desplegar o verificar runtime**
El cambio ya está hecho. El foco es construir el artefacto, hot-deployer,
o confirmar que un bundle está `Active` y el portal refleja el cambio.
→ activar `deploying-liferay`

**3. El runtime está roto o no hay causa raíz clara**
El portal no arranca, un bundle está en `Installed`/`Resolved`, hay una
regresión funcional o visual sin causa obvia, o los logs muestran errores
no esperados.
→ activar `troubleshooting-liferay`

**4. Migración de estructuras Journal con riesgo de pérdida de datos**
El cambio implica modificar una estructura DDM con contenido publicado,
eliminar campos, o hacer una migración masiva de artículos.
→ activar `migrating-journal-structures`

Si la tarea mezcla desarrollo y despliegue (lo habitual en una issue),
empezar por `developing-liferay`: su lifecycle cubre ya el deploy y la
validación.

---

## Flujo canónico orientativo

Todo trabajo Liferay sigue esta secuencia. Los comandos concretos dependen
del artefacto; la skill especialista tiene los detalles.

**Discover** — entender qué hay antes de tocar nada

Según el caso: `inventory page`, `inventory structures`, `resource resolve-adt`,
`env:info`. Si hay una URL afectada, `inventory page` es siempre el primer paso.

**Change** — cambio mínimo necesario sobre el artefacto identificado

**Deploy** — el más pequeño posible según el artefacto

`deploy:module` para OSGi, `deploy:theme` para tema,
`resource sync-*` individual para structures/templates/fragments.

**Verify** — siempre, sin excepción

`osgi:status` si aplica, `env:logs SINCE=2m`, validación en runtime real
en la URL/puerto del worktree activo reportado por `task env:info`.

---

## Guardrails transversales

Estas reglas aplican en los tres dominios. Las skills especializadas las
desarrollan; aquí están consolidadas como contrato común.

**Entorno primero**
Si cualquier comando falla con `java.net.ConnectException`, verificar con
`task env:info` y arrancar con `task env:start` antes de depurar nada más.

**Deploy mínimo**
Nunca usar `deploy:all` o bulk cuando es posible hacer un deploy acotado.
El deploy más pequeño posible reduce el riesgo y acelera el ciclo.

**Verificar ACTIVE tras deploy**
No asumir que un deploy ha funcionado. Siempre `task osgi:status` +
`task env:logs` tras cualquier despliegue de módulo.

**No bulk sync en resources versionados**
Para structures, templates, ADTs y fragments: sync individual por defecto.
Bulk sync solo con razón escrita y flag `--bulk` explícito. Aplica a
cualquier `task liferay -- resource sync-*` o `export-and-sync`.

---

## Referencias por dominio

**`developing-liferay`**
Activar cuando: cambios de código, FTL, SCSS, DDM, fragments, Page Editor.
No cubre: diagnóstico de fallos de runtime, compilación autónoma.
Referencias: `developing-liferay/references/` (theme, structures, fragments, osgi, breaking-changes)

**`deploying-liferay`**
Activar cuando: compilar, hot-deploy, verificar bundle `Active`, sync individual de resources versionados.
No cubre: decisión de qué cambiar, diagnóstico de causa raíz.
Referencias: `deploying-liferay/references/worktree-pitfalls.md`

**`troubleshooting-liferay`**
Activar cuando: portal caído, bundle `Installed`, regresión, FTL error, reindex.
No cubre: implementación del fix, deploy del artefacto corregido.
Referencias: `troubleshooting-liferay/references/` (ddm-migration, reindex-journal)

**`migrating-journal-structures`**
Activar cuando: cambio de estructura DDM con contenido publicado o migración masiva.
No cubre: desarrollo general ni despliegues de módulo.
