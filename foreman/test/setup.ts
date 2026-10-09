// Foremans that tests start as child processes inherit this, so prompts and feed lines are
// deterministic regardless of the OS account name.
process.env.AGENTCRAFT_USER_NAME = 'Alex';

// Hermetic git: when the suite runs inside an AgentCraft agent, the agent's git-safety env
// (blocked push protocol, forced -c config, commit-signing off, ceiling dirs, author identity)
// leaks into the tests' git calls and breaks the ones that expect a plain git. Strip it so
// every test sees the same git no matter where vitest is started from.
const GIT_ENV_VARS = new Set([
  'GIT_ALLOW_PROTOCOL', 'GIT_CONFIG_COUNT', 'GIT_CONFIG_PARAMETERS', 'GIT_CEILING_DIRECTORIES',
  'GIT_EDITOR', 'GCM_INTERACTIVE', 'GIT_TERMINAL_PROMPT',
  // same list as GIT_REDIRECT_VARS in src/gitsafety.ts
  'GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR', 'GIT_OBJECT_DIRECTORY',
  'GIT_ALTERNATE_OBJECT_DIRECTORIES', 'GIT_NAMESPACE', 'GIT_DISCOVERY_ACROSS_FILESYSTEM',
  'GIT_CONFIG', 'GIT_REPLACE_REF_BASE', 'GIT_SHALLOW_FILE', 'GIT_GRAFT_FILE',
]);
for (const k of Object.keys(process.env)) {
  const u = k.toUpperCase();
  if (GIT_ENV_VARS.has(u) || /^GIT_CONFIG_(KEY|VALUE)_\d+$/.test(u) || /^GIT_(AUTHOR|COMMITTER)_/.test(u)) delete process.env[k];
}
