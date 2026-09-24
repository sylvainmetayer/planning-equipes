// The rules of the consigne form's draft, next to `consignes.ts` whose state
// it persists: what is kept, what counts as a change, what can be restored.
//
// The consigne form differs from the two fiches in one respect: its stands
// list is not typed, it is *proposed* by the server for the first date and
// the band, and re-read whenever they move. A draft therefore keeps the rows
// only once a gesture touched one of them — otherwise restoring the dates and
// the band is enough, and the server proposes again — and « modified » never
// counts a proposal landing, only what the user did.

import { ConsigneEdition } from '../../core/models';
import type { ModeConsigne } from './consigne-form-dialog';
import { ConsigneForm, FenetreSaisie, RepasSaisie, StandForm } from './consignes';

/** What is persisted: the form, the stands only when a gesture chose among them. */
export interface ConsigneDraft {
  dates: string[];
  fermetureDebut: string;
  fermetureFin: string;
  motif: string;
  prereglage: string | null;
  fenetres: FenetreSaisie[];
  repas: RepasSaisie;
  /** `null` while no stand row was touched: the server's proposal is then taken afresh. */
  stands: StandForm[] | null;
}

/** The record a consigne form is about: `null` for a new one, else the mode and the row's date. */
export function consigneRecordId(
  mode: ModeConsigne,
  consigne: ConsigneEdition | null,
): string | null {
  return mode === 'poser' || consigne === null ? null : `${mode}.${consigne.date}`;
}

/** The date a record id names — what a page checks against the consignes it holds. */
export function consigneDateOf(recordId: string): string {
  const dot = recordId.indexOf('.');
  return dot === -1 ? recordId : recordId.slice(dot + 1);
}

/** The draft of a form: everything typed, the rows only when `standsTouched`. */
export function toConsigneDraft(form: ConsigneForm, standsTouched: boolean): ConsigneDraft {
  return {
    dates: [...form.dates],
    fermetureDebut: form.fermetureDebut,
    fermetureFin: form.fermetureFin,
    motif: form.motif,
    prereglage: form.prereglage,
    fenetres: form.fenetres,
    repas: form.repas,
    stands: standsTouched ? form.stands : null,
  };
}

/**
 * True when the form no longer says what it said at opening. The typed fields
 * are compared; the stand rows count as soon as a gesture touched one —
 * their content comes and goes with the server's proposal, so comparing it
 * would call every re-read a modification.
 */
export function isConsigneModified(initial: ConsigneDraft, current: ConsigneDraft): boolean {
  if (current.stands !== null) {
    return true;
  }
  return JSON.stringify(withoutStands(initial)) !== JSON.stringify(withoutStands(current));
}

/** What the storage hands back, checked on its shape; `null` for anything the form could not hold. */
export function readConsigneDraft(raw: unknown): ConsigneDraft | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const draft = raw as Record<string, unknown>;
  const valid =
    Array.isArray(draft['dates']) &&
    draft['dates'].every((date) => typeof date === 'string') &&
    typeof draft['fermetureDebut'] === 'string' &&
    typeof draft['fermetureFin'] === 'string' &&
    typeof draft['motif'] === 'string' &&
    (draft['prereglage'] === null || typeof draft['prereglage'] === 'string') &&
    Array.isArray(draft['fenetres']) &&
    draft['fenetres'].every(isWindow) &&
    isMeals(draft['repas']) &&
    (draft['stands'] === null ||
      (Array.isArray(draft['stands']) && draft['stands'].every(isStandRow)));
  return valid ? (raw as ConsigneDraft) : null;
}

function withoutStands(draft: ConsigneDraft): Omit<ConsigneDraft, 'stands'> {
  const { stands: _stands, ...rest } = draft;
  return rest;
}

function isWindow(value: unknown): boolean {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const slot = value as Record<string, unknown>;
  return (
    typeof slot['debut'] === 'string' &&
    typeof slot['fin'] === 'string' &&
    typeof slot['effectif'] === 'string'
  );
}

function isMeals(value: unknown): boolean {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const meals = value as Record<string, unknown>;
  return ['midiDebut', 'midiFin', 'soirDebut', 'soirFin', 'coupureMinutes', 'justification'].every(
    (field) => typeof meals[field] === 'string',
  );
}

function isStandRow(value: unknown): boolean {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const row = value as Record<string, unknown>;
  return (
    typeof row['standId'] === 'string' &&
    typeof row['coche'] === 'boolean' &&
    Array.isArray(row['fenetres']) &&
    row['fenetres'].every(isWindow)
  );
}
