// The lines of the home checklist: what each block of `EtatEdition` reads as
// on screen — its title, its state, the one sentence carrying the figures,
// and the screen that makes the step move. Pure functions, called at runtime
// (never at module scope) so `$localize` resolves after the catalog is loaded.

import { EtatEdition, StatutEtat } from '../../core/models';
import { intlLocale } from '../../core/locale';
import { libelleJour } from '../../core/horaire-stand';

/** One line of the checklist, ready to render. */
export interface LigneEtat {
  /** Stable id, the `track` key and the anchor of the E2E assertions. */
  id: string;
  titre: string;
  statut: StatutEtat;
  /** The figures behind the state, in one sentence. */
  detail: string;
  /** The screen the line leads to; absent on a line whose detail unfolds in place instead. */
  lien?: LienEtat;
  /** A detail this line unfolds below itself rather than on another screen. */
  panneau?: 'coherence';
}

/** Where a line leads: the screen that moves the step forward. */
export interface LienEtat {
  route: string;
  queryParams?: Record<string, string>;
  libelle: string;
}

/** How many lines sit in each state — the one sentence above the list. */
export interface BilanEtat {
  faits: number;
  attention: number;
  info: number;
  aFaire: number;
}

export function statutLabel(statut: StatutEtat): string {
  switch (statut) {
    case 'A_FAIRE':
      return $localize`:@@accueil.statut.aFaire:À faire`;
    case 'ATTENTION':
      return $localize`:@@accueil.statut.attention:À vérifier`;
    case 'INFO':
      return $localize`:@@accueil.statut.info:Pour information`;
    case 'FAIT':
      return $localize`:@@accueil.statut.fait:Fait`;
  }
}

export function statutIcon(statut: StatutEtat): string {
  switch (statut) {
    case 'A_FAIRE':
      return 'radio_button_unchecked';
    case 'ATTENTION':
      return 'error_outline';
    case 'INFO':
      return 'info_outline';
    case 'FAIT':
      return 'check_circle';
  }
}

function formatInstant(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString(intlLocale()) : '';
}

function referentiels(etat: EtatEdition): LigneEtat {
  const { stands, animateurs, creneaux, statut } = etat.referentiels;
  // The first empty referential is where the entry starts, in the guide's
  // order: the créneaux first — nothing else has dates before them, and the
  // stands' openings are typed against them (ADR 0032).
  let route = '/animateurs';
  if (creneaux === 0) {
    route = '/creneaux';
  } else if (stands === 0) {
    route = '/stands';
  }
  return {
    id: 'referentiels',
    titre: $localize`:@@accueil.ligne.referentiels:Référentiels saisis`,
    statut,
    detail: $localize`:@@accueil.detail.referentiels:${stands}:stands: stands · ${animateurs}:animateurs: animateurs · ${creneaux}:creneaux: créneaux`,
    lien: {
      route: statut === 'FAIT' ? '/stands' : route,
      libelle:
        statut === 'FAIT'
          ? $localize`:@@accueil.lien.referentiels.voir:Voir les stands`
          : $localize`:@@accueil.lien.referentiels.saisir:Saisir les référentiels`,
    },
  };
}

/**
 * What was entered, read as a whole: the anomalies every other screen detects
 * on its own, counted here and unfolded in place — no screen to go to, the
 * detail is the list, and each of its lines links to the fiche that fixes it.
 */
function coherence(etat: EtatEdition): LigneEtat {
  const { bloquants, aVerifier, informations, statut } = etat.coherence;
  const total = bloquants + aVerifier + informations;
  const parts: string[] = [];
  if (bloquants > 0) {
    parts.push($localize`:@@accueil.detail.coherence.bloquants:${bloquants}:count: bloquant(s)`);
  }
  if (aVerifier > 0) {
    parts.push($localize`:@@accueil.detail.coherence.aVerifier:${aVerifier}:count: à vérifier`);
  }
  if (informations > 0) {
    parts.push(
      $localize`:@@accueil.detail.coherence.informations:${informations}:count: pour information`,
    );
  }
  return {
    id: 'coherence',
    titre: $localize`:@@accueil.ligne.coherence:Cohérence du référentiel`,
    statut,
    detail:
      total === 0
        ? $localize`:@@accueil.detail.coherence.ok:Aucune anomalie dans ce qui est saisi`
        : parts.join(' · '),
    panneau: total === 0 ? undefined : 'coherence',
  };
}

