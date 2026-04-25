#!/usr/bin/env node

const path = require('node:path');
const { readJsonFile } = require('./json-state');

async function run() {
  const summary = await readJsonFile(path.join(__dirname, 'state', 'pipeline-summary.json'), null);
  if (!summary) {
    process.stdout.write('No summary available.\n');
    return;
  }

  const lines = [
    `Cycle: ${summary.cycle}`,
    `Status: ${summary.status}`,
    `Owner: ${summary.owner && summary.owner.role ? summary.owner.role : '-'}`,
    `Task: ${summary.owner && summary.owner.taskId ? summary.owner.taskId : '-'}`,
    `Goal: ${summary.goal && summary.goal.status ? summary.goal.status : '-'}`,
    `Decision: ${summary.decision && summary.decision.kind ? summary.decision.kind : '-'} - ${summary.decision && summary.decision.reason ? summary.decision.reason : '-'}`,
    `Open questions: ${summary.openQuestions && summary.openQuestions.total !== undefined ? summary.openQuestions.total : 0}`,
    `Waiting user: ${summary.openQuestions && summary.openQuestions.waitingUser !== undefined ? summary.openQuestions.waitingUser : 0}`,
    `Updated: ${summary.updatedAt || '-'}`,
  ];
  process.stdout.write(`${lines.join('\n')}\n`);
}

run().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.stack || error.message : String(error)}\n`);
  process.exitCode = 1;
});
