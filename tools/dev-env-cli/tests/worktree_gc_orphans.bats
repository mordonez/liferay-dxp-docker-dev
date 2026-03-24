#!/usr/bin/env bats

setup() {
  REPO_ROOT="$(cd "${BATS_TEST_DIRNAME}/../../.." && pwd)"
  TEST_ENVS_ROOT="$(mktemp -d)"
  ORPHAN_NAME="issue-bats-orphan-$$"
  mkdir -p "${TEST_ENVS_ROOT}/${ORPHAN_NAME}"
}

teardown() {
  rm -rf "${TEST_ENVS_ROOT}" >/dev/null 2>&1 || true
}

@test "worktree-gc reporta env huerfano en dry-run" {
  run env WORKTREE_BTRFS_ENVS_ROOT="${TEST_ENVS_ROOT}" \
    REPO_ROOT="${REPO_ROOT}" \
    bash "${REPO_ROOT}/tools/dev-env-cli/plugins/core/local-ops.sh" worktree-gc --days 0
  [ "$status" -eq 0 ]
  [[ "$output" == *"GC orphan env: ${ORPHAN_NAME}"* ]]
}

@test "worktree-clean elimina env huerfano especifico" {
  local TEST_MAIN_ROOT="${TEST_ENVS_ROOT}/main-repo"
  mkdir -p "${TEST_MAIN_ROOT}/docker"
  run env WORKTREE_BTRFS_ENVS_ROOT="${TEST_ENVS_ROOT}" \
    REPO_ROOT="${TEST_MAIN_ROOT}" \
    LOCAL_OPS_DOCKER_DIR="${TEST_MAIN_ROOT}/docker" \
    bash "${REPO_ROOT}/tools/dev-env-cli/plugins/core/local-ops.sh" worktree-clean "${ORPHAN_NAME}" --apply 2>&1
  [ "$status" -eq 0 ]
  [ ! -d "${TEST_ENVS_ROOT}/${ORPHAN_NAME}" ]
}