function collecte(etat: EtatEdition): LigneEtat {
  const { ouverte, declarationsEnAttente, declarationsTraitees, statut } = etat.collecte;
  let detail: string;
  if (declarationsEnAttente > 0) {
    detail = $localize`:@@accueil.detail.collecte.enAttente:${declarationsEnAttente}:count: déclaration(s) à appliquer ou refuser`;
  } else if (ouverte) {
    detail = $localize`:@@accueil.detail.collecte.ouverte:Collecte ouverte, aucune déclaration en attente`;
  } else if (declarationsTraitees > 0) {
    detail = $localize`:@@accueil.detail.collecte.fermee:Collecte fermée, ${declarationsTraitees}:count: déclaration(s) traitée(s)`;
  } else {
    detail = $localize`:@@accueil.detail.collecte.jamais:Collecte fermée, rien n'a été collecté`;
  }
  return {
    id: 'collecte',
    titre: $localize`:@@accueil.ligne.collecte:Collecte des disponibilités`,
    statut,
    detail,
    lien: {
      route: '/disponibilites',
      libelle: $localize`:@@accueil.lien.collecte:Ouvrir les disponibilités`,
    },
  };
}

function ouvertures(etat: EtatEdition): LigneEtat {
  const { anomalies, fenetresSansEffet, standsJamaisOuverts, informations, statut } =
    etat.ouvertures;
  let detail: string;
  if (statut === 'A_FAIRE') {
    detail = $localize`:@@accueil.detail.ouvertures.aFaire:Sans stand ni créneau, rien à lire`;
  } else if (anomalies > 0 && anomalies === informations) {
    // Only how the rules are written: settled by the resolution, said for information.
    detail = $localize`:@@accueil.detail.ouvertures.informations:${informations}:count: point(s) pour information : règles ou fenêtres d'horaires qui se recouvrent ou ne servent à rien`;
  } else if (fenetresSansEffet > 0) {
    // Named apart: a window outside every vacation is the one mistake a
    // hand-typed grid makes that the openings screen alone would bury.
    detail = $localize`:@@accueil.detail.ouvertures.fenetres:${anomalies}:count: anomalie(s), dont ${fenetresSansEffet}:fenetres: fenêtre(s) hors de toute vacation, ${standsJamaisOuverts}:fermes: stand(s) jamais ouvert(s)`;
  } else if (anomalies > 0) {
    detail = $localize`:@@accueil.detail.ouvertures.anomalies:${anomalies}:count: anomalie(s), ${standsJamaisOuverts}:fermes: stand(s) jamais ouvert(s)`;
  } else {
    detail = $localize`:@@accueil.detail.ouvertures.ok:Aucune anomalie`;
  }
  return {
    id: 'ouvertures',
    titre: $localize`:@@accueil.ligne.ouvertures:Ouvertures des stands`,
    statut,
    detail,
    lien: {
      route: '/ouvertures',
      libelle: $localize`:@@accueil.lien.ouvertures:Voir les ouvertures`,
    },
  };
}

function besoin(etat: EtatEdition): LigneEtat {
  const { animateurs, minimum, manque, statut } = etat.besoin;
  let detail: string;
  if (statut === 'A_FAIRE') {
    detail = $localize`:@@accueil.detail.besoin.aFaire:Le besoin se calcule une fois stands, créneaux et animateurs saisis`;
  } else if (manque > 0) {
    detail = $localize`:@@accueil.detail.besoin.manque:${animateurs}:animateurs: animateurs pour un minimum de ${minimum}:minimum: : il en manque ${manque}:manque:`;
  } else {
    detail = $localize`:@@accueil.detail.besoin.ok:${animateurs}:animateurs: animateurs pour un minimum de ${minimum}:minimum:`;
  }
  return {
    id: 'besoin',
    titre: $localize`:@@accueil.ligne.besoin:Besoin en animateurs`,
    statut,
    detail,
    lien: {
      route: '/diagnostic',
      queryParams: { onglet: 'besoin' },
      libelle: $localize`:@@accueil.lien.besoin:Voir le besoin`,
    },
  };
}

