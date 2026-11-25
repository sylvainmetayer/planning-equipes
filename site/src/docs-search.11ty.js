// What the site search reads of the docs: one light index (title, description,
// h2/h3 and their anchors) instead of each page's whole Markdown twin
export default class {
  data() {
    return { permalink: '/docs/search.json', eleventyExcludeFromCollections: true };
  }

  render({ docs }) {
    return JSON.stringify(Object.fromEntries(docs.map((d) => [d.url, { t: d.h1, d: d.description, h: d.toc.map((h) => [h.id, h.text]) }])));
  }
}
