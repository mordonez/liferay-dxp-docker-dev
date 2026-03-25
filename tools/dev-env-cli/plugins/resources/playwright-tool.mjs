#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const DEFAULT_PLAYWRIGHT_TIMEOUT_MS = 30000;
const SESSION_READY_TIMEOUT_MS = 8000;
const SESSION_LOCK_DIR = path.resolve(process.cwd(), '.tmp', 'playwright-session-locks');
const HELD_SESSION_LOCKS = new Map();

function usage() {
  console.log(`playwright wrapper for repo-local automation

Usage:
  task playwright -- [global-playwright-cli-flags] run-file --file <script.js>
  task playwright -- [global-playwright-cli-flags] ensure-session --kind runtime|editor --url <pageUrlOrFullUrl>
  task playwright -- [global-playwright-cli-flags] ensure-editor-session --url <pageUrl>
  task playwright -- [global-playwright-cli-flags] editor-login --url <pageUrl>
  task playwright -- [global-playwright-cli-flags] page-state --url <pageUrl>
  task playwright -- [global-playwright-cli-flags] editor-state --url <pageUrl>
  task playwright -- [global-playwright-cli-flags] editor-select-item --name <label> [--url <pageUrl>]
  task playwright -- [global-playwright-cli-flags] editor-hide-item --name <label> [--url <pageUrl>]
  task playwright -- [global-playwright-cli-flags] editor-hide-items --name <label> [--name <label> ...] [--url <pageUrl>]
  task playwright -- [global-playwright-cli-flags] editor-open-selected-item-options [--url <pageUrl>]
  task playwright -- [global-playwright-cli-flags] editor-delete-selected [--name <label>] [--url <pageUrl>]
  task playwright -- [global-playwright-cli-flags] editor-publish-and-verify --url <pageUrl> [--verify-runtime-url <fullUrlOrPageUrl>] [--runtime-base-url <baseUrl>] [--runtime-expect-empty true|false] [--runtime-expect-search-bar visible|hidden|ignore] [--runtime-expect-sort visible|hidden|ignore] [--runtime-expect-epoch1970 true|false|ignore] [--runtime-artifacts-dir <dir>] [--runtime-screenshot-name <name>]
  task playwright -- [global-playwright-cli-flags] runtime-check --url <fullUrlOrPageUrl> [--base-url <baseUrl>] [--expect-empty true|false] [--expect-search-bar visible|hidden|ignore] [--expect-sort visible|hidden|ignore] [--expect-epoch1970 true|false|ignore] [--artifacts-dir <dir>] [--screenshot-name <name>]

Global flags are forwarded to playwright-cli, for example:
  task playwright -- -s=page-editor-login editor-state --url /web/facultat-farmacia-alimentacio/agenda

Session preflight:
  - Run helpers from the same worktree root as the target environment.
  - ensure-editor-session and ensure-session --kind ... can auto-open the session if it does not exist yet.
  - Other helpers still expect an already-open session and will fail fast if it is missing.

Precondition for Page Editor helpers:
  1. Preferred path: let ensure-editor-session bootstrap the browser session for you.
  2. Manual fallback:
     playwright-cli -s=<session> open "http://127.0.0.1:8433/c/portal/login" --config=.playwright/cli.config.json
  3. Then use task playwright helpers against that same session.
`);
}

function failTyped(failureKind, message, extra = {}, exitCode = 1) {
  const payload = {ok: false, failureKind, error: message, ...extra};
  console.error(JSON.stringify(payload, null, 2));
  process.exit(exitCode);
}

function fail(message, extra = {}) {
  failTyped(extra.failureKind ?? 'tool', message, extra);
}

function sleep(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function parseArgs(argv) {
  const globalArgs = [];
  let index = 0;

  while (index < argv.length) {
    const arg = argv[index];
    if (!arg.startsWith('-')) {
      break;
    }
    globalArgs.push(arg);
    index += 1;
  }

  const command = argv[index];
  const rest = argv.slice(index + 1);
  return {globalArgs, command, rest};
}

function parseOptions(args) {
  const options = {};
  const positional = [];

  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (arg.startsWith('--')) {
      const eqIndex = arg.indexOf('=');
      if (eqIndex > -1) {
        options[arg.slice(2, eqIndex)] = arg.slice(eqIndex + 1);
      } else if (i + 1 < args.length && !args[i + 1].startsWith('-')) {
        options[arg.slice(2)] = args[i + 1];
        i += 1;
      } else {
        options[arg.slice(2)] = 'true';
      }
      continue;
    }
    positional.push(arg);
  }

  return {options, positional};
}

function optionValues(args, key) {
  const values = [];
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (arg === `--${key}` && i + 1 < args.length) {
      values.push(args[i + 1]);
      i += 1;
      continue;
    }
    if (arg.startsWith(`--${key}=`)) {
      values.push(arg.slice(key.length + 3));
    }
  }
  return values;
}

function repoPlaywrightConfigArg() {
  const configPath = path.resolve(process.cwd(), '.playwright', 'cli.config.json');
  if (!fs.existsSync(configPath)) {
    return null;
  }
  return `--config=${configPath}`;
}

function withRepoConfig(globalArgs) {
  if (globalArgs.some(arg => arg === '--config' || arg.startsWith('--config='))) {
    return globalArgs;
  }
  const configArg = repoPlaywrightConfigArg();
  return configArg ? [...globalArgs, configArg] : globalArgs;
}

function sessionNameFromArgs(globalArgs) {
  for (let index = 0; index < globalArgs.length; index += 1) {
    const arg = globalArgs[index];
    if (arg.startsWith('-s=')) {
      return arg.slice(3);
    }
    if (arg === '-s' || arg === '--session') {
      return globalArgs[index + 1] ?? null;
    }
    if (arg.startsWith('--session=')) {
      return arg.slice('--session='.length);
    }
  }
  return null;
}

function ensureLockDir() {
  fs.mkdirSync(SESSION_LOCK_DIR, {recursive: true});
}

function sessionLockPath(sessionName) {
  ensureLockDir();
  return path.join(SESSION_LOCK_DIR, `${sessionName}.lock.json`);
}

function readJsonFileSafe(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch {
    return null;
  }
}

function releaseSessionLock(sessionName) {
  const held = HELD_SESSION_LOCKS.get(sessionName);
  if (!held) {
    return;
  }
  try {
    if (fs.existsSync(held.lockPath)) {
      const current = readJsonFileSafe(held.lockPath);
      if (!current || current.pid === process.pid) {
        fs.unlinkSync(held.lockPath);
      }
    }
  } catch {
    // ignore cleanup failures on exit
  }
  HELD_SESSION_LOCKS.delete(sessionName);
}

