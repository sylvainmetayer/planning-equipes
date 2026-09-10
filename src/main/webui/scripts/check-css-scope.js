#!/usr/bin/env node
'use strict';

/*
 * Every class a screen uses is defined in a stylesheet that screen loads.
 *
 * The CSS is split two ways (issue #392, B9): the partials of `src/styles/`
 * are global — `src/styles.css` imports them — and a routed page may carry
 * its own stylesheet (`styleUrl`, encapsulation `None`), loaded with its lazy
 * chunk and nowhere else. A class defined in a page's stylesheet and used by
 * another page is therefore styled or not depending on which route was
 * visited first: exactly the bug nothing else can see, since the unit tests
 * render without CSS and the eye only checks the route it is on.
 *
 * Two rules, over the whole tree:
 *   1. every class used by a page — its template, and the templates of every
 *      component it reaches through its imports (dialogs included) — is
 *      defined in a global partial, in the page's own stylesheets, or in the
 *      shell that hosts it;
 *   2. a class defined in a page's stylesheet is used by no component outside
 *      that page (or outside another page that attaches the same file).
 *
 * Static `class="…"`, `[class.x]` and the `[ngClass]` object keys are read
 * from the templates; the string literals of a component's TypeScript are
 * read too, so a class built in code (`'etat-' + etat`) is caught by its
 * prefix at least. Material's own classes are not ours to define.
 *
 *   node scripts/check-css-scope.js
 */
const { readdirSync, readFileSync, statSync, existsSync } = require('node:fs');
const { join, dirname, relative, resolve } = require('node:path');

const ROOT = join(__dirname, '..', 'src');
const APP = join(ROOT, 'app');
const OWN_PREFIXES = ['mat-', 'mdc-', 'cdk-', 'material-icons', 'ng-', 'leaflet-'];

function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else out.push(path);
  }
  return out;
}

const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, ' ');

/** Class names a stylesheet defines, `@import`s followed. */
function classesOf(cssPath, seen = new Set()) {
  if (seen.has(cssPath) || !existsSync(cssPath)) return new Set();
  seen.add(cssPath);
  const css = stripComments(readFileSync(cssPath, 'utf8'));
  const classes = new Set();
  for (const m of css.matchAll(/@import\s+(?:url\()?['"]?([^'")]+)['"]?\)?/g)) {
    for (const c of classesOf(resolve(dirname(cssPath), m[1]), seen)) classes.add(c);
  }
  for (const block of css.matchAll(/([^{}]+)\{/g)) {
    for (const c of block[1].matchAll(/\.([a-zA-Z][\w-]*)/g)) classes.add(c[1]);
  }
  return classes;
}

/** Class names a template uses: static attributes, `[class.x]`, `[ngClass]` keys. */
function classesUsed(template) {
  const used = new Set();
  for (const m of template.matchAll(/(?<![[(\w])class="([^"]*)"/g)) {
    for (const c of m[1].split(/\s+/)) if (c && !c.includes('{{')) used.add(c);
  }
  for (const m of template.matchAll(/\[class\.([\w-]+)\]/g)) used.add(m[1]);
  for (const m of template.matchAll(/\[ngClass\]="\{([^}]*)\}"/g)) {
    for (const k of m[1].matchAll(/'?([a-zA-Z][\w-]*)'?\s*:/g)) used.add(k[1]);
  }
  return used;
}

/** String-literal tokens of a TypeScript file that name a class defined somewhere. */
function tokensInCode(ts, known) {
  const used = new Set();
  const code = ts.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
  for (const m of code.matchAll(/(['"`])((?:\\.|(?!\1)[^\\\n])*)\1/g)) {
    for (const t of m[2].matchAll(/[a-zA-Z][\w-]*/g)) if (known.has(t[0])) used.add(t[0]);
  }
  return used;
}

/** Every component: its template, its stylesheets, the app files it imports. */
function components() {
  const byFile = new Map();
  for (const file of walk(APP, []).filter((f) => f.endsWith('.ts') && !f.endsWith('.spec.ts'))) {
    const ts = readFileSync(file, 'utf8');
    if (!/@Component\(/.test(ts)) continue;
    const dir = dirname(file);
    let template = '';
    const tplUrl = /templateUrl:\s*'([^']+)'/.exec(ts);
    if (tplUrl) template = readFileSync(resolve(dir, tplUrl[1]), 'utf8');
    const inline = /template:\s*`([\s\S]*?)`\s*,?\s*\n/.exec(ts);
    if (inline) template += inline[1];
    const styles = [];
    const one = /styleUrl:\s*'([^']+)'/.exec(ts);
    if (one) styles.push(resolve(dir, one[1]));
    const many = /styleUrls:\s*\[([^\]]*)\]/.exec(ts);
    if (many) for (const m of many[1].matchAll(/'([^']+)'/g)) styles.push(resolve(dir, m[1]));
    const imports = [];
    for (const m of ts.matchAll(/from\s+'(\.[^']+)'/g)) {
      const target = resolve(dir, m[1]) + '.ts';
      if (existsSync(target)) imports.push(target);
    }
    byFile.set(file, {
      file,
      ts,
      template,
      styles,
      imports,
      encapsulationNone: /ViewEncapsulation\.None/.test(ts),
    });
  }
  return byFile;
}

