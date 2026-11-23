// Date of the last commit touching the site, so dateModified and lastmod follow
// the content instead of a hand-written date. A build without git history falls
// back to today.
import { execFileSync } from 'node:child_process';

export default function () {
  let modified = new Date().toISOString().slice(0, 10);
  try {
    const out = execFileSync('git', ['log', '-1', '--format=%cs', '--', '.'], { encoding: 'utf8' }).trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(out)) modified = out;
  } catch {
    // no git: keep today
  }
  const env = process.env.ELEVENTY_ENV || 'production';
  return { modified, env, analytics: env === 'production' };
}
