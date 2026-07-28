// Administration page: planning actions (sample, solve, analyze) and exports.
// Reference-data CRUD lives in reference-data.js.

import { getJson, fetchJson, downloadFile } from './api.js';
import { reloadReferenceData } from './reference-data.js';
import {
  hasRunningJob,
  onRunningJobsChange,
  registerJobResultHandler,
  submitAnalyzeJob,
  submitSolveJob
} from './jobs.js';
import {
  buildPlanningFromReferenceData,
  getLastSolvedPlanning,
  requirePlanning,
  setLastSolvedPlanning
} from './planning-state.js';

const planningOutput = document.getElementById('planning-output');
const loadSampleButton = document.getElementById('solve-btn');
const timefoldSolveButton = document.getElementById('timefold-solve-btn');
const analyzeButton = document.getElementById('analyze-btn');
const exportPdfButton = document.getElementById('export-pdf-btn');
const exportIcsButton = document.getElementById('export-ics-btn');
const resetDbButton = document.getElementById('reset-db-btn');

export function initAdmin() {
  loadSampleButton.addEventListener('click', onLoadSample);
  resetDbButton.addEventListener('click', onResetDatabase);
  timefoldSolveButton.addEventListener('click', onTimefoldSolve);
  analyzeButton.addEventListener('click', onAnalyze);
  exportPdfButton.addEventListener('click', onExportPdf);
  exportIcsButton.addEventListener('click', onExportIcs);
  // Applies the payload of a job that finished while no click handler was
  // awaiting it (page reloaded during a multi-minute solve).
  registerJobResultHandler('SOLVE', applySolveResult);
  registerJobResultHandler('ANALYZE', applyAnalyzeResult);
  // Keeps the solver buttons locked while a job runs, including a job resumed
  // after a page reload.
  onRunningJobsChange((count) => setSolverButtonsDisabled(count > 0));
}

async function onLoadSample() {
  loadSampleButton.disabled = true;
  planningOutput.textContent = 'Loading sample planning...';
  try {
    const sample = await getJson('/api/planning/sample');
    setLastSolvedPlanning(sample);
    // Import the sample reference data into the CRUD store so it is editable.
    await fetch('/api/reference-data/import', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(sample)
    });
    await reloadReferenceData();
    planningOutput.textContent = 'Sample planning loaded. Reference data populated and editable below.';
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  } finally {
    loadSampleButton.disabled = false;
  }
}

// Blank-slate reset: reloads the demo scenario into the database with every
// seat unassigned, so a test run starts from clean, unsolved data.
async function onResetDatabase() {
  if (!window.confirm('Reset the database with the sample scenario? Every stand, timeslot, animator, assignment and ad hoc constraint is replaced.')) {
    return;
  }
  resetDbButton.disabled = true;
  planningOutput.textContent = 'Resetting database...';
  try {
    const summary = await fetchJson('/api/planning/reset', { method: 'POST' });
    setLastSolvedPlanning(null);
    await reloadReferenceData();
    planningOutput.textContent =
      `Database reset: ${summary.animateurs} animators, ${summary.stands} stands, `
      + `${summary.creneaux} timeslots, ${summary.postes} unassigned seats.`;
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  } finally {
    resetDbButton.disabled = false;
  }
}

async function onTimefoldSolve() {
  if (solverJobAlreadyRunning()) {
    return;
  }
  setSolverButtonsDisabled(true);
  planningOutput.textContent = 'Submitting solve to the background solver...';
  try {
    const solvePromise = submitSolveJob(await planningToWorkOn());
    planningOutput.textContent =
      'Solving with Timefold in the background. You can keep browsing; a notification will pop up when it is done.';
    applySolveResult(await solvePromise);
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  } finally {
    setSolverButtonsDisabled(false);
  }
}

// Only one solver job at a time: a solve and an analysis both run the solver,
// so they must never be started in parallel.
function solverJobAlreadyRunning() {
  if (!hasRunningJob('SOLVE') && !hasRunningJob('ANALYZE')) {
    return false;
  }
  planningOutput.textContent = 'A solver job is already running. Wait for it to finish before starting another one.';
  return true;
}

function setSolverButtonsDisabled(disabled) {
  timefoldSolveButton.disabled = disabled;
  analyzeButton.disabled = disabled;
}

// Solver input: the planning solved during this session if any, otherwise a
// fresh problem built from the reference data.
async function planningToWorkOn() {
  return getLastSolvedPlanning() || buildPlanningFromReferenceData();
}

function applySolveResult(solved) {
  setLastSolvedPlanning(solved);
  planningOutput.textContent = JSON.stringify(solved, null, 2);
}

async function onAnalyze() {
  if (solverJobAlreadyRunning()) {
    return;
  }
  setSolverButtonsDisabled(true);
  planningOutput.textContent = 'Submitting analysis to the background solver...';
  try {
    const analyzePromise = submitAnalyzeJob(await planningToWorkOn());
    planningOutput.textContent =
      'Analyzing the solution in the background. You can keep browsing; a notification will pop up when it is done.';
    applyAnalyzeResult(await analyzePromise);
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  } finally {
    setSolverButtonsDisabled(false);
  }
}

function applyAnalyzeResult(analysis) {
  if (analysis.planning) {
    setLastSolvedPlanning(analysis.planning);
  }
  planningOutput.textContent = JSON.stringify(analysis, null, 2);
}

async function onExportPdf() {
  try {
    const planning = await requirePlanning();
    planningOutput.textContent = await downloadFile(
      '/api/planning/export/pdf/all',
      'planning-pdf.zip',
      planning,
      'application/zip'
    );
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  }
}

async function onExportIcs() {
  try {
    const planning = await requirePlanning();
    planningOutput.textContent = await downloadFile(
      '/api/planning/export/ics/all',
      'planning-ics.zip',
      planning,
      'application/zip'
    );
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  }
}
