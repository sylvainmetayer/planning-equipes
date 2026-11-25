// Search of the site map page: the form submits ?q= to this same page, so a search
// has an address and can be refined from its results. The text searched is each
// page's Markdown twin (<url>index.md), fetched from the links of the map itself;
// for the docs, too heavy for that, one light index of their titles (/docs/search.json).
const form = globalThis.document?.querySelector("[data-search]");

const normalize = (text) =>
  text.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

// Every word must appear in the page; the excerpt is the line holding the first one
export function match(text, words) {
  const flat = normalize(text);
  if (!words.every((w) => flat.includes(w))) return null;
  const lines = text.split("\n").filter((l) => normalize(l).includes(words[0]));
  const line = lines.find((l) => !l.startsWith("#")) ?? lines[0] ?? "";
  const plain = line
    .replace(/\[([^[\]]*)\]\([^()]*\)/g, "$1")
    .replace(/[*_`]/g, "")
    .replace(/^(?:#+|-|\d+\.) +/, "");
  return plain.length > 200 ? `${plain.slice(0, 200)}…` : plain;
}

let docsIndex;
const loadDocs = () =>
  (docsIndex ??= fetch("/docs/search.json")
    .then((r) => (r.ok ? r.json() : {}))
    .catch(() => ({})));

// A docs page as lines the match reads: its title, its description, its headings
export function docMatch(entry, words) {
  if (!entry) return { excerpt: null };
  const text = [
    `# ${entry.t}`,
    entry.d,
    ...entry.h.map(([, h]) => `## ${h}`),
  ].join("\n");
  const found = match(text, words);
  // The excerpt is a heading: the result leads to it
  const id = entry.h.find(([, h]) => h.replace(/[*_`]/g, "") === found)?.[0];
  return { excerpt: found, hash: id ? `#${id}` : "" };
}

async function excerpt(a, words) {
  const href = a.getAttribute("href");
  try {
    if (href.startsWith("/docs/"))
      return { a, href, ...docMatch((await loadDocs())[href], words) };
    const r = await fetch(`${href}index.md`);
    return { a, href, excerpt: match(r.ok ? await r.text() : "", words) };
  } catch {
    return { a, href, excerpt: null };
  }
}

function render(found, q, t) {
  let say = t.tPlusieurs;
  if (found.length === 0) say = t.tAucun;
  else if (found.length === 1) say = t.tUn;
  const message = say.replace("{n}", found.length).replace("{q}", q);
  document.querySelector("[data-search-status]").textContent = found.length
    ? `${message} ${t.tPagination}`
    : message;
  const list = document.querySelector("[data-search-results]");
  list.replaceChildren(
    ...found.map(({ a, href, hash = "", excerpt }) => {
      const li = document.createElement("li");
      li.lang = a.closest("[lang]").lang; // the docs are French, even on the English map
      const link = document.createElement("a");
      link.href = href + hash;
      link.textContent = a.textContent;
      const p = document.createElement("p");
      p.textContent = excerpt;
      li.append(link, p);
      return li;
    }),
  );
  list.hidden = found.length === 0;
}

if (form) {
  const q = new URLSearchParams(location.search).get("q")?.trim() ?? "";
  form.querySelector('input[name="q"]').value = q;
  const words = normalize(q).split(/\s+/).filter(Boolean);
  if (words.length) {
    const all = await Promise.all(
      [...document.querySelectorAll("a[data-page]")].map((a) =>
        excerpt(a, words),
      ),
    );
    render(
      all.filter((f) => f.excerpt !== null),
      q,
      form.dataset,
    );
  }
}
