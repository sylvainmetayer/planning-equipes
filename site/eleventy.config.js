import { build } from 'esbuild';
import { statSync } from 'node:fs';
import { markdown, markdownInline, typo } from './src/text.js';
import schema from './src/schema.js';
import { toMarkdown } from './src/markdown.js';

// Cloudflare Pages builds: production on main, preview elsewhere (no analytics
// beacon on a preview). Set here rather than as project variables, so the
// repository owns it, as ref.sylvain.dev does.
if (!process.env.ELEVENTY_ENV && process.env.CF_PAGES) {
  process.env.ELEVENTY_ENV = process.env.CF_PAGES_BRANCH === 'main' ? 'production' : 'preview';
}

const fonts = '@fontsource-variable';

export default function (eleventyConfig) {
  eleventyConfig.addPassthroughCopy('src/images');
  eleventyConfig.addPassthroughCopy('src/videos');
  eleventyConfig.addPassthroughCopy('src/og-fr.png');
  eleventyConfig.addPassthroughCopy('src/og-en.png');
  eleventyConfig.addPassthroughCopy({ 'src/_headers': '_headers', 'src/favicon.svg': 'favicon.svg' });
  eleventyConfig.addPassthroughCopy({ 'src/assets/external.svg': 'assets/external.svg' });
  eleventyConfig.addPassthroughCopy({
    [`node_modules/${fonts}/big-shoulders-display/files/big-shoulders-display-latin-wght-normal.woff2`]: 'assets/fonts/big-shoulders-display.woff2',
    [`node_modules/${fonts}/golos-text/files/golos-text-latin-wght-normal.woff2`]: 'assets/fonts/golos-text.woff2',
  });
  eleventyConfig.addWatchTarget('src/assets/*.css');
  eleventyConfig.addWatchTarget('src/_data/pages/');

  // One minified stylesheet: the tokens and the base rules are bundled in.
  eleventyConfig.on('eleventy.after', async ({ dir }) => {
    await build({
      entryPoints: ['src/assets/style.css'],
      outfile: `${dir.output}/assets/style.css`,
      bundle: true,
      minify: true,
      external: ['/assets/*'],
    });
  });

  eleventyConfig.addFilter('typo', typo);
  eleventyConfig.addFilter('md', markdown);
  eleventyConfig.addFilter('mdi', markdownInline);
  eleventyConfig.addFilter('json', (value) => JSON.stringify(value));
  // JSON-LD of a page: "<" is escaped so no text can close the script block
  eleventyConfig.addFilter('jsonld', (page, modified) => JSON.stringify(schema(page, modified), null, 1).replaceAll('<', '\\u003c'));
  eleventyConfig.addFilter('markdownTwin', toMarkdown);
  // Weight of a published file, in the unit of the language ("547 ko", "547 kB")
  eleventyConfig.addFilter('weight', (path, unit) => `${Math.round(statSync(`src${path}`).size / 1000)} ${unit}`);
  // The part of a sentence after its first separator ("Planning par jour : qui…" gives "qui…")
  eleventyConfig.addFilter('after', (text, sep) => text.slice(text.indexOf(sep) + 1).trim());
  eleventyConfig.addFilter('cap', (text) => text.charAt(0).toUpperCase() + text.slice(1));
  eleventyConfig.addFilter('byId', (items, id) => items.find((i) => i.id === id));

  return {
    dir: { input: 'src', output: '_site' },
    markdownTemplateEngine: 'njk',
    htmlTemplateEngine: 'njk',
  };
}
