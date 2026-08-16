// What a bulk edit of animateurs can change, and how it applies to one row.
// Kept apart from the dialog so the rules are unit-tested without rendering.

import { ModeBooleen, ModeListe, appliquerModeBooleen, appliquerModeListe } from '../../core/bulk-edit';
import { Animateur, NiveauCompetence } from '../../core/models';

/** Appréciation and indisponibilités are keyed edits: add/overwrite one entry, or remove it. */
export type ModeEntree = 'AUCUN' | 'AJOUTER' | 'RETIRER';

export interface AnimateurBulkPatch {
  manager: ModeBooleen;
  /** `AJOUTER` overwrites the level when the animateur already holds that typologie. */
  competence: { mode: ModeEntree; typologie: string; niveau: NiveauCompetence };
  souhaits: { mode: ModeListe; typologies: string[] };
  /** One ISO date added to (or removed from) every selected animateur's unavailable days. */
  indisponibilite: { mode: ModeEntree; jour: string };
}

export function patchAnimateurVide(): AnimateurBulkPatch {
  return {
    manager: 'INCHANGE',
    competence: { mode: 'AUCUN', typologie: '', niveau: 'AUTONOME' },
    souhaits: { mode: 'AUCUN', typologies: [] },
    indisponibilite: { mode: 'AUCUN', jour: '' }
  };
}

/** True while the form would change nothing: the submit button stays disabled. */
export function patchAnimateurEstVide(patch: AnimateurBulkPatch): boolean {
  const competenceInactive = patch.competence.mode === 'AUCUN' || !patch.competence.typologie;
  const souhaitsInactifs =
    patch.souhaits.mode === 'AUCUN' ||
    (patch.souhaits.typologies.length === 0 && patch.souhaits.mode !== 'REMPLACER');
  const indisponibiliteInactive = patch.indisponibilite.mode === 'AUCUN' || !patch.indisponibilite.jour;
  return patch.manager === 'INCHANGE' && competenceInactive && souhaitsInactifs && indisponibiliteInactive;
}

export function appliquerPatchAnimateur(animateur: Animateur, patch: AnimateurBulkPatch): Animateur {
  return {
    ...animateur,
    manager: appliquerModeBooleen(Boolean(animateur.manager), patch.manager),
    competences: appliquerCompetence(animateur.competences ?? {}, patch.competence),
    souhaits: appliquerModeListe(animateur.souhaits ?? [], patch.souhaits.typologies, patch.souhaits.mode),
    joursIndisponibles: appliquerIndisponibilite(animateur.joursIndisponibles ?? [], patch.indisponibilite)
  };
}

function appliquerCompetence(
  actuelles: Record<string, NiveauCompetence>,
  patch: AnimateurBulkPatch['competence']
): Record<string, NiveauCompetence> {
  if (patch.mode === 'AUCUN' || !patch.typologie) {
    return { ...actuelles };
  }
  const competences = { ...actuelles };
  if (patch.mode === 'RETIRER') {
    delete competences[patch.typologie];
    return competences;
  }
  competences[patch.typologie] = patch.niveau;
  return competences;
}

function appliquerIndisponibilite(jours: readonly string[], patch: AnimateurBulkPatch['indisponibilite']): string[] {
  if (patch.mode === 'AUCUN' || !patch.jour) {
    return [...jours];
  }
  return appliquerModeListe(jours, [patch.jour], patch.mode === 'RETIRER' ? 'RETIRER' : 'AJOUTER');
}
