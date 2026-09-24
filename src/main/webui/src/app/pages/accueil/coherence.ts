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
import { LienEtat } from './accueil';

/** One line of the panel, ready to render. */
export interface CoherenceLine {
  gravite: CoherenceSeverity;
  severityLabel: string;
  /** The source's sentence, unchanged. */
  sentence: string;
  lien: LienEtat;
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
        route: '/ad-hoc-constraints',
        queryParams: id ? { edit: id } : undefined,
        libelle: $localize`:@@accueil.coherence.lien.adHoc:Ajustement manuel`,
      };
    case 'VERROUILLAGE':
      return {
        route: '/verrouillages',
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
        libelle: $localize`:@@accueil.coherence.lien.ouvertures:Ouvertures des stands`,
      };
    case 'ANIMATEURS':
      return {
        route: '/animateurs',
        libelle: $localize`:@@accueil.coherence.lien.animateurs:Animateurs`,
      };
    case 'AJUSTEMENTS':
      return {
        route: '/ad-hoc-constraints',
        libelle: $localize`:@@accueil.coherence.lien.adHocListe:Ajustements manuels`,
      };
  }
}

/** The panel: the families holding at least one line, in the server's order, lines as sorted there. */
export function coherenceGroups(rapport: CoherenceReport): CoherenceGroup[] {
  return rapport.familles
    .map((comptage) => ({
      famille: comptage.famille,
      titre: familyTitle(comptage.famille),
      comptage: familyCounts(comptage),
      lignes: rapport.anomalies
        .filter((ligne) => ligne.famille === comptage.famille)
        .map((ligne) => ({
          gravite: ligne.gravite,
          severityLabel: severityLabel(ligne.gravite),
          sentence: ligne.message,
          lien: coherenceLink(ligne),
        })),
    }))
    .filter((groupe) => groupe.lignes.length > 0);
}
