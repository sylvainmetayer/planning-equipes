// Full CRUD editors for every business entity: stands, animateurs (with skill
// levels and off-days), timeslots, typologies and ad hoc constraints. Owns its
// own DOM inside #reference-data and its local editing state.

import { getJson, fetchJson } from './api.js';

const STATUTS = ['BENEVOLE', 'SALARIE'];
const NIVEAUX = ['DEBUTANT', 'AUTONOME', 'REFERENT'];
const CONTRAINTE_TYPES = [
  ['INDISPONIBILITE_FORCEE', 'Forced unavailability'],
  ['INCOMPATIBILITE', 'Incompatibility'],
  ['AFFECTATION_FORCEE', 'Forced assignment']
];

// Module state: reference collections and per-entity editing ids.
const state = {
  typologies: [],
  creneaux: [],
  animateurs: [],
  stands: [],
  contraintes: [],
  editing: { stand: null, animateur: null, creneau: null, typologie: null, contrainte: null }
};

let root;
let statusBar;

export function initReferenceData() {
  root = document.getElementById('reference-data');
  if (!root) {
    return;
  }
  root.innerHTML = skeleton();
  statusBar = root.querySelector('#ref-status');
  wireForms();
  reloadAndRender().catch(showError);
}

// Re-fetch every collection and repaint the editors. Exposed so other modules
// (e.g. after importing a sample planning) can refresh the CRUD screens.
export async function reloadReferenceData() {
  if (!root) {
    return;
  }
  await reloadAndRender();
}

function skeleton() {
  return `
    <p id="ref-status" class="ref-status" aria-live="polite"></p>

    <section class="ref-editor">
      <h2>Stands</h2>
      <form id="ref-stand-form" class="ref-form">
        <div class="ref-fields">
          <label>Id <input name="id" required></label>
          <label>Name <input name="nom" required></label>
          <label>Min staff <input name="effectifMin" type="number" min="0" value="1"></label>
          <label>Max staff <input name="effectifMax" type="number" min="0" value="1"></label>
          <label class="ref-check"><input name="reserveMajeurs" type="checkbox"> Adults only</label>
        </div>
        <fieldset class="ref-subeditor">
          <legend>Game typologies</legend>
          <div id="ref-stand-typologies" class="ref-checkbox-grid"></div>
        </fieldset>
        <div class="ref-actions">
          <button type="submit">Save stand</button>
          <button type="button" class="ref-cancel" data-entity="stand">Cancel edit</button>
        </div>
      </form>
      <table class="ref-list"><tbody id="ref-stands-list"></tbody></table>
    </section>

    <section class="ref-editor">
      <h2>Animators</h2>
      <form id="ref-animateur-form" class="ref-form">
        <div class="ref-fields">
          <label>Id <input name="id" required></label>
          <label>First name <input name="prenom" required></label>
          <label>Last name <input name="nom" required></label>
          <label>Birth date <input name="dateNaissance" type="date"></label>
          <label>Status
            <select name="statut">${STATUTS.map((s) => `<option value="${s}">${s}</option>`).join('')}</select>
          </label>
        </div>
        <fieldset class="ref-subeditor">
          <legend>Skill levels</legend>
          <div id="ref-competences"></div>
          <button type="button" id="ref-add-competence" class="ref-mini">+ Add skill</button>
        </fieldset>
        <fieldset class="ref-subeditor">
          <legend>Off days (unavailable)</legend>
          <div class="ref-inline">
            <input type="date" id="ref-jour-input">
            <button type="button" id="ref-add-jour" class="ref-mini">+ Add off day</button>
          </div>
          <div id="ref-jours" class="ref-chips"></div>
        </fieldset>
        <div class="ref-actions">
          <button type="submit">Save animator</button>
          <button type="button" class="ref-cancel" data-entity="animateur">Cancel edit</button>
        </div>
      </form>
      <table class="ref-list"><tbody id="ref-animateurs-list"></tbody></table>
    </section>

    <section class="ref-editor">
      <h2>Timeslots</h2>
      <form id="ref-creneau-form" class="ref-form">
        <div class="ref-fields">
          <label>Id <input name="id" required></label>
          <label>Festival day <input name="jour" type="number" min="1" max="15" required></label>
          <label>Date <input name="date" type="date" required></label>
          <label>Start <input name="heureDebut" type="time" required></label>
          <label>End <input name="heureFin" type="time" required></label>
        </div>
        <div class="ref-actions">
          <button type="submit">Save timeslot</button>
          <button type="button" class="ref-cancel" data-entity="creneau">Cancel edit</button>
        </div>
      </form>
      <table class="ref-list"><tbody id="ref-creneaux-list"></tbody></table>
    </section>

    <section class="ref-editor">
      <h2>Typologies</h2>
      <form id="ref-typologie-form" class="ref-form">
        <div class="ref-fields">
          <label>Id <input name="id" required></label>
          <label>Label <input name="label"></label>
        </div>
        <div class="ref-actions">
          <button type="submit">Save typology</button>
          <button type="button" class="ref-cancel" data-entity="typologie">Cancel edit</button>
        </div>
      </form>
      <table class="ref-list"><tbody id="ref-typologies-list"></tbody></table>
    </section>

    <section class="ref-editor">
      <h2>Ad hoc constraints</h2>
      <form id="ref-contrainte-form" class="ref-form">
        <div class="ref-fields">
          <label>Id <input name="id" required></label>
          <label>Type
            <select name="type">${CONTRAINTE_TYPES.map(([v, l]) => `<option value="${v}">${l}</option>`).join('')}</select>
          </label>
          <label>Timeslot (optional)
            <select name="creneau" id="ref-contrainte-creneau"></select>
          </label>
          <label>Stand (optional)
            <select name="stand" id="ref-contrainte-stand"></select>
          </label>
          <label>Reason <input name="raison"></label>
        </div>
        <fieldset class="ref-subeditor">
          <legend>Animators concerned</legend>
          <div id="ref-contrainte-animateurs" class="ref-checkbox-grid"></div>
        </fieldset>
        <div class="ref-actions">
          <button type="submit">Save constraint</button>
          <button type="button" class="ref-cancel" data-entity="contrainte">Cancel edit</button>
        </div>
      </form>
      <table class="ref-list"><tbody id="ref-contraintes-list"></tbody></table>
    </section>
  `;
}

