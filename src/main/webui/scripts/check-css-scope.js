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
 * Static `class="…"`, `[class.x]`, the literals of `[class]="…"` and the
 * `[ngClass]` object keys are read from the templates; the string literals
 * of a component's TypeScript, and of the non-component modules of its
 * folder, are read too, so a class built in code (`'etat-' + etat`) is
 * caught by its prefix at least. Material's own classes are not ours to
 * define.
 *
 *   node scripts/check-css-scope.js
 */
const { readdirSync, readFileSync, statSync, existsSync } = require('node:fs');
const { join, dirname, relative, resolve } = require('node:path');
const { byCodeUnit } = require('./code-unit-order');

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

/**
 * The text before each `{`, back to the previous brace, when it is not empty —
 * what `/([^{}]+)\{/g` captured, without its quadratic retries on a run that
 * is not followed by a `{`.
 */
function selectorsBeforeBraces(css) {
  const pieces = css.split('{');
  pieces.pop();
  return pieces.map((piece) => piece.slice(piece.lastIndexOf('}') + 1)).filter((s) => s !== '');
}

/** Class names a stylesheet defines, `@import`s followed. */
function classesOf(cssPath, seen = new Set()) {
  if (seen.has(cssPath) || !existsSync(cssPath)) return new Set();
  seen.add(cssPath);
  const css = stripComments(readFileSync(cssPath, 'utf8'));
  const classes = new Set();
  for (const m of css.matchAll(/@import\s+(?:url\()?['"]?([^'")]+)['"]?\)?/g)) {
    for (const c of classesOf(resolve(dirname(cssPath), m[1]), seen)) classes.add(c);
  }
  for (const selector of selectorsBeforeBraces(css)) {
    for (const c of selector.matchAll(/\.([a-zA-Z][\w-]*)/g)) classes.add(c[1]);
  }
  return classes;
}

const isSpace = (ch) => /\s/.test(ch);
const isWordOrDash = (ch) => /[\w-]/.test(ch);

/**
 * The keys of an `[ngClass]` object body: before every `:`, an optional quote
 * and blanks, the run of word characters from its first letter — what
 * `/'?([a-zA-Z][\w-]*)'?\s*:/g` captured, scanned from each colon instead.
 */
function ngClassKeys(body) {
  const keys = [];
  for (let colon = body.indexOf(':'); colon >= 0; colon = body.indexOf(':', colon + 1)) {
    let end = colon;
    while (end > 0 && isSpace(body[end - 1])) end -= 1;
    if (end > 0 && body[end - 1] === "'") end -= 1;
    let start = end;
    while (start > 0 && isWordOrDash(body[start - 1])) start -= 1;
    const run = body.slice(start, end);
    const first = run.search(/[a-zA-Z]/);
    if (first >= 0) keys.push(run.slice(first));
  }
  return keys;
}

function addStaticClasses(template, used) {
  for (const m of template.matchAll(/(?<![[(\w])class="([^"]*)"/g)) {
    for (const c of m[1].split(/\s+/)) if (c && !c.includes('{{')) used.add(c);
  }
}

// `[class]="'heatmap-legend'"`, `[class]="'pastille ' + etat"`: the quoted
// words of the expression. A class built entirely in code is caught below.
function addBoundClassLiterals(template, used) {
  for (const m of template.matchAll(/\[class\]="([^"]*)"/g)) {
    for (const q of m[1].matchAll(/'([^']*)'/g)) {
      for (const c of q[1].split(/\s+/)) if (/^[a-zA-Z][\w-]*$/.test(c)) used.add(c);
    }
  }
}

/** Class names a template uses: static attributes, `[class.x]`, the literals of `[class]="…"`, `[ngClass]` keys. */
function classesUsed(template) {
  const used = new Set();
  addStaticClasses(template, used);
  for (const m of template.matchAll(/\[class\.([\w-]+)\]/g)) used.add(m[1]);
  addBoundClassLiterals(template, used);
  for (const m of template.matchAll(/\[ngClass\]="\{([^}]*)\}"/g)) {
    for (const k of ngClassKeys(m[1])) used.add(k);
  }
  return used;
}

