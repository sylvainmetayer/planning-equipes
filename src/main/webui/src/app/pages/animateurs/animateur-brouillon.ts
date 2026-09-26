// The rules of the fiche animateur's draft, out of the dialog so they are
// tested without rendering — the pattern of `declaration-brouillon.ts`: where
// the form opens from, what counts as a change, what can be restored.
//
// The draft itself is persisted by `core/brouillon-formulaire.ts`, in
// sessionStorage only (see `SESSION_DRAFT_STORAGE`): it carries an identity,
// a birth date, an e-mail and a phone number.

import { Animateur, NiveauCompetence } from '../../core/models';

const LEVELS: ReadonlySet<unknown> = new Set<NiveauCompetence>([
  'DEBUTANT',
  'AUTONOME',
  'REFERENT',
]);

export interface CompetenceRow {
  typologie: string;
  niveau: NiveauCompetence;
}

/** The form's own state: flat, strings where the inputs are. */
export interface AnimateurDraft {
  id: string;
  prenom: string;
  nom: string;
  dateNaissance: string;
  manager: boolean;
  email: string;
  telephone: string;
  competences: CompetenceRow[];
  souhaits: string[];
  joursIndisponibles: string[];
  /** The store's `modifieLe` at opening, sent back as the write's precondition (issue #362). */
  modifieLe: string | null;
}

/** Where the form opens from: the fiche as the store holds it, or an empty one. */
export function toDraft(animateur: Animateur | null): AnimateurDraft {
  if (!animateur) {
    return {
      id: '',
      prenom: '',
      nom: '',
      dateNaissance: '',
      manager: false,
      email: '',
      telephone: '',
      competences: [],
      souhaits: [],
      joursIndisponibles: [],
      modifieLe: null,
    };
  }
  return {
    id: animateur.id,
    modifieLe: animateur.modifieLe ?? null,
    prenom: animateur.prenom ?? '',
    nom: animateur.nom ?? '',
    dateNaissance: animateur.dateNaissance ?? '',
    manager: animateur.manager ?? false,
    email: animateur.email ?? '',
    telephone: animateur.telephone ?? '',
    competences: Object.entries(animateur.competences ?? {}).map(([typologie, niveau]) => ({
      typologie,
      niveau,
    })),
    souhaits: [...(animateur.souhaits ?? [])],
    joursIndisponibles: [...(animateur.joursIndisponibles ?? [])],
  };
}

/**
 * True when the form no longer says what it said at opening. Every field
 * counts, the precondition excepted — it is not typed, it is carried.
 */
export function isAnimateurModified(initial: AnimateurDraft, current: AnimateurDraft): boolean {
  return fingerprint(initial) !== fingerprint(current);
}

/**
 * What the storage hands back, checked field by field: `null` for anything a
 * form could not hold — a draft of another shape, tampered with, or of a
 * fiche other than the one opened (`recordId`, `null` for a creation).
 */
export function readAnimateurDraft(raw: unknown, recordId: string | null): AnimateurDraft | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const draft = raw as Record<string, unknown>;
  const textFields = ['id', 'prenom', 'nom', 'dateNaissance', 'email', 'telephone'] as const;
  if (!textFields.every((field) => typeof draft[field] === 'string')) {
    return null;
  }
  if (
    typeof draft['manager'] !== 'boolean' ||
    !(draft['modifieLe'] === null || typeof draft['modifieLe'] === 'string') ||
    !isStringList(draft['souhaits']) ||
    !isStringList(draft['joursIndisponibles']) ||
    !Array.isArray(draft['competences']) ||
    !draft['competences'].every(isCompetenceRow)
  ) {
    return null;
  }
  if (recordId !== null && draft['id'] !== recordId) {
    return null;
  }
  return raw as AnimateurDraft;
}

function fingerprint(draft: AnimateurDraft): string {
  const { modifieLe: _modifieLe, ...typed } = draft;
  return JSON.stringify(typed);
}

function isStringList(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

function isCompetenceRow(value: unknown): value is CompetenceRow {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const row = value as Record<string, unknown>;
  return typeof row['typologie'] === 'string' && LEVELS.has(row['niveau']);
}
