// Background solver jobs: submit, poll, and report without blocking the UI.
//
// A solve can run for minutes. Instead of awaiting a long HTTP request, the
// browser posts to the /async endpoints, gets a job id, and polls
// /api/jobs/{id} until the job finishes. Running jobs are mirrored in
// sessionStorage so a page reload keeps following them.

import { fetchJson, getJson } from './api.js';
import { notify, requestDesktopPermission } from './notifications.js';

const POLL_INTERVAL_MS = 2000;
const STORAGE_KEY = 'planning-equipes.running-jobs';

const runningJobs = new Map();
const resultHandlers = new Map();
const runningJobsListeners = new Set();

let monitorElement = null;
let tickTimer = null;

export function initJobMonitor() {
  monitorElement = document.getElementById('job-monitor');
  resumeStoredJobs();
  renderMonitor();
}

/**
 * Registers what to do with the payload of a job that finished without an
 * awaiting caller (typically after a page reload).
 */
export function registerJobResultHandler(type, handler) {
  resultHandlers.set(type, handler);
}

/** Notified whenever the set of running jobs changes (start, end, resume). */
export function onRunningJobsChange(listener) {
  runningJobsListeners.add(listener);
  listener(runningJobs.size);
}

export function hasRunningJob(type) {
  return [...runningJobs.values()].some((job) => job.type === type);
}

export function submitSolveJob(planning, { seconds } = {}) {
  return submitJob('/api/solve/async', planning, { type: 'SOLVE', label: 'Timefold solve', seconds });
}

export function submitAnalyzeJob(planning, { seconds } = {}) {
  return submitJob('/api/solve/analyze/async', planning, { type: 'ANALYZE', label: 'Solution analysis', seconds });
}

async function submitJob(endpoint, payload, { type, label, seconds }) {
  requestDesktopPermission();
  const url = seconds ? `${endpoint}?seconds=${encodeURIComponent(seconds)}` : endpoint;
  const job = await fetchJson(url, { method: 'POST', body: JSON.stringify(payload) });
  notify({
    title: `${label} started`,
    message: 'This runs in the background — you can keep using the app.',
    variant: 'info',
    timeout: 5000
  });
  return track({ id: job.id, type, label, startedAt: Date.now() }, { dispatchToHandler: false });
}

async function track(entry, { dispatchToHandler }) {
  runningJobs.set(entry.id, entry);
  persistRunningJobs();
  renderMonitor();
  startTicker();
  try {
    const job = await pollUntilFinished(entry.id);
    if (job.status !== 'COMPLETED') {
      throw new Error(job.error || `${entry.label} ${job.status.toLowerCase()}`);
    }
    notify({
      title: `${entry.label} finished`,
      message: describeResult(entry.type, job.result),
      variant: 'success',
      desktop: true
    });
    if (dispatchToHandler) {
      resultHandlers.get(entry.type)?.(job.result);
    }
    return job.result;
  } catch (error) {
    notify({
      title: `${entry.label} failed`,
      message: error.message,
      variant: 'error',
      desktop: true,
      timeout: 0
    });
    throw error;
  } finally {
    runningJobs.delete(entry.id);
    persistRunningJobs();
    renderMonitor();
    stopTickerIfIdle();
  }
}

async function pollUntilFinished(jobId) {
  for (;;) {
    const job = await getJson(`/api/jobs/${jobId}`);
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status)) {
      return job;
    }
    await sleep(POLL_INTERVAL_MS);
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function describeResult(type, result) {
  if (!result) {
    return '';
  }
  if (type === 'ANALYZE') {
    return `Score ${formatScore(result.score)} — ${result.postesNonPourvus} unfilled seats.`;
  }
  const unfilled = Array.isArray(result.postes)
    ? result.postes.filter((poste) => !poste.animateur).length
    : null;
  const score = formatScore(result.score);
  return unfilled === null ? `Score ${score}.` : `Score ${score} — ${unfilled} unfilled seats.`;
}

// A solved planning serializes its score as an object, while the analysis
// endpoint already returns the printable form.
function formatScore(score) {
  if (!score) {
    return 'n/a';
  }
  if (typeof score === 'string') {
    return score;
  }
  return `${score.hardScore}hard/${score.mediumScore}medium/${score.softScore}soft`;
}

function renderMonitor() {
  runningJobsListeners.forEach((listener) => listener(runningJobs.size));
  if (!monitorElement) {
    return;
  }
  const jobs = [...runningJobs.values()];
  monitorElement.classList.toggle('hidden', jobs.length === 0);
  monitorElement.textContent = '';
  jobs.forEach((job) => {
    const row = document.createElement('span');
    row.className = 'job-monitor-item';
    row.textContent = `${job.label} running… ${formatElapsed(job.startedAt)}`;
    monitorElement.appendChild(row);
  });
}

function formatElapsed(startedAt) {
  const seconds = Math.max(0, Math.round((Date.now() - startedAt) / 1000));
  const minutes = Math.floor(seconds / 60);
  return minutes > 0 ? `${minutes}m ${String(seconds % 60).padStart(2, '0')}s` : `${seconds}s`;
}

function startTicker() {
  if (!tickTimer) {
    tickTimer = setInterval(renderMonitor, 1000);
  }
}

function stopTickerIfIdle() {
  if (tickTimer && runningJobs.size === 0) {
    clearInterval(tickTimer);
    tickTimer = null;
  }
}

function persistRunningJobs() {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify([...runningJobs.values()]));
  } catch {
    /* storage may be unavailable (private mode); tracking still works in memory */
  }
}

function readStoredJobs() {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

// Re-attaches to jobs that were still running when the page was reloaded.
// Jobs the server no longer knows about (restart, retention purge) are dropped
// silently instead of raising a spurious error toast.
async function resumeStoredJobs() {
  const stored = readStoredJobs().filter((entry) => entry?.id && !runningJobs.has(entry.id));
  const known = await Promise.all(stored.map((entry) => jobExists(entry.id)));
  stored
    .filter((_, index) => known[index])
    .forEach((entry) => {
      track({ ...entry, startedAt: entry.startedAt || Date.now() }, { dispatchToHandler: true })
        .catch(() => {
          /* failure already surfaced as a toast */
        });
    });
  if (known.includes(false)) {
    persistRunningJobs();
  }
}

async function jobExists(jobId) {
  try {
    const response = await fetch(`/api/jobs/${jobId}`);
    return response.ok;
  } catch {
    return false;
  }
}
