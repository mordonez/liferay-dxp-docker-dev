#!/usr/bin/env node

import path from 'node:path';
import {spawnSync} from 'node:child_process';

function usage() {
  console.log(`liferay machine wrapper

Usage:
  task liferay-machine -- <liferay-cli args>

Example:
  task liferay-machine -- page-layout diff --url /web/a/inici --reference-url /web/b/inici
`);
}

function parseJsonMaybe(raw) {
  const text = (raw ?? '').trim();
  if (!text.startsWith('{') && !text.startsWith('[')) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

const args = process.argv.slice(2);
if (!args.length || args.includes('--help') || args.includes('-h')) {
  usage();
  process.exit(0);
}

const repoRoot = process.cwd();
const script = path.join(repoRoot, 'tools', 'dev-env-cli', 'plugins', 'resources', 'liferay-cli.sh');
const result = spawnSync(script, args, {
  cwd: repoRoot,
  encoding: 'utf8',
  env: {
    ...process.env,
    REPO_ROOT: repoRoot,
  },
});

const stdout = result.stdout ?? '';
const stderr = result.stderr ?? '';
const exitCode = typeof result.status === 'number' ? result.status : 1;
const parsed = parseJsonMaybe(stdout);
const isPageLayoutDiff =
  args[0] === 'page-layout' &&
  args[1] === 'diff';
const failureKind =
  exitCode === 0 ? null :
  (stderr.includes('java.net.ConnectException') ? 'tool' :
    (isPageLayoutDiff && exitCode === 1 && parsed ? 'assertion' : 'tool'));
const reason =
  stderr.includes('java.net.ConnectException') ? 'connect-exception' :
  (isPageLayoutDiff && exitCode === 1 && parsed ? 'page-layout-differences-found' : null);

console.log(JSON.stringify({
  ok: exitCode === 0,
  exitCode,
  failureKind,
  reason,
  args,
  result: parsed,
  stdout: stdout.trim(),
  stderr: stderr.trim(),
}, null, 2));
