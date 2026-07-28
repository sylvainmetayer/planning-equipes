// Background solver jobs: submit, follow, and report without blocking the UI.
//
// A solve can run for minutes. Instead of awaiting a long HTTP request, the
// browser posts to the /async endpoints and follows the job through the
// server.
//
// The "a solver run is in progress" state belongs to the server, not to the
// browser: every client polls /api/jobs/active, so a solve started in one tab
// locks the solver buttons and shows the same elapsed time in another browser,
// in a private window, or after clearing the local storage.

import { getJson } from './api.js';
import { notify, requestDesktopPermission } from './notifications.js';

const POLL_INTERVAL_MS = 2000;
const LABELS = {
  SOLVE: 'Timefold solve',
  ANALYZE: 'Solution analysis'
};

const resultHandlers = new Map();
const activeJobListeners = new Set();

// Job currently held by the server, as seen by this client: { id, type, label,
// startedAtMs, mine }. null when the solver is idle.
let activeJob = null;
// False until the first answer from the server: callers stay pessimistic
// (solver considered busy) rather than briefly offering a locked action.
let solverStateKnown = false;
let monitorElement = null;
let pollTimer = null;
let tickTimer = null;

export function initJobMonitor() {
  monitorElement = document.getElementById('job-monitor');
  renderMonitor();
  syncActiveJob();
  if (!pollTimer) {
    pollTimer = setInterval(syncActiveJob, POLL_INTERVAL_MS);
  }
}

/**
 * Registers what to do with the payload of a finished job. Results are always
 * dispatched here, whoever started the job: a solve launched from another
 * browser also updates this one when it completes.
 */
export function registerJobResultHandler(type, handler) {
  resultHandlers.set(type, handler);
}

/**
 * Notified whenever the server-side active job changes (start, end, adoption).
 * The first call happens once the server state is known, so a listener must
 * assume the solver is busy until then.
 */
export function onActiveJobChange(listener) {
  activeJobListeners.add(listener);
  if (solverStateKnown) {
    listener(activeJob);
  }
}

/** Server-side solver lock as last seen by this client. */
export function getActiveJob() {
  return activeJob;
}

export function isSolverBusy() {
  return activeJob !== null || !solverStateKnown;
}

export function describeActiveJob() {
  if (!activeJob) {
    return solverStateKnown ? '' : 'The solver state is not known yet.';
  }
  const origin = activeJob.mine ? '' : ' (started from another session)';
  return `${activeJob.label} has been running for ${formatElapsed(activeJob.startedAtMs)}${origin}.`;
}

export function submitSolveJob(planning, { seconds } = {}) {
  return submitJob('/api/solve/async', planning, { type: 'SOLVE', seconds });
}

export function submitAnalyzeJob(planning, { seconds } = {}) {
  return submitJob('/api/solve/analyze/async', planning, { type: 'ANALYZE', seconds });
}

async function submitJob(endpoint, payload, { type, seconds }) {
  requestDesktopPermission();
  const label = LABELS[type] || type;
  const url = seconds ? `${endpoint}?seconds=${encodeURIComponent(seconds)}` : endpoint;
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  const job = await response.json().catch(() => null);
  // 409: the server already runs a solve, possibly submitted by another client.
  if (response.status === 409 && job) {
    adopt(job, { mine: false });
    throw new Error(`${LABELS[job.type] || job.type} is already running (${formatDuration(job.elapsedSeconds)}). `
      + 'Wait for it to finish before starting another one.');
  }
  if (!response.ok || !job) {
    throw new Error(`Request failed with ${response.status}`);
  }
  adopt(job, { mine: true });
  notify({
    title: `${label} started`,
    message: 'This runs on the server — you can keep using the app, from this browser or another one.',
    variant: 'info',
    timeout: 5000
  });
  return job;
}

