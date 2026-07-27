// Monthly assignment calendar with animator/stand filters.

import { loadPlanningForDisplay } from './planning-state.js';
import { uniqueById } from './utils.js';
import {
  getMonthStart,
  shiftMonth,
  toDateKey,
  parseDateKey,
  buildMonthCells,
  pickDefaultDateKey
} from './date-utils.js';

const calendarRefreshButton = document.getElementById('calendar-refresh-btn');
const calendarContainer = document.getElementById('calendar-container');
const calendarMonthLabel = document.getElementById('calendar-month-label');
const calendarDayDetails = document.getElementById('calendar-day-details');
const calendarPrevMonthButton = document.getElementById('calendar-prev-month-btn');
const calendarNextMonthButton = document.getElementById('calendar-next-month-btn');
const calendarTodayButton = document.getElementById('calendar-today-btn');
const calendarResetFiltersButton = document.getElementById('calendar-reset-filters-btn');
const calendarAnimateurFilter = document.getElementById('calendar-animateur-filter');
const calendarStandFilter = document.getElementById('calendar-stand-filter');

let selectedCalendarDateKey = null;
let displayedCalendarMonth = getMonthStart(new Date());
let selectedCalendarAnimateurId = 'ALL';
let selectedCalendarStandId = 'ALL';

function renderCalendarSafe() {
  renderCalendar().catch((error) => {
    calendarContainer.textContent = `Error: ${error.message}`;
  });
}

export function initMonthCalendar() {
  calendarRefreshButton.addEventListener('click', renderCalendarSafe);

  calendarPrevMonthButton.addEventListener('click', () => {
    displayedCalendarMonth = shiftMonth(displayedCalendarMonth, -1);
    renderCalendarSafe();
  });

  calendarNextMonthButton.addEventListener('click', () => {
    displayedCalendarMonth = shiftMonth(displayedCalendarMonth, 1);
    renderCalendarSafe();
  });

  calendarTodayButton.addEventListener('click', () => {
    displayedCalendarMonth = getMonthStart(new Date());
    selectedCalendarDateKey = toDateKey(new Date());
    renderCalendarSafe();
  });

  calendarResetFiltersButton.addEventListener('click', () => {
    selectedCalendarAnimateurId = 'ALL';
    selectedCalendarStandId = 'ALL';
    selectedCalendarDateKey = null;
    calendarAnimateurFilter.value = 'ALL';
    calendarStandFilter.value = 'ALL';
    renderCalendarSafe();
  });

  calendarAnimateurFilter.addEventListener('change', () => {
    selectedCalendarAnimateurId = calendarAnimateurFilter.value;
    selectedCalendarDateKey = null;
    renderCalendarSafe();
  });

  calendarStandFilter.addEventListener('change', () => {
    selectedCalendarStandId = calendarStandFilter.value;
    selectedCalendarDateKey = null;
    renderCalendarSafe();
  });
}

export async function renderCalendar() {
  calendarContainer.innerHTML = '';
  calendarDayDetails.innerHTML = '<p class="calendar-empty">Loading calendar...</p>';
  const planning = await loadPlanningForDisplay();
  const postes = planning.postes || [];

  populateCalendarFilterOptions(postes);
  const filteredPostes = applyCalendarFilters(postes);

  const assignmentsByDate = buildAssignmentsByDate(filteredPostes);
  const monthCells = buildMonthCells(displayedCalendarMonth);
  const monthKey = `${displayedCalendarMonth.getFullYear()}-${String(displayedCalendarMonth.getMonth() + 1).padStart(2, '0')}`;

  if (assignmentsByDate.size === 0) {
    calendarContainer.innerHTML = '<p class="calendar-empty">No planning data available yet.</p>';
    calendarDayDetails.innerHTML =
      '<p class="calendar-empty">Run "Solve with Timefold" from the Administration page to fill this calendar.</p>';
    return;
  }

  const availableDateKeys = Array.from(assignmentsByDate.keys()).sort();
  if (!selectedCalendarDateKey || !assignmentsByDate.has(selectedCalendarDateKey)) {
    selectedCalendarDateKey = pickDefaultDateKey(availableDateKeys, monthKey);
  }

  const monthTitle = displayedCalendarMonth.toLocaleDateString(undefined, {
    month: 'long',
    year: 'numeric'
  });
  calendarMonthLabel.textContent = monthTitle;

  monthCells.forEach((cellDate) => {
    const key = toDateKey(cellDate);
    const dayAssignments = assignmentsByDate.get(key) || [];
    const cellButton = document.createElement('button');
    cellButton.type = 'button';
    cellButton.className = 'calendar-day-cell';

    if (cellDate.getMonth() !== displayedCalendarMonth.getMonth()) {
      cellButton.classList.add('is-other-month');
    }
    if (key === toDateKey(new Date())) {
      cellButton.classList.add('is-today');
    }
    if (key === selectedCalendarDateKey) {
      cellButton.classList.add('is-selected');
    }

    const dayNumber = document.createElement('div');
    dayNumber.className = 'calendar-day-number';
    dayNumber.textContent = cellDate.getDate();
    cellButton.appendChild(dayNumber);

    const dayMeta = document.createElement('div');
    dayMeta.className = 'calendar-day-meta';

    const countLabel = document.createElement('span');
    countLabel.textContent = `${dayAssignments.length} slot${dayAssignments.length > 1 ? 's' : ''}`;
    dayMeta.appendChild(countLabel);

    const dot = document.createElement('span');
    dot.className = `calendar-dot${dayAssignments.length > 0 ? ' has-events' : ''}`;
    dayMeta.appendChild(dot);

    cellButton.appendChild(dayMeta);
    cellButton.addEventListener('click', () => {
      selectedCalendarDateKey = key;
      if (cellDate.getMonth() !== displayedCalendarMonth.getMonth()) {
        displayedCalendarMonth = getMonthStart(cellDate);
      }
      renderCalendarSafe();
    });
    calendarContainer.appendChild(cellButton);
  });

  renderCalendarDayDetails(selectedCalendarDateKey, assignmentsByDate);
}

