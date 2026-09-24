// The rules of the fiche stand's draft, next to `stand-draft.ts` whose state
// it persists: what counts as a change, what can be restored. The draft is
// stored as the form holds it — `saisie` of a rule included, the line as
// typed, so a half-written `10:0` comes back as it was left.

import { formaterFenetres } from '../../core/horaire-stand';
import { HoraireDraft, StandDraft } from './stand-draft';

const EFFORT_LEVELS = ['NORMAL', 'EPUISANT'];

/**
 * True when the form no longer says what it said at opening. Only what is
 * sent counts, plus a line still being typed: unfolding a rule or switching
 * it to its detailed view is reading, not editing.
 */
export function isStandModified(initial: StandDraft, current: StandDraft): boolean {
  return fingerprint(initial) !== fingerprint(current);
}

/**
 * What the storage hands back, checked on its shape: `null` for anything the
 * form could not hold, or for a draft of a stand other than the one opened
 * (`recordId`, `null` for a creation).
 */
export function readStandDraft(raw: unknown, recordId: string | null): StandDraft | null {
  if (!isObject(raw)) {
    return null;
  }
  const valid =
    typeof raw['id'] === 'string' &&
    typeof raw['nom'] === 'string' &&
    typeof raw['reserveMajeurs'] === 'boolean' &&
    typeof raw['premium'] === 'boolean' &&
    EFFORT_LEVELS.includes(raw['niveauEffort'] as string) &&
    (raw['emplacementId'] === null || typeof raw['emplacementId'] === 'string') &&
    (raw['modifieLe'] === null || typeof raw['modifieLe'] === 'string') &&
    Array.isArray(raw['typologiesProposees']) &&
    raw['typologiesProposees'].every((id) => typeof id === 'string') &&
    isObjectList(raw['indisponibilites']) &&
    isObjectList(raw['ouvertures']) &&
    isObjectList(raw['horaires']) &&
    raw['horaires'].every(
      (rule) =>
        Array.isArray(rule['fenetres']) &&
        Array.isArray(rule['joursSemaine']) &&
        Array.isArray(rule['dates']),
    );
  if (!valid) {
    return null;
  }
  if (recordId !== null && raw['id'] !== recordId) {
    return null;
  }
  return raw as unknown as StandDraft;
}

function fingerprint(draft: StandDraft): string {
  const { modifieLe: _modifieLe, horaires, ...rest } = draft;
  return JSON.stringify({ ...rest, horaires: horaires.map(ruleAsTyped) });
}

/** A rule without its display state; the typed line kept only while it says more than the windows. */
function ruleAsTyped(rule: HoraireDraft): HoraireDraft {
  const { saisie, deplie: _deplie, detail: _detail, ...entity } = rule;
  const typing = typeof saisie === 'string' && saisie !== formaterFenetres(rule.fenetres);
  return typing ? { ...entity, saisie } : entity;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isObjectList(value: unknown): value is Record<string, unknown>[] {
  return Array.isArray(value) && value.every(isObject);
}
