const path = require('node:path');
const { readJsonFile, writeJsonFile } = require('./json-state');

const REGISTRY_PATH = path.join(__dirname, 'state', 'role-session-registry.json');

async function loadSessionRegistry() {
  return readJsonFile(REGISTRY_PATH, { roles: {} });
}

async function saveSessionRegistry(registry) {
  return writeJsonFile(REGISTRY_PATH, registry);
}

module.exports = {
  REGISTRY_PATH,
  loadSessionRegistry,
  saveSessionRegistry,
};
