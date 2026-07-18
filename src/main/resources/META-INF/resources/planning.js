const planningOutput = document.getElementById('planning-output');
const solveButton = document.getElementById('solve-btn');
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

solveButton.addEventListener('click', async () => {
  solveButton.disabled = true;
  planningOutput.textContent = 'Loading planning...';
  try {
    const sample = await getJson('/api/planning/sample');
    lastSolvedPlanning = await fetchJson('/api/solve', {
      method: 'POST',
      body: JSON.stringify(sample)
    });
    planningOutput.textContent = JSON.stringify(lastSolvedPlanning, null, 2);
  } catch (error) {
    planningOutput.textContent = `Error: ${error.message}`;
  } finally {
    solveButton.disabled = false;
  }
});

exportPdfButton.addEventListener('click', async () => {
  const planning = await ensurePlanning();
  await downloadFile('/api/planning/export/pdf/global', 'planning-global.pdf', planning, 'application/pdf');
});

exportIcsButton.addEventListener('click', async () => {
  const planning = await ensurePlanning();
  const animateur = (planning.animateurs || [])[0];
  if (!animateur) {
    planningOutput.textContent = 'No animator available for ICS export.';
    return;
  }
  await downloadFile(`/api/planning/export/ics/animateur/${encodeURIComponent(animateur.id)}`, `${animateur.id}.ics`, planning, 'text/calendar');
});

standForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const id = standForm.elements.id.value.trim();
  const nom = standForm.elements.nom.value.trim();
  await fetchJson('/api/stands', {
    method: 'POST',
    body: JSON.stringify({
      id,
      nom,
      typologiesProposees: ['STRATEGIE'],
      effectifMin: 1,
      effectifMax: 1,
      reserveMajeurs: false
    })
  });
  standForm.reset();
  await refreshStands();
});

animateurForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const id = animateurForm.elements.id.value.trim();
  const prenom = animateurForm.elements.prenom.value.trim();
  const nom = animateurForm.elements.nom.value.trim();
  await fetchJson('/api/animateurs', {
    method: 'POST',
    body: JSON.stringify({
      id,
      prenom,
      nom,
      dateNaissance: '2000-01-01',
      statut: 'BENEVOLE',
      competences: { STRATEGIE: 'AUTONOME' },
      disponibilites: [],
      contactLegal: null
    })
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
  await fetchJson('/api/creneaux', {
    method: 'POST',
    body: JSON.stringify({ id, jour, date, heureDebut, heureFin })
  });
  creneauForm.reset();
  await refreshCreneaux();
});

typologieForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const id = typologieForm.elements.id.value.trim();
  const label = typologieForm.elements.label.value.trim();
  await fetchJson('/api/typologies', {
    method: 'POST',
    body: JSON.stringify({ id, label })
  });
  typologieForm.reset();
  await refreshTypologies();
});

contrainteForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const id = contrainteForm.elements.id.value.trim();
  const type = contrainteForm.elements.type.value;
  await fetchJson('/api/contraintes-ad-hoc', {
    method: 'POST',
    body: JSON.stringify({ id, type, animateursConcernes: [], creeParUtilisateurId: 'ui' })
  });
  contrainteForm.reset();
  await refreshContraintes();
});

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
