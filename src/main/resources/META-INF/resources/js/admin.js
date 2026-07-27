// Administration page: planning actions (sample, solve, analyze) and exports.
// Reference-data CRUD lives in reference-data.js.

import { getJson, fetchJson, downloadFile } from './api.js';
import { reloadReferenceData } from './reference-data.js';
import {
  buildPlanningFromReferenceData,
  ensurePlanning,
  getLastSolvedPlanning,
  setLastSolvedPlanning
} from './planning-state.js';

const planningOutput = document.getElementById('planning-output');
const loadSampleButton = document.getElementById('solve-btn');
const timefoldSolveButton = document.getElementById('timefold-solve-btn');
const analyzeButton = document.getElementById('analyze-btn');
const exportPdfButton = document.getElementById('export-pdf-btn');
const exportIcsButton = document.getElementById('export-ics-btn');

export function initAdmin() {
  loadSampleButton.addEventListener('click', onLoadSample);
  timefoldSolveButton.addEventListener('click', onTimefoldSolve);
  analyzeButton.addEventListener('click', onAnalyze);
  exportPdfButton.addEventListener('click', onExportPdf);
  exportIcsButton.addEventListener('click', onExportIcs);
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

async function onTimefoldSolve() {
  timefoldSolveButton.disabled = true;
  planningOutput.textContent = 'Solving with Timefold...';
  try {
    let planningToSolve = getLastSolvedPlanning();
    if (!planningToSolve) {
      planningToSolve = await buildPlanningFromReferenceData();
    }
    const solved = await fetchJson('/api/solve', {
      method: 'POST',
      body: JSON.stringify(planningToSolve)
    });
    setLastSolvedPlanning(solved);
    planningOutput.textContent = JSON.stringify(solved, null, 2);
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  } finally {
    timefoldSolveButton.disabled = false;
  }
}

async function onAnalyze() {
  analyzeButton.disabled = true;
  planningOutput.textContent = 'Analyzing solution...';
  try {
    const planningToAnalyze = await ensurePlanning();
    const analysis = await fetchJson('/api/solve/analyze', {
      method: 'POST',
      body: JSON.stringify(planningToAnalyze)
    });
    const planning = analysis.planning || planningToAnalyze;
    setLastSolvedPlanning(planning);
    planningOutput.textContent = JSON.stringify(analysis, null, 2);
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  } finally {
    analyzeButton.disabled = false;
  }
}

async function onExportPdf() {
  try {
    const planning = await ensurePlanning();
    planningOutput.textContent = await downloadFile(
      '/api/planning/export/pdf/global',
      'planning-global.pdf',
      planning,
      'application/pdf'
    );
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  }
}

async function onExportIcs() {
  try {
    const planning = await ensurePlanning();
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
