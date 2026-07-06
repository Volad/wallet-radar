const target = process.env.API_PROXY_TARGET || 'http://localhost:8080';

const proxyConfig = {
  target,
  secure: false,
  changeOrigin: true,
  ws: true,
  logLevel: 'warn',
};

module.exports = {
  '/api': proxyConfig,
  '/oauth2': proxyConfig,
  '/login/oauth2': proxyConfig,
};