/** The routed pages, each with the shell that hosts it. */
function pages(byFile) {
  const routes = readFileSync(join(APP, 'app.routes.ts'), 'utf8');
  const shells = {
    admin: join(APP, 'shell', 'admin-shell.ts'),
    espace: join(APP, 'pages', 'espace-animateur', 'espace-animateur-shell.ts'),
  };
  const result = [];
  for (const m of routes.matchAll(/import\('(\.\/[^']+)'\)/g)) {
    const file = resolve(APP, m[1]) + '.ts';
    if (!byFile.has(file) || file === shells.admin || file === shells.espace) continue;
    const rel = relative(APP, file);
    const shell = rel.startsWith('pages/espace-animateur/')
      ? shells.espace
      : rel.startsWith('pages/login') || rel.startsWith('pages/mentions-legales')
        ? null
        : shells.admin;
    result.push({ file, shell });
  }
  return result;
}

/** A page and every app component it reaches through imports (dialogs included). */
function closure(byFile, start) {
  const seen = new Set();
  const stack = [start];
  while (stack.length) {
    const f = stack.pop();
    if (seen.has(f) || !byFile.has(f)) continue;
    seen.add(f);
    for (const dep of byFile.get(f).imports) stack.push(dep);
  }
  return seen;
}

const isOurs = (c) => !OWN_PREFIXES.some((p) => c.startsWith(p));

function main() {
  const byFile = components();
  const globalClasses = classesOf(join(ROOT, 'styles.css'));
  const allDefined = new Set(globalClasses);
  const sheetClasses = new Map();
  for (const c of byFile.values()) {
    for (const s of c.styles) {
      if (!sheetClasses.has(s)) sheetClasses.set(s, classesOf(s));
      for (const k of sheetClasses.get(s)) allDefined.add(k);
    }
  }
  for (const c of byFile.values()) {
    c.used = new Set(
      [...classesUsed(c.template), ...tokensInCode(c.ts, allDefined)].filter(isOurs),
    );
  }

  const failures = [];
  const usersOfSheet = new Map(); // stylesheet -> pages that attach it (through their closure)
  const shells = [
    ...new Set(
      pages(byFile)
        .map((p) => p.shell)
        .filter(Boolean),
    ),
  ].map((file) => ({ file, shell: null }));
  for (const page of [...pages(byFile), ...shells]) {
    const members = closure(byFile, page.file);
    const scope = new Set(globalClasses);
    const sheets = new Set();
    for (const f of members) for (const s of byFile.get(f).styles) sheets.add(s);
    if (page.shell) for (const s of byFile.get(page.shell).styles) sheets.add(s);
    for (const s of sheets) {
      for (const k of sheetClasses.get(s) ?? classesOf(s)) scope.add(k);
      if (!usersOfSheet.has(s)) usersOfSheet.set(s, new Set());
      usersOfSheet.get(s).add(page.file);
    }
    for (const f of members) {
      for (const c of byFile.get(f).used) {
        if (!scope.has(c) && allDefined.has(c)) {
          failures.push(
            `${relative(APP, page.file)} : ${relative(APP, f)} uses .${c}, defined only in a stylesheet this page does not load`,
          );
        }
      }
    }
  }
  // rule 2: a page stylesheet's class used outside the pages that attach it
  const pageOfComponent = new Map();
  for (const page of [...pages(byFile), ...shells])
    for (const f of closure(byFile, page.file)) {
      if (!pageOfComponent.has(f)) pageOfComponent.set(f, new Set());
      pageOfComponent.get(f).add(page.file);
    }
  for (const [sheet, owners] of usersOfSheet) {
    for (const c of sheetClasses.get(sheet) ?? []) {
      if (globalClasses.has(c)) continue;
      for (const comp of byFile.values()) {
        if (!comp.used.has(c)) continue;
        const its = pageOfComponent.get(comp.file) ?? new Set([comp.file]);
        if (![...its].some((p) => owners.has(p))) {
          failures.push(
            `${relative(ROOT, sheet)} defines .${c}, used by ${relative(APP, comp.file)} outside the pages that load it`,
          );
        }
      }
    }
  }
  if (failures.length) {
    console.error('check-css-scope : ' + failures.length + ' classe(s) hors de portée :');
    for (const f of [...new Set(failures)].sort()) console.error('  ' + f);
    process.exit(1);
  }
  console.log(
    `check-css-scope : ${byFile.size} composants, ${pages(byFile).length} routes, ${usersOfSheet.size} feuilles de route — chaque classe utilisée est définie là où l'écran la charge.`,
  );
}

main();