function wireForms() {
  root.querySelector('#ref-stand-form').addEventListener('submit', onStandSubmit);
  root.querySelector('#ref-animateur-form').addEventListener('submit', onAnimateurSubmit);
  root.querySelector('#ref-creneau-form').addEventListener('submit', onCreneauSubmit);
  root.querySelector('#ref-typologie-form').addEventListener('submit', onTypologieSubmit);
  root.querySelector('#ref-contrainte-form').addEventListener('submit', onContrainteSubmit);

  root.querySelector('#ref-add-competence').addEventListener('click', () => addCompetenceRow());
  root.querySelector('#ref-add-jour').addEventListener('click', onAddJour);

  root.querySelectorAll('.ref-cancel').forEach((button) => {
    button.addEventListener('click', () => cancelEdit(button.dataset.entity));
  });
}

async function reloadAndRender() {
  const [typologies, creneaux, animateurs, stands, contraintes] = await Promise.all([
    getJson('/api/typologies'),
    getJson('/api/creneaux'),
    getJson('/api/animateurs'),
    getJson('/api/stands'),
    getJson('/api/contraintes-ad-hoc')
  ]);
  state.typologies = typologies;
  state.creneaux = creneaux;
  state.animateurs = animateurs;
  state.stands = stands;
  state.contraintes = contraintes;
  renderAll();
}

function renderAll() {
  renderStandTypologyChoices();
  renderStandsList();
  renderAnimateursList();
  renderCreneauxList();
  renderTypologiesList();
  renderContrainteOptions();
  renderContraintesList();
}

/* ------------------------------- Stands -------------------------------- */

function renderStandTypologyChoices(selected = []) {
  const container = root.querySelector('#ref-stand-typologies');
  container.innerHTML = state.typologies
    .map(
      (typo) => `<label class="ref-check"><input type="checkbox" value="${typo.id}"${
        selected.includes(typo.id) ? ' checked' : ''
      }> ${typo.label || typo.id}</label>`
    )
    .join('');
}

function renderStandsList() {
  const body = root.querySelector('#ref-stands-list');
  body.innerHTML = '';
  state.stands.forEach((stand) => {
    const typos = (stand.typologiesProposees || []).join(', ');
    const summary = `<strong>${stand.id}</strong> — ${escapeHtml(stand.nom || '')} `
      + `[${stand.effectifMin}-${stand.effectifMax}${stand.reserveMajeurs ? ', adults' : ''}] ${typos}`;
    body.appendChild(listRow(summary, () => editStand(stand), () => remove('stands', stand.id)));
  });
}