function acquireSessionLock(sessionName, context) {
  if (!sessionName || HELD_SESSION_LOCKS.has(sessionName)) {
    return;
  }
  const lockPath = sessionLockPath(sessionName);
  const payload = {
    pid: process.pid,
    sessionName,
    context,
    createdAt: new Date().toISOString(),
  };
  try {
    const fd = fs.openSync(lockPath, 'wx');
    fs.writeFileSync(fd, JSON.stringify(payload, null, 2));
    fs.closeSync(fd);
  } catch (error) {
    if (error?.code === 'EEXIST') {
      failTyped('session', 'La sesión de playwright-cli está ocupada', {
        reason: 'session-busy',
        sessionName,
        lockPath,
        lockOwner: readJsonFileSafe(lockPath),
      });
    }
    failTyped('session', `No se pudo bloquear la sesión ${sessionName}: ${error.message}`, {
      reason: 'session-lock-failed',
      sessionName,
      lockPath,
    });
  }
  HELD_SESSION_LOCKS.set(sessionName, {lockPath});
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? process.cwd(),
    encoding: 'utf8',
    env: {...process.env, ...(options.env ?? {})},
    timeout: options.timeoutMs,
    killSignal: options.killSignal ?? 'SIGKILL',
  });

  if (options.allowFailure) {
    return result;
  }

  if (result.error) {
    if (result.error.code === 'ETIMEDOUT') {
      failTyped('tool', `El comando ${command} agotó el tiempo de espera`, {
        timeoutMs: options.timeoutMs ?? null,
        stdout: result.stdout?.trim() ?? '',
        stderr: result.stderr?.trim() ?? '',
      });
    }
    failTyped('tool', `No se pudo ejecutar ${command}: ${result.error.message}`);
  }
  if ((result.status ?? 1) !== 0) {
    failTyped('tool', `El comando ${command} falló`, {
      status: result.status,
      stdout: result.stdout?.trim() ?? '',
      stderr: result.stderr?.trim() ?? '',
    });
  }
  return result;
}

function parsePlaywrightResult(stdout) {
  const marker = '### Result';
  const codeMarker = '### Ran Playwright code';
  const markerIndex = stdout.indexOf(marker);
  if (markerIndex === -1) {
    return null;
  }
  const afterMarker = stdout.slice(markerIndex + marker.length).trimStart();
  const codeIndex = afterMarker.indexOf(codeMarker);
  const raw = (codeIndex === -1 ? afterMarker : afterMarker.slice(0, codeIndex)).trim();
  if (!raw) {
    return null;
  }
  return JSON.parse(raw);
}

function runPlaywright(globalArgs, commandArgs, options = {}) {
  const result = run('playwright-cli', [...withRepoConfig(globalArgs), ...commandArgs], {
    allowFailure: options.allowFailure ?? false,
    timeoutMs: options.timeoutMs ?? DEFAULT_PLAYWRIGHT_TIMEOUT_MS,
  });

  if (options.allowFailure) {
    return result;
  }

  const parsed = parsePlaywrightResult(result.stdout ?? '');
  return {
    raw: result,
    result: parsed,
  };
}

function tryPlaywright(globalArgs, commandArgs, options = {}) {
  const result = run('playwright-cli', [...withRepoConfig(globalArgs), ...commandArgs], {
    allowFailure: true,
    timeoutMs: options.timeoutMs ?? DEFAULT_PLAYWRIGHT_TIMEOUT_MS,
  });

  return {
    ok: !result.error && (result.status ?? 1) === 0,
    raw: result,
    result: parsePlaywrightResult(result.stdout ?? ''),
  };
}

function sessionList() {
  const result = run('playwright-cli', ['list']);
  return result.stdout ?? '';
}

function isSessionOpen(sessionName) {
  const output = sessionList();
  return output.includes(`- ${sessionName}:`) && output.includes('status: open');
}

function openSession(globalArgs, initialUrl) {
  const sessionName = sessionNameFromArgs(globalArgs);
  run('playwright-cli', [...withRepoConfig(globalArgs), 'open', initialUrl], {
    timeoutMs: 90000,
  });
  return sessionName;
}

function ensureSessionOpen(globalArgs, initialUrl, options = {}) {
  const sessionName = sessionNameFromArgs(globalArgs);
  if (!sessionName) {
    failTyped('session', 'Los helpers de playwright requieren una sesión explícita (-s=<nombre>)', {
      reason: 'session-required',
    });
  }

  if (options.autoOpen && !isSessionOpen(sessionName)) {
    openSession(globalArgs, initialUrl);
  }

  for (let attempt = 0; attempt < 10; attempt += 1) {
    if (isSessionOpen(sessionName)) {
      return sessionName;
    }
    sleep(300);
  }
  failTyped('session', 'La sesión de playwright-cli no está abierta', {
    sessionName,
    reason: 'session-not-open',
    openCommand: `playwright-cli -s=${sessionName} open "${initialUrl}" --config=.playwright/cli.config.json`,
  });
}

function sessionReadyCode() {
  return buildRunCode(`
async (page) => {
  await page.waitForLoadState('domcontentloaded').catch(() => null);
  return await page.evaluate(() => ({
    href: location.href,
    title: document.title,
    signedIn: !!window.Liferay?.ThemeDisplay?.isSignedIn?.(),
    loginInputs: document.querySelectorAll('#_com_liferay_login_web_portlet_LoginPortlet_login').length,
    passwordInputs: document.querySelectorAll('input[type="password"]').length,
    dataNameCount: document.querySelectorAll('[data-name]').length,
  }));
}`);
}

function ensureSessionReady(globalArgs, initialUrl, options = {}) {
  const sessionName = ensureSessionOpen(globalArgs, initialUrl, options);
  let lastFailure = null;

  for (let attempt = 0; attempt < (options.attempts ?? 8); attempt += 1) {
    const probe = tryPlaywright(globalArgs, ['run-code', sessionReadyCode()], {
      timeoutMs: options.timeoutMs ?? SESSION_READY_TIMEOUT_MS,
    });

    if (probe.ok && probe.result) {
      if (options.requireSignedIn && !probe.result.signedIn) {
        lastFailure = {
          reason: 'session-not-authenticated',
          state: probe.result,
        };
      } else {
        return probe.result;
      }
    } else {
      lastFailure = {
        reason: probe.raw?.error?.code === 'ETIMEDOUT' ? 'session-timeout' : 'session-not-responding',
        status: probe.raw?.status ?? null,
        stdout: probe.raw?.stdout?.trim() ?? '',
        stderr: probe.raw?.stderr?.trim() ?? '',
        error: probe.raw?.error?.message ?? null,
      };
    }

    sleep(options.delayMs ?? 500);
  }

  failTyped('session', 'La sesión de playwright-cli está abierta pero no responde de forma estable', {
    sessionName,
    reason: 'session-not-ready',
    openCommand: `playwright-cli -s=${sessionName} open "${initialUrl}" --config=.playwright/cli.config.json`,
    lastFailure,
  });
}

function ensureOption(options, key) {
  const value = options[key];
  if (!value || value === 'true') {
    fail(`Falta --${key}`);
  }
  return value;
}

function inventoryPage(url) {
  const result = run('task', [
    'liferay',
    '--',
    'inventory',
    'page',
    '--url',
    url,
    '--format',
    'json',
  ]);
  return JSON.parse(result.stdout);
}

function pageExport(url) {
  const result = run('task', [
    'liferay',
    '--',
    'page-layout',
    'export',
    '--url',
    url,
  ]);
  return JSON.parse(result.stdout);
}

function editorUrlFor(pageUrl) {
  const inventory = inventoryPage(pageUrl);
  const editUrl = inventory.adminUrls?.['Edit URL'];
  if (!editUrl) {
    fail('inventory page no devolvió adminUrls.Edit URL', {pageUrl});
  }
  return editUrl;
}

