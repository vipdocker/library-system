import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { ESLint } from 'eslint';

const eslint = new ESLint({ cwd: fileURLToPath(new URL('.', import.meta.url)) });

const blockedImports = [
  ['src/components/probe.ts', '../features/catalog/api'],
  ['src/auth/probe.ts', '../app/router'],
  ['src/features/stats/probe.ts', '../../app/menu'],
  ['src/features/stats/probe.ts', '../catalog/pages/BookSearchPage'],
  ['src/features/stats/probe.ts', './__tests__/fixtures'],
  ['src/features/stats/probe.ts', 'axios'],
];

for (const [filePath, target] of blockedImports) {
  for (const source of [
    `import * as value from '${target}'; export { value };`,
    `export * from '${target}';`,
    `export const load = () => import('${target}');`,
  ]) {
    test(`blocks ${source} in ${filePath}`, async () => {
      const [result] = await eslint.lintText(source, { filePath });
      assert.ok(result.messages.some(({ ruleId }) =>
        ruleId === 'no-restricted-imports' || ruleId === 'no-restricted-syntax'));
    });
  }
}

for (const expression of ['fetch', 'window.fetch', 'globalThis.fetch', 'self.fetch']) {
  test(`blocks ${expression} outside the HTTP client`, async () => {
    const [result] = await eslint.lintText(`export const load = () => ${expression}('/api/books');`, {
      filePath: 'src/features/catalog/api.ts',
    });
    assert.ok(result.messages.some(({ ruleId }) => ruleId === 'no-restricted-globals'));
  });
}

const allowedImports = [
  ['src/app/probe.ts', '../features/catalog/pages/BookSearchPage'],
  ['src/features/circulation/probe.ts', '../catalog/api'],
  ['src/features/stats/probe.ts', '../dunning/components/DunningRegistrationModal'],
  ['src/features/stats/__tests__/probe.test.ts', '../../dunning/__tests__/fixtures'],
];

for (const [filePath, target] of allowedImports) {
  test(`allows lazy loading ${target} in ${filePath}`, async () => {
    const [result] = await eslint.lintText(`export const load = () => import('${target}');`, { filePath });
    assert.equal(result.errorCount, 0, JSON.stringify(result.messages));
  });
}

for (const filePath of ['src/api/client.ts', 'src/api/__tests__/probe.test.ts']) {
  test(`allows fetch in ${filePath}`, async () => {
    const [result] = await eslint.lintText("export const load = () => fetch('/api/books');", { filePath });
    assert.equal(result.errorCount, 0, JSON.stringify(result.messages));
  });
}