async function onStandSubmit(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const selected = Array.from(root.querySelectorAll('#ref-stand-typologies input:checked')).map((i) => i.value);
  const stand = {
    id: form.elements.id.value.trim(),
    nom: form.elements.nom.value.trim(),
    typologiesProposees: selected,
    effectifMin: Number(form.elements.effectifMin.value) || 0,
    effectifMax: Number(form.elements.effectifMax.value) || 0,
    reserveMajeurs: form.elements.reserveMajeurs.checked
  };
  await save('stands', stand, state.editing.stand);
  cancelEdit('stand');
}

function editStand(stand) {
  state.editing.stand = stand.id;
  const form = root.querySelector('#ref-stand-form');
  form.elements.id.value = stand.id;
  form.elements.id.readOnly = true;
  form.elements.nom.value = stand.nom || '';
  form.elements.effectifMin.value = stand.effectifMin;
  form.elements.effectifMax.value = stand.effectifMax;
  form.elements.reserveMajeurs.checked = Boolean(stand.reserveMajeurs);
  renderStandTypologyChoices(stand.typologiesProposees || []);
  markEditing('stand');
}

/* ------------------------------ Animateurs ----------------------------- */

function renderAnimateursList() {
  const body = root.querySelector('#ref-animateurs-list');
  body.innerHTML = '';
  state.animateurs.forEach((animateur) => {
    const comps = Object.entries(animateur.competences || {})
      .map(([typo, niv]) => `${typo}:${niv}`)
      .join(', ');
    const off = (animateur.joursIndisponibles || []).length;
    const summary = `<strong>${animateur.id}</strong> — ${escapeHtml(animateur.prenom || '')} `
      + `${escapeHtml(animateur.nom || '')} [${animateur.statut || ''}] ${comps}`
      + `${off ? ` · ${off} off day(s)` : ''}`;
    body.appendChild(listRow(summary, () => editAnimateur(animateur), () => remove('animateurs', animateur.id)));
  });
}

function addCompetenceRow(typo = '', niveau = 'AUTONOME') {
  const container = root.querySelector('#ref-competences');
  const row = document.createElement('div');
  row.className = 'ref-competence-row ref-inline';
  const typoOptions = state.typologies
    .map((t) => `<option value="${t.id}"${t.id === typo ? ' selected' : ''}>${t.label || t.id}</option>`)
    .join('');
  const nivOptions = NIVEAUX.map((n) => `<option value="${n}"${n === niveau ? ' selected' : ''}>${n}</option>`).join('');
  row.innerHTML = `<select class="ref-comp-typo">${typoOptions}</select>`
    + `<select class="ref-comp-niveau">${nivOptions}</select>`
    + `<button type="button" class="ref-mini ref-remove">×</button>`;
  row.querySelector('.ref-remove').addEventListener('click', () => row.remove());
  container.appendChild(row);
}

function onAddJour() {
  const input = root.querySelector('#ref-jour-input');
  if (input.value) {
    addJourChip(input.value);
    input.value = '';
  }
}

function addJourChip(date) {
  const container = root.querySelector('#ref-jours');
  if (Array.from(container.children).some((chip) => chip.dataset.date === date)) {
    return;
  }
  const chip = document.createElement('span');
  chip.className = 'ref-chip';
  chip.dataset.date = date;
  chip.innerHTML = `${date} <button type="button" class="ref-chip-remove">×</button>`;
  chip.querySelector('.ref-chip-remove').addEventListener('click', () => chip.remove());
  container.appendChild(chip);
}

async function onAnimateurSubmit(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const competences = {};
  root.querySelectorAll('#ref-competences .ref-competence-row').forEach((row) => {
    const typo = row.querySelector('.ref-comp-typo').value;
    const niveau = row.querySelector('.ref-comp-niveau').value;
    if (typo) {
      competences[typo] = niveau;
    }
  });
  const joursIndisponibles = Array.from(root.querySelectorAll('#ref-jours .ref-chip')).map((chip) => chip.dataset.date);
  const animateur = {
    id: form.elements.id.value.trim(),
    prenom: form.elements.prenom.value.trim(),
    nom: form.elements.nom.value.trim(),
    dateNaissance: form.elements.dateNaissance.value || null,
    statut: form.elements.statut.value,
    competences,
    joursIndisponibles
  };
  await save('animateurs', animateur, state.editing.animateur);
  cancelEdit('animateur');
}