// Single source of truth: what the server reports as its active job.
async function syncActiveJob() {
  const server = await fetchActiveJob();
  if (server === undefined) {
    return; // transient network error: keep the last known state
  }
  if (activeJob && (!server || server.id !== activeJob.id)) {
    const finished = activeJob;
    setActiveJob(null);
    await reportFinishedJob(finished);
  }
  if (server) {
    // adopt() keeps the "mine" flag for a job this client already follows.
    adopt(server, { mine: false });
  }
  if (!solverStateKnown) {
    solverStateKnown = true;
    notifyListeners();
  }
}

async function fetchActiveJob() {
  try {
    const response = await fetch('/api/jobs/active');
    if (response.status === 204) {
      return null;
    }
    if (!response.ok) {
      return undefined;
    }
    return await response.json();
  } catch {
    return undefined;
  }
}

// Starts (or refreshes) the local view of the server-side job. Elapsed time is
// rebased on the server value so every client shows the same duration, whatever
// its clock or when it connected.
function adopt(job, { mine }) {
  const known = activeJob?.id === job.id;
  const entry = {
    id: job.id,
    type: job.type,
    label: LABELS[job.type] || job.type,
    startedAtMs: Date.now() - (Number(job.elapsedSeconds) || 0) * 1000,
    mine: mine || (known && activeJob.mine)
  };
  setActiveJob(entry);
  if (!known && !entry.mine) {
    notify({
      title: `${entry.label} already running`,
      message: `Started from another session ${formatDuration(job.elapsedSeconds)} ago. Solver actions are locked until it finishes.`,
      variant: 'info'
    });
  }
}

async function reportFinishedJob(entry) {
  const job = await getJson(`/api/jobs/${entry.id}`).catch(() => null);
  if (!job) {
    notify({
      title: `${entry.label} is over`,
      message: 'The server no longer knows this job (restart or retention purge).',
      variant: 'info'
    });
    return;
  }
  if (job.status !== 'COMPLETED') {
    notify({
      title: `${entry.label} ${job.status.toLowerCase()}`,
      message: job.error || '',
      variant: 'error',
      desktop: true,
      timeout: 0
    });
    return;
  }
  notify({
    title: `${entry.label} finished in ${formatDuration(job.elapsedSeconds)}`,
    message: describeResult(job.type, job.result),
    variant: 'success',
    desktop: true
  });
  resultHandlers.get(job.type)?.(job.result);
}

function setActiveJob(entry) {
  const changed = (activeJob?.id ?? null) !== (entry?.id ?? null);
  activeJob = entry;
  if (entry) {
    startTicker();
  } else {
    stopTicker();
  }
  renderMonitor();
  // Listeners only hear about transitions, not about every poll tick.
  if (changed) {
    notifyListeners();
  }
}

function notifyListeners() {
  activeJobListeners.forEach((listener) => listener(activeJob));
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
  if (!monitorElement) {
    return;
  }
  monitorElement.classList.toggle('hidden', activeJob === null);
  monitorElement.textContent = '';
  if (!activeJob) {
    return;
  }
  const row = document.createElement('span');
  row.className = 'job-monitor-item';
  const origin = activeJob.mine ? '' : ' (another session)';
  row.textContent = `${activeJob.label} running… ${formatElapsed(activeJob.startedAtMs)}${origin}`;
  monitorElement.appendChild(row);
}

function formatElapsed(startedAtMs) {
  return formatDuration(Math.round((Date.now() - startedAtMs) / 1000));
}

function formatDuration(totalSeconds) {
  const seconds = Math.max(0, Number(totalSeconds) || 0);
  const minutes = Math.floor(seconds / 60);
  return minutes > 0 ? `${minutes}m ${String(seconds % 60).padStart(2, '0')}s` : `${seconds}s`;
}

function startTicker() {
  if (!tickTimer) {
    tickTimer = setInterval(renderMonitor, 1000);
  }
}

function stopTicker() {
  if (tickTimer) {
    clearInterval(tickTimer);
    tickTimer = null;
  }
}
