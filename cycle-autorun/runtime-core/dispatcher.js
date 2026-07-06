#!/usr/bin/env node

const path = require('node:path');
const { readTextFile, writeJsonFile, appendTextFile, statMtimeIso } = require('./json-state');
const {
  buildPrompt,
  getHandoffPath,
  getNextRole,
  getNextTaskId,
  getQuestionLogPath,
  getRoleConfig,
  getRoleOutputPath,
  isGoalReached,
  loadConfig,
  loadGoalStatus,
  nowIso,
  readQuestions,
  validateForwardHandoff,
} = require('./protocol');

const STATE_PATH = path.join(__dirname, 'state', 'pipeline-state.json');
const SUMMARY_PATH = path.join(__dirname, 'state', 'pipeline-summary.json');
const QUESTION_INDEX_PATH = path.join(__dirname, 'state', 'question-index.json');

function readFlag(argv, flagName) {
  const index = argv.indexOf(flagName);
  if (index === -1 || !argv[index + 1]) {
    return null;
  }
  return argv[index + 1];
}

function parseArgs(argv) {
  return {
    mode: readFlag(argv, '--mode') || 'reconcile_pipeline',
  };
}

async function loadPipelineState() {
  const { readJsonFile } = require('./json-state');
  return readJsonFile(STATE_PATH, null);
}

function normalizeState(raw, config) {
  const state = raw && typeof raw === 'object' ? raw : {};
  return {
    version: state.version || 1,
    runtime: state.runtime || config.runtime.name,
    updatedAt: nowIso(),
    currentCycle: Number(state.currentCycle || 1),
    status: state.status || 'idle',
    owner: {
      role: state.owner && state.owner.role ? state.owner.role : 'financial-analyst',
      taskId: state.owner && state.owner.taskId ? state.owner.taskId : (getRoleConfig(config, 'financial-analyst') || {}).taskId,
      sessionKey: state.owner ? state.owner.sessionKey || null : null,
      since: state.owner ? state.owner.since || null : null,
    },
    control: {
      status: 'idle',
      nextAction: 'none',
      targetRole: null,
      taskId: null,
      prompt: null,
      transitionType: null,
      questionId: null,
      reason: null,
      sourcePath: null,
      issuedAt: null,
      ...(state.control || {}),
    },
    decision: {
      kind: 'wait',
      reason: 'no decision yet',
      ...(state.decision || {}),
    },
    goal: {
      status: 'active',
      summary: '',
      path: null,
      reachedAt: null,
      ...(state.goal || {}),
    },
    observed: {
      lastMeaningfulSessionActivityAt: null,
      lastArtifactAt: null,
      lastHandoffAt: null,
      lastAssistantVisibleText: null,
      lastAssistantVisibleAt: null,
      wakeDue: false,
      resetDue: false,
      ...(state.observed || {}),
    },
    notes: Array.isArray(state.notes) ? state.notes : [],
  };
}

function buildQuestionIndex(questions) {
  const counts = {};
  for (const item of questions) {
    counts[item.status] = (counts[item.status] || 0) + 1;
  }
  return {
    total: questions.length,
    counts,
    openBlocking: questions.filter((item) => item.status === 'open' && item.blocking).length,
    waitingUser: questions.filter((item) => item.status === 'open' && item.blocking && item.to === 'user').length,
    updatedAt: nowIso(),
  };
}

function firstBlockingQuestionForTarget(questions, target) {
  return questions.find((item) => item.status === 'open' && item.blocking && item.to === target) || null;
}

async function buildRolePrompt(config, state, role, expectedCheckpoint) {
  const roleConfig = getRoleConfig(config, role) || {};
  return buildPrompt(config, 'dispatchRole', {
    role,
    taskId: roleConfig.taskId || state.owner.taskId || '',
    roleDescription: roleConfig.description || '',
    pipelineConfigPath: config.documents.pipelineConfig,
    roleDocPath: roleConfig.roleDoc || '',
    goalDocPath: config.documents.goal,
    handoffPolicyPath: config.documents.handoffPolicy,
    questionPolicyPath: config.documents.questionPolicy,
    goalPolicyPath: config.documents.goalPolicy,
    goalStatusPath: config.paths.goalStatusPattern.replace('{cycle}', String(state.currentCycle)),
    handoffPath: config.paths.handoffPattern
      .replace('{cycle}', String(state.currentCycle))
      .replace('{role}', role),
    questionLogPath: config.paths.questionLogPattern.replace('{cycle}', String(state.currentCycle)),
    roleOutputPath: config.paths.roleOutputPattern
      .replace('{cycle}', String(state.currentCycle))
      .replace('{role}', role),
    allowedQuestionTargets: Array.isArray(roleConfig.canAsk) ? roleConfig.canAsk.join(', ') : '',
    cycle: state.currentCycle,
    nextRole: getNextRole(config, role) || '-',
    expectedCheckpoint: expectedCheckpoint || roleConfig.description || 'Continue the current cycle batch until blocked, transfer-ready, or goal reached.',
  });
}

