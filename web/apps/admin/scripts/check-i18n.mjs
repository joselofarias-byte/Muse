import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const temp = mkdtempSync(join(root, '.i18n-test-'));
try {
  await build({ entryPoints: [join(root, 'src/i18n.ts')], outfile: join(temp, 'i18n.mjs'), bundle: true, platform: 'node', format: 'esm' });
  const { ADMIN_MESSAGES, adminLocale, t, monthlyPrice } = await import(pathToFileURL(join(temp, 'i18n.mjs')));
  for (const [key, entry] of Object.entries(ADMIN_MESSAGES)) {
    assert.ok(entry.es.trim(), key);
    assert.ok(!/[\u3400-\u9fff]/u.test(entry.es), key);
    const slots = value => [...value.matchAll(/\{\w+\}/g)].map(match => match[0]).sort();
    assert.deepEqual(slots(entry.es), slots(entry.zh), key);
  }
  assert.equal(adminLocale(['es-UY', 'zh-CN']), 'es');
  assert.equal(adminLocale(['zh-CN', 'es-UY']), 'zh');
  assert.equal(adminLocale([]), 'zh');
  assert.equal(t('requestFailed', { status: 503 }, ['es']), 'La solicitud falló (503)');
  assert.equal(t('editPlan', { name: '$& {status}' }, ['es']), 'Cambiar precio de $& {status}');
  assert.equal(t('login', {}, ['zh']), '登录');
  const price = monthlyPrice(12345, ['es-UY']);
  assert.ok(price.includes('123,45') && price.endsWith('/mes'), price);
  const main = readFileSync(join(root, 'src/main.tsx'), 'utf8');
  const api = readFileSync(join(root, 'src/api.ts'), 'utf8');
  assert.ok(!/[\u3400-\u9fff]/u.test(main + api), 'Admin copy must use the catalog');
  await build({ stdin: { contents: main.split('function App()')[0] + '\nexport { AdminLogin, Dashboard };', resolveDir: join(root, 'src'), loader: 'tsx' }, outfile: join(temp, 'components.mjs'), bundle: true, platform: 'node', format: 'esm', external: ['react', 'react/*', 'react-dom/*'], jsx: 'automatic', loader: { '.css': 'empty' }, define: { 'import.meta.env.VITE_API_BASE': '"http://localhost:8080"' } });
  const previousNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { languages: ['es-UY'] } });
  try {
    const { AdminLogin, Dashboard } = await import(pathToFileURL(join(temp, 'components.mjs')));
    const login = renderToStaticMarkup(createElement(AdminLogin, { onLogin() {} }));
    const dashboard = renderToStaticMarkup(createElement(Dashboard, { session: { token: 'test', userId: 'test', expiresAt: 0 }, onLogout() {} }));
    assert.ok(login.includes('Panel de administración') && login.includes('Correo del administrador'));
    assert.ok(dashboard.includes('Planes y precios') && dashboard.includes('centavos de yuan'));
    assert.ok(!/[\u3400-\u9fff]/u.test(login + dashboard));
  } finally {
    if (previousNavigator) Object.defineProperty(globalThis, 'navigator', previousNavigator);
    else delete globalThis.navigator;
  }
  console.log(`Admin i18n passed: ${Object.keys(ADMIN_MESSAGES).length} messages, placeholders, locale priority, CNY amounts and 2 Spanish screen renders.`);
} finally {
  rmSync(temp, { recursive: true, force: true });
}
