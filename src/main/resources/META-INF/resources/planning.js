const planningOutput = document.getElementById('planning-output');
const loadSampleButton = document.getElementById('solve-btn');
const timefoldSolveButton = document.getElementById('timefold-solve-btn');
const analyzeButton = document.getElementById('analyze-btn');
const exportPdfButton = document.getElementById('export-pdf-btn');
const exportIcsButton = document.getElementById('export-ics-btn');

const navButtons = document.querySelectorAll('.nav-btn');
const pages = document.querySelectorAll('.page');
const calendarRefreshButton = document.getElementById('calendar-refresh-btn');
const calendarContainer = document.getElementById('calendar-container');
const dayCalendarRefreshButton = document.getElementById('day-calendar-refresh-btn');
const dayCalendarContainer = document.getElementById('day-calendar-container');
const persistedCountLabel = document.getElementById('persisted-count');

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

analyzeButton.addEventListener('click', async () => {
  analyzeButton.disabled = true;
  planningOutput.textContent = 'Analyzing solution...';
  try {
    const planningToAnalyze = await ensurePlanning();
    const analysis = await fetchJson('/api/solve/analyze', {
      method: 'POST',
      body: JSON.stringify(planningToAnalyze)
    });
    lastSolvedPlanning = analysis.planning || planningToAnalyze;
    hydrateLocalStateFromPlanning(lastSolvedPlanning);
    renderLocalReferenceData();
    planningOutput.textContent = JSON.stringify(analysis, null, 2);
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  } finally {
    analyzeButton.disabled = false;
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
    await downloadFile('/api/planning/export/ics/all', 'planning-ics.zip', planning, 'application/zip');
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  }
});

navButtons.forEach((button) => {
  button.addEventListener('click', async () => {
    navButtons.forEach((btn) => btn.classList.toggle('active', btn === button));
    pages.forEach((page) => page.classList.toggle('hidden', page.id !== button.dataset.page));
    if (button.dataset.page === 'calendar-page') {
      await renderCalendar();
    }
    if (button.dataset.page === 'day-calendar-page') {
      await renderDayCalendar();
    }
  });
});

calendarRefreshButton.addEventListener('click', () => {
  renderCalendar().catch((error) => {
    calendarContainer.textContent = `Error: ${error.message}`;
  });
});

dayCalendarRefreshButton.addEventListener('click', () => {
  renderDayCalendar().catch((error) => {
    dayCalendarContainer.textContent = `Error: ${error.message}`;
  });
});

async function renderCalendar() {
  calendarContainer.textContent = 'Loading calendar...';
  const planning = await ensurePlanning();
  const postes = planning.postes || [];

  const creneauxById = new Map();
  const standsById = new Map();
  postes.forEach((poste) => {
    if (poste.creneau) {
      creneauxById.set(poste.creneau.id, poste.creneau);
    }
    if (poste.stand) {
      standsById.set(poste.stand.id, poste.stand);
    }
  });

  const creneaux = Array.from(creneauxById.values()).sort(
    (a, b) => `${a.date}T${a.heureDebut}`.localeCompare(`${b.date}T${b.heureDebut}`)
  );
  const stands = Array.from(standsById.values()).sort((a, b) => a.nom.localeCompare(b.nom));

  if (creneaux.length === 0 || stands.length === 0) {
    calendarContainer.textContent = 'No planning data available yet.';
    return;
  }

  // Group animators by stand + timeslot
  const assignments = new Map(); // key: creneauId|standId -> [animateur names]
  postes.forEach((poste) => {
    if (!poste.creneau || !poste.stand) {
      return;
    }
    const key = `${poste.creneau.id}|${poste.stand.id}`;
    const names = assignments.get(key) || [];
    if (poste.animateur) {
      names.push(`${poste.animateur.prenom || ''} ${poste.animateur.nom || ''}`.trim());
    }
    assignments.set(key, names);
  });

  const table = document.createElement('table');
  table.className = 'calendar-table';

  const thead = document.createElement('thead');
  const headRow = document.createElement('tr');
  headRow.appendChild(document.createElement('th')).textContent = 'Timeslot';
  stands.forEach((stand) => {
    const th = document.createElement('th');
    th.textContent = stand.nom;
    headRow.appendChild(th);
  });
  thead.appendChild(headRow);
  table.appendChild(thead);

  const tbody = document.createElement('tbody');
  creneaux.forEach((creneau) => {
    const row = document.createElement('tr');
    const th = document.createElement('th');
    th.textContent = `J${creneau.jour} ${creneau.date} ${creneau.heureDebut}-${creneau.heureFin}`;
    row.appendChild(th);
    stands.forEach((stand) => {
      const td = document.createElement('td');
      const names = assignments.get(`${creneau.id}|${stand.id}`) || [];
      if (names.length === 0) {
        td.textContent = '-';
        td.className = 'empty-slot';
      } else {
        td.textContent = names.join(', ');
      }
      row.appendChild(td);
    });
    tbody.appendChild(row);
  });
  table.appendChild(tbody);

  calendarContainer.innerHTML = '';
  calendarContainer.appendChild(table);
}

