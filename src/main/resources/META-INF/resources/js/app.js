// Application entry point: page navigation and module wiring.

import { initAdmin } from './admin.js';
import { initReferenceData } from './reference-data.js';
import { initMonthCalendar, renderCalendar } from './calendar-month.js';
import { initDayCalendar, renderDayCalendar } from './calendar-day.js';

const navButtons = document.querySelectorAll('.nav-btn');
const pages = document.querySelectorAll('.page');

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

initAdmin();
initReferenceData();
initMonthCalendar();
initDayCalendar();