function baseUrlFor(pageUrl) {
  return new URL(editorUrlFor(pageUrl)).origin;
}

function sessionStateCode() {
  return buildRunCode(`
async (page) => {
  await page.waitForLoadState('domcontentloaded').catch(() => null);
  return await page.evaluate(() => {
    const loginInputs = document.querySelectorAll('#_com_liferay_login_web_portlet_LoginPortlet_login').length;
    const passwordInputs = document.querySelectorAll('input[type="password"]').length;
    const hasLoginForm = loginInputs > 0 || passwordInputs > 0;
    const controls = Array.from(document.querySelectorAll('button, a, [role="button"], [role="menuitem"]'));
    const hasUserProfile = controls.some(node => /Perfil de l'usuari|User Profile|Perfil del usuario/i.test((node.textContent || '').trim()) || /Perfil de l'usuari|User Profile|Perfil del usuario/i.test(node.getAttribute('aria-label') || ''));
    const hasControlMenu = Boolean(
      document.querySelector('[aria-label="Menú de control"]') ||
      document.querySelector('[aria-label="Menú de producte"]') ||
      document.querySelector('[aria-label="Pàgines del lloc"]')
    );
    const themeDisplaySignedIn = !!window.Liferay?.ThemeDisplay?.isSignedIn?.();
    return {
      href: location.href,
      title: document.title,
      signedIn: themeDisplaySignedIn || (!hasLoginForm && (hasUserProfile || hasControlMenu)),
      passwordInputs,
      loginInputs,
      dataNameCount: document.querySelectorAll('[data-name]').length,
      publishButtons: Array.from(document.querySelectorAll('button'))
        .filter(node => /Publica|Publish|Publicar/i.test((node.textContent || '').trim()) || /Publica|Publish|Publicar/i.test(node.getAttribute('aria-label') || ''))
        .length
    };
  });
}`);
}

function currentSessionState(globalArgs) {
  return runPlaywright(globalArgs, ['run-code', sessionStateCode()], {
    timeoutMs: SESSION_READY_TIMEOUT_MS,
  }).result;
}

function loginCode(baseUrl) {
  return buildRunCode(`
async (page) => {
  const baseUrl = ${JSON.stringify(baseUrl)};
  const loginSelector = '#_com_liferay_login_web_portlet_LoginPortlet_login';
  const passwordSelector = '#_com_liferay_login_web_portlet_LoginPortlet_password';

  await page.goto(baseUrl + '/c/portal/login', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(500);

  if (await page.locator(loginSelector).count()) {
    await page.locator(loginSelector).fill('admin@liferay.local');
    await page.locator(passwordSelector).fill('test');
    const submit = page.locator('button[type="submit"]').filter({ hasText: /Accedeix|Sign In|Inicia sessió|Iniciar sesión/i }).first();
    await submit.click({ noWaitAfter: true }).catch(() => null);
  }

  for (let attempt = 0; attempt < 30; attempt += 1) {
    await page.waitForTimeout(500);
    const state = await page.evaluate(() => {
      const loginInputs = document.querySelectorAll('#_com_liferay_login_web_portlet_LoginPortlet_login').length;
      const passwordInputs = document.querySelectorAll('input[type="password"]').length;
      const hasLoginForm = loginInputs > 0 || passwordInputs > 0;
      const controls = Array.from(document.querySelectorAll('button, a, [role="button"], [role="menuitem"]'));
      const hasUserProfile = controls.some(node => /Perfil de l'usuari|User Profile|Perfil del usuario/i.test((node.textContent || '').trim()) || /Perfil de l'usuari|User Profile|Perfil del usuario/i.test(node.getAttribute('aria-label') || ''));
      const hasControlMenu = Boolean(
        document.querySelector('[aria-label="Menú de control"]') ||
        document.querySelector('[aria-label="Menú de producte"]') ||
        document.querySelector('[aria-label="Pàgines del lloc"]')
      );
      const themeDisplaySignedIn = !!window.Liferay?.ThemeDisplay?.isSignedIn?.();
      return {
        href: location.href,
        title: document.title,
        signedIn: themeDisplaySignedIn || (!hasLoginForm && (hasUserProfile || hasControlMenu)),
        loginInputs
      };
    });
    if (state.signedIn && state.loginInputs === 0) {
      return state;
    }
  }

  return await page.evaluate(() => {
    const loginInputs = document.querySelectorAll('#_com_liferay_login_web_portlet_LoginPortlet_login').length;
    const passwordInputs = document.querySelectorAll('input[type="password"]').length;
    const hasLoginForm = loginInputs > 0 || passwordInputs > 0;
    const controls = Array.from(document.querySelectorAll('button, a, [role="button"], [role="menuitem"]'));
    const hasUserProfile = controls.some(node => /Perfil de l'usuari|User Profile|Perfil del usuario/i.test((node.textContent || '').trim()) || /Perfil de l'usuari|User Profile|Perfil del usuario/i.test(node.getAttribute('aria-label') || ''));
    const hasControlMenu = Boolean(
      document.querySelector('[aria-label="Menú de control"]') ||
      document.querySelector('[aria-label="Menú de producte"]') ||
      document.querySelector('[aria-label="Pàgines del lloc"]')
    );
    const themeDisplaySignedIn = !!window.Liferay?.ThemeDisplay?.isSignedIn?.();
    return {
      href: location.href,
      title: document.title,
      signedIn: themeDisplaySignedIn || (!hasLoginForm && (hasUserProfile || hasControlMenu)),
      loginInputs
    };
  });
}`);
}

function ensureAdminSession(globalArgs, baseUrl) {
  ensureSessionReady(globalArgs, `${baseUrl}/c/portal/login`, {autoOpen: true});
  const state = currentSessionState(globalArgs);
  if (state?.signedIn) {
    return state;
  }

  runPlaywright(globalArgs, ['goto', `${baseUrl}/c/portal/login`]);
  const loginPageState = currentSessionState(globalArgs);
  if (loginPageState?.signedIn) {
    return loginPageState;
  }

  const loggedIn = runPlaywright(globalArgs, ['run-code', loginCode(baseUrl)]).result;
  if (!loggedIn?.signedIn) {
    failTyped('session', 'No se pudo iniciar sesión admin para el Page Editor', {
      reason: 'login-failed',
      sessionName: sessionNameFromArgs(globalArgs),
      baseUrl,
      loginState: loggedIn,
      hint: 'Abre una sesión dedicada del editor desde este mismo worktree y reutilízala con -s=<session>.',
    });
  }
  return loggedIn;
}

function isEditorState(state, editorUrl) {
  const href = state?.href ?? '';
  return Boolean(
    href &&
    (
      href.startsWith(editorUrl) ||
      href.includes('p_l_mode=edit') ||
      (state?.dataNameCount ?? 0) > 0 ||
      (state?.publishButtons ?? 0) > 0
    )
  );
}

