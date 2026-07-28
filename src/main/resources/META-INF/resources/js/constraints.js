// Constraints page: business catalogue of the active rules, enriched with the
// score of the latest analysis when one is available.

import { getJson } from './api.js';

const constraintsContainer = document.getElementById('constraints-container');
const constraintsSummary = document.getElementById('constraints-summary');
const constraintsRefreshButton = document.getElementById('constraints-refresh-btn');

const NIVEAU_LABELS = {
  HARD: 'Dure (bloquante)',
  MEDIUM: 'Medium (fortement pénalisée)',
  SOFT: 'Souple (optimisée en dernier)'
};

export function initConstraints() {
  constraintsRefreshButton.addEventListener('click', () => {
    renderConstraints().catch((error) => {
      constraintsContainer.textContent = `Error: ${error.message}`;
    });
  });
}

export async function renderConstraints() {
  constraintsContainer.textContent = 'Loading constraints...';
  const view = await getJson('/api/constraints');
  renderSummary(view);
  renderGroups(view.contraintes || []);
}

function renderSummary(view) {
  constraintsSummary.textContent = '';
  if (!view.analysedAt) {
    constraintsSummary.textContent =
      'No analysis yet — run "Analyze solution" from the Administration page to see how each rule scored.';
    return;
  }
  const analysedAt = new Date(view.analysedAt).toLocaleString();
  constraintsSummary.textContent =
    `Last analysis ${analysedAt} — score ${view.scoreGlobal}, ${view.postesNonPourvus} unfilled seats.`;
}

function renderGroups(constraints) {
  constraintsContainer.textContent = '';
  if (constraints.length === 0) {
    constraintsContainer.textContent = 'No constraint declared.';
    return;
  }
  const groups = new Map();
  constraints.forEach((constraint) => {
    if (!groups.has(constraint.categorie)) {
      groups.set(constraint.categorie, []);
    }
    groups.get(constraint.categorie).push(constraint);
  });

  groups.forEach((items, categorie) => {
    const section = document.createElement('section');
    section.className = 'constraint-group';

    const heading = document.createElement('h3');
    heading.textContent = categorie;
    section.appendChild(heading);

    items.forEach((constraint) => section.appendChild(buildCard(constraint)));
    constraintsContainer.appendChild(section);
  });
}

function buildCard(constraint) {
  const card = document.createElement('article');
  card.className = 'constraint-card';

  const header = document.createElement('div');
  header.className = 'constraint-card-header';

  const name = document.createElement('strong');
  name.textContent = constraint.name;
  header.appendChild(name);

  const badge = document.createElement('span');
  badge.className = `constraint-badge constraint-badge-${constraint.niveau.toLowerCase()}`;
  badge.textContent = NIVEAU_LABELS[constraint.niveau] || constraint.niveau;
  header.appendChild(badge);

  card.appendChild(header);

  const description = document.createElement('p');
  description.className = 'constraint-description';
  description.textContent = constraint.description;
  card.appendChild(description);

  const result = document.createElement('p');
  result.className = 'constraint-result';
  if (constraint.score === null || constraint.score === undefined) {
    result.classList.add('constraint-result-empty');
    result.textContent = 'Not evaluated yet.';
  } else {
    const violated = constraint.matchCount > 0;
    result.classList.add(violated ? 'constraint-result-hit' : 'constraint-result-clean');
    result.textContent = violated
      ? `${constraint.matchCount} match(es) — score ${constraint.score}`
      : `Satisfied — score ${constraint.score}`;
  }
  card.appendChild(result);

  return card;
}
