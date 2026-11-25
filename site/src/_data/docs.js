import { loadDocs } from '../docs.js';

// The pages of /docs, read from ../docs at build time
export default function () {
  return loadDocs();
}