function ensureEditorSession(globalArgs, pageUrl) {
  const editorUrl = editorUrlFor(pageUrl);
  const baseUrl = new URL(editorUrl).origin;
  ensureSessionReady(globalArgs, `${baseUrl}/c/portal/login`, {autoOpen: true});

  const initialState = currentSessionState(globalArgs);
  let status = 'unknown';

  if (initialState?.signedIn && isEditorState(initialState, editorUrl)) {
    ensureEditorLoaded(globalArgs);
    return {
      baseUrl,
      editorUrl,
      status: 'already-authenticated',
      initialState,
      session: runPlaywright(globalArgs, ['run-code', sessionStateCode()]).result,
      draft: runPlaywright(globalArgs, ['run-code', editorStateCode()]).result,
    };
  }

  if (initialState?.signedIn) {
    runPlaywright(globalArgs, ['goto', editorUrl]);
    ensureEditorLoaded(globalArgs);
    return {
      baseUrl,
      editorUrl,
      status: 'navigated-to-editor',
      initialState,
      session: runPlaywright(globalArgs, ['run-code', sessionStateCode()]).result,
      draft: runPlaywright(globalArgs, ['run-code', editorStateCode()]).result,
    };
  }

  const loggedIn = ensureAdminSession(globalArgs, baseUrl);
  if (!loggedIn?.signedIn) {
    failTyped('session', 'No se pudo dejar preparada la sesión del Page Editor', {
      reason: 'login-required-but-failed',
      sessionName: sessionNameFromArgs(globalArgs),
      baseUrl,
      editorUrl,
      initialState,
      loginState: loggedIn,
      hint: 'Abre una sesión dedicada del editor desde este mismo worktree y reutilízala con -s=<session>.',
    });
  }

  runPlaywright(globalArgs, ['goto', editorUrl]);
  ensureEditorLoaded(globalArgs);
  status = 'logged-in';

  return {
    baseUrl,
    editorUrl,
    status,
    initialState,
    session: runPlaywright(globalArgs, ['run-code', sessionStateCode()]).result,
    draft: runPlaywright(globalArgs, ['run-code', editorStateCode()]).result,
  };
}

function gotoEditor(globalArgs, pageUrl) {
  const editUrl = editorUrlFor(pageUrl);
  const baseUrl = new URL(editUrl).origin;
  ensureAdminSession(globalArgs, baseUrl);
  runPlaywright(globalArgs, ['goto', editUrl]);
  return editUrl;
}

function ensureEditorLoaded(globalArgs) {
  let lastState = null;
  for (let attempt = 0; attempt < 15; attempt += 1) {
    const {result} = runPlaywright(globalArgs, ['run-code', sessionStateCode()], {
      timeoutMs: SESSION_READY_TIMEOUT_MS,
    });
    lastState = result;
    if ((result?.dataNameCount ?? 0) > 0 || (result?.publishButtons ?? 0) > 0) {
      return result;
    }
    if (result?.signedIn === false) {
      failTyped('session', 'La sesión actual no está autenticada; p_l_mode=edit está devolviendo la página pública', {
        reason: 'editor-not-authenticated',
        editorState: result,
      });
    }
    if ((result?.passwordInputs ?? 0) > 0) {
      failTyped('session', 'La sesión de editor ha caído al login', {
        reason: 'editor-fell-back-to-login',
        editorState: result,
      });
    }
    runPlaywright(globalArgs, ['run-code', buildRunCode(`
async (page) => {
  await page.waitForTimeout(500);
  return true;
}`)]);
  }
  failTyped('navigation', 'La UI del editor no se estabilizó o no mostró señales válidas', {
    reason: 'editor-not-stable',
    editorState: lastState,
  });
}

function ensureSessionCommand(globalArgs, kind, pageOrUrl) {
  if (kind !== 'runtime' && kind !== 'editor') {
    failTyped('tool', 'El valor de --kind debe ser runtime o editor', {reason: 'invalid-kind', kind});
  }

  if (kind === 'editor') {
    const pageUrl = pageOrUrl ?? failTyped('tool', 'Falta --url para ensure-session --kind editor', {reason: 'missing-url'});
    const editUrl = gotoEditor(globalArgs, pageUrl);
    const editorState = ensureEditorLoaded(globalArgs);
    printJson({
      ok: true,
      kind,
      sessionName: sessionNameFromArgs(globalArgs),
      editorUrl: editUrl,
      state: editorState,
    });
    return;
  }

  const runtimeUrl = resolveRuntimeUrl(
    pageOrUrl ?? failTyped('tool', 'Falta --url para ensure-session --kind runtime', {reason: 'missing-url'}),
    null
  );
  ensureSessionReady(globalArgs, runtimeUrl, {autoOpen: true});
  runPlaywright(globalArgs, ['goto', runtimeUrl]);
  const state = currentSessionState(globalArgs);
  printJson({
    ok: true,
    kind,
    sessionName: sessionNameFromArgs(globalArgs),
    url: runtimeUrl,
    state,
  });
}

function collectLiveItems(exportJson) {
  const items = [];

  function walk(pageElements) {
    for (const element of pageElements ?? []) {
      const definition = element.definition ?? {};
      const fragmentKey = definition.fragment?.key;
      if (fragmentKey) {
        items.push({
          kind: 'fragment',
          id: element.id,
          name: fragmentKey,
        });
      }
      const widgetInstance = definition.widgetInstance;
      if (widgetInstance?.widgetName) {
        items.push({
          kind: 'widget',
          id: element.id,
          name: widgetInstance.widgetName,
          config: widgetInstance.widgetConfig ?? {},
        });
      }
      walk(element.pageElements);
    }
  }

  walk(exportJson.headlessSitePage?.pageDefinition?.pageElement?.pageElements ?? []);
  return items;
}

function buildRunCode(functionSource) {
  return functionSource.replace(/\n/g, ' ');
}

function editorStateCode() {
  return buildRunCode(`
async (page) => {
  await page.bringToFront();
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(800);

  const state = await page.evaluate(() => {
    const isVisibleFn = (node) => {
      if (!node) {
        return false;
      }
      const style = window.getComputedStyle(node);
      if (style.visibility === 'hidden' || style.display === 'none') {
        return false;
      }
      const rect = node.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0;
    };
    const visibleNames = Array.from(document.querySelectorAll('[data-name]'))
      .filter(node => isVisibleFn(node))
      .map(node => node.getAttribute('data-name') || '')
      .filter(Boolean);
    const selectedItems = Array.from(document.querySelectorAll('[data-name]'))
      .filter(node => {
        const cls = node.className || '';
        return isVisibleFn(node) && (cls.includes('active') || cls.includes('page-editor__topper--active') || (node.getAttribute('aria-selected') || '') === 'true');
      })
      .map(node => node.getAttribute('data-name') || '')
      .filter(Boolean);
    const publishButton = Array.from(document.querySelectorAll('button'))
      .filter(node => isVisibleFn(node))
      .find(node => /Publica/i.test((node.textContent || '').trim()) || /Publica/i.test(node.getAttribute('aria-label') || ''));
    const discardDraftButton = Array.from(document.querySelectorAll('button'))
      .filter(node => isVisibleFn(node))
      .find(node => /Descarta l'esborrany|Discard draft|Descartar borrador/i.test((node.textContent || '').trim()) || /Descarta l'esborrany|Discard draft|Descartar borrador/i.test(node.getAttribute('aria-label') || ''));

    return {
      url: location.href,
      itemsPresent: Array.from(new Set(visibleNames)),
      selectedItems,
      publishButton: publishButton ? {
        text: (publishButton.textContent || '').trim(),
        disabled: publishButton.disabled || publishButton.hasAttribute('disabled'),
        ariaLabel: publishButton.getAttribute('aria-label') || ''
      } : null,
      discardDraftButton: discardDraftButton ? {
        text: (discardDraftButton.textContent || '').trim(),
        disabled: discardDraftButton.disabled || discardDraftButton.hasAttribute('disabled'),
        ariaLabel: discardDraftButton.getAttribute('aria-label') || ''
      } : null,
      hasPendingChanges: discardDraftButton ?
        !(discardDraftButton.disabled || discardDraftButton.hasAttribute('disabled')) :
        (publishButton ? !(publishButton.disabled || publishButton.hasAttribute('disabled')) : null)
    };
  });

  return state;
}`);
}