async function reconcilePipeline() {
  const config = await loadConfig();
  const rawState = await loadPipelineState();
  const state = normalizeState(rawState, config);
  const cycle = state.currentCycle;
  const owner = state.owner.role;
  const nextRole = getNextRole(config, owner);

  const goalStatus = await loadGoalStatus(config, cycle);
  state.goal = {
    status: goalStatus.status,
    summary: goalStatus.summary || '',
    path: config.paths.goalStatusPattern.replace('{cycle}', String(cycle)),
    reachedAt: goalStatus.updatedAt || null,
  };

  const questions = await readQuestions(config, cycle);
  const questionIndex = buildQuestionIndex(questions);
  await writeJsonFile(QUESTION_INDEX_PATH, questionIndex);

  if (config.runtime.stopOnGoalReached && isGoalReached(config, goalStatus)) {
    state.status = 'goal_reached';
    state.control = {
      status: 'idle',
      nextAction: 'none',
      targetRole: null,
      taskId: null,
      prompt: null,
      transitionType: null,
      questionId: null,
      reason: null,
      sourcePath: null,
      issuedAt: null,
    };
    state.decision = {
      kind: 'goal_reached',
      reason: `cycle goal already reached (${goalStatus.status})`,
    };
    state.notes = [state.decision.reason];
  } else {
    const userQuestion = firstBlockingQuestionForTarget(questions, 'user');
    if (userQuestion) {
      state.status = 'waiting_user_answer';
      state.control = {
        status: 'idle',
        nextAction: 'none',
        targetRole: null,
        taskId: null,
        prompt: null,
        transitionType: null,
        questionId: userQuestion.id,
        reason: 'waiting for user answer',
        sourcePath: config.paths.questionLogPattern.replace('{cycle}', String(cycle)),
        issuedAt: null,
      };
      state.decision = {
        kind: 'waiting_user_answer',
        reason: `blocking question ${userQuestion.id} is addressed to user`,
      };
      state.notes = [state.decision.reason];
    } else {
      const blockingRoleQuestion = questions.find((item) => item.status === 'open' && item.blocking && item.to !== 'user' && item.to !== owner) || null;
      if (blockingRoleQuestion) {
        const prompt = await buildPrompt(config, 'answerQuestion', {
          role: blockingRoleQuestion.to,
          questionId: blockingRoleQuestion.id,
          fromRole: blockingRoleQuestion.from,
          toRole: blockingRoleQuestion.to,
          cycle,
          questionText: blockingRoleQuestion.question,
          questionContext: blockingRoleQuestion.context || '',
          questionLogPath: config.paths.questionLogPattern.replace('{cycle}', String(cycle)),
        });
        state.status = 'question_routing_required';
        state.control = {
          status: 'pending',
          nextAction: 'dispatch_role',
          targetRole: blockingRoleQuestion.to,
          taskId: (getRoleConfig(config, blockingRoleQuestion.to) || {}).taskId || null,
          prompt,
          transitionType: 'question',
          questionId: blockingRoleQuestion.id,
          reason: `blocking question ${blockingRoleQuestion.id} must be answered by ${blockingRoleQuestion.to}`,
          sourcePath: config.paths.questionLogPattern.replace('{cycle}', String(cycle)),
          issuedAt: nowIso(),
        };
        state.decision = {
          kind: 'question_routing_required',
          reason: state.control.reason,
        };
        state.notes = [state.decision.reason];
      } else if (nextRole) {
        const handoffPath = getHandoffPath(config, cycle, nextRole);
        const handoffContent = await readTextFile(handoffPath, '');
        if (handoffContent.trim()) {
          const validation = validateForwardHandoff(config, owner, nextRole, cycle, handoffContent);
          if (validation.ready) {
            const prompt = await buildRolePrompt(config, state, nextRole, validation.parsed.nextRoleRequirements);
            state.status = 'transition_required';
            state.control = {
              status: 'pending',
              nextAction: 'dispatch_role',
              targetRole: nextRole,
              taskId: getNextTaskId(config, owner),
              prompt,
              transitionType: 'forward',
              questionId: null,
              reason: `validated handoff ${owner} -> ${nextRole}`,
              sourcePath: config.paths.handoffPattern
                .replace('{cycle}', String(cycle))
                .replace('{role}', nextRole),
              issuedAt: nowIso(),
            };
            state.decision = {
              kind: 'transition_required',
              reason: state.control.reason,
            };
            state.notes = [state.decision.reason];
          } else {
            const prompt = await buildPrompt(config, 'fixHandoff', {
              role: owner,
              cycle,
              nextRole,
              handoffPath: config.paths.handoffPattern
                .replace('{cycle}', String(cycle))
                .replace('{role}', nextRole),
              handoffPolicyPath: config.documents.handoffPolicy,
              handoffErrors: validation.errors.join('; '),
            });
            state.status = 'handoff_clarification_required';
            state.control = {
              status: 'pending',
              nextAction: 'dispatch_role',
              targetRole: owner,
              taskId: state.owner.taskId,
              prompt,
              transitionType: 'handoff_fix',
              questionId: null,
              reason: `handoff clarification required before ${owner} -> ${nextRole}`,
              sourcePath: config.paths.handoffPattern
                .replace('{cycle}', String(cycle))
                .replace('{role}', nextRole),
              issuedAt: nowIso(),
            };
            state.decision = {
              kind: 'handoff_clarification_required',
              reason: state.control.reason,
            };
            state.notes = validation.errors;
          }
        } else {
          const lastActivity = Date.parse(state.observed.lastMeaningfulSessionActivityAt || '');
          const wakeDue = !Number.isFinite(lastActivity) || (Date.now() - lastActivity) >= Number(config.runtime.wakeAfterMs || 300000);
          if (wakeDue) {
            const prompt = await buildRolePrompt(config, state, owner, state.owner.taskId ? `Continue ${state.owner.taskId} for cycle ${cycle}.` : null);
            state.status = 'wake_required';
            state.control = {
              status: 'pending',
              nextAction: 'dispatch_role',
              targetRole: owner,
              taskId: state.owner.taskId,
              prompt,
              transitionType: 'wake',
              questionId: null,
              reason: `${owner} has no transition-ready handoff and should continue current cycle work`,
              sourcePath: null,
              issuedAt: nowIso(),
            };
            state.decision = {
              kind: 'wake_required',
              reason: state.control.reason,
            };
            state.notes = [state.decision.reason];
          } else {
            state.status = 'idle';
            state.control = {
              status: 'idle',
              nextAction: 'none',
              targetRole: null,
              taskId: null,
              prompt: null,
              transitionType: null,
              questionId: null,
              reason: null,
              sourcePath: null,
              issuedAt: null,
            };
            state.decision = {
              kind: 'wait',
              reason: `${owner} has no transition-ready handoff yet`,
            };
            state.notes = [state.decision.reason];
          }
        }
      } else {
        state.status = 'idle';
        state.control = {
          status: 'idle',
          nextAction: 'none',
          targetRole: null,
          taskId: null,
          prompt: null,
          transitionType: null,
          questionId: null,
          reason: null,
          sourcePath: null,
          issuedAt: null,
        };
        state.decision = {
          kind: 'wait',
          reason: `no forward edge configured for ${owner}`,
        };
        state.notes = [state.decision.reason];
      }
    }
  }

  state.updatedAt = nowIso();
  await writeJsonFile(STATE_PATH, state);
  await writeJsonFile(SUMMARY_PATH, {
    cycle: cycle,
    status: state.status,
    owner: state.owner,
    goal: state.goal,
    decision: state.decision,
    openQuestions: questionIndex,
    updatedAt: state.updatedAt,
  });
  await appendTextFile(
    path.join(__dirname, 'state', 'decision-log.jsonl'),
    `${JSON.stringify({ at: state.updatedAt, cycle, decision: state.decision, status: state.status })}\n`
  );
  return state;
}

async function run() {
  const args = parseArgs(process.argv.slice(2));
  if (args.mode !== 'reconcile_pipeline') {
    throw new Error(`Unsupported mode: ${args.mode}`);
  }
  const state = await reconcilePipeline();
  process.stdout.write(`${JSON.stringify({ ok: true, state }, null, 2)}\n`);
}

run().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.stack || error.message : String(error)}\n`);
  process.exitCode = 1;
});
