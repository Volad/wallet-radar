const fs = require('node:fs/promises');
const path = require('node:path');

function isRecoverableJsonError(error) {
  return error instanceof SyntaxError || (error && error.code === 'ENOENT');
}

async function readJsonFile(filePath, fallback = null) {
  try {
    const raw = await fs.readFile(filePath, 'utf8');
    if (!raw.trim()) {
      return fallback;
    }
    return JSON.parse(raw);
  } catch (error) {
    if (isRecoverableJsonError(error)) {
      return fallback;
    }
    throw error;
  }
}

async function writeJsonFile(filePath, value) {
  const directoryPath = path.dirname(filePath);
  const tempPath = path.join(directoryPath, `.${path.basename(filePath)}.${process.pid}.${Date.now()}.tmp`);
  await fs.mkdir(directoryPath, { recursive: true });
  try {
    await fs.writeFile(tempPath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
    await fs.rename(tempPath, filePath);
  } catch (error) {
    await fs.unlink(tempPath).catch(() => {});
    throw error;
  }
}

async function writeTextFile(filePath, value) {
  const directoryPath = path.dirname(filePath);
  await fs.mkdir(directoryPath, { recursive: true });
  await fs.writeFile(filePath, value, 'utf8');
}

async function readTextFile(filePath, fallback = '') {
  try {
    return await fs.readFile(filePath, 'utf8');
  } catch (error) {
    if (error && error.code === 'ENOENT') {
      return fallback;
    }
    throw error;
  }
}

async function appendTextFile(filePath, value) {
  const directoryPath = path.dirname(filePath);
  await fs.mkdir(directoryPath, { recursive: true });
  await fs.appendFile(filePath, value, 'utf8');
}

async function statMtimeIso(filePath) {
  try {
    const stat = await fs.stat(filePath);
    return stat.mtime.toISOString();
  } catch {
    return null;
  }
}

module.exports = {
  appendTextFile,
  readJsonFile,
  readTextFile,
  statMtimeIso,
  writeJsonFile,
  writeTextFile,
};
