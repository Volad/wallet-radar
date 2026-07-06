const fs = require('node:fs/promises');
const path = require('node:path');
const { readJsonFile, readTextFile, writeTextFile } = require('./json-state');

const ROOT = path.resolve(__dirname, '..');
const CONFIG_PATH = path.join(ROOT, 'pipeline.config.json');

function nowIso() {
  return new Date().toISOString();
}

function resolveRoot(relativePath) {
  return path.join(ROOT, relativePath);
}

async function loadConfig() {
  return readJsonFile(CONFIG_PATH, null);
}

function formatPattern(pattern, values) {
  return String(pattern || '').replace(/\{([^}]+)\}/g, (_, key) => {
    const value = values[key];
    return value === undefined || value === null ? '' : String(value);
  });
}

function getRoleConfig(config, role) {
  return (config && config.roles && config.roles[role]) || null;
}

function getNextRole(config, role) {
  const edges = (config && config.workflow && config.workflow.forwardEdges) || [];
  const edge = edges.find((item) => item.from === role);
  return edge ? edge.to : null;
}

function getNextTaskId(config, role) {
  const edges = (config && config.workflow && config.workflow.forwardEdges) || [];
  const edge = edges.find((item) => item.from === role);
  return edge ? edge.taskId : null;
}

function getGoalStatusPath(config, cycle) {
  return resolveRoot(formatPattern(config.paths.goalStatusPattern, { cycle }));
}

function getQuestionLogPath(config, cycle) {
  return resolveRoot(formatPattern(config.paths.questionLogPattern, { cycle }));
}

function getHandoffPath(config, cycle, role) {
  return resolveRoot(formatPattern(config.paths.handoffPattern, { cycle, role }));
}

function getRoleOutputPath(config, cycle, role) {
  return resolveRoot(formatPattern(config.paths.roleOutputPattern, { cycle, role }));
}

async function readQuestions(config, cycle) {
  const filePath = getQuestionLogPath(config, cycle);
  const raw = await readTextFile(filePath, '');
  return raw
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
}

async function writeQuestions(config, cycle, questions) {
  const filePath = getQuestionLogPath(config, cycle);
  const payload = questions.map((item) => JSON.stringify(item)).join('\n');
  await writeTextFile(filePath, payload ? `${payload}\n` : '');
}

function renderTemplate(template, variables) {
  return String(template || '').replace(/\{\{\s*([a-zA-Z0-9_]+)\s*\}\}/g, (_, key) => {
    const value = variables[key];
    return value === undefined || value === null ? '' : String(value);
  });
}

async function buildPrompt(config, templateKey, variables) {
  const relativePath = config.promptTemplates[templateKey];
  if (!relativePath) {
    return '';
  }
  const template = await readTextFile(resolveRoot(relativePath), '');
  return renderTemplate(template, variables);
}

function extractHandoffSection(content, heading) {
  const text = String(content || '');
  const escapedHeading = heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = text.match(new RegExp(`^##\\s+${escapedHeading}\\s*$([\\s\\S]*?)(?=^##\\s+|\\Z)`, 'mi'));
  if (!match) {
    return null;
  }
  return match[1]
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .join(' ')
    .trim() || null;
}

function detectInlineHeader(content, headerName) {
  const text = String(content || '');
  const escapedHeader = headerName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = text.match(new RegExp(`^-\\s+${escapedHeader}:\\s*(.+)$`, 'mi'));
  return match ? match[1].trim().replace(/^`+|`+$/g, '').trim() : null;
}

function parseHandoff(content) {
  return {
    status: detectInlineHeader(content, 'Status'),
    task: detectInlineHeader(content, 'Task'),
    transition: detectInlineHeader(content, 'Transition'),
    cycle: detectInlineHeader(content, 'Cycle'),
    inputBasis: detectInlineHeader(content, 'Input basis'),
    previousOwner: detectInlineHeader(content, 'Previous owner'),
    nextOwner: detectInlineHeader(content, 'Next owner'),
    summary: extractHandoffSection(content, 'Summary'),
    nextRoleRequirements: extractHandoffSection(content, 'Next role requirements'),
    artifactReferences: extractHandoffSection(content, 'Artifact references'),
    notes: extractHandoffSection(content, 'Notes'),
  };
}

function parseArtifactLinks(sectionText) {
  return String(sectionText || '')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => /cycle-data\/cycle\//.test(line) || /`[^`]+`/.test(line));
}

function validateForwardHandoff(config, owner, nextRole, cycle, content) {
  const parsed = parseHandoff(content);
  const errors = [];
  if (!parsed.status) {
    errors.push('missing Status header');
  } else if (parsed.status !== config.handoff.incomingForwardStatus) {
    errors.push(`wrong status: expected ${config.handoff.incomingForwardStatus}, got ${parsed.status}`);
  }
  if (String(parsed.cycle || '') !== String(cycle)) {
    errors.push(`wrong cycle: expected ${cycle}, got ${parsed.cycle || 'missing'}`);
  }
  if (parsed.previousOwner !== owner) {
    errors.push(`wrong Previous owner: expected ${owner}, got ${parsed.previousOwner || 'missing'}`);
  }
  if (parsed.nextOwner !== nextRole) {
    errors.push(`wrong Next owner: expected ${nextRole}, got ${parsed.nextOwner || 'missing'}`);
  }
  if (!parsed.summary) {
    errors.push('missing Summary section');
  }
  if (!parsed.nextRoleRequirements) {
    errors.push('missing Next role requirements section');
  }
  if (!parsed.artifactReferences) {
    errors.push('missing Artifact references section');
  }
  if (parseArtifactLinks(parsed.artifactReferences).length === 0) {
    errors.push('Artifact references section must contain at least one artifact link');
  }
  return {
    ready: errors.length === 0,
    errors,
    parsed,
  };
}

async function loadGoalStatus(config, cycle) {
  const goalStatusPath = getGoalStatusPath(config, cycle);
  return readJsonFile(goalStatusPath, {
    cycle,
    status: config.goal.defaultStatus,
    summary: '',
    updatedAt: null,
  });
}

function isGoalReached(config, goalStatus) {
  const terminal = new Set((config.goal && config.goal.terminalStatuses) || []);
  return terminal.has(String((goalStatus && goalStatus.status) || '').trim());
}

module.exports = {
  CONFIG_PATH,
  ROOT,
  buildPrompt,
  formatPattern,
  getGoalStatusPath,
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
  parseArtifactLinks,
  parseHandoff,
  readQuestions,
  resolveRoot,
  validateForwardHandoff,
  writeQuestions,
};
