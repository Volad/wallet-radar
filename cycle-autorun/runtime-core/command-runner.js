#!/usr/bin/env node

const path = require('node:path');
const { spawn } = require('node:child_process');
const { appendTextFile, readJsonFile, writeJsonFile } = require('./json-state');
const { loadConfig, readQuestions, writeQuestions, nowIso } = require('./protocol');

const COMMANDS_DIR = path.join(__dirname, 'commands');
const COMMAND_INDEX_PATH = path.join(COMMANDS_DIR, 'index.json');
const DISPATCH_RESULT_PATH = path.join(__dirname, 'state', 'dispatch-result.json');
const DISPATCH_REQUESTS_DIR = path.join(__dirname, 'state', 'dispatch-requests');

function readFlag(argv, flagName) {
  const index = argv.indexOf(flagName);
  if (index === -1 || !argv[index + 1]) {
    return null;
  }
  return argv[index + 1];
}

function spawnNode(scriptPath, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [scriptPath, ...args], {
      cwd: path.resolve(__dirname, '..'),
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk.toString(); });
    child.stderr.on('data', (chunk) => { stderr += chunk.toString(); });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0) {
        resolve({ stdout, stderr });
        return;
      }
      reject(new Error(stderr || stdout || `${path.basename(scriptPath)} exited with code ${code}`));
    });
  });
}

async function loadCommand(commandId) {
  return readJsonFile(path.join(COMMANDS_DIR, `${commandId}.json`), null);
}

async function saveCommand(command) {
  return writeJsonFile(path.join(COMMANDS_DIR, `${command.id}.json`), command);
}

async function updateCommandIndex(activeCommandId, lastCompletedCommandId) {
  return writeJsonFile(COMMAND_INDEX_PATH, {
    activeCommandId,
    lastCompletedCommandId,
    lastUpdatedAt: nowIso(),
  });
}

async function applyQuestionMutation(config, command) {
  const cycle = Number(command.payload.cycle);
  const questions = await readQuestions(config, cycle);
  if (command.type === 'question_open') {
    questions.push({
      ...command.payload,
      createdAt: command.payload.createdAt || nowIso(),
      updatedAt: nowIso(),
    });
    await writeQuestions(config, cycle, questions);
    return { questionId: command.payload.id, action: 'opened' };
  }

  const index = questions.findIndex((item) => item.id === command.payload.id);
  if (index === -1) {
    throw new Error(`Question not found: ${command.payload.id}`);
  }
  const current = questions[index];

  if (command.type === 'question_answer') {
    questions[index] = {
      ...current,
      status: 'answered',
      answer: command.payload.answer,
      answeredAt: nowIso(),
      updatedAt: nowIso(),
    };
  } else if (command.type === 'question_resolve') {
    questions[index] = {
      ...current,
      status: 'resolved',
      resolvedAt: nowIso(),
      updatedAt: nowIso(),
    };
  } else if (command.type === 'question_cancel') {
    questions[index] = {
      ...current,
      status: 'cancelled',
      cancelledAt: nowIso(),
      updatedAt: nowIso(),
    };
  }

  await writeQuestions(config, cycle, questions);
  return { questionId: command.payload.id, action: command.type };
}

async function executeCommand(command) {
  const config = await loadConfig();
  if (command.type === 'reconcile_pipeline') {
    const result = await spawnNode(path.join(__dirname, 'dispatcher.js'), ['--mode', 'reconcile_pipeline']);
    return { result: result.stdout ? JSON.parse(result.stdout) : null };
  }

  if (command.type === 'dispatch_role_prompt') {
    const request = {
      requestId: command.id,
      role: command.payload.role,
      taskId: command.payload.taskId || null,
      prompt: command.payload.prompt,
      transitionType: command.payload.transitionType || null,
      questionId: command.payload.questionId || null,
      reason: command.payload.reason || null,
      sourcePath: command.payload.sourcePath || null,
      createdAt: nowIso(),
    };
    await writeJsonFile(path.join(DISPATCH_REQUESTS_DIR, `${request.requestId}.json`), request);
    await writeJsonFile(DISPATCH_RESULT_PATH, {
      status: 'queued',
      requestId: request.requestId,
      role: request.role,
      createdAt: request.createdAt,
    });
    return { request };
  }

  if (['question_open', 'question_answer', 'question_resolve', 'question_cancel'].includes(command.type)) {
    return applyQuestionMutation(config, command);
  }

  throw new Error(`Unsupported command type: ${command.type}`);
}

async function run() {
  const commandId = readFlag(process.argv.slice(2), '--id');
  const index = await readJsonFile(COMMAND_INDEX_PATH, { activeCommandId: null, lastCompletedCommandId: null, lastUpdatedAt: null });
  const effectiveId = commandId || index.activeCommandId;
  if (!effectiveId) {
    process.stdout.write(`${JSON.stringify({ ok: true, skipped: true, reason: 'no active command' }, null, 2)}\n`);
    return;
  }

  const command = await loadCommand(effectiveId);
  if (!command) {
    throw new Error(`Command not found: ${effectiveId}`);
  }

  command.status = 'running';
  command.startedAt = nowIso();
  await saveCommand(command);

  try {
    const result = await executeCommand(command);
    command.status = 'succeeded';
    command.completedAt = nowIso();
    command.result = result;
    await saveCommand(command);
    await updateCommandIndex(null, command.id);
    await appendTextFile(path.join(__dirname, 'state', 'decision-log.jsonl'), `${JSON.stringify({ at: nowIso(), command: command.id, type: command.type, status: command.status })}\n`);
    process.stdout.write(`${JSON.stringify({ ok: true, command }, null, 2)}\n`);
  } catch (error) {
    command.status = 'failed';
    command.completedAt = nowIso();
    command.error = error instanceof Error ? error.message : String(error);
    await saveCommand(command);
    await updateCommandIndex(null, command.id);
    throw error;
  }
}

run().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.stack || error.message : String(error)}\n`);
  process.exitCode = 1;
});