function editAnimateur(animateur) {
  state.editing.animateur = animateur.id;
  const form = root.querySelector('#ref-animateur-form');
  form.elements.id.value = animateur.id;
  form.elements.id.readOnly = true;
  form.elements.prenom.value = animateur.prenom || '';
  form.elements.nom.value = animateur.nom || '';
  form.elements.dateNaissance.value = animateur.dateNaissance || '';
  form.elements.statut.value = animateur.statut || STATUTS[0];
  root.querySelector('#ref-competences').innerHTML = '';
  Object.entries(animateur.competences || {}).forEach(([typo, niveau]) => addCompetenceRow(typo, niveau));
  root.querySelector('#ref-jours').innerHTML = '';
  (animateur.joursIndisponibles || []).forEach((date) => addJourChip(date));
  markEditing('animateur');
}

/* ------------------------------- Creneaux ------------------------------ */

function renderCreneauxList() {
  const body = root.querySelector('#ref-creneaux-list');
  body.innerHTML = '';
  state.creneaux.forEach((creneau) => {
    const summary = `<strong>${creneau.id}</strong> — J${creneau.jour} ${creneau.date || ''} `
      + `${creneau.heureDebut || ''}-${creneau.heureFin || ''}`;
    body.appendChild(listRow(summary, () => editCreneau(creneau), () => remove('creneaux', creneau.id)));
  });
}

async function onCreneauSubmit(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const creneau = {
    id: form.elements.id.value.trim(),
    jour: Number(form.elements.jour.value),
    date: form.elements.date.value,
    heureDebut: form.elements.heureDebut.value,
    heureFin: form.elements.heureFin.value
  };
  await save('creneaux', creneau, state.editing.creneau);
  cancelEdit('creneau');
}

function editCreneau(creneau) {
  state.editing.creneau = creneau.id;
  const form = root.querySelector('#ref-creneau-form');
  form.elements.id.value = creneau.id;
  form.elements.id.readOnly = true;
  form.elements.jour.value = creneau.jour;
  form.elements.date.value = creneau.date || '';
  form.elements.heureDebut.value = creneau.heureDebut || '';
  form.elements.heureFin.value = creneau.heureFin || '';
  markEditing('creneau');
}

/* ------------------------------ Typologies ----------------------------- */

function renderTypologiesList() {
  const body = root.querySelector('#ref-typologies-list');
  body.innerHTML = '';
  state.typologies.forEach((typologie) => {
    const summary = `<strong>${typologie.id}</strong> — ${escapeHtml(typologie.label || '')}`;
    body.appendChild(listRow(summary, () => editTypologie(typologie), () => remove('typologies', typologie.id)));
  });
}

async function onTypologieSubmit(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const typologie = {
    id: form.elements.id.value.trim(),
    label: form.elements.label.value.trim()
  };
  await save('typologies', typologie, state.editing.typologie);
  cancelEdit('typologie');
}

function editTypologie(typologie) {
  state.editing.typologie = typologie.id;
  const form = root.querySelector('#ref-typologie-form');
  form.elements.id.value = typologie.id;
  form.elements.id.readOnly = true;
  form.elements.label.value = typologie.label || '';
  markEditing('typologie');
}

/* ---------------------------- Ad hoc constraints ----------------------- */

function renderContrainteOptions() {
  const creneauSelect = root.querySelector('#ref-contrainte-creneau');
  creneauSelect.innerHTML = '<option value="">— none —</option>'
    + state.creneaux.map((c) => `<option value="${c.id}">${c.id}</option>`).join('');
  const standSelect = root.querySelector('#ref-contrainte-stand');
  standSelect.innerHTML = '<option value="">— none —</option>'
    + state.stands.map((s) => `<option value="${s.id}">${s.id} — ${escapeHtml(s.nom || '')}</option>`).join('');
  renderContrainteAnimateurChoices();
}

function renderContrainteAnimateurChoices(selected = []) {
  const container = root.querySelector('#ref-contrainte-animateurs');
  container.innerHTML = state.animateurs
    .map(
      (a) => `<label class="ref-check"><input type="checkbox" value="${a.id}"${
        selected.includes(a.id) ? ' checked' : ''
      }> ${a.id} — ${escapeHtml(a.prenom || '')} ${escapeHtml(a.nom || '')}</label>`
    )
    .join('');
}

function renderContraintesList() {
  const body = root.querySelector('#ref-contraintes-list');
  body.innerHTML = '';
  state.contraintes.forEach((contrainte) => {
    const ids = (contrainte.animateursConcernes || []).map((a) => a.id).join(', ');
    const scope = [contrainte.creneau ? `slot ${contrainte.creneau.id}` : '', contrainte.stand ? `stand ${contrainte.stand.id}` : '']
      .filter(Boolean)
      .join(' / ');
    const summary = `<strong>${contrainte.id}</strong> — ${contrainte.type} [${ids}]${scope ? ` · ${scope}` : ''}`;
    body.appendChild(listRow(summary, () => editContrainte(contrainte), () => remove('contraintes-ad-hoc', contrainte.id)));
  });
}

