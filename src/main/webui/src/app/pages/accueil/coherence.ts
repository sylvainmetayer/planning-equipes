// The coherence checklist as the home screen's unfolded panel shows it: one
// group per family, one line per anomaly with its severity, the source's
// sentence and the fiche or screen that corrects it. Pure functions, tested
// without rendering, called at runtime so `$localize` resolves after the
// catalog is loaded.

import {
  CoherenceFamily,
  CoherenceIssue,
  CoherenceReport,
  CoherenceSeverity,
  FamilyCount,
} from '../../core/models';
import type { LienEtat } from './accueil';

/** One line of the panel, ready to render. */
export interface CoherenceLine {
  gravite: CoherenceSeverity;
  severityLabel: string;
  /** The source's sentence, prefixed by its count when identical anomalies were merged into it. */
  sentence: string;
  lien: LienEtat;
  /** How many anomalies of the report this line stands for. */
  count: number;
}

/** Identical anomalies, merged: the first of them, and how many there were. */
export interface MergedIssue {
  issue: CoherenceIssue;
  /** The message with its leading date taken off, when it had one. */
  message: string;
  count: number;
  /** The dates the merged anomalies fell on, in the report's order. */
  dates: string[];
}

/**
 * The message an anomaly shares with its twins on other days: its leading
 * ISO date taken off — « 2026-09-01 : rien entre 12:00 et 13:00 … » is the
 * same gap as the one of the 2nd, and the date is in the issue's own field.
 */
function datelessMessage(issue: CoherenceIssue): string {
  if (issue.date && issue.message.startsWith(issue.date)) {
    return issue.message
      .slice(issue.date.length)
      .replace(/^\s*[:—-]\s*/, '')
      .trim();
  }
  return issue.message;
}

/**
 * Merges the anomalies that say the same thing about the same object: same
 * family, severity, code, subject and message once its date is taken off.
 * The key is the whole of what the line would say and link to, so nothing
 * different is ever folded into a count — sixteen days of the same gap in
 * the grid become one line, two different gaps stay two.
 */
export function mergeIdentical(issues: readonly CoherenceIssue[]): MergedIssue[] {
  const merged = new Map<string, MergedIssue>();
  for (const issue of issues) {
    const message = datelessMessage(issue);
    const key = [
      issue.famille,
      issue.gravite,
      issue.code,
      issue.objet,
      issue.objetId ?? '',
      message,
    ].join('\u0000');
    const current = merged.get(key);
    if (current) {
      current.count++;
      if (issue.date) {
        current.dates.push(issue.date);
      }
    } else {
      merged.set(key, { issue, message, count: 1, dates: issue.date ? [issue.date] : [] });
    }
  }
  return [...merged.values()];
}

/** « 16 jours : rien entre 12:00 et 13:00 … », or the source's sentence when it stands alone. */
export function mergedSentence(merged: MergedIssue): string {
  if (merged.count === 1) {
    return merged.issue.message;
  }
  const count = merged.count;
  const message = merged.message;
  return merged.dates.length === merged.count
    ? $localize`:@@accueil.coherence.fusion.jours:${count}:count: jours : ${message}:message:`
    : $localize`:@@accueil.coherence.fusion.fois:${message}:message: (${count}:count: fois)`;
}

/** One family of the panel: its title, its counts in words, its lines. */
export interface CoherenceGroup {
  famille: CoherenceFamily;
  titre: string;
  comptage: string;
  lignes: CoherenceLine[];
}

export function familyTitle(famille: CoherenceFamily): string {
  switch (famille) {
    case 'STANDS':
      return $localize`:@@accueil.coherence.famille.stands:Stands et ouvertures`;
    case 'CRENEAUX':
      return $localize`:@@accueil.coherence.famille.creneaux:Créneaux`;
    case 'ANIMATEURS':
      return $localize`:@@accueil.coherence.famille.animateurs:Animateurs`;
    case 'AJUSTEMENTS':
      return $localize`:@@accueil.coherence.famille.ajustements:Ajustements manuels`;
    case 'CAPACITE':
      return $localize`:@@accueil.coherence.famille.capacite:Capacité`;
  }
}

export function severityLabel(gravite: CoherenceSeverity): string {
  switch (gravite) {
    case 'BLOQUANT':
      return $localize`:@@accueil.coherence.gravite.bloquant:Bloquant`;
    case 'A_VERIFIER':
      return $localize`:@@accueil.coherence.gravite.aVerifier:À vérifier`;
    case 'INFORMATION':
      return $localize`:@@accueil.coherence.gravite.information:Pour information`;
  }
}

