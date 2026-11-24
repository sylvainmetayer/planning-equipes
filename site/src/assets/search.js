// Search of the site map page: the form submits ?q= to this same page, so a search
// has an address and can be refined from its results. The text searched is each
// page's Markdown twin (<url>index.md), fetched from the links of the map itself.
const form = globalThis.document?.querySelector('[data-search]');

const normalize = (text) => text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();

// Every word must appear in the page; the excerpt is the line holding the first one
export function match(text, words) {
  const flat = normalize(text);
  if (!words.every((w) => flat.includes(w))) return null;
  const lines = text.split('\n').filter((l) => normalize(l).includes(words[0]));
  const line = lines.find((l) => !l.startsWith('#')) ?? lines[0] ?? '';
  const plain = line.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/[*_`]/g, '').replace(/^(#+|-|\d+\.)\s+/, '');
  return plain.length > 200 ? `${plain.slice(0, 200)}…` : plain;
}

if (form) {
  const q = new URLSearchParams(location.search).get('q')?.trim() ?? '';
  const input = form.querySelector('input[name="q"]');
  const status = document.querySelector('[data-search-status]');
  const list = document.querySelector('[data-search-results]');
  const t = form.dataset;
  input.value = q;
  const words = normalize(q).split(/\s+/).filter(Boolean);
  if (words.length) {
    const pages = [...document.querySelectorAll('a[data-page]')];
    Promise.all(
      pages.map((a) =>
        fetch(`${a.getAttribute('href')}index.md`)
          .then((r) => (r.ok ? r.text() : ''))
          .catch(() => '')
          .then((text) => ({ a, excerpt: match(text, words) })),
      ),
    ).then((all) => {
      const found = all.filter((f) => f.excerpt !== null);
      const say = found.length === 0 ? t.tAucun : found.length === 1 ? t.tUn : t.tPlusieurs;
      status.textContent = `${say.replace('{n}', found.length).replace('{q}', q)}${found.length ? ` ${t.tPagination}` : ''}`;
      list.replaceChildren(
        ...found.map(({ a, excerpt }) => {
          const li = document.createElement('li');
          const link = document.createElement('a');
          link.href = a.getAttribute('href');
          link.textContent = a.textContent;
          const p = document.createElement('p');
          p.textContent = excerpt;
          li.append(link, p);
          return li;
        }),
      );
      list.hidden = found.length === 0;
    });
  }
}