function buildAssignmentsByDate(postes) {
  const assignmentsByDate = new Map();
  postes.forEach((poste) => {
    const creneau = poste.creneau;
    const stand = poste.stand;
    if (!creneau || !stand || !creneau.date) {
      return;
    }

    const dateKey = creneau.date;
    if (!assignmentsByDate.has(dateKey)) {
      assignmentsByDate.set(dateKey, []);
    }

    const dateEntries = assignmentsByDate.get(dateKey);
    let slotEntry = dateEntries.find((entry) => entry.creneauId === creneau.id);
    if (!slotEntry) {
      slotEntry = {
        creneauId: creneau.id,
        heureDebut: creneau.heureDebut,
        heureFin: creneau.heureFin,
        jour: creneau.jour,
        stands: new Map()
      };
      dateEntries.push(slotEntry);
    }

    if (!slotEntry.stands.has(stand.id)) {
      slotEntry.stands.set(stand.id, {
        standNom: stand.nom,
        names: []
      });
    }

    if (poste.animateur) {
      const fullName = `${poste.animateur.prenom || ''} ${poste.animateur.nom || ''}`.trim();
      slotEntry.stands.get(stand.id).names.push(fullName);
    }
  });

  assignmentsByDate.forEach((entries, dateKey) => {
    entries.sort((left, right) => {
      return `${left.heureDebut}`.localeCompare(`${right.heureDebut}`);
    });
    entries.forEach((entry) => {
      entry.stands = Array.from(entry.stands.values()).sort((left, right) => {
        return (left.standNom || '').localeCompare(right.standNom || '');
      });
    });
    assignmentsByDate.set(dateKey, entries);
  });

  return assignmentsByDate;
}

function populateCalendarFilterOptions(postes) {
  const animateurs = uniqueById(
    postes
      .map((poste) => poste.animateur)
      .filter(Boolean)
  ).sort((left, right) => {
    return `${left.prenom || ''} ${left.nom || ''}`.localeCompare(`${right.prenom || ''} ${right.nom || ''}`);
  });

  const stands = uniqueById(
    postes
      .map((poste) => poste.stand)
      .filter(Boolean)
  ).sort((left, right) => {
    return (left.nom || '').localeCompare(right.nom || '');
  });

  fillSelectOptions(
    calendarAnimateurFilter,
    animateurs.map((animateur) => ({
      value: animateur.id,
      label: `${animateur.prenom || ''} ${animateur.nom || ''}`.trim()
    })),
    'ALL',
    'All animators'
  );

  fillSelectOptions(
    calendarStandFilter,
    stands.map((stand) => ({
      value: stand.id,
      label: stand.nom || stand.id
    })),
    'ALL',
    'All stands'
  );

  if (![...calendarAnimateurFilter.options].some((option) => option.value === selectedCalendarAnimateurId)) {
    selectedCalendarAnimateurId = 'ALL';
  }
  if (![...calendarStandFilter.options].some((option) => option.value === selectedCalendarStandId)) {
    selectedCalendarStandId = 'ALL';
  }

  calendarAnimateurFilter.value = selectedCalendarAnimateurId;
  calendarStandFilter.value = selectedCalendarStandId;
}

function fillSelectOptions(selectElement, options, allValue, allLabel) {
  selectElement.innerHTML = '';

  const allOption = document.createElement('option');
  allOption.value = allValue;
  allOption.textContent = allLabel;
  selectElement.appendChild(allOption);

  options.forEach((optionData) => {
    const option = document.createElement('option');
    option.value = optionData.value;
    option.textContent = optionData.label;
    selectElement.appendChild(option);
  });
}

function applyCalendarFilters(postes) {
  return postes.filter((poste) => {
    const standMatches = selectedCalendarStandId === 'ALL' || poste.stand?.id === selectedCalendarStandId;
    if (!standMatches) {
      return false;
    }

    if (selectedCalendarAnimateurId === 'ALL') {
      return true;
    }

    return poste.animateur?.id === selectedCalendarAnimateurId;
  });
}

function renderCalendarDayDetails(dateKey, assignmentsByDate) {
  const entries = assignmentsByDate.get(dateKey) || [];
  const date = parseDateKey(dateKey);
  const dayTitle = date.toLocaleDateString(undefined, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric'
  });

  calendarDayDetails.innerHTML = '';
  const title = document.createElement('h4');
  title.textContent = dayTitle;
  calendarDayDetails.appendChild(title);

  if (entries.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'calendar-empty';
    empty.textContent = 'No assignment for this date.';
    calendarDayDetails.appendChild(empty);
    return;
  }

  entries.forEach((entry) => {
    const slotBlock = document.createElement('div');
    slotBlock.className = 'day-detail-slot';

    const slotTitle = document.createElement('div');
    slotTitle.className = 'day-detail-title';
    slotTitle.textContent = `${entry.heureDebut} - ${entry.heureFin}`;
    slotBlock.appendChild(slotTitle);

    entry.stands.forEach((standInfo) => {
      const line = document.createElement('div');
      line.className = standInfo.names.length === 0 ? 'day-detail-line empty-slot' : 'day-detail-line';
      const namesLabel = standInfo.names.length === 0 ? '(unassigned)' : standInfo.names.join(', ');
      line.textContent = `${standInfo.standNom}: ${namesLabel}`;
      slotBlock.appendChild(line);
    });

    calendarDayDetails.appendChild(slotBlock);
  });
}