function selectItemCode(name) {
  return buildRunCode(`
async (page) => {
  const expectedName = ${JSON.stringify(name)};
  await page.bringToFront();
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(500);

  const target = page.locator('[data-name="' + expectedName.replace(/"/g, '\\"') + '"]').first();
  if (!await target.count()) {
    return {ok: false, reason: 'item-not-found', expectedName, selectedItems: []};
  }

  await target.scrollIntoViewIfNeeded().catch(() => null);
  await target.click({ timeout: 5000, force: true }).catch(() => null);
  await page.waitForTimeout(800);

  const selectedItems = await page.evaluate(() =>
    Array.from(document.querySelectorAll('[data-name]'))
      .filter(node => {
        const cls = node.className || '';
        return cls.includes('active') || cls.includes('page-editor__topper--active') || (node.getAttribute('aria-selected') || '') === 'true';
      })
      .map(node => node.getAttribute('data-name'))
      .filter(Boolean)
  );

  return {
    ok: selectedItems.includes(expectedName),
    expectedName,
    selectedItems
  };
}`);
}

function openSelectedOptionsCode() {
  return buildRunCode(`
async (page) => {
  await page.bringToFront();
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(500);

  const opened = await page.evaluate(() => {
    const isVisible = (node) => {
      if (!node) {
        return false;
      }
      const style = window.getComputedStyle(node);
      const rect = node.getBoundingClientRect();
      return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
    };
    const selectedItems = Array.from(document.querySelectorAll('[data-name]'))
      .filter(node => {
        const cls = node.className || '';
        return isVisible(node) && (cls.includes('active') || cls.includes('page-editor__topper--active') || (node.getAttribute('aria-selected') || '') === 'true');
      })
      .map(node => node.getAttribute('data-name'))
      .filter(Boolean);

    const buttons = Array.from(document.querySelectorAll('button[aria-label="Opcions"].btn-unstyled'))
      .filter(node => isVisible(node))
      .filter(node => ((node.parentElement?.parentElement?.className) || '').includes('page-editor__topper__item'));

    const button = buttons.at(-1);
    if (!button) {
      return {ok: false, reason: 'context-options-not-found', selectedItems};
    }

    button.click();
    const menuId = button.getAttribute('aria-controls');
    const menu = menuId ? document.getElementById(menuId) : null;
    const items = menu ? Array.from(menu.querySelectorAll('button, a, [role="menuitem"]'))
      .map(node => (node.textContent || '').trim())
      .filter(Boolean) : [];

    return {
      ok: Boolean(menu),
      selectedItems,
      menuId,
      items
    };
  });

  await page.waitForTimeout(300);
  return opened;
}`);
}

function deleteSelectedCode(name) {
  return buildRunCode(`
async (page) => {
  const expectedName = ${name ? JSON.stringify(name) : 'null'};
  await page.bringToFront();
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(500);

  const selection = await page.evaluate((forcedName) => {
    const isVisible = (node) => {
      if (!node) {
        return false;
      }
      const style = window.getComputedStyle(node);
      const rect = node.getBoundingClientRect();
      return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
    };

    if (forcedName) {
      const forced = document.querySelector('[data-name="' + forcedName.replace(/"/g, '\\"') + '"]');
      if (forced) {
        forced.scrollIntoView({block: 'center'});
        forced.click();
      }
    }

    const selectedItems = Array.from(document.querySelectorAll('[data-name]'))
      .filter(node => {
        const cls = node.className || '';
        return isVisible(node) && (cls.includes('active') || cls.includes('page-editor__topper--active') || (node.getAttribute('aria-selected') || '') === 'true');
      })
      .map(node => node.getAttribute('data-name'))
      .filter(Boolean);

    return {selectedItems};
  }, expectedName);

  await page.waitForTimeout(500);

  const targetName = expectedName || selection.selectedItems[0] || null;
  if (!targetName) {
    return {ok: false, reason: 'no-selected-item', selectedItems: selection.selectedItems};
  }

  const opened = await page.evaluate(() => {
    const isVisible = (node) => {
      if (!node) {
        return false;
      }
      const style = window.getComputedStyle(node);
      const rect = node.getBoundingClientRect();
      return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
    };
    const buttons = Array.from(document.querySelectorAll('button[aria-label="Opcions"].btn-unstyled'))
      .filter(node => isVisible(node))
      .filter(node => ((node.parentElement?.parentElement?.className) || '').includes('page-editor__topper__item'));
    const button = buttons.at(-1);
    if (!button) {
      return {ok: false, reason: 'context-options-not-found'};
    }
    button.click();
    return {ok: true, menuId: button.getAttribute('aria-controls')};
  });

  if (!opened?.ok || !opened?.menuId) {
    return {ok: false, reason: opened?.reason || 'context-options-not-found', targetName};
  }

  await page.waitForTimeout(400);

  const deleteClicked = await page.evaluate((menuId) => {
    const menu = document.getElementById(menuId);
    const deleteNode = Array.from(menu?.querySelectorAll('button, a, [role="menuitem"]') || [])
      .find(node => ((node.textContent || '').trim()) === 'Esborra');
    deleteNode?.click();
    return Boolean(deleteNode);
  }, opened.menuId);

  if (!deleteClicked) {
    return {ok: false, reason: 'delete-menu-item-not-found', targetName, menuId: opened.menuId};
  }

  await page.waitForTimeout(600);

  await page.evaluate(() => {
    const isVisible = (node) => {
      if (!node) {
        return false;
      }
      const style = window.getComputedStyle(node);
      const rect = node.getBoundingClientRect();
      return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
    };
    const confirm = Array.from(document.querySelectorAll('button'))
      .filter(node => isVisible(node))
      .find(node => {
        if ((node.textContent || '').trim() !== 'Esborra') {
          return false;
        }
        return Boolean(node.closest('[role="dialog"], .modal, .modal-dialog'));
      });
    confirm?.click();
  });

  let gone = false;
  for (let i = 0; i < 20; i += 1) {
    await page.waitForTimeout(250);
    gone = await page.evaluate((nameToCheck) =>
      !Array.from(document.querySelectorAll('[data-name]')).some(node => node.getAttribute('data-name') === nameToCheck),
      targetName
    );
    if (gone) {
      break;
    }
  }

  const selectedAfter = await page.evaluate(() =>
    Array.from(document.querySelectorAll('[data-name]'))
      .filter(node => {
        const cls = node.className || '';
        return cls.includes('active') || cls.includes('page-editor__topper--active') || (node.getAttribute('aria-selected') || '') === 'true';
      })
      .map(node => node.getAttribute('data-name'))
      .filter(Boolean)
  );

  return {
    ok: gone,
    targetName,
    selectedBefore: selection.selectedItems,
    selectedAfter,
    gone
  };
}`);
}

