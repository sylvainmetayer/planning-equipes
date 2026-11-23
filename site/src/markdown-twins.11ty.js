export default class {
  data() {
    return {
      pagination: { data: 'allPages', size: 1, alias: 'p' },
      permalink: (data) => `${data.p.url}index.md`,
      eleventyExcludeFromCollections: true,
    };
  }

  render(data) {
    return this.markdownTwin(data.p);
  }
}
