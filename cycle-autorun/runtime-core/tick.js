#!/usr/bin/env node

const path = require('node:path');
const { spawn } = require('node:child_process');

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

async function run() {
  const dispatcherResult = await spawnNode(path.join(__dirname, 'dispatcher.js'), ['--mode', 'reconcile_pipeline']);
  const controllerResult = await spawnNode(path.join(__dirname, 'controller.js'), ['--reason', 'tick']);
  process.stdout.write(JSON.stringify({
    ok: true,
    dispatcher: dispatcherResult.stdout ? JSON.parse(dispatcherResult.stdout) : null,
    controller: controllerResult.stdout ? JSON.parse(controllerResult.stdout) : null,
  }, null, 2));
  process.stdout.write('\n');
}

run().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.stack || error.message : String(error)}\n`);
  process.exitCode = 1;
});