function hideItemCode(name) {
  return buildRunCode(`
async (page) => {
  const targetName = ${JSON.stringify(name)};
  await page.bringToFront();
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(500);

  return await page.evaluate(async (forcedName) => {
    const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
    const isVisible = (node) => {
      if (!node) {
        return false;
      }
      const style = window.getComputedStyle(node);
      const rect = node.getBoundingClientRect();
      return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
    };
    const visibleDataNames = () => Array.from(document.querySelectorAll('[data-name]'))
      .filter(isVisible)
      .map(node => node.getAttribute('data-name') || '')
      .filter(Boolean);

    const target = Array.from(document.querySelectorAll('[data-name]'))
      .find(node => isVisible(node) && node.getAttribute('data-name') === forcedName);
    if (!target) {
      return {ok: false, reason: 'item-not-found', targetName: forcedName, visibleItems: visibleDataNames()};
    }

    target.scrollIntoView({block: 'center'});
    target.click();
    await sleep(500);

    const selectedItems = Array.from(document.querySelectorAll('[data-name]'))
      .filter(node => {
        const cls = node.className || '';
        return isVisible(node) && (cls.includes('active') || cls.includes('page-editor__topper--active') || (node.getAttribute('aria-selected') || '') === 'true');
      })
      .map(node => node.getAttribute('data-name'))
      .filter(Boolean);

    const buttons = Array.from(document.querySelectorAll('button[aria-label="Opcions"].btn-unstyled'))
      .filter(node => isVisible(node))
      .filter(node => ((node.parentElement?.parentElement?.className) || '').includes('page-editor__topper__item'));
    const button = buttons.at(-1);
    if (!button) {
      return {ok: false, reason: 'context-options-not-found', targetName: forcedName, selectedItems};
    }

    button.click();
    await sleep(300);

    const menuId = button.getAttribute('aria-controls');
    const menu = menuId ? document.getElementById(menuId) : null;
    const menuItems = Array.from(menu?.querySelectorAll('button, a, [role="menuitem"]') || [])
      .map(node => (node.textContent || '').trim())
      .filter(Boolean);
    const hideAction = Array.from(menu?.querySelectorAll('button, a, [role="menuitem"]') || [])
      .find(node => /Amaga el fragment|Hide fragment|Oculta el fragmento/i.test((node.textContent || '').trim()));

    if (!hideAction) {
      return {ok: false, reason: 'hide-action-not-found', targetName: forcedName, selectedItems, menuItems};
    }

    hideAction.click();
    await sleep(700);

    return {
      ok: true,
      targetName: forcedName,
      selectedItems,
      menuItems,
      action: (hideAction.textContent || '').trim(),
    };
  }, targetName);
}`);
}

function publishClickCode() {
  return buildRunCode(`
async (page) => {
  await page.bringToFront();
  await page.waitForLoadState('domcontentloaded');

  let clickResult = {clicked: false, strategy: 'not-found'};

  const stableButton = page.locator('.page-editor__theme-adapter-buttons button.btn.btn-sm.btn-primary[aria-label="Publica"]').first();
  if (await stableButton.count()) {
    try {
      await stableButton.click({noWaitAfter: true, timeout: 5000});
      clickResult = {clicked: true, strategy: 'stable-toolbar-selector'};
    } catch {
      clickResult = {clicked: false, strategy: 'stable-toolbar-selector-failed'};
    }
  }

  if (!clickResult.clicked) {
    const roleButton = page.getByRole('button', { name: 'Publica' }).first();
    if (await roleButton.count()) {
      try {
        await roleButton.click({noWaitAfter: true, timeout: 5000});
        clickResult = {clicked: true, strategy: 'role-button'};
      } catch {
        clickResult = {clicked: false, strategy: 'role-button-failed'};
      }
    }
  }

  return clickResult;
}`);
}

function publishStatusCode() {
  return buildRunCode(`
async (page) => {
  await page.bringToFront();
  await page.waitForLoadState('domcontentloaded').catch(() => null);

  return await page.evaluate(() => {
    const isVisible = (node) => {
      if (!node) {
        return false;
      }
      const style = window.getComputedStyle(node);
      const rect = node.getBoundingClientRect();
      return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
    };

    const buttons = Array.from(document.querySelectorAll('button')).filter(isVisible);
    const publishButton = buttons.find((node) => {
      const text = (node.textContent || '').trim();
      const aria = node.getAttribute('aria-label') || '';
      return /Publica|Publish|Publicar/i.test(text) || /Publica|Publish|Publicar/i.test(aria);
    });

    const toasts = Array.from(document.querySelectorAll('[role="alert"], .alert, .toast, .notification'))
      .filter(isVisible)
      .map((node) => (node.textContent || '').trim())
      .filter(Boolean);

    return {
      found: Boolean(publishButton),
      text: publishButton ? (publishButton.textContent || '').trim() : null,
      disabled: publishButton ? (publishButton.disabled || publishButton.hasAttribute('disabled')) : null,
      ariaLabel: publishButton ? (publishButton.getAttribute('aria-label') || '') : null,
      toastDetected: toasts.some((text) => /public|publish|actualitzat|actualizado|saved|desat/i.test(text)),
      toasts,
      href: location.href,
    };
  });
}`);
}

function runtimeProbeCode(targetUrl, screenshotPath) {
  return buildRunCode(`
async (page) => {
  const url = ${JSON.stringify(targetUrl)};
  const output = ${JSON.stringify(screenshotPath)};

  await page.goto(url, {waitUntil: 'domcontentloaded'});
  await page.waitForTimeout(5000);
  await page.screenshot({path: output, fullPage: true});

  return await page.evaluate(() => {
    const isVisible = (node) => {
      if (!node) {
        return false;
      }
      const style = window.getComputedStyle(node);
      const rect = node.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
    };

    const visibleText = Array.from(document.querySelectorAll('body *'))
      .filter(node => node.childElementCount === 0 && isVisible(node))
      .map(node => (node.textContent || '').trim())
      .filter(Boolean);

    const textBlob = visibleText.join('\\n');
    const resultAnchors = Array.from(document.querySelectorAll('a'))
      .filter(isVisible)
      .map(node => (node.textContent || '').trim())
      .filter(text => text.length > 10)
      .slice(0, 20);

    return {
      href: location.href,
      title: document.title,
      hasEmptyMessage: /No s[’']?han trobat resultats/i.test(textBlob),
      hasSearchBarPortlet: Array.from(document.querySelectorAll('.portlet-search-bar')).some(isVisible),
      hasSortPortlet: Array.from(document.querySelectorAll('.portlet-sort')).some(isVisible),
      hasUBDynamicPortlet: Array.from(document.querySelectorAll('[id^="portlet_es_ricoh_ub_share_search_contributor_UBDynamicCustomFilterValue"]')).some(isVisible),
      hasEpoch1970: /01\\/01\\/1970/.test(textBlob),
      visibleTextSample: visibleText.slice(0, 40),
      resultAnchors,
    };
  });
}`);
}

