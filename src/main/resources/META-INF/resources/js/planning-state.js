// Shared planning state and lazy loading of a solved planning.

import { getJson } from './api.js';

let lastSolvedPlanning = null;

export function getLastSolvedPlanning() {
  return lastSolvedPlanning;
}

export function setLastSolvedPlanning(planning) {
  lastSolvedPlanning = planning;
}

// Builds a fresh problem from the server-side reference data (database-backed):
// one PosteAffectation per required seat (stand.effectifMax) on every timeslot.
// Never falls back to the demo sample: the sample is only loaded when the user
// explicitly requests it from the admin screen.
export async function buildPlanningFromReferenceData() {
  const [animateurs, stands, creneaux] = await Promise.all([
    getJson('/api/animateurs'),
    getJson('/api/stands'),
    getJson('/api/creneaux')
  ]);
  if (animateurs.length === 0 || stands.length === 0 || creneaux.length === 0) {
    throw new Error('No reference data. Load the sample or create stands, animators and timeslots first.');
  }
  const postes = [];
  let counter = 0;
  for (const stand of stands) {
    const seats = Math.max(1, Number(stand.effectifMax) || 1);
    for (const creneau of creneaux) {
      for (let seat = 0; seat < seats; seat += 1) {
        postes.push({ id: `poste-${counter++}`, stand, creneau, animateur: null });
      }
    }
  }
  return { animateurs, postes, score: null };
}

// Read-only planning source for the display pages (calendars) and exports.
// Returns the planning solved during this session if there is one, otherwise
// the last solution persisted in the database. It never starts a solve:
// only the Administration page may launch solver jobs, so switching tabs can
// no longer spawn parallel solver runs.
export async function loadPlanningForDisplay() {
  if (lastSolvedPlanning) {
    return lastSolvedPlanning;
  }
  return getJson('/api/planning/persisted');
}

// Same read-only source, but fails loudly when nothing has been solved yet:
// used by actions that cannot produce anything without a planning (exports).
export async function requirePlanning() {
  const planning = await loadPlanningForDisplay();
  if (!planning || !(planning.postes || []).length) {
    throw new Error('No planning available yet. Run "Solve with Timefold" first.');
  }
  return planning;
}