/** The counts of a family in one short sentence, the zeros left out. */
export function familyCounts(comptage: FamilyCount): string {
  const parts: string[] = [];
  if (comptage.bloquants > 0) {
    const count = comptage.bloquants;
    parts.push($localize`:@@accueil.coherence.comptage.bloquants:${count}:count: bloquant(s)`);
  }
  if (comptage.aVerifier > 0) {
    const count = comptage.aVerifier;
    parts.push($localize`:@@accueil.coherence.comptage.aVerifier:${count}:count: à vérifier`);
  }
  if (comptage.informations > 0) {
    const count = comptage.informations;
    parts.push(
      $localize`:@@accueil.coherence.comptage.informations:${count}:count: pour information`,
    );
  }
  return parts.join(' · ');
}

/**
 * The fiche or screen that corrects a line — the same links the Problèmes
 * tab gives a symptom: straight to the fiche, open for editing, when the line
 * names one.
 */
export function coherenceLink(ligne: CoherenceIssue): LienEtat {
  const id = ligne.objetId;
  switch (ligne.objet) {
    case 'STAND':
      return {
        route: '/stands',
        queryParams: id ? { edit: id } : undefined,
        libelle: $localize`:@@accueil.coherence.lien.stand:Fiche stand`,
      };
    case 'CRENEAU':
      return {
        route: '/creneaux',
        queryParams: id ? { edit: id } : undefined,
        libelle: $localize`:@@accueil.coherence.lien.creneau:Fiche créneau`,
      };
    case 'ANIMATEUR':
      return {
        route: '/animateurs',
        queryParams: id ? { edit: id } : undefined,
        libelle: $localize`:@@accueil.coherence.lien.animateur:Fiche animateur`,
      };
    case 'CONTRAINTE_AD_HOC':
      return {
        route: '/consignes-solveur',
        queryParams: id ? { onglet: 'ajustements', edit: id } : { onglet: 'ajustements' },
        libelle: $localize`:@@accueil.coherence.lien.adHoc:Ajustement manuel`,
      };
    case 'VERROUILLAGE':
      return {
        route: '/consignes-solveur',
        queryParams: { onglet: 'verrouillages' },
        libelle: $localize`:@@accueil.coherence.lien.verrouillages:Verrouillages`,
      };
    case 'TYPOLOGIE':
      return {
        route: '/diagnostic',
        queryParams: { onglet: 'besoin' },
        libelle: $localize`:@@accueil.coherence.lien.besoin:Besoin en animateurs`,
      };
    case 'EDITION':
      return editionLink(ligne.famille);
  }
}

/** A line about the edition as a whole goes to the screen of its family. */
function editionLink(famille: CoherenceFamily): LienEtat {
  switch (famille) {
    case 'CAPACITE':
      return {
        route: '/diagnostic',
        queryParams: { onglet: 'besoin' },
        libelle: $localize`:@@accueil.coherence.lien.besoin:Besoin en animateurs`,
      };
    case 'CRENEAUX':
      return {
        route: '/creneaux',
        libelle: $localize`:@@accueil.coherence.lien.grille:Grille de créneaux`,
      };
    case 'STANDS':
      return {
        route: '/ouvertures',
        libelle: $localize`:@@accueil.coherence.lien.ouvertures:Horaires des stands`,
      };
    case 'ANIMATEURS':
      return {
        route: '/animateurs',
        libelle: $localize`:@@accueil.coherence.lien.animateurs:Animateurs`,
      };
    case 'AJUSTEMENTS':
      return {
        route: '/consignes-solveur',
        queryParams: { onglet: 'ajustements' },
        libelle: $localize`:@@accueil.coherence.lien.adHocListe:Ajustements manuels`,
      };
  }
}

/** One line per set of identical anomalies, with its count, in the report's order. */
export function coherenceLines(issues: readonly CoherenceIssue[]): CoherenceLine[] {
  return mergeIdentical(issues).map((merged) => ({
    gravite: merged.issue.gravite,
    severityLabel: severityLabel(merged.issue.gravite),
    sentence: mergedSentence(merged),
    lien: coherenceLink(merged.issue),
    count: merged.count,
  }));
}

/**
 * The panel: the families holding at least one line, in the server's order,
 * lines as sorted there — identical anomalies merged into one line with their
 * count.
 */
export function coherenceGroups(rapport: CoherenceReport): CoherenceGroup[] {
  return rapport.familles
    .map((comptage) => ({
      famille: comptage.famille,
      titre: familyTitle(comptage.famille),
      comptage: familyCounts(comptage),
      lignes: coherenceLines(
        rapport.anomalies.filter((ligne) => ligne.famille === comptage.famille),
      ),
    }))
    .filter((groupe) => groupe.lignes.length > 0);
}
