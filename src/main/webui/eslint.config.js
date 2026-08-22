// Mechanical defence of the conventions this frontend already follows.
//
// The audit measured them and found them intact — 69/69 components on OnPush,
// zero `@Input()`/`@Output()` decorators, zero `*ngIf`/`*ngFor`, zero `@for`
// without `track`, one `.subscribe()` and it is guarded. Nothing enforced any
// of it: they held by discipline alone, and would degrade the day someone else
// (or an agent) contributed. These rules are the invariants that would be
// expensive to lose, set to `error`; the rest of the recommended sets stays
// advisory so the file does not become noise to scroll past.

const eslint = require('@eslint/js');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

module.exports = tseslint.config(
  {
    // `coverage/**` is generated: the v8 reporter writes an HTML report whose
    // pages are neither Angular templates nor sources. Linting them yields
    // 150-odd errors about a report nobody wrote. CI never saw it because
    // lint runs before the tests that produce it — locally, the order is
    // whatever you happen to type.
    ignores: ['dist/**', 'node_modules/**', '.angular/**', 'coverage/**', 'src/version.ts']
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...angular.configs.tsRecommended
    ],
    processor: angular.processInlineTemplates,
    rules: {
      // Zoneless: a component rendering on the default strategy re-renders on
      // every application-wide check, which is exactly what this app avoids.
      '@angular-eslint/prefer-on-push-component-change-detection': 'error',
      // The function-based APIs are used everywhere; the decorators are not.
      '@angular-eslint/prefer-signals': 'error',
      '@angular-eslint/no-input-rename': 'error',
      '@angular-eslint/no-output-native': 'error',
      '@angular-eslint/use-lifecycle-interface': 'error',
      // `any` would undo the "zero any" state the audit measured.
      '@typescript-eslint/no-explicit-any': 'error',
      // `ignoreRestSiblings`: omitting a key by destructuring (`const {score,
      // ...rest} = x`) is the idiomatic way to build an `Omit<>` payload.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', ignoreRestSiblings: true }
      ],
      // Selector conventions of the repository.
      '@angular-eslint/component-selector': ['error', { type: 'element', prefix: 'app', style: 'kebab-case' }],
      '@angular-eslint/directive-selector': ['error', { type: 'attribute', prefix: 'app', style: 'camelCase' }]
    }
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended],
    rules: {
      // The single most common Angular performance-and-DOM-identity bug. The
      // audit found 0 occurrences over 39 templates; this keeps it that way.
      '@angular-eslint/template/use-track-by-function': 'error',
      // Accessibility rules worth failing on: this app is used by minors on
      // phones and has 177 aria-labels — the bar is already high.
      '@angular-eslint/template/alt-text': 'error',
      '@angular-eslint/template/elements-content': 'error',
      '@angular-eslint/template/label-has-associated-control': 'error',
      '@angular-eslint/template/valid-aria': 'error'
    }
  },
  {
    // Specs legitimately reach into privates and build partial fixtures.
    files: ['**/*.spec.ts', 'e2e/**/*.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      // Playwright *requires* the first test argument to be a destructuring
      // pattern, and `{}` is how a test declares it needs no fixture. Renaming
      // it to satisfy the linter makes the runner refuse to load the file
      // ("First argument must use the object destructuring pattern") — which is
      // exactly what happened, and what the e2e run caught.
      'no-empty-pattern': 'off'
    }
  }
);