async function renderDayCalendar() {
  dayCalendarContainer.textContent = 'Loading calendar...';
  await refreshPersistedCount();

  const planning = await ensurePlanning();
  const postes = planning.postes || [];
  if (postes.length === 0) {
    dayCalendarContainer.textContent = 'No planning data available yet.';
    return;
  }

  // Group timeslots by festival day, and assignments by timeslot + stand.
  const days = new Map(); // jour -> { jour, date, creneaux: Map<creneauId, creneau> }
  const assignments = new Map(); // creneauId -> Map<standId, {stand, names[]}>

  postes.forEach((poste) => {
    const creneau = poste.creneau;
    const stand = poste.stand;
    if (!creneau || !stand) {
      return;
    }
    if (!days.has(creneau.jour)) {
      days.set(creneau.jour, { jour: creneau.jour, date: creneau.date, creneaux: new Map() });
    }
    days.get(creneau.jour).creneaux.set(creneau.id, creneau);

    if (!assignments.has(creneau.id)) {
      assignments.set(creneau.id, new Map());
    }
    const standMap = assignments.get(creneau.id);
    if (!standMap.has(stand.id)) {
      standMap.set(stand.id, { stand, names: [] });
    }
    if (poste.animateur) {
      standMap.get(stand.id).names.push(
        `${poste.animateur.prenom || ''} ${poste.animateur.nom || ''}`.trim()
      );
    }
  });

  const sortedDays = Array.from(days.values()).sort((a, b) => a.jour - b.jour);

  const grid = document.createElement('div');
  grid.className = 'day-calendar-grid';

  sortedDays.forEach((day) => {
    const card = document.createElement('div');
    card.className = 'day-card';

    const header = document.createElement('div');
    header.className = 'day-card-header';
    header.textContent = day.date ? `Day ${day.jour} — ${day.date}` : `Day ${day.jour}`;
    card.appendChild(header);

    const creneaux = Array.from(day.creneaux.values()).sort(
      (a, b) => `${a.heureDebut}`.localeCompare(`${b.heureDebut}`)
    );

    creneaux.forEach((creneau) => {
      const slot = document.createElement('div');
      slot.className = 'day-slot';

      const slotTitle = document.createElement('div');
      slotTitle.className = 'day-slot-title';
      slotTitle.textContent = `${creneau.heureDebut} – ${creneau.heureFin}`;
      slot.appendChild(slotTitle);

      const standMap = assignments.get(creneau.id) || new Map();
      const stands = Array.from(standMap.values()).sort((a, b) =>
        (a.stand.nom || '').localeCompare(b.stand.nom || '')
      );

      if (stands.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'day-slot-empty';
        empty.textContent = 'No stand';
        slot.appendChild(empty);
      } else {
        stands.forEach(({ stand, names }) => {
          const line = document.createElement('div');
          line.className = names.length === 0 ? 'day-stand empty-slot' : 'day-stand';
          const label = names.length === 0 ? '(unassigned)' : names.join(', ');
          line.textContent = `${stand.nom}: ${label}`;
          slot.appendChild(line);
        });
      }

      card.appendChild(slot);
    });

    grid.appendChild(card);
  });

  dayCalendarContainer.innerHTML = '';
  dayCalendarContainer.appendChild(grid);
}

async function refreshPersistedCount() {
  if (!persistedCountLabel) {
    return;
  }
  try {
    const status = await getJson('/api/planning/persisted/count');
    persistedCountLabel.textContent = status.assignments;
  } catch (error) {
    persistedCountLabel.textContent = 'n/a';
  }
}

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
    disponibilites: []
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
