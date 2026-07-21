// Shared planning state and lazy loading of a solved planning.

import { getJson, fetchJson } from './api.js';

let lastSolvedPlanning = null;

export function getLastSolvedPlanning() {
  return lastSolvedPlanning;
}

export function setLastSolvedPlanning(planning) {
  lastSolvedPlanning = planning;
}

export async function ensurePlanning() {
  if (!lastSolvedPlanning) {
    const sample = await getJson('/api/planning/sample');
    lastSolvedPlanning = await fetchJson('/api/solve', {
      method: 'POST',
      body: JSON.stringify(sample)
    });
  }
  return lastSolvedPlanning;
}
