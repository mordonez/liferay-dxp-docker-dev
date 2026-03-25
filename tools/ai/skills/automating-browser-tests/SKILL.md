---
name: automating-browser-tests
description: "Automating browser-based testing using Playwright. Use when you need to verify UI changes, capture visual evidence, reproduce frontend issues, or perform end-to-end functional checks in the Liferay portal."
allowed-tools: Bash(playwright-cli:*)
---

# Automating Browser Tests (QA Specialist)

This skill manages browser-based automation to ensure frontend reliability. It specializes in Liferay-specific patterns (Admin login, modal handling, asset verification).

Regla operativa:
- Usa `task playwright` como interfaz por defecto.
- **Excepciones permitidas**: `playwright-cli open` (para abrir sesión), `task playwright-ui` (para observar en escritorio) o perfiles persistentes de GitHub.
- **Comparación remota/producción**: SOLO si es pedida explícitamente por el usuario o como fase de contraste documentada tras la reproducción local. Evita usar Playwright remoto/MCP como camino normal de resolución.
- Si quieres ver el navegador en escritorio durante una prueba manual, usa `task playwright-ui`.

## Cuándo es el camino correcto

Usa esta skill no solo para QA visual, sino también para **mutaciones reales en el Liferay Page Editor** cuando el problema es de:

- layout/composition de una content page
- fragment instances mal colocadas, duplicadas o sobrantes
- configuración de widgets/fragmentos a nivel de página
- site building hecho en UI y no versionado como código fuente

En esos casos, `task playwright` es el camino canónico de escritura. No intentes inventar writes contra Headless Delivery solo porque `pageDefinition` sea legible.

## 🔄 The Automation Lifecycle (Mandatory)

### 1. Research & Analysis
- **Identify Target**: Locate the page or component that needs testing.
- **Consult Patterns**: Check `REFERENCE.md` for Liferay-specific login or modal selectors.

### 2. Strategy & Planning
- **Design Script**: Decide if a full `run-code` script is needed or if a simple `snapshot` is enough.
- **Define Pass Rate**: Determine what counts as a successful test (e.g., "Page contains text X", "Button is clickable").

### 3. Execution (Running)
- **Open Session**: Use `playwright-cli -s=<name> open` with repo config.
- **Perform Action**: Execute scripts using `run-code` or manual navigation.
- **Capture Evidence**: Take screenshots or snapshots for the PR.
- **Persist Evidence**: Save screenshots and browser artifacts under `.tmp/<issue-or-session>/` so validation is reproducible and easy to reference later.
- **Publish Evidence**: For visual/UI fixes, the final closeout must upload the key screenshot/video to GitHub as a native attachment in the PR or issue comment composer. Use GitHub's `Attach files` control or drag and drop the file into the comment box so GitHub inserts the anonymized asset URL. `.tmp/...` is staging, not the final reviewer-facing destination.
- **Escalate When Needed**: For flaky or hard-to-explain browser failures, prefer `tracing-start`/`tracing-stop` before spending time on guesswork. Use `video-start`/`video-stop` when a visual replay is more useful than a static screenshot.

### 4. Validation & Verification
- **Assertion**: Compare current state against the expected baseline.
- **Cleanup**: Close all sessions (`playwright-cli close-all`) after completion.

## Mutaciones de Page Editor en Liferay

Flujo obligatorio cuando la issue es de composición/configuración de página:

1. Descubre la página con:

```bash
task liferay -- inventory page --url <fullUrl>
```

2. Usa `adminUrls` del inventario como fuente de verdad:
- `Edit URL` para abrir el editor
- `Configure URL (...)` solo para settings de página, no para composición interna

3. Antes de tocar nada, exporta la estructura actual:

```bash
task liferay -- page-layout export --url <fullUrl>
```

4. Abre el `Edit URL` con `task playwright -- ... ensure-editor-session` o, si quieres verlo en escritorio, con `task playwright-ui`.

Flujo operativo recomendado con el wrapper del repo:

```bash
playwright-cli -s=runtime-<issue> open "<runtime-url>" --config=.playwright/cli.config.json

task playwright -- -s=page-editor-<issue> ensure-editor-session --url <pageUrl>
task playwright -- -s=page-editor-<issue> editor-state --url <pageUrl>
```

## Guardrails de sesión

- No ejecutar dos helpers Playwright en paralelo sobre la misma sesión.
  Si ves `reason: session-busy`, secuenciar en lugar de forzar nueva sesión.
- Usar siempre sesiones con nombre descriptivo: `-s=runtime-<issue>`, `-s=editor-<issue>`.
- Cerrar todas las sesiones al terminar: `playwright-cli close-all`.

## ✅ Checklist de Verificación

- [ ] La URL objetivo carga correctamente (HTTP 200).
- [ ] Los elementos críticos están visibles (sin errores 404/500).
- [ ] Las acciones del usuario (clicks, formularios) funcionan como se espera.
- [ ] Las capturas de pantalla muestran el estado antes/después del cambio.
- [ ] La evidencia está subida como attachment nativo en GitHub.
