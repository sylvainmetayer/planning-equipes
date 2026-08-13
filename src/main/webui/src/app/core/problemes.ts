// Merges the two independent sources of "what is wrong with this planning"
// into a single list ranked by severity, shared by the Problèmes page and the
// summary banner of the Solveur page.
//
// The two sources answer different questions and neither subsumes the other:
// - `FeasibilityReport.causes` (`GET /api/feasibility`) is a pre-solve capacity
//   check, available at any time — even before a solve has ever run;
// - `ConstraintsView.contraintes` (`GET /api/constraints`) is the diagnostic of
//   the last analysed solve, so it only exists after one.
//
// Every function here is pure and called at runtime (never at module scope), so
// the `$localize` labels resolve after `main.ts` has loaded the translations.

import { CauseInfaisabilite, ConstraintView, FeasibilityReport, NiveauContrainte } from './models';

/** Display severity of the merged list, from the most to the least blocking. */
export type NiveauProbleme = 'BLOQUANT' | 'AVERTISSEMENT' | 'MINEUR';

export type SourceProbleme = 'FAISABILITE' | 'CONTRAINTE';

export interface Probleme {
  /** Stable within one merge, used as the `@for` track key. */
  id: string;
  niveau: NiveauProbleme;
  source: SourceProbleme;
  /** Short qualification of the problem (cause type or constraint name). */
  titre: string;
  message: string;
  /** Concrete entities involved: créneau, stands, or one line per violation. */
  details: string[];
}

export interface ComptageProblemes {
  bloquants: number;
  avertissements: number;
  mineurs: number;
  total: number;
}

const RANG_NIVEAU: Record<NiveauProbleme, number> = { BLOQUANT: 0, AVERTISSEMENT: 1, MINEUR: 2 };
// Within one severity tier, a structural capacity problem comes before a
// constraint violation: it must be fixed first, since no solve can work around it.
const RANG_SOURCE: Record<SourceProbleme, number> = { FAISABILITE: 0, CONTRAINTE: 1 };

export function niveauDeCause(severite: CauseInfaisabilite['severite']): NiveauProbleme {
  return severite === 'CRITIQUE' ? 'BLOQUANT' : 'AVERTISSEMENT';
}

export function niveauDeContrainte(niveau: NiveauContrainte): NiveauProbleme {
  switch (niveau) {
    case 'HARD':
      return 'BLOQUANT';
    case 'MEDIUM':
      return 'AVERTISSEMENT';
    default:
      return 'MINEUR';
  }
}

export function niveauProblemeLabel(niveau: NiveauProbleme): string {
  switch (niveau) {
    case 'BLOQUANT':
      return $localize`:@@problemes.niveau.bloquant:Bloquant`;
    case 'AVERTISSEMENT':
      return $localize`:@@problemes.niveau.avertissement:Avertissement`;
    default:
      return $localize`:@@problemes.niveau.mineur:Mineur`;
  }
}

export function typeCauseLabel(): string {
  return $localize`:@@problemes.cause.creneauSousEffectif:Créneau en sous-effectif`;
}

/** Créneau and stands named by a cause, as printable lines. */
export function detailsDeCause(cause: CauseInfaisabilite): string[] {
  const details: string[] = [];
  if (cause.creneauId !== null && cause.creneauId !== undefined) {
    const creneauId = String(cause.creneauId);
    const date = cause.date ?? '';
    const heures = cause.heureDebut && cause.heureFin ? `${cause.heureDebut}–${cause.heureFin}` : '';
    details.push($localize`:@@problemes.detail.creneau:Créneau ${creneauId}:id: ${date}:date: ${heures}:hours:`);
  }
  if (cause.standIds.length > 0) {
    const stands = cause.standIds.join(', ');
    details.push($localize`:@@problemes.detail.stands:Stands : ${stands}:stands:`);
  }
  if (cause.manque > 0) {
    const manque = cause.manque;
    const demande = cause.demande;
    const capacite = cause.capacite;
    details.push(
      $localize`:@@problemes.detail.effectif:Il manque ${manque}:manque: animateur(s) : ${demande}:demande: demandé(s) pour ${capacite}:capacite: disponible(s).`
    );
  }
  return details;
}

/**
 * Builds the ranked problem list. Both arguments are optional so the caller can
 * render whatever it already has: the feasibility report is available before any
 * solve, the constraint diagnostic only after one.
 *
 * Only constraints with at least one match are kept — a satisfied rule is not a
 * problem. HARD constraints carry their per-match `violations` lines; MEDIUM and
 * SOFT ones only report how many matches they scored.
 */
export function construireProblemes(
  report: FeasibilityReport | null,
  contraintes: ConstraintView[] = []
): Probleme[] {
  const problemes: Probleme[] = [];

  (report?.causes ?? []).forEach((cause, index) => {
    problemes.push({
      id: `cause-${index}`,
      niveau: niveauDeCause(cause.severite),
      source: 'FAISABILITE',
      titre: typeCauseLabel(),
      message: cause.message,
      details: detailsDeCause(cause)
    });
  });

  contraintes
    .filter((contrainte) => (contrainte.matchCount ?? 0) > 0)
    .forEach((contrainte) => {
      const matchCount = contrainte.matchCount ?? 0;
      problemes.push({
        id: `contrainte-${contrainte.name}`,
        niveau: niveauDeContrainte(contrainte.niveau),
        source: 'CONTRAINTE',
        titre: contrainte.name,
        message: contrainte.description,
        details:
          contrainte.violations.length > 0
            ? contrainte.violations
            : [$localize`:@@problemes.detail.matches:${matchCount}:count: correspondance(s) sur la dernière analyse.`]
      });
    });

  // Array.prototype.sort is stable, so problems of the same tier and source keep
  // the order the server ranked them in.
  return problemes.sort(
    (a, b) => RANG_NIVEAU[a.niveau] - RANG_NIVEAU[b.niveau] || RANG_SOURCE[a.source] - RANG_SOURCE[b.source]
  );
}

export function compterProblemes(problemes: Probleme[]): ComptageProblemes {
  return {
    bloquants: problemes.filter((probleme) => probleme.niveau === 'BLOQUANT').length,
    avertissements: problemes.filter((probleme) => probleme.niveau === 'AVERTISSEMENT').length,
    mineurs: problemes.filter((probleme) => probleme.niveau === 'MINEUR').length,
    total: problemes.length
  };
}
