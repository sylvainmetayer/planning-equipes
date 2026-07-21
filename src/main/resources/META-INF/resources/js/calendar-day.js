// Day calendar grouped by festival day.

import { getJson } from './api.js';
import { ensurePlanning } from './planning-state.js';

const dayCalendarRefreshButton = document.getElementById('day-calendar-refresh-btn');
const dayCalendarContainer = document.getElementById('day-calendar-container');
const persistedCountLabel = document.getElementById('persisted-count');

export function initDayCalendar() {
  dayCalendarRefreshButton.addEventListener('click', () => {
    renderDayCalendar().catch((error) => {
      dayCalendarContainer.textContent = `Error: ${error.message}`;
    });
  });
}

export async function renderDayCalendar() {
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
