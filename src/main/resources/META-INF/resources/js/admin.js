// Administration page: planning actions, exports and reference-data CRUD.

import { getJson, fetchJson, downloadFile } from './api.js';
import { uniqueById } from './utils.js';
import {
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

const standForm = document.getElementById('stand-form');
const standsList = document.getElementById('stands-list');
const animateurForm = document.getElementById('animateur-form');
const animateursList = document.getElementById('animateurs-list');
const creneauForm = document.getElementById('creneau-form');
const creneauxList = document.getElementById('creneaux-list');
const typologieForm = document.getElementById('typologie-form');
const typologiesList = document.getElementById('typologies-list');
const contrainteForm = document.getElementById('contrainte-form');
const contraintesList = document.getElementById('contraintes-list');

// Local state for manually entered data
let localStands = [];
let localAnimateurs = [];
let localCreneaux = [];
let localTypologies = [];
let localContraintes = [];

export function initAdmin() {
  loadSampleButton.addEventListener('click', onLoadSample);
  timefoldSolveButton.addEventListener('click', onTimefoldSolve);
  analyzeButton.addEventListener('click', onAnalyze);
  exportPdfButton.addEventListener('click', onExportPdf);
  exportIcsButton.addEventListener('click', onExportIcs);

  standForm.addEventListener('submit', onStandSubmit);
  animateurForm.addEventListener('submit', onAnimateurSubmit);
  creneauForm.addEventListener('submit', onCreneauSubmit);
  typologieForm.addEventListener('submit', onTypologieSubmit);
  contrainteForm.addEventListener('submit', onContrainteSubmit);

  Promise.all([
    refreshStands(),
    refreshAnimateurs(),
    refreshCreneaux(),
    refreshTypologies(),
    refreshContraintes()
  ]).catch((error) => {
    planningOutput.textContent = `Error: ${error.message}`;
  });
}

async function onLoadSample() {
  loadSampleButton.disabled = true;
  planningOutput.textContent = 'Loading sample planning...';
  try {
    const sample = await getJson('/api/planning/sample');
    planningOutput.textContent = JSON.stringify(sample, null, 2);
    setLastSolvedPlanning(sample);

    hydrateLocalStateFromPlanning(sample);
    renderLocalReferenceData();

    planningOutput.textContent = 'Sample planning loaded. Data populated in forms.';
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

    // If no loaded planning, try to build from local data
    if (!planningToSolve) {
      if (localAnimateurs.length === 0 && localStands.length === 0) {
        throw new Error('No planning loaded. Click "Load Sample" first or enter data manually.');
      }
      planningToSolve = buildLocalPlanning();
    }

    const solved = await fetchJson('/api/solve', {
      method: 'POST',
      body: JSON.stringify(planningToSolve)
    });
    setLastSolvedPlanning(solved);
    hydrateLocalStateFromPlanning(solved);
    renderLocalReferenceData();
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
    hydrateLocalStateFromPlanning(planning);
    renderLocalReferenceData();
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
    const message = await downloadFile(
      '/api/planning/export/pdf/global',
      'planning-global.pdf',
      planning,
      'application/pdf'
    );
    planningOutput.textContent = message;
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  }
}

async function onExportIcs() {
  try {
    const planning = await ensurePlanning();
    const message = await downloadFile(
      '/api/planning/export/ics/all',
      'planning-ics.zip',
      planning,
      'application/zip'
    );
    planningOutput.textContent = message;
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  }
}

async function onStandSubmit(event) {
  event.preventDefault();
  const id = standForm.elements.id.value.trim();
  const nom = standForm.elements.nom.value.trim();
  const stand = {
    id,
    nom,
    typologiesProposees: ['STRATEGIE'],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false
  };
  localStands.push(stand);
  await fetchJson('/api/stands', {
    method: 'POST',
    body: JSON.stringify(stand)
  });
  standForm.reset();
  await refreshStands();
}

async function onAnimateurSubmit(event) {
  event.preventDefault();
  const id = animateurForm.elements.id.value.trim();
  const prenom = animateurForm.elements.prenom.value.trim();
  const nom = animateurForm.elements.nom.value.trim();
  const animateur = {
    id,
    prenom,
    nom,
    dateNaissance: '2000-01-01',
    statut: 'BENEVOLE',
    competences: { STRATEGIE: 'AUTONOME' },
    disponibilites: []
  };
  localAnimateurs.push(animateur);
  await fetchJson('/api/animateurs', {
    method: 'POST',
    body: JSON.stringify(animateur)
  });
  animateurForm.reset();
  await refreshAnimateurs();
}

async function onCreneauSubmit(event) {
  event.preventDefault();
  const id = creneauForm.elements.id.value.trim();
  const jour = Number(creneauForm.elements.jour.value);
  const date = creneauForm.elements.date.value;
  const heureDebut = creneauForm.elements.heureDebut.value;
  const heureFin = creneauForm.elements.heureFin.value;
  const creneau = { id, jour, date, heureDebut, heureFin };
  localCreneaux.push(creneau);
  await fetchJson('/api/creneaux', {
    method: 'POST',
    body: JSON.stringify(creneau)
  });
  creneauForm.reset();
  await refreshCreneaux();
}

async function onTypologieSubmit(event) {
  event.preventDefault();
  const id = typologieForm.elements.id.value.trim();
  const label = typologieForm.elements.label.value.trim();
  const typologie = { id, label };
  localTypologies.push(typologie);
  await fetchJson('/api/typologies', {
    method: 'POST',
    body: JSON.stringify(typologie)
  });
  typologieForm.reset();
  await refreshTypologies();
}

async function onContrainteSubmit(event) {
  event.preventDefault();
  const id = contrainteForm.elements.id.value.trim();
  const type = contrainteForm.elements.type.value;
  const contrainte = { id, type, animateursConcernes: [], creeParUtilisateurId: 'ui' };
  localContraintes.push(contrainte);
  await fetchJson('/api/contraintes-ad-hoc', {
    method: 'POST',
    body: JSON.stringify(contrainte)
  });
  contrainteForm.reset();
  await refreshContraintes();
}

function buildLocalPlanning() {
  // Build a planning from locally entered data
  // Generate PosteAffectation for each combination of stand and creneau
  const postes = [];
  let posteCounter = 0;

  for (const stand of localStands) {
    for (const creneau of localCreneaux) {
      // Create posts based on effectifMin to effectifMax
      const numPosts = Math.min(stand.effectifMax, 2); // default to 2 if not specified
      for (let i = 0; i < numPosts; i++) {
        postes.push({
          id: `poste-${posteCounter++}`,
          stand,
          creneau,
          animateur: null
        });
      }
    }
  }

  return {
    animateurs: localAnimateurs,
    stands: localStands,
    creneaux: localCreneaux,
    typologies: localTypologies,
    contraintesAdHoc: localContraintes,
    postes,
    score: null
  };
}

function hydrateLocalStateFromPlanning(planning) {
  const postes = planning.postes || [];

  localAnimateurs = planning.animateurs || [];
  localStands = uniqueById(postes.map((poste) => poste.stand).filter(Boolean));
  localCreneaux = uniqueById(postes.map((poste) => poste.creneau).filter(Boolean));
  localContraintes = planning.contraintesAdHoc || [];

  const typologyIds = new Set();
  localStands.forEach((stand) => {
    (stand.typologiesProposees || []).forEach((typologie) => typologyIds.add(typologie));
  });
  localAnimateurs.forEach((animateur) => {
    Object.keys(animateur.competences || {}).forEach((typologie) => typologyIds.add(typologie));
  });
  localTypologies = Array.from(typologyIds)
    .sort((left, right) => left.localeCompare(right))
    .map((typologie) => ({ id: typologie, label: typologie }));
}

function renderLocalReferenceData() {
  renderSimpleList(standsList, localStands, (stand) => `${stand.id} - ${stand.nom}`);
  renderSimpleList(animateursList, localAnimateurs, (animateur) => `${animateur.id} - ${animateur.prenom} ${animateur.nom}`);
  renderSimpleList(
    creneauxList,
    localCreneaux,
    (creneau) => `${creneau.id} - J${creneau.jour} ${creneau.date} ${creneau.heureDebut}-${creneau.heureFin}`
  );
  renderSimpleList(typologiesList, localTypologies, (typologie) => `${typologie.id} - ${typologie.label}`);
  renderSimpleList(contraintesList, localContraintes, (contrainte) => `${contrainte.id} - ${contrainte.type}`);
}

async function refreshStands() {
  renderSimpleList(standsList, await getJson('/api/stands'), (stand) => `${stand.id} - ${stand.nom}`);
}

async function refreshAnimateurs() {
  renderSimpleList(animateursList, await getJson('/api/animateurs'), (animateur) => `${animateur.id} - ${animateur.prenom} ${animateur.nom}`);
}

async function refreshCreneaux() {
  renderSimpleList(
    creneauxList,
    await getJson('/api/creneaux'),
    (creneau) => `${creneau.id} - J${creneau.jour} ${creneau.date} ${creneau.heureDebut}-${creneau.heureFin}`
  );
}

async function refreshTypologies() {
  renderSimpleList(typologiesList, await getJson('/api/typologies'), (typologie) => `${typologie.id} - ${typologie.label}`);
}

async function refreshContraintes() {
  renderSimpleList(contraintesList, await getJson('/api/contraintes-ad-hoc'), (contrainte) => `${contrainte.id} - ${contrainte.type}`);
}

function renderSimpleList(element, items, formatter) {
  element.innerHTML = '';
  items.forEach((item) => {
    const li = document.createElement('li');
    li.textContent = formatter(item);
    element.appendChild(li);
  });
}
