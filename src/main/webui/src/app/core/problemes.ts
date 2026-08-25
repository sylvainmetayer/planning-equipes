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

import {
  CauseInfaisabilite,
  ConstraintView,
  ContributionAdHoc,
  FeasibilityReport,
  NiveauContrainte,
  TypeCauseInfaisabilite
} from './models';

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
  /**
   * Where to go to act on the problem. A cause names a créneau and stands, and
   * the answer is almost always to edit one of them: without the link the user
   * has to memorise an id and go hunting for it in another screen.
   */
  liens: LienProbleme[];
}

/** A route the problem can be acted upon from. */
export interface LienProbleme {
  route: string;
  libelle: string;
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

export function typeCauseLabel(type: TypeCauseInfaisabilite): string {
  return type === 'CONTRAINTES_AD_HOC_CONTRADICTOIRES'
    ? $localize`:@@problemes.cause.contraintesAdHocContradictoires:Contraintes ad hoc contradictoires`
    : $localize`:@@problemes.cause.creneauSousEffectif:Créneau en sous-effectif`;
}

/** Créneau and stands named by a cause, as printable lines. */
/** Screens a feasibility cause can be acted upon from, in the order one would try them. */
export function liensDeCause(cause: CauseInfaisabilite): LienProbleme[] {
  const liens: LienProbleme[] = [];
  if (cause.creneauId !== null && cause.creneauId !== undefined) {
    liens.push({ route: '/creneaux', libelle: $localize`:@@problemes.lien.creneaux:Voir les créneaux` });
  }
  if (cause.standIds.length > 0) {
    liens.push({ route: '/stands', libelle: $localize`:@@problemes.lien.stands:Voir les stands` });
    liens.push({ route: '/ouvertures', libelle: $localize`:@@problemes.lien.ouvertures:Vérifier les ouvertures` });
  }
  if (cause.manque > 0) {
    liens.push({ route: '/staffing', libelle: $localize`:@@problemes.lien.staffing:Besoin en animateurs` });
  }
  if ((cause.contrainteIds ?? []).length > 0) {
    liens.push({
      route: '/ad-hoc-constraints',
      libelle: $localize`:@@problemes.lien.adHoc:Voir les contraintes ad hoc`
    });
  }
  return liens;
}

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
  if ((cause.contrainteIds ?? []).length > 0) {
    const contraintes = cause.contrainteIds.join(', ');
    details.push($localize`:@@problemes.detail.contraintesAdHoc:Contraintes ad hoc : ${contraintes}:contraintes:`);
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

/** One line naming the exceptions a rule failed on, with how much each accounts for. */
function detailsEnCause(contributions: ContributionAdHoc[]): string[] {
  if (contributions.length === 0) {
    return [];
  }
  const noms = contributions
    .map((contribution) => `${contribution.contrainteId} (${contribution.violations})`)
    .join(', ');
  return [$localize`:@@problemes.detail.adHocEnCause:Exceptions en cause : ${noms}:noms:`];
}

/**
 * Builds the ranked problem list. Both arguments are optional so the caller can
 * render whatever it already has: the feasibility report is available before any
 * solve, the constraint diagnostic only after one.
 *
 * Only constraints with at least one match are kept — a satisfied rule is not a
 * problem. HARD constraints carry their per-match `violations` lines; MEDIUM and
 * SOFT ones only report how many matches they scored.
 *
 * `contraintesAdHocEnCause` comes from the same diagnostic and attributes the
 * ad hoc rules' violations to the exceptions that caused them.
 */
export function construireProblemes(
  report: FeasibilityReport | null,
  contraintes: ConstraintView[] = [],
  contraintesAdHocEnCause: ContributionAdHoc[] = []
): Probleme[] {
  const problemes: Probleme[] = [];

  (report?.causes ?? []).forEach((cause, index) => {
    problemes.push({
      id: `cause-${index}`,
      niveau: niveauDeCause(cause.severite),
      source: 'FAISABILITE',
      titre: typeCauseLabel(cause.type),
      message: cause.message,
      details: detailsDeCause(cause),
      liens: liensDeCause(cause)
    });
  });

  contraintes
    .filter((contrainte) => (contrainte.matchCount ?? 0) > 0)
    .forEach((contrainte) => {
      const matchCount = contrainte.matchCount ?? 0;
      // Which of the user's own exceptions this rule failed on, before the
      // per-match lines: "affectationForcee : 12" is where reading stops
      // otherwise, and the exceptions are the only thing anyone can act on.
      const enCause = contraintesAdHocEnCause.filter((contribution) =>
        contribution.contraintes.includes(contrainte.name)
      );
      const lignes =
        contrainte.violations.length > 0
          ? contrainte.violations
          : [$localize`:@@problemes.detail.matches:${matchCount}:count: correspondance(s) sur la dernière analyse.`];
      const liens: LienProbleme[] = [
        // A violated rule is acted upon on the constraints screen: that is where
        // its weight is explained and where it can be relaxed.
        { route: '/constraints', libelle: $localize`:@@problemes.lien.contraintes:Voir la règle` }
      ];
      if (enCause.length > 0) {
        liens.push({
          route: '/ad-hoc-constraints',
          libelle: $localize`:@@problemes.lien.adHoc:Voir les contraintes ad hoc`
        });
      }
      problemes.push({
        id: `contrainte-${contrainte.name}`,
        niveau: niveauDeContrainte(contrainte.niveau),
        source: 'CONTRAINTE',
        titre: contrainte.name,
        message: contrainte.description,
        details: [...detailsEnCause(enCause), ...lignes],
        liens
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
