// Shared planning state and lazy loading of a solved planning.

import { getJson, fetchJson } from './api.js';

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

// Returns the in-memory solved planning, or builds one from the persisted
// reference data and solves it. Does not touch the demo sample.
export async function ensurePlanning() {
  if (!lastSolvedPlanning) {
    const planning = await buildPlanningFromReferenceData();
    lastSolvedPlanning = await fetchJson('/api/solve', {
      method: 'POST',
      body: JSON.stringify(planning)
    });
  }
  return lastSolvedPlanning;
}