function resolution(etat: EtatEdition): LigneEtat {
  const bloc = etat.resolution;
  let detail: string;
  if (bloc.solveEnCours) {
    detail = $localize`:@@accueil.detail.resolution.enCours:Résolution en cours`;
  } else if (!bloc.resolue) {
    detail = $localize`:@@accueil.detail.resolution.jamais:Aucune résolution pour le moment`;
  } else {
    const quand = formatInstant(bloc.resoluLe);
    const parts = [$localize`:@@accueil.detail.resolution.date:Résolue le ${quand}:date:`];
    if (bloc.score) {
      parts.push($localize`:@@accueil.detail.resolution.score:score ${bloc.score}:score:`);
    }
    if (bloc.scoreHorsPlancher && bloc.scoreHorsPlancher !== bloc.score) {
      parts.push(
        $localize`:@@accueil.detail.resolution.horsPlancher:hors plancher ${bloc.scoreHorsPlancher}:score:`,
      );
    }
    if (bloc.faisable === false) {
      parts.push($localize`:@@accueil.detail.resolution.infaisable:règles dures en défaut`);
    }
    if (bloc.dataStale) {
      parts.push($localize`:@@accueil.detail.resolution.stale:données modifiées depuis`);
    }
    detail = parts.join(' · ');
  }
  return {
    id: 'resolution',
    titre: $localize`:@@accueil.ligne.resolution:Dernière résolution`,
    statut: bloc.statut,
    detail,
    lien: {
      route: '/solveur',
      libelle: $localize`:@@accueil.lien.resolution:Ouvrir le solveur`,
    },
  };
}

function problemes(etat: EtatEdition): LigneEtat {
  const { bloquants, avertissements, reglesAnalysees, statut } = etat.problemes;
  let detail: string;
  if (statut === 'A_FAIRE') {
    detail = $localize`:@@accueil.detail.problemes.aFaire:Le diagnostic attend les référentiels`;
  } else if (bloquants > 0) {
    detail = $localize`:@@accueil.detail.problemes.comptage:${bloquants}:bloquants: bloquant(s) · ${avertissements}:avertissements: avertissement(s)`;
  } else if (avertissements > 0) {
    // Nothing blocks: the figure is read, not acted upon. A plan with zero
    // warnings does not exist on a real event, so it is said as a report.
    detail = $localize`:@@accueil.detail.problemes.avertissements:${avertissements}:avertissements: avertissement(s), rien de bloquant`;
  } else {
    detail = reglesAnalysees
      ? $localize`:@@accueil.detail.problemes.ok:Aucun problème signalé`
      : // Nothing has measured the rules since the application started: an
        // acknowledgement here would be one nobody earned.
        $localize`:@@accueil.detail.problemes.nonMesure:Règles non analysées depuis le démarrage`;
  }
  return {
    id: 'problemes',
    titre: $localize`:@@accueil.ligne.problemes:Problèmes`,
    statut,
    detail,
    lien: {
      route: '/diagnostic',
      queryParams: { onglet: 'problemes' },
      libelle: $localize`:@@accueil.lien.problemes:Voir les problèmes`,
    },
  };
}

/**
 * The step between a plan that holds and a plan somebody has actually read,
 * counted in days accepted.
 */
function relecture(etat: EtatEdition): LigneEtat {
  const { journees, journeesValidees, statut } = etat.relecture;
  let detail: string;
  if (journees === 0) {
    detail = $localize`:@@accueil.detail.relecture.sansJournee:La relecture attend une grille de créneaux`;
  } else if (statut === 'A_FAIRE') {
    detail = $localize`:@@accueil.detail.relecture.aFaire:Aucune journée relue sur ${journees}:total:`;
  } else {
    detail = $localize`:@@accueil.detail.relecture.comptage:${journeesValidees}:validees: journée(s) relue(s) sur ${journees}:total:`;
  }
  return {
    id: 'relecture',
    titre: $localize`:@@accueil.ligne.relecture:Relecture`,
    statut,
    detail,
    lien: {
      route: '/journee',
      libelle: $localize`:@@accueil.lien.relecture:Relire une journée`,
    },
  };
}

