// The Markdown twin of each docs page: its source, as written (the search and llms.txt read it)
export default class DocsTwins {
  data() {
    return {
      pagination: { data: 'docs', size: 1, alias: 'p' },
      permalink: (data) => `${data.p.url}index.md`,
      eleventyExcludeFromCollections: true,
    };
  }

  render(data) {
    return data.p.source;
  }
}
