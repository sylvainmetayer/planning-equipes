// Data transfer page section: SQL dump export/import and CSV imports of the
// reference data. Kept out of admin.js which owns the planning actions.

import { downloadUrl, postRaw } from './api.js';
import { reloadReferenceData } from './reference-data.js';
import { setLastSolvedPlanning } from './planning-state.js';

const output = document.getElementById('data-transfer-output');
const exportSqlButton = document.getElementById('export-sql-btn');
const importSqlButton = document.getElementById('import-sql-btn');
const importSqlInput = document.getElementById('import-sql-input');
const importCsvInput = document.getElementById('import-csv-input');
const csvButtons = {
  animateurs: document.getElementById('import-csv-animateurs-btn'),
  stands: document.getElementById('import-csv-stands-btn'),
  creneaux: document.getElementById('import-csv-creneaux-btn')
};

// Entity awaiting the file picked in the shared CSV file input.
let pendingCsvEntity = null;

export function initDataTransfer() {
  exportSqlButton.addEventListener('click', onExportSql);
  importSqlButton.addEventListener('click', () => importSqlInput.click());
  importSqlInput.addEventListener('change', onImportSql);
  Object.entries(csvButtons).forEach(([entity, button]) => {
    button.addEventListener('click', () => {
      pendingCsvEntity = entity;
      importCsvInput.click();
    });
  });
  importCsvInput.addEventListener('change', onImportCsv);
}

async function onImportCsv(event) {
  const entity = pendingCsvEntity;
  const file = event.target.files && event.target.files[0];
  event.target.value = '';
  pendingCsvEntity = null;
  if (!file || !entity) {
    return;
  }
  if (!window.confirm(`Import ${file.name} as ${entity}? Existing ${entity} and every assignment are replaced.`)) {
    return;
  }
  const button = csvButtons[entity];
  button.disabled = true;
  output.textContent = `Importing ${file.name} as ${entity}...`;
  try {
    const csv = await file.text();
    const summary = await postRaw(`/api/import/csv/${entity}`, csv, 'text/csv');
    setLastSolvedPlanning(null);
    await reloadReferenceData();
    output.textContent = summary.message;
  } catch (error) {
    output.textContent = `Error: ${error.message}`;
  } finally {
    button.disabled = false;
  }
}

async function onExportSql() {
  exportSqlButton.disabled = true;
  output.textContent = 'Building the SQL dump...';
  try {
    const message = await downloadUrl('/api/database/export', 'planning-equipes.sql', 'application/sql');
    output.textContent = message;
  } catch (error) {
    output.textContent = `Error: ${error.message}`;
  } finally {
    exportSqlButton.disabled = false;
  }
}

async function onImportSql(event) {
  const file = event.target.files && event.target.files[0];
  event.target.value = '';
  if (!file) {
    return;
  }
  if (!window.confirm(`Replay ${file.name}? The current database content is replaced.`)) {
    return;
  }
  importSqlButton.disabled = true;
  output.textContent = `Importing ${file.name}...`;
  try {
    const script = await file.text();
    const summary = await postRaw('/api/database/import', script, 'application/sql');
    setLastSolvedPlanning(null);
    await reloadReferenceData();
    output.textContent = summary.message;
  } catch (error) {
    output.textContent = `Error: ${error.message}`;
  } finally {
    importSqlButton.disabled = false;
  }
}
