#!/usr/bin/env node

const path = require('node:path');
const { readJsonFile, writeJsonFile } = require('./json-state');
const { loadConfig, nowIso } = require('./protocol');

const COMMANDS_DIR = path.join(__dirname, 'commands');
const COMMAND_INDEX_PATH = path.join(COMMANDS_DIR, 'index.json');
const PIPELINE_STATE_PATH = path.join(__dirname, 'state', 'pipeline-state.json');

const TERMINAL_COMMAND_STATUSES = new Set(['succeeded', 'failed', 'timed_out', 'cancelled']);

function readFlag(argv, flagName) {
  const index = argv.indexOf(flagName);
  if (index === -1 || !argv[index + 1]) {
    return null;
  }
  return argv[index + 1];
}

function parseArgs(argv) {
  return {
    reason: readFlag(argv, '--reason') || 'tick',
  };
}

async function loadActiveCommand() {
  const commandIndex = await readJsonFile(COMMAND_INDEX_PATH, {
    activeCommandId: null,
    lastCompletedCommandId: null,
    lastUpdatedAt: null,
  });

  if (!commandIndex.activeCommandId) {
    return { commandIndex, activeCommand: null };
  }

  const commandPath = path.join(COMMANDS_DIR, `${commandIndex.activeCommandId}.json`);
  const activeCommand = await readJsonFile(commandPath, null);
  return { commandIndex, activeCommand };
}

function buildNextCommand(state) {
  if (state.control && state.control.status === 'pending' && state.control.nextAction === 'dispatch_role') {
    return {
      type: 'dispatch_role_prompt',
      payload: {
        role: state.control.targetRole,
        taskId: state.control.taskId,
        prompt: state.control.prompt,
        transitionType: state.control.transitionType,
        questionId: state.control.questionId,
        reason: state.control.reason,
        sourcePath: state.control.sourcePath,
      },
    };
  }

  return {
    type: 'reconcile_pipeline',
    payload: {},
  };
}

async function enqueueCommand(commandIndex, nextCommand) {
  const id = `${Date.now()}-${nextCommand.type}`;
  const createdAt = nowIso();
  const command = {
    id,
    type: nextCommand.type,
    status: 'queued',
    createdAt,
    payload: nextCommand.payload,
  };
  await writeJsonFile(path.join(COMMANDS_DIR, `${id}.json`), command);
  await writeJsonFile(COMMAND_INDEX_PATH, {
    activeCommandId: id,
    lastCompletedCommandId: commandIndex.lastCompletedCommandId || null,
    lastUpdatedAt: createdAt,
  });
  return command;
}

async function run() {
  parseArgs(process.argv.slice(2));
  await loadConfig();
  const state = await readJsonFile(PIPELINE_STATE_PATH, null);
  const { commandIndex, activeCommand } = await loadActiveCommand();
  if (activeCommand && !TERMINAL_COMMAND_STATUSES.has(activeCommand.status)) {
    process.stdout.write(`${JSON.stringify({ ok: true, skipped: true, reason: 'active command still running' }, null, 2)}\n`);
    return;
  }
  const nextCommand = buildNextCommand(state || {});
  const queued = await enqueueCommand(commandIndex, nextCommand);
  process.stdout.write(`${JSON.stringify({ ok: true, queued }, null, 2)}\n`);
}

run().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.stack || error.message : String(error)}\n`);
  process.exitCode = 1;
});