function publication(etat: EtatEdition): LigneEtat {
  const { jamaisPublie, dernierePublicationLe, personnesAPrevenir, statut } = etat.publication;
  let detail: string;
  if (etat.resolution.solveEnCours) {
    // Publishing is refused while a solve runs, and the count is read off a
    // plan about to be rewritten: the line says wait, not « publish ».
    detail = $localize`:@@accueil.detail.publication.solveEnCours:Résolution en cours : la publication attend la fin`;
  } else if (jamaisPublie) {
    detail = $localize`:@@accueil.detail.publication.jamais:Jamais publié`;
  } else if (personnesAPrevenir > 0) {
    detail = $localize`:@@accueil.detail.publication.aPrevenir:${personnesAPrevenir}:count: personne(s) à prévenir`;
  } else {
    const quand = formatInstant(dernierePublicationLe);
    detail = $localize`:@@accueil.detail.publication.aJour:À jour, publié le ${quand}:date:`;
  }
  return {
    id: 'publication',
    titre: $localize`:@@accueil.ligne.publication:Publication`,
    statut,
    detail,
    lien: {
      // Its own screen since issue #320, no longer a card of the solver's.
      route: '/publication',
      libelle: $localize`:@@accueil.lien.publication:Publier`,
    },
  };
}

function confirmations(etat: EtatEdition): LigneEtat {
  const { confirmes, relances, silencieux, statut } = etat.confirmations;
  const detail =
    statut === 'A_FAIRE'
      ? $localize`:@@accueil.detail.confirmations.aFaire:Les accusés de réception suivent la publication`
      : $localize`:@@accueil.detail.confirmations.comptage:${confirmes}:confirmes: confirmé(s) · ${relances}:relances: relancé(s) · ${silencieux}:silencieux: silencieux`;
  const restants = relances + silencieux > 0;
  return {
    id: 'confirmations',
    titre: $localize`:@@accueil.ligne.confirmations:Accusés de réception`,
    statut,
    detail,
    lien: {
      route: '/animateurs',
      // The filter #504 ships: only the people who have not answered.
      queryParams: restants ? { confirmation: 'jamais' } : undefined,
      libelle: restants
        ? $localize`:@@accueil.lien.confirmations.silencieux:Voir qui n'a pas répondu`
        : $localize`:@@accueil.lien.confirmations.voir:Voir les animateurs`,
    },
  };
}

function foire(etat: EtatEdition): LigneEtat {
  const { ouverte, demandesEnAttente, statut } = etat.foire;
  let detail: string;
  if (statut === 'A_FAIRE') {
    detail = $localize`:@@accueil.detail.foire.aFaire:Les échanges s'ouvrent après la publication`;
  } else if (demandesEnAttente > 0) {
    detail = $localize`:@@accueil.detail.foire.enAttente:${demandesEnAttente}:count: demande(s) en attente`;
  } else if (ouverte) {
    detail = $localize`:@@accueil.detail.foire.ouverte:Foire ouverte, aucune demande en attente`;
  } else {
    detail = $localize`:@@accueil.detail.foire.fermee:Foire fermée, aucune demande en attente`;
  }
  return {
    id: 'foire',
    titre: $localize`:@@accueil.ligne.foire:Foire au planning`,
    statut,
    detail,
    lien: {
      route: '/echanges',
      libelle: $localize`:@@accueil.lien.foire:Voir les échanges`,
    },
  };
}

/** The checklist in the guide's order, from the first record to the acknowledged plan. */
export function buildLignes(etat: EtatEdition): LigneEtat[] {
  return [
    referentiels(etat),
    coherence(etat),
    collecte(etat),
    ouvertures(etat),
    besoin(etat),
    resolution(etat),
    problemes(etat),
    relecture(etat),
    publication(etat),
    confirmations(etat),
    foire(etat),
  ];
}

export function summarizeLignes(lignes: readonly LigneEtat[]): BilanEtat {
  return {
    faits: lignes.filter((ligne) => ligne.statut === 'FAIT').length,
    attention: lignes.filter((ligne) => ligne.statut === 'ATTENTION').length,
    info: lignes.filter((ligne) => ligne.statut === 'INFO').length,
    aFaire: lignes.filter((ligne) => ligne.statut === 'A_FAIRE').length,
  };
}

/* ------------------------ « À traiter aujourd'hui » ------------------------ */

/** One subject of the box: its sentence, whether it is an alert, and the screen that settles it. */
export interface TodayItem {
  id: string;
  /** The subject in one sentence, its figures included. */
  sentence: string;
  alerte: boolean;
  lien: LienEtat;
}

/** How many dates the « journées à relire » sentence spells out. */
const DAYS_CITED = 4;

function formatDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString(intlLocale()) : '';
}

/**
 * The subjects of « À traiter aujourd'hui », those with something to say
 * only. Empty means the box is not drawn: « rien à traiter » reads as an
 * absence, not as one more green block.
 */
