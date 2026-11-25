// Quick search of the whole site, opened by Ctrl+K (⌘K), "/" or the header button.
// One index (/search.json), fetched on the first opening: the docs guides and their
// sections first, then the pages of the showcase, all in the language of the page.
// The docs home also reads ?q= of its own form, with the same search.
const normalize = (text) => text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
const MAX = 12;

// Every word must appear; guides, then their sections, then pages, all of `lang`
export function search(index, query, lang) {
  const words = normalize(query).split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  const has = (text) => {
    const flat = normalize(text);
    return words.every((w) => flat.includes(w));
  };
  const guides = [];
  const sections = [];
  const pages = [];
  for (const e of index) {
    if (e.l !== lang) continue;
    if (e.k === 'doc') {
      if (has(`${e.t} ${e.d}`)) guides.push({ kind: 'guide', href: e.u, title: e.t, text: e.d, lang });
      for (const [id, h] of e.h) if (has(h)) sections.push({ kind: 'section', href: `${e.u}#${id}`, title: h, text: e.t, lang });
    } else if (has(`${e.t} ${e.d}`)) pages.push({ kind: 'page', href: e.u, title: e.t, text: e.d, lang });
  }
  return [...guides, ...sections, ...pages].slice(0, MAX);
}

const dialog = globalThis.document?.querySelector('[data-pal]');

if (dialog && typeof dialog.showModal === 'function') {
  const input = dialog.querySelector('input');
  const list = dialog.querySelector('[role="listbox"]');
  const status = dialog.querySelector('[role="status"]');
  const t = dialog.dataset;
  const lang = document.documentElement.lang;
  let index;
  let opener = null;
  let results = [];
  let active = -1;

  // null when the index cannot be fetched; a failure is not kept, the next opening tries again
  const load = () => {
    index ??= fetch('/search.json')
      .then((r) => (r.ok ? r.json() : null))
      .catch(() => null)
      .then((json) => {
        if (!json) index = undefined;
        return json;
      });
    return index;
  };

  const say = (n, q) => {
    if (!q.trim()) return t.tAide;
    return (n === 0 ? t.tAucun : n === 1 ? t.tUn : t.tPlusieurs).replace('{n}', n).replace('{q}', q.trim());
  };

  const mark = (i) => {
    list.querySelector('[aria-selected="true"]')?.setAttribute('aria-selected', 'false');
    active = i;
    const li = list.children[i];
    if (li) {
      li.setAttribute('aria-selected', 'true');
      li.scrollIntoView({ block: 'nearest' });
      input.setAttribute('aria-activedescendant', li.id);
    } else input.removeAttribute('aria-activedescendant');
  };

  const run = async () => {
    const q = input.value;
    const data = await load();
    if (q !== input.value) return; // a later keystroke owns the list
    if (!data) {
      status.textContent = t.tErreur;
      return;
    }
    const found = search(data, q, lang);
    results = found;
    list.replaceChildren(
      ...found.map((r, i) => {
        const li = document.createElement('li');
        li.id = `pal-${i}`;
        li.setAttribute('role', 'option');
        li.setAttribute('aria-selected', 'false');
        li.lang = r.lang;
        const kind = document.createElement('span');
        kind.className = 'pal__kind';
        kind.textContent = t[`t${r.kind[0].toUpperCase()}${r.kind.slice(1)}`];
        const title = document.createElement('span');
        title.className = 'pal__title';
        title.textContent = r.title;
        const text = document.createElement('span');
        text.className = 'pal__text';
        text.textContent = r.text;
        li.append(kind, title, text);
        li.addEventListener('click', () => go(i));
        return li;
      }),
    );
    list.hidden = !found.length;
    input.setAttribute('aria-expanded', String(found.length > 0));
    mark(found.length ? 0 : -1);
    status.textContent = say(found.length, q);
  };

  const go = (i) => {
    const r = results[i];
    if (!r) return;
    dialog.close();
    location.assign(r.href);
  };

  const open = () => {
    if (dialog.open) return;
    opener = document.activeElement;
    dialog.showModal();
    input.select();
    load(); // fetched on the first opening only
    status.textContent = say(0, input.value);
  };

  input.addEventListener('input', run);
  input.addEventListener('keydown', (event) => {
    const n = results.length;
    if (event.key === 'ArrowDown' && n) mark((active + 1) % n);
    else if (event.key === 'ArrowUp' && n) mark((active - 1 + n) % n);
    else if (event.key === 'Enter' && active >= 0) go(active);
    else return;
    event.preventDefault();
  });
  dialog.querySelector('[data-pal-close]').addEventListener('click', () => dialog.close());
  // A click on the dialog itself, not on its panel, is a click outside
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog) dialog.close();
  });
  dialog.addEventListener('close', () => opener?.focus?.());

  // The header button is a link to the docs search without script
  for (const a of document.querySelectorAll('[data-pal-open]')) {
    a.addEventListener('click', (event) => {
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      open();
    });
  }
  const mac = /Mac|iPhone|iPad/.test(navigator.platform ?? '');
  for (const k of document.querySelectorAll('[data-pal-key]')) {
    k.textContent = mac ? '⌘K' : 'Ctrl K';
    k.hidden = false;
  }

  document.addEventListener('keydown', (event) => {
    const typing = event.target.closest?.('input, textarea, select, [contenteditable]');
    const shortcut = (event.key === 'k' || event.key === 'K') && (event.ctrlKey || event.metaKey) && !event.altKey;
    const slash = event.key === '/' && !typing && !event.ctrlKey && !event.metaKey && !event.altKey;
    if (!shortcut && !slash) return;
    event.preventDefault();
    open();
  });

  // The docs home: its own form leads to /docs/?q=, answered with the same search
  const form = document.querySelector('[data-search]');
  const q = new URLSearchParams(location.search).get('q')?.trim() ?? '';
  if (form && q) {
    form.querySelector('input[name="q"]').value = q;
    const data = await load();
    const found = data ? search(data, q, lang) : [];
    document.querySelector('[data-search-status]').textContent = data ? say(found.length, q) : t.tErreur;
    const out = document.querySelector('[data-search-results]');
    out.replaceChildren(
      ...found.map((r) => {
        const li = document.createElement('li');
        li.lang = r.lang;
        const a = document.createElement('a');
        a.href = r.href;
        a.textContent = r.title;
        const p = document.createElement('p');
        p.textContent = r.text;
        li.append(a, p);
        return li;
      }),
    );
    out.hidden = found.length === 0;
  }
}
