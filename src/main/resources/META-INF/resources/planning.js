const planningOutput = document.getElementById('planning-output');
const loadSampleButton = document.getElementById('solve-btn');
const timefoldSolveButton = document.getElementById('timefold-solve-btn');
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

let lastSolvedPlanning = null;

// Local state for manually entered data
let localStands = [];
let localAnimateurs = [];
let localCreneaux = [];
let localTypologies = [];
let localContraintes = [];

loadSampleButton.addEventListener('click', async () => {
  loadSampleButton.disabled = true;
  planningOutput.textContent = 'Loading sample planning...';
  try {
    const sample = await getJson('/api/planning/sample');
    planningOutput.textContent = JSON.stringify(sample, null, 2);
    lastSolvedPlanning = sample;

    hydrateLocalStateFromPlanning(sample);
    renderLocalReferenceData();
    
    planningOutput.textContent = 'Sample planning loaded. Data populated in forms.';
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  } finally {
    loadSampleButton.disabled = false;
  }
});

timefoldSolveButton.addEventListener('click', async () => {
  timefoldSolveButton.disabled = true;
  planningOutput.textContent = 'Solving with Timefold...';
  try {
    let planningToSolve = lastSolvedPlanning;
    
    // If no loaded planning, try to build from local data
    if (!planningToSolve) {
      if (localAnimateurs.length === 0 && localStands.length === 0) {
        throw new Error('No planning loaded. Click "Load Sample" first or enter data manually.');
      }
      planningToSolve = buildLocalPlanning();
    }
    
    lastSolvedPlanning = await fetchJson('/api/solve', {
      method: 'POST',
      body: JSON.stringify(planningToSolve)
    });
    hydrateLocalStateFromPlanning(lastSolvedPlanning);
    renderLocalReferenceData();
    planningOutput.textContent = JSON.stringify(lastSolvedPlanning, null, 2);
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  } finally {
    timefoldSolveButton.disabled = false;
  }
});

exportPdfButton.addEventListener('click', async () => {
  try {
    const planning = await ensurePlanning();
    await downloadFile('/api/planning/export/pdf/global', 'planning-global.pdf', planning, 'application/pdf');
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  }
});

exportIcsButton.addEventListener('click', async () => {
  try {
    const planning = await ensurePlanning();
    const animateur = (planning.animateurs || [])[0];
    if (!animateur) {
      planningOutput.textContent = 'No animator available for ICS export.';
      return;
    }
    await downloadFile(`/api/planning/export/ics/animateur/${encodeURIComponent(animateur.id)}`, `${animateur.id}.ics`, planning, 'text/calendar');
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  }
});

standForm.addEventListener('submit', async (event) => {
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
});

animateurForm.addEventListener('submit', async (event) => {
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
    disponibilites: [],
    contactLegal: null
  };
  localAnimateurs.push(animateur);
  await fetchJson('/api/animateurs', {
    method: 'POST',
    body: JSON.stringify(animateur)
  });
  animateurForm.reset();
  await refreshAnimateurs();
});

creneauForm.addEventListener('submit', async (event) => {
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
});

typologieForm.addEventListener('submit', async (event) => {
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
});

contrainteForm.addEventListener('submit', async (event) => {
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
});

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

function uniqueById(items) {
  return Array.from(new Map(items.map((item) => [item.id, item])).values());
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

async function ensurePlanning() {
  if (!lastSolvedPlanning) {
    const sample = await getJson('/api/planning/sample');
    lastSolvedPlanning = await fetchJson('/api/solve', {
      method: 'POST',
      body: JSON.stringify(sample)
    });
  }
  return lastSolvedPlanning;
}

async function downloadFile(url, filename, payload, contentType) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  if (!response.ok) {
    throw new Error(`Request failed with ${response.status}`);
  }
  const blob = await response.blob();
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(objectUrl);
  planningOutput.textContent = `Downloaded ${filename} (${contentType}).`;
}

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Request failed with ${response.status}`);
  }
  return response.json();
}

async function fetchJson(url, options) {
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  });
  if (!response.ok) {
    throw new Error(`Request failed with ${response.status}`);
  }
  return response.json();
}

Promise.all([
  refreshStands(),
  refreshAnimateurs(),
  refreshCreneaux(),
  refreshTypologies(),
  refreshContraintes()
]).catch((error) => {
  planningOutput.textContent = `Error: ${error.message}`;
});