export function buildToday(etat: EtatEdition): TodayItem[] {
  const bloc = etat.aTraiter;
  const items: TodayItem[] = [];
  if (bloc.declarationsEnAttente > 0) {
    const count = bloc.declarationsEnAttente;
    const depuis = formatDate(bloc.plusAncienneDeclaration);
    items.push({
      id: 'declarations',
      sentence: depuis
        ? $localize`:@@accueil.aTraiter.declarations:${count}:count: déclaration(s) de disponibilité à appliquer ou refuser, la plus ancienne reçue le ${depuis}:date:`
        : $localize`:@@accueil.aTraiter.declarationsSansDate:${count}:count: déclaration(s) de disponibilité à appliquer ou refuser`,
      alerte: false,
      lien: {
        route: '/disponibilites',
        // The pending ones only, the oldest first: the declarations this line counts.
        queryParams: { statut: 'en-attente' },
        libelle: $localize`:@@accueil.aTraiter.lien.declarations:Traiter les déclarations`,
      },
    });
  }
  if (bloc.echangesAArbitrer > 0) {
    const count = bloc.echangesAArbitrer;
    const overdue = bloc.echangesEnAlerte;
    const threshold = bloc.seuilAncienneteJours;
    items.push({
      id: 'echanges',
      sentence:
        overdue > 0
          ? $localize`:@@accueil.aTraiter.echangesAnciens:${count}:count: demande(s) d'échange à arbitrer, dont ${overdue}:anciennes: en attente depuis plus de ${threshold}:seuil: jour(s)`
          : $localize`:@@accueil.aTraiter.echanges:${count}:count: demande(s) d'échange à arbitrer`,
      alerte: overdue > 0,
      lien: {
        route: '/echanges',
        // The requests to arbitrate only, the longest waiting first: the ones this line counts.
        queryParams: { statut: 'a-arbitrer' },
        libelle: $localize`:@@accueil.aTraiter.lien.echanges:Arbitrer les échanges`,
      },
    });
  }
  if (bloc.journeesNonRelues.length > 0) {
    const count = bloc.journeesNonRelues.length;
    const horizon = bloc.horizonJours;
    const cited = bloc.journeesNonRelues.slice(0, DAYS_CITED).map(libelleJour);
    const dates = cited.join(', ') + (count > DAYS_CITED ? '…' : '');
    const first = bloc.journeesNonRelues[0];
    const firstLabel = libelleJour(first);
    items.push({
      id: 'relecture',
      sentence: $localize`:@@accueil.aTraiter.relecture:${count}:count: journée(s) à relire dans les ${horizon}:horizon: prochains jours : ${dates}:dates:`,
      alerte: false,
      lien: {
        route: '/journee',
        queryParams: { date: first },
        libelle: $localize`:@@accueil.aTraiter.lien.relecture:Relire le ${firstLabel}:jour:`,
      },
    });
  }
  if (bloc.silencieuxARelancer > 0) {
    const count = bloc.silencieuxARelancer;
    const jours = bloc.silenceJours;
    items.push({
      id: 'silencieux',
      sentence: $localize`:@@accueil.aTraiter.silencieux:${count}:count: personne(s) sans réponse depuis plus de ${jours}:jours: jour(s), jamais relancée(s)`,
      alerte: false,
      lien: {
        route: '/animateurs',
        // The filter the Animateurs page reads from its address (issue #504),
        // narrowed to the never reminded: the people this line counts.
        queryParams: { silence: String(jours), relance: 'jamais' },
        libelle: $localize`:@@accueil.aTraiter.lien.silencieux:Voir qui relancer`,
      },
    });
  }
  if (bloc.donneesModifiees) {
    items.push({
      id: 'donnees',
      sentence: $localize`:@@accueil.aTraiter.donnees:Des données ont changé depuis la dernière résolution`,
      alerte: false,
      lien: {
        route: '/solveur',
        libelle: $localize`:@@accueil.aTraiter.lien.donnees:Ouvrir le solveur`,
      },
    });
  }
  if (bloc.personnesAPrevenir > 0) {
    const count = bloc.personnesAPrevenir;
    items.push({
      id: 'prevenir',
      sentence: $localize`:@@accueil.aTraiter.prevenir:${count}:count: personne(s) à prévenir d'un planning qui a changé`,
      alerte: false,
      lien: {
        route: '/publication',
        libelle: $localize`:@@accueil.aTraiter.lien.prevenir:Publier`,
      },
    });
  }
  return items;
}