async function onContrainteSubmit(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const selectedIds = Array.from(root.querySelectorAll('#ref-contrainte-animateurs input:checked')).map((i) => i.value);
  const creneauId = form.elements.creneau.value;
  const standId = form.elements.stand.value;
  const contrainte = {
    id: form.elements.id.value.trim(),
    type: form.elements.type.value,
    animateursConcernes: selectedIds.map((id) => ({ id })),
    creneau: creneauId ? { id: creneauId } : null,
    stand: standId ? { id: standId } : null,
    raison: form.elements.raison.value.trim(),
    creeParUtilisateurId: 'ui'
  };
  // Backend exposes POST (create/overwrite by id) and DELETE only.
  await fetchJson('/api/contraintes-ad-hoc', { method: 'POST', body: JSON.stringify(contrainte) });
  notify(`Constraint ${contrainte.id} saved.`);
  cancelEdit('contrainte');
  await reloadAndRender();
}

function editContrainte(contrainte) {
  state.editing.contrainte = contrainte.id;
  const form = root.querySelector('#ref-contrainte-form');
  form.elements.id.value = contrainte.id;
  form.elements.id.readOnly = true;
  form.elements.type.value = contrainte.type;
  form.elements.creneau.value = contrainte.creneau ? contrainte.creneau.id : '';
  form.elements.stand.value = contrainte.stand ? contrainte.stand.id : '';
  form.elements.raison.value = contrainte.raison || '';
  renderContrainteAnimateurChoices((contrainte.animateursConcernes || []).map((a) => a.id));
  markEditing('contrainte');
}

/* -------------------------------- Shared ------------------------------- */

async function save(resource, payload, editingId) {
  if (editingId) {
    await fetchJson(`/api/${resource}/${encodeURIComponent(editingId)}`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    });
    notify(`${payload.id} updated.`);
  } else {
    await fetchJson(`/api/${resource}`, { method: 'POST', body: JSON.stringify(payload) });
    notify(`${payload.id} created.`);
  }
  await reloadAndRender();
}

async function remove(resource, id) {
  if (!window.confirm(`Delete ${id}?`)) {
    return;
  }
  const response = await fetch(`/api/${resource}/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!response.ok) {
    showError(new Error(`Delete failed with ${response.status}`));
    return;
  }
  notify(`${id} deleted.`);
  await reloadAndRender();
}

function cancelEdit(entity) {
  state.editing[entity] = null;
  const form = root.querySelector(`#ref-${entity}-form`);
  form.reset();
  form.elements.id.readOnly = false;
  form.classList.remove('is-editing');
  if (entity === 'animateur') {
    root.querySelector('#ref-competences').innerHTML = '';
    root.querySelector('#ref-jours').innerHTML = '';
  }
  if (entity === 'stand') {
    renderStandTypologyChoices();
  }
  if (entity === 'contrainte') {
    renderContrainteAnimateurChoices();
  }
}

function markEditing(entity) {
  root.querySelector(`#ref-${entity}-form`).classList.add('is-editing');
  notify(`Editing ${state.editing[entity]}…`);
}

function listRow(html, onEdit, onDelete) {
  const tr = document.createElement('tr');
  const cell = document.createElement('td');
  cell.className = 'ref-cell';
  cell.innerHTML = html;
  const actions = document.createElement('td');
  actions.className = 'ref-row-actions';
  const editBtn = document.createElement('button');
  editBtn.type = 'button';
  editBtn.className = 'ref-mini';
  editBtn.textContent = 'Edit';
  editBtn.addEventListener('click', onEdit);
  const delBtn = document.createElement('button');
  delBtn.type = 'button';
  delBtn.className = 'ref-mini ref-danger';
  delBtn.textContent = 'Delete';
  delBtn.addEventListener('click', onDelete);
  actions.append(editBtn, delBtn);
  tr.append(cell, actions);
  return tr;
}

function notify(message) {
  if (statusBar) {
    statusBar.textContent = message;
    statusBar.classList.remove('is-error');
  }
}

function showError(error) {
  if (statusBar) {
    statusBar.textContent = `Error: ${error.message}`;
    statusBar.classList.add('is-error');
  }
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[char]));
}