function printJson(payload) {
  console.log(JSON.stringify(payload, null, 2));
}

function runFile(globalArgs, rest) {
  const {options, positional} = parseOptions(rest);
  const file = options.file ?? positional[0];
  if (!file) {
    fail('Falta --file para run-file');
  }

  const sessionName = sessionNameFromArgs(globalArgs);
  if (sessionName) {
    ensureSessionReady(globalArgs, 'about:blank');
  }

  const absolute = path.resolve(file);
  const code = fs.readFileSync(absolute, 'utf8');
  const result = run('playwright-cli', [...withRepoConfig(globalArgs), 'run-code', code], {
    allowFailure: true,
    timeoutMs: DEFAULT_PLAYWRIGHT_TIMEOUT_MS,
  });

  process.stdout.write(result.stdout ?? '');
  process.stderr.write(result.stderr ?? '');
  process.exit(result.status ?? 1);
}

function runEditorLogin(globalArgs, pageUrl) {
  const ensured = ensureEditorSession(globalArgs, pageUrl);
  printJson({
    ok: true,
    baseUrl: ensured.baseUrl,
    editorUrl: ensured.editorUrl,
    status: ensured.status,
    initialState: ensured.initialState,
    session: ensured.session,
    draft: ensured.draft,
  });
}

function runEditorState(globalArgs, pageUrl, openEditor) {
  let editUrl = null;
  if (openEditor) {
    editUrl = gotoEditor(globalArgs, pageUrl);
  }
  ensureEditorLoaded(globalArgs);
  const state = runPlaywright(globalArgs, ['run-code', editorStateCode()]).result;
  printJson({
    ok: true,
    editorUrl: editUrl ?? state?.url ?? null,
    draft: state,
  });
}

function runPageState(globalArgs, pageUrl) {
  const editUrl = gotoEditor(globalArgs, pageUrl);
  ensureEditorLoaded(globalArgs);
  const draft = runPlaywright(globalArgs, ['run-code', editorStateCode()]).result;
  const liveExport = pageExport(pageUrl);
  const liveItems = collectLiveItems(liveExport);

  printJson({
    ok: true,
    editorUrl: editUrl,
    draft,
    live: {
      source: liveExport.source,
      itemsPresent: liveItems,
    },
  });
}

function runEditorSelectItem(globalArgs, pageUrl, name) {
  if (pageUrl) {
    gotoEditor(globalArgs, pageUrl);
  }
  ensureEditorLoaded(globalArgs);
  const result = runPlaywright(globalArgs, ['run-code', selectItemCode(name)]).result;
  printJson(result);
  process.exit(result?.ok ? 0 : 1);
}

function runEditorHideItem(globalArgs, pageUrl, name) {
  if (pageUrl) {
    gotoEditor(globalArgs, pageUrl);
  }
  ensureEditorLoaded(globalArgs);
  const result = runPlaywright(globalArgs, ['run-code', hideItemCode(name)]).result;
  printJson(result);
  process.exit(result?.ok ? 0 : 1);
}

function runEditorHideItems(globalArgs, pageUrl, names) {
  if (!names.length) {
    failTyped('tool', 'Falta al menos un --name para editor-hide-items', {
      reason: 'missing-name',
    });
  }
  if (pageUrl) {
    gotoEditor(globalArgs, pageUrl);
  }
  ensureEditorLoaded(globalArgs);
  const results = names.map((name) => runPlaywright(globalArgs, ['run-code', hideItemCode(name)]).result);
  const ok = results.every((result) => result?.ok);
  printJson({ok, results});
  process.exit(ok ? 0 : 1);
}

function runOpenSelectedItemOptions(globalArgs, pageUrl) {
  if (pageUrl) {
    gotoEditor(globalArgs, pageUrl);
  }
  ensureEditorLoaded(globalArgs);
  const result = runPlaywright(globalArgs, ['run-code', openSelectedOptionsCode()]).result;
  printJson(result);
  process.exit(result?.ok ? 0 : 1);
}

function runDeleteSelected(globalArgs, pageUrl, name) {
  if (pageUrl) {
    gotoEditor(globalArgs, pageUrl);
  }
  ensureEditorLoaded(globalArgs);
  const result = runPlaywright(globalArgs, ['run-code', deleteSelectedCode(name)]).result;
  printJson(result);
  process.exit(result?.ok ? 0 : 1);
}

function runPublishAndVerify(globalArgs, pageUrl, runtimeOptions = null) {
  const editUrl = gotoEditor(globalArgs, pageUrl);
  ensureEditorLoaded(globalArgs);
  const draftBefore = runPlaywright(globalArgs, ['run-code', editorStateCode()]).result;
  const liveBefore = pageExport(pageUrl);
  const publishClick = tryPlaywright(globalArgs, ['run-code', publishClickCode()], {
    timeoutMs: 10000,
  });

  let publishStatus = null;
  for (let attempt = 0; attempt < 30; attempt += 1) {
    sleep(500);
    const statusProbe = tryPlaywright(globalArgs, ['run-code', publishStatusCode()], {
      timeoutMs: SESSION_READY_TIMEOUT_MS,
    });
    if (statusProbe.ok && statusProbe.result) {
      publishStatus = statusProbe.result;
      if ((publishStatus.found && publishStatus.disabled) || publishStatus.toastDetected) {
        break;
      }
    }
  }

  let liveAfter = null;
  let liveUpdated = false;
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const waitResult = runPlaywright(globalArgs, ['run-code', buildRunCode(`
async (page) => {
  await page.waitForTimeout(1000);
  return true;
}`)]);
    void waitResult;
    liveAfter = pageExport(pageUrl);
    liveUpdated = JSON.stringify(liveAfter.headlessSitePage?.pageDefinition ?? {}) !== JSON.stringify(liveBefore.headlessSitePage?.pageDefinition ?? {});
    if (liveUpdated) {
      break;
    }
  }

  runPlaywright(globalArgs, ['goto', editUrl]);
  ensureEditorLoaded(globalArgs);
  const draftAfter = runPlaywright(globalArgs, ['run-code', editorStateCode()]).result;
  const draftCleared =
    draftAfter?.hasPendingChanges === false ||
    draftAfter?.discardDraftButton?.disabled === true ||
    publishStatus?.disabled === true;
  const publishClicked = publishClick.result?.clicked === true;
  const clickStrategy = publishClick.result?.strategy ?? null;
  const toastDetected = publishStatus?.toastDetected === true;
  const publishObserved = Boolean(publishClicked || toastDetected || liveUpdated);

  const payload = {
    ok: Boolean(publishObserved && draftCleared),
    editorUrl: editUrl,
    draftBefore,
    draftAfter,
    publishObserved,
    publishClicked,
    clickStrategy,
    toastDetected,
    publishStatus,
    draftCleared,
    liveUpdated,
  };

  if (runtimeOptions?.verifyRuntimeUrl) {
    payload.runtimeVerification = runRuntimeCheckOnce(
      globalArgs,
      runtimeOptions.verifyRuntimeUrl,
      runtimeOptions
    );
    payload.runtimeVerified =
      payload.runtimeVerification.assertions.emptyMatches &&
      payload.runtimeVerification.assertions.searchBarMatches &&
      payload.runtimeVerification.assertions.sortMatches &&
      payload.runtimeVerification.assertions.epoch1970Matches;

    runPlaywright(globalArgs, ['goto', editUrl]);
    ensureEditorLoaded(globalArgs);
    payload.editorStateAfterRuntimeCheck = runPlaywright(globalArgs, ['run-code', editorStateCode()]).result;
  }

  payload.successCriteria = {
    publishObserved,
    draftCleared,
    runtimeVerified: payload.runtimeVerified ?? null,
    liveUpdated,
  };

  if (!payload.ok || (payload.runtimeVerified ?? true) === false) {
    payload.ok = false;
    payload.failureKind = payload.runtimeVerified === false ? 'assertion' : 'navigation';
    payload.reason = payload.runtimeVerified === false ? 'runtime-assertions-failed' : 'publish-not-confirmed';
  }

  printJson(payload);
  process.exit(payload.ok && (payload.runtimeVerified ?? true) ? 0 : 1);
}