/** String-literal tokens of a TypeScript file that name a class defined somewhere. */
function tokensInCode(ts, known) {
  const used = new Set();
  const code = ts.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
  for (const m of code.matchAll(/(['"`])((?:\\.|(?!\1)[^\\\n])*)\1/g)) {
    // A path is not a class: `import('./pages/login/login-page')` names the
    // file, not `.login-page`.
    if (m[2].includes('/')) continue;
    for (const t of m[2].matchAll(/[a-zA-Z][\w-]*/g)) if (known.has(t[0])) used.add(t[0]);
  }
  return used;
}

/**
 * Whether the characters from `index` on are blanks holding a newline, with at
 * most one comma among them before that newline — `\s*,?\s*\n`, without the
 * ambiguity of two adjacent `\s*`.
 */
function closesTemplateProperty(ts, index) {
  let i = index;
  let commaSeen = false;
  while (i < ts.length) {
    const ch = ts[i];
    if (ch === '\n') return true;
    if (ch === ',' && !commaSeen) commaSeen = true;
    else if (!isSpace(ch)) return false;
    i += 1;
  }
  return false;
}

/**
 * The inline `template:` of a component: from the backtick after `template:`
 * to the first backtick that closes the property (`\`,` then a newline) —
 * what `/template:\s*`([\s\S]*?)`\s*,?\s*\n/` captured.
 */
function inlineTemplate(ts) {
  for (const opening of ts.matchAll(/template:\s*`/g)) {
    const start = opening.index + opening[0].length;
    for (let tick = ts.indexOf('`', start); tick >= 0; tick = ts.indexOf('`', tick + 1)) {
      if (closesTemplateProperty(ts, tick + 1)) return ts.slice(start, tick);
    }
  }
  return null;
}

function templateOf(ts, dir) {
  let template = '';
  const tplUrl = /templateUrl:\s*'([^']+)'/.exec(ts);
  if (tplUrl) template = readFileSync(resolve(dir, tplUrl[1]), 'utf8');
  const inline = inlineTemplate(ts);
  if (inline !== null) template += inline;
  return template;
}

function stylesOf(ts, dir) {
  const styles = [];
  const one = /styleUrl:\s*'([^']+)'/.exec(ts);
  if (one) styles.push(resolve(dir, one[1]));
  const many = /styleUrls:\s*\[([^\]]*)\]/.exec(ts);
  if (many) for (const m of many[1].matchAll(/'([^']+)'/g)) styles.push(resolve(dir, m[1]));
  return styles;
}

function importsOf(ts, dir) {
  const imports = [];
  for (const m of ts.matchAll(/from\s+'(\.[^']+)'/g)) {
    const target = resolve(dir, m[1]) + '.ts';
    if (existsSync(target)) imports.push(target);
  }
  return imports;
}

/** Every component: its template, its stylesheets, the app files it imports. */
function components() {
  const byFile = new Map();
  for (const file of walk(APP, []).filter((f) => f.endsWith('.ts') && !f.endsWith('.spec.ts'))) {
    const ts = readFileSync(file, 'utf8');
    if (!/@Component\(/.test(ts)) continue;
    const dir = dirname(file);
    byFile.set(file, {
      file,
      ts,
      template: templateOf(ts, dir),
      styles: stylesOf(ts, dir),
      imports: importsOf(ts, dir),
      encapsulationNone: /ViewEncapsulation\.None/.test(ts),
    });
  }
  return byFile;
}

/** The shell hosting a routed page: the espace's, none for the public pages, else the admin one. */
function shellOf(rel, shells) {
  if (rel.startsWith('pages/espace-animateur/')) return shells.espace;
  if (rel.startsWith('pages/login') || rel.startsWith('pages/mentions-legales')) return null;
  return shells.admin;
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
    result.push({ file, shell: shellOf(relative(APP, file), shells) });
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

/** Adds `value` to the set `map` holds under `key`, creating it first. */
function addTo(map, key, value) {
  if (!map.has(key)) map.set(key, new Set());
  map.get(key).add(value);
}

/**
 * The classes of the global partials, of every component stylesheet, and the
 * files those reach through `@import` — the stylesheets something loads.
 */
function definedClasses(byFile) {
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
  return { reached, globalClasses, allDefined, sheetClasses };
}

// The classes a page builds in code often live in a sibling module that is
// not a component (`ouvertures.ts`, `carte-jour-map.ts`): every non-spec
// .ts of the component's folder is read for its string literals too.
function recordUsedClasses(byFile, allDefined) {
  const codeOfDir = new Map();
  const siblingCode = (dir) => {
    if (!codeOfDir.has(dir)) {
      codeOfDir.set(
        dir,
        readdirSync(dir)
          .filter((n) => n.endsWith('.ts') && !n.endsWith('.spec.ts') && !byFile.has(join(dir, n)))
          .map((n) => readFileSync(join(dir, n), 'utf8'))
          .join('\n'),
      );
    }
    return codeOfDir.get(dir);
  };
  for (const c of byFile.values()) {
    c.used = new Set(
      [
        ...classesUsed(c.template),
        ...tokensInCode(c.ts, allDefined),
        ...tokensInCode(siblingCode(dirname(c.file)), allDefined),
      ].filter(isOurs),
    );
  }
}

/** The classes of a sheet that every selector naming them qualifies by another class of that sheet. */
function scopedClassesOf(sheet, own, globalClasses) {
  const css = stripComments(readFileSync(sheet, 'utf8'));
  const bySelector = new Map();
  for (const selectors of selectorsBeforeBraces(css)) {
    for (const sel of selectors.split(',')) {
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
  return scoped;
}

/** Rule 1, page by page; fills `usersOfSheet` (stylesheet -> pages that attach it) on the way. */
function checkPageScopes(ctx, failures) {
  const { byFile, routed, globalClasses, sheetClasses, emulated, unscoped, usersOfSheet } = ctx;
  for (const page of routed) {
    const members = closure(byFile, page.file);
    const scope = new Set(globalClasses);
    const sheets = new Set();
    for (const f of members) for (const s of byFile.get(f).styles) sheets.add(s);
    // A shell sheet reaches its pages only when the shell renders it
    // unencapsulated: emulated, Angular rewrites its selectors to the shell's
    // own template, and a page using one of its classes gets nothing.
    if (page.shell)
      for (const s of byFile.get(page.shell).styles) if (!emulated.has(s)) sheets.add(s);
    for (const s of sheets) {
      for (const k of sheetClasses.get(s)) scope.add(k);
      addTo(usersOfSheet, s, page.file);
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
}

// A class only an emulated sheet defines, used outside the component that
// owns the sheet, is styled nowhere: the leak the commit that re-scoped the
// `.etat-*` classes fixed by hand, on the four templates it happened to see.
function checkEmulatedLeaks(ctx, failures) {
  const { byFile, globalClasses, sheetClasses, emulated } = ctx;
  const definedUnencapsulated = new Set(globalClasses);
  for (const [sheet, classes] of sheetClasses)
    if (!emulated.has(sheet)) for (const c of classes) definedUnencapsulated.add(c);
  for (const owner of byFile.values()) {
    for (const sheet of owner.styles.filter((s) => emulated.has(s))) {
      const leaking = [...(sheetClasses.get(sheet) ?? [])].filter(
        (c) => !definedUnencapsulated.has(c),
      );
      for (const c of leaking) {
        for (const comp of byFile.values()) {
          if (comp.file !== owner.file && comp.used.has(c)) {
            failures.push(
              `${relative(ROOT, sheet)} defines .${c} under emulated encapsulation, which ${relative(APP, comp.file)} uses: styled nowhere`,
            );
          }
        }
      }
    }
  }
}

/** Rule 2: a page stylesheet's class used outside the pages that attach it. */
function checkSheetLeaks(ctx, failures) {
  const { byFile, globalClasses, sheetClasses, emulated, scopedIn, pageOfComponent, usersOfSheet } =
    ctx;
  for (const [sheet, owners] of usersOfSheet) {
    if (emulated.has(sheet)) continue;
    const exposed = [...(sheetClasses.get(sheet) ?? [])].filter(
      (c) => !globalClasses.has(c) && !scopedIn.get(sheet)?.has(c),
    );
    for (const c of exposed) {
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
}

function main() {
  const byFile = components();
  const shells = [
    ...new Set(
      pages(byFile)
        .map((p) => p.shell)
        .filter(Boolean),
    ),
  ].map((file) => ({ file, shell: null }));
  const { reached, globalClasses, allDefined, sheetClasses } = definedClasses(byFile);
  // rule 3: every stylesheet is loaded by something. A route sheet whose
  // `styleUrl` was dropped leaves the screen unstyled and its classes out of
  // `allDefined` at the same time — rules 1 and 2 go blind exactly when they
  // should shout, since a class nobody defines is never "out of scope".
  const orphans = walk(ROOT, [])
    .filter((f) => f.endsWith('.css') && !reached.has(f))
    .map((f) => `${relative(ROOT, f)} is loaded by nothing: neither styles.css nor a styleUrl`);
  recordUsedClasses(byFile, allDefined);

  // rule 2: a page stylesheet's class used outside the pages that attach it.
  // A class only ever named next to another class of the same sheet
  // (`.carte-jour-pastille.etat-ferme`, `.fragilite-synthese .synthese-alerte`)
  // is scoped by that sheet and cannot reach another page: not a leak. A
  // sheet of an emulated-encapsulation component is scoped by Angular itself.
  const emulated = new Set();
  for (const c of byFile.values())
    if (!c.encapsulationNone) for (const s of c.styles) emulated.add(s);
  const scopedIn = new Map(); // sheet -> classes every selector qualifies by a sibling class of the sheet
  for (const [sheet, own] of sheetClasses) {
    scopedIn.set(sheet, scopedClassesOf(sheet, own, globalClasses));
  }
  const routed = [...pages(byFile), ...shells];
  const pageOfComponent = new Map();
  for (const page of routed)
    for (const f of closure(byFile, page.file)) addTo(pageOfComponent, f, page.file);
  // a class that is scoped wherever it is defined cannot be met by another page: rule 1 ignores it
  const unscoped = new Set(globalClasses);
  for (const [sheet, classes] of sheetClasses)
    for (const c of classes) if (!scopedIn.get(sheet)?.has(c)) unscoped.add(c);

  const ctx = {
    byFile,
    routed,
    globalClasses,
    sheetClasses,
    emulated,
    scopedIn,
    pageOfComponent,
    unscoped,
    usersOfSheet: new Map(),
  };
  const failures = [];
  checkPageScopes(ctx, failures);
  checkEmulatedLeaks(ctx, failures);
  checkSheetLeaks(ctx, failures);
  failures.push(...orphans);
  if (failures.length) {
    console.error('check-css-scope : ' + failures.length + ' classe(s) hors de portée :');
    for (const f of [...new Set(failures)].sort(byCodeUnit)) console.error('  ' + f);
    process.exit(1);
  }
  console.log(
    `check-css-scope : ${byFile.size} composants, ${pages(byFile).length} routes, ${ctx.usersOfSheet.size} feuilles de route — chaque classe utilisée est définie là où l'écran la charge.`,
  );
}

main();
