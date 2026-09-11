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
 * Three rules, over the whole tree:
 *   1. every class used by a page — its template, and the templates of every
 *      component it reaches through its imports (dialogs included) — is
 *      defined in a global partial, in the page's own stylesheets, or in the
 *      shell that hosts it;
 *   2. a class defined in a page's stylesheet is used by no component outside
 *      that page (or outside another page that attaches the same file);
 *   3. every stylesheet under `src/` is reached — imported by `styles.css` or
 *      named by a `styleUrl` — since a sheet nobody loads takes its classes
 *      out of the first two rules along with the screen's styling.
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
  const shells = [
    ...new Set(
      pages(byFile)
        .map((p) => p.shell)
        .filter(Boolean),
    ),
  ].map((file) => ({ file, shell: null }));
  const reached = new Set();
  const classesAndReach = (sheet) => {
    const seen = new Set();
    const classes = classesOf(sheet, seen);
    for (const f of seen) reached.add(f);
    return classes;
  };
  const globalClasses = classesAndReach(join(ROOT, 'styles.css'));
  const allDefined = new Set(globalClasses);
  const sheetClasses = new Map();
  for (const c of byFile.values()) {
    for (const s of c.styles) {
      if (!sheetClasses.has(s)) sheetClasses.set(s, classesAndReach(s));
      for (const k of sheetClasses.get(s)) allDefined.add(k);
    }
  }
  // rule 3: every stylesheet is loaded by something. A route sheet whose
  // `styleUrl` was dropped leaves the screen unstyled and its classes out of
  // `allDefined` at the same time — rules 1 and 2 go blind exactly when they
  // should shout, since a class nobody defines is never "out of scope".
  const orphans = walk(ROOT, [])
    .filter((f) => f.endsWith('.css') && !reached.has(f))
    .map((f) => `${relative(ROOT, f)} is loaded by nothing: neither styles.css nor a styleUrl`);
  for (const c of byFile.values()) {
    c.used = new Set(
      [...classesUsed(c.template), ...tokensInCode(c.ts, allDefined)].filter(isOurs),
    );
  }

  // rule 2: a page stylesheet's class used outside the pages that attach it.
  // A class only ever named next to another class of the same sheet
  // (`.carte-jour-pastille.etat-ferme`, `.fragilite-synthese .synthese-alerte`)
  // is scoped by that sheet and cannot reach another page: not a leak. A
  // sheet of an emulated-encapsulation component is scoped by Angular itself.
  const emulated = new Set();
  for (const c of byFile.values())
    if (!c.encapsulationNone) for (const s of c.styles) emulated.add(s);
  const scopedIn = new Map(); // sheet -> classes every selector qualifies by a sibling class of the sheet
  for (const sheet of sheetClasses.keys()) {
    const css = stripComments(readFileSync(sheet, 'utf8'));
    const own = sheetClasses.get(sheet);
    const bySelector = new Map();
    for (const block of css.matchAll(/([^{}]+)\{/g)) {
      for (const sel of block[1].split(',')) {
        const names = [...sel.matchAll(/\.([a-zA-Z][\w-]*)/g)].map((m) => m[1]);
        for (const c of names) {
          if (!bySelector.has(c)) bySelector.set(c, []);
          bySelector.get(c).push(names.filter((n) => n !== c));
        }
      }
    }
    const scoped = new Set();
    for (const [c, others] of bySelector) {
      if (others.every((list) => list.some((n) => own.has(n) && !globalClasses.has(n))))
        scoped.add(c);
    }
    scopedIn.set(sheet, scoped);
  }
  const pageOfComponent = new Map();
  for (const page of [...pages(byFile), ...shells])
    for (const f of closure(byFile, page.file)) {
      if (!pageOfComponent.has(f)) pageOfComponent.set(f, new Set());
      pageOfComponent.get(f).add(page.file);
    }
  // a class that is scoped wherever it is defined cannot be met by another page: rule 1 ignores it
  const unscoped = new Set(globalClasses);
  for (const [sheet, classes] of sheetClasses)
    for (const c of classes) if (!scopedIn.get(sheet)?.has(c)) unscoped.add(c);

  const failures = [];
  const usersOfSheet = new Map(); // stylesheet -> pages that attach it (through their closure)
  for (const page of [...pages(byFile), ...shells]) {
    const members = closure(byFile, page.file);
    const scope = new Set(globalClasses);
    const sheets = new Set();
    for (const f of members) for (const s of byFile.get(f).styles) sheets.add(s);
    if (page.shell) for (const s of byFile.get(page.shell).styles) sheets.add(s);
    for (const s of sheets) {
      for (const k of sheetClasses.get(s)) scope.add(k);
      if (!usersOfSheet.has(s)) usersOfSheet.set(s, new Set());
      usersOfSheet.get(s).add(page.file);
    }
    for (const f of members) {
      for (const c of byFile.get(f).used) {
        if (!scope.has(c) && unscoped.has(c)) {
          failures.push(
            `${relative(APP, page.file)} : ${relative(APP, f)} uses .${c}, defined only in a stylesheet this page does not load`,
          );
        }
      }
    }
  }
  for (const [sheet, owners] of usersOfSheet) {
    if (emulated.has(sheet)) continue;
    for (const c of sheetClasses.get(sheet) ?? []) {
      if (globalClasses.has(c) || scopedIn.get(sheet)?.has(c)) continue;
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
  failures.push(...orphans);
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