function normalizeArtifactsDir(artifactsDir) {
  return path.resolve(artifactsDir ?? path.join('.tmp', 'playwright-artifacts'));
}

function ensureDir(directory) {
  fs.mkdirSync(directory, {recursive: true});
}

function joinUrl(pageUrl, query) {
  const normalizedQuery = query.startsWith('?') ? query : `?${query}`;
  return `${pageUrl}${normalizedQuery}`;
}

function resolveRuntimeUrl(url, baseUrl) {
  if (url.startsWith('http://') || url.startsWith('https://')) {
    return url;
  }
  if (baseUrl) {
    return new URL(url, baseUrl).toString();
  }
  return `${baseUrlFor(url)}${url}`;
}

function matchExpectation(actual, expected) {
  if (expected === 'ignore' || expected === null || expected === undefined) {
    return true;
  }
  return actual === expected;
}

function runtimeExpectationsFromOptions(options, prefix = '') {
  return {
    baseUrl: options[`${prefix}base-url`] ?? null,
    expectEmpty: options[`${prefix}expect-empty`] ?? null,
    expectSearchBar: options[`${prefix}expect-search-bar`] ?? 'ignore',
    expectSort: options[`${prefix}expect-sort`] ?? 'ignore',
    expectEpoch1970: options[`${prefix}expect-epoch1970`] ?? null,
    artifactsDir: options[`${prefix}artifacts-dir`] ?? null,
    screenshotName: options[`${prefix}screenshot-name`] ?? null,
  };
}

function runRuntimeCheckOnce(globalArgs, pageUrl, options) {
  const targetUrl = resolveRuntimeUrl(pageUrl, options.baseUrl ?? null);
  const outputDir = normalizeArtifactsDir(options.artifactsDir ?? null);
  ensureDir(outputDir);
  const screenshotName = options.screenshotName ?? 'runtime-check.png';
  const screenshotPath = path.join(outputDir, screenshotName);

  ensureSessionReady(globalArgs, targetUrl);
  const result = runPlaywright(globalArgs, ['run-code', runtimeProbeCode(targetUrl, screenshotPath)], {
    timeoutMs: 45000,
  }).result;

  const expectedEmpty = options.expectEmpty === 'true' ? true : options.expectEmpty === 'false' ? false : null;
  const expectedSearchBar = options.expectSearchBar ?? 'ignore';
  const expectedSort = options.expectSort ?? 'ignore';
  const expectedEpoch1970 = options.expectEpoch1970 === 'true' ? true : options.expectEpoch1970 === 'false' ? false : null;

  return {
    ok: Boolean(result),
    result,
    screenshot: screenshotPath,
    assertions: {
      emptyMatches: matchExpectation(result?.hasEmptyMessage ?? null, expectedEmpty),
      searchBarMatches: matchExpectation(
        expectedSearchBar === 'visible' ? true : expectedSearchBar === 'hidden' ? false : null,
        expectedSearchBar === 'ignore' ? null : result?.hasSearchBarPortlet ?? null
      ),
      sortMatches: matchExpectation(
        expectedSort === 'visible' ? true : expectedSort === 'hidden' ? false : null,
        expectedSort === 'ignore' ? null : result?.hasSortPortlet ?? null
      ),
      epoch1970Matches: matchExpectation(result?.hasEpoch1970 ?? null, expectedEpoch1970),
    },
  };
}

function runRuntimeCheck(globalArgs, pageUrl, options) {
  const payload = runRuntimeCheckOnce(globalArgs, pageUrl, options);
  const assertionsOk =
    payload.assertions.emptyMatches &&
    payload.assertions.searchBarMatches &&
    payload.assertions.sortMatches &&
    payload.assertions.epoch1970Matches;

  if (!assertionsOk) {
    payload.ok = false;
    payload.failureKind = 'assertion';
    payload.reason = 'runtime-assertions-failed';
  }

  printJson(payload);
  process.exit(assertionsOk ? 0 : 1);
}

function main() {
  const {globalArgs, command, rest} = parseArgs(process.argv.slice(2));

  if (!command || command === '--help' || command === '-h' || command === 'help') {
    usage();
    return;
  }

  const sessionName = sessionNameFromArgs(globalArgs);
  if (sessionName) {
    acquireSessionLock(sessionName, command);
    process.on('exit', () => releaseSessionLock(sessionName));
    for (const signal of ['SIGINT', 'SIGTERM']) {
      process.on(signal, () => {
        releaseSessionLock(sessionName);
        process.exit(1);
      });
    }
  }

  if (command === 'run-file') {
    runFile(globalArgs, rest);
    return;
  }

  const {options} = parseOptions(rest);
  const hideNames = optionValues(rest, 'name');

  switch (command) {
    case 'ensure-session':
      ensureSessionCommand(globalArgs, ensureOption(options, 'kind'), ensureOption(options, 'url'));
      return;
    case 'ensure-editor-session':
    case 'ensure-editor-auth':
    case 'editor-login':
      runEditorLogin(globalArgs, ensureOption(options, 'url'));
      return;
    case 'page-state':
      runPageState(globalArgs, ensureOption(options, 'url'));
      return;
    case 'editor-state':
      runEditorState(globalArgs, ensureOption(options, 'url'), true);
      return;
    case 'editor-select-item':
      runEditorSelectItem(globalArgs, options.url, ensureOption(options, 'name'));
      return;
    case 'editor-hide-item':
      runEditorHideItem(globalArgs, options.url, ensureOption(options, 'name'));
      return;
    case 'editor-hide-items':
      runEditorHideItems(globalArgs, options.url, hideNames);
      return;
    case 'editor-open-selected-item-options':
      runOpenSelectedItemOptions(globalArgs, options.url);
      return;
    case 'editor-delete-selected':
      runDeleteSelected(globalArgs, options.url, options.name ?? null);
      return;
    case 'editor-publish-and-verify':
      runPublishAndVerify(
        globalArgs,
        ensureOption(options, 'url'),
        options['verify-runtime-url'] ? {
          verifyRuntimeUrl: options['verify-runtime-url'],
          ...runtimeExpectationsFromOptions(options, 'runtime-'),
        } : null
      );
      return;
    case 'runtime-check':
      runRuntimeCheck(globalArgs, ensureOption(options, 'url'), runtimeExpectationsFromOptions(options));
      return;
    default:
      fail(`Comando no soportado: ${command}`);
  }
}

main();
