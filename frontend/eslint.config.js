import { readdirSync } from 'node:fs';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

// 模块边界规则。改动边界即改动架构约定，需在变更说明里写明理由。
const FEATURES = readdirSync(new URL('./src/features', import.meta.url), { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name);
const TEST_FILES = ['**/__tests__/**', '**/*.test.{ts,tsx}'];

const noAppOrFeatures = {
  regex: '(^|/)(app|features)(/|$)',
  message: 'api / components / auth 是共享层，不能依赖 app 或 features。',
};
const noApp = {
  regex: '(^|/)app(/|$)',
  message: 'features 不能依赖 app（路由、布局）；路由只在 app/router.tsx 里引用页面。',
};
const noTestCode = {
  regex: '(^|/)__tests__(/|$)',
  message: '生产代码不能引用 __tests__ 下的测试代码。',
};
const noAxios = {
  regex: '^axios(/|$)',
  message: '统一使用 src/api/client.ts 封装的 fetch。',
};
const noOtherFeaturePages = (self) => ({
  regex: `(^|/)(${FEATURES.filter((name) => name !== self).join('|')})/pages(/|$)`,
  message: '不能引用其他 feature 的页面；跨 feature 优先复用它的 api 或 components。',
});

const restrictImports = (files, ignores, patterns) => ({
  files,
  ignores,
  rules: {
    'no-restricted-imports': ['error', { patterns }],
    'no-restricted-syntax': ['error', ...patterns.map(({ regex, message }) => ({
      selector: `ImportExpression[source.value=/${regex.replaceAll('/', '\\u002f')}/]`,
      message,
    }))],
  },
});

const sharedLayers = 'src/{api,components,auth}/**/*.{ts,tsx}';
const appLayer = ['src/app/**/*.{ts,tsx}', 'src/*.{ts,tsx}'];

export default tseslint.config(
  {
    ignores: ['dist/**', 'node_modules/**', 'playwright-report/**', 'test-results/**', 'coverage/**'],
  },
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
    },
  },

  restrictImports([sharedLayers], TEST_FILES, [noAppOrFeatures, noTestCode, noAxios]),
  restrictImports(TEST_FILES.map((glob) => `src/{api,components,auth}/${glob}`), [], [noAppOrFeatures]),
  restrictImports(appLayer, TEST_FILES, [noTestCode, noAxios]),
  ...FEATURES.flatMap((feature) => [
    restrictImports([`src/features/${feature}/**/*.{ts,tsx}`], TEST_FILES, [
      noApp,
      noOtherFeaturePages(feature),
      noTestCode,
      noAxios,
    ]),
    restrictImports(TEST_FILES.map((glob) => `src/features/${feature}/${glob}`), [], [
      noApp,
      noOtherFeaturePages(feature),
    ]),
  ]),

  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: [...TEST_FILES, 'src/api/client.ts'],
    languageOptions: {
      globals: { window: 'readonly', globalThis: 'readonly', self: 'readonly' },
    },
    rules: {
      'no-restricted-globals': [
        'error',
        {
          globals: [{ name: 'fetch', message: '只有 src/api/client.ts 可以直接调用 fetch，其余请求走 api 封装。' }],
          checkGlobalObject: true,
          globalObjects: ['window', 'globalThis', 'self'],
        },
      ],
    },
  },
);
