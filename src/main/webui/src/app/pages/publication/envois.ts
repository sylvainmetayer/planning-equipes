// The pure side of the Diffuser table: which people a filter keeps, and the
// words each column puts on a person — « envoyé », « échec (adresse refusée) »,
// « silencieux ». Kept apart from the component so the sentences an organiser
// decides on are pinned down by a test that renders nothing.

import { CauseEchec, LigneEnvoi } from '../../core/models';

/** The filters of the table, and the values of the `filtre` query param. */
export type FiltreEnvois =
  'tous' | 'a-prevenir' | 'echec' | 'sans-email' | 'silencieux' | 'differes';

const FILTRES: readonly FiltreEnvois[] = [
  'tous',
  'a-prevenir',
  'echec',
  'sans-email',
  'silencieux',
  'differes',
];

/** Reads the `filtre` query param; anything unknown is the whole table. */
export function readFiltreEnvois(value: string | null): FiltreEnvois {
  return (FILTRES as readonly string[]).includes(value ?? '') ? (value as FiltreEnvois) : 'tous';
}

/**
 * Reads the `jour` query param: a date `AAAA-MM-JJ`, or null. Anything else is
 * no day at all rather than a filter that keeps nobody.
 */
export function readJourEnvois(value: string | null): string | null {
  return value && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : null;
}

/**
 * Seated by the published plan and never answered: the people a reminder is
 * for. Somebody with no seat has nothing to acknowledge.
 */
export function isSilent(ligne: LigneEnvoi): boolean {
  return ligne.affecte && ligne.confirmation !== 'CONFIRME';
}

/** The latest planning mail of this person failed. */
export function hasFailed(ligne: LigneEnvoi): boolean {
  return ligne.envoi?.statut === 'ECHEC';
}

/** Whether a person belongs to a filter. */
export function matchesFilter(ligne: LigneEnvoi, filtre: FiltreEnvois): boolean {
  switch (filtre) {
    case 'tous':
      return true;
    case 'a-prevenir':
      return ligne.aPrevenir;
    case 'echec':
      return hasFailed(ligne);
    case 'sans-email':
      return !ligne.email;
    case 'silencieux':
      return isSilent(ligne);
    case 'differes':
      return ligne.differe;
  }
}

/**
 * The rows a filter and a day keep. The day narrows to the people whose
 * pending changes belong to it — the rule the Journée's « Changements »
 * applies, so « ce qui change le 06/09 » counts the same people on both
 * screens.
 */
export function filterEnvois(
  lignes: readonly LigneEnvoi[],
  filtre: FiltreEnvois,
  jour: string | null,
): LigneEnvoi[] {
  return lignes.filter(
    (ligne) => matchesFilter(ligne, filtre) && (!jour || ligne.joursAPrevenir.includes(jour)),
  );
}

/** How many people each filter holds — the counts of the chips. */
export function countByFilter(lignes: readonly LigneEnvoi[]): Record<FiltreEnvois, number> {
  const comptes: Record<FiltreEnvois, number> = {
    tous: 0,
    'a-prevenir': 0,
    echec: 0,
    'sans-email': 0,
    silencieux: 0,
    differes: 0,
  };
  for (const ligne of lignes) {
    for (const filtre of FILTRES) {
      if (matchesFilter(ligne, filtre)) {
        comptes[filtre]++;
      }
    }
  }
  return comptes;
}

/** The days any pending change belongs to, sorted — what the day selector offers. */
export function daysToAnnounce(lignes: readonly LigneEnvoi[]): string[] {
  const jours = new Set<string>();
  for (const ligne of lignes) {
    ligne.joursAPrevenir.forEach((jour) => jours.add(jour));
  }
  return [...jours].sort((gauche, droite) => gauche.localeCompare(droite));
}

function shortDate(iso: string, locale: string): string {
  return new Date(iso).toLocaleDateString(locale, { day: '2-digit', month: '2-digit' });
}

function shortDateTime(iso: string, locale: string): string {
  return new Date(iso).toLocaleString(locale, {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** « v3 · 01/09 », or a dash for somebody never told anything. */
export function versionLabel(ligne: LigneEnvoi, locale: string): string {
  if (!ligne.version) {
    return '—';
  }
  const date = ligne.version.publieLe ? ` · ${shortDate(ligne.version.publieLe, locale)}` : '';
  return $localize`:@@diffuser.version:v${ligne.version.numero}:numero:${date}:date:`;
}

/** The cause of a failure, in a few words. */
export function causeLabel(cause: CauseEchec | null): string {
  switch (cause) {
    case 'ADRESSE_REFUSEE':
      return $localize`:@@diffuser.cause.adresse:adresse refusée`;
    case 'BOITE_PLEINE':
      return $localize`:@@diffuser.cause.boitePleine:boîte pleine`;
    case 'SERVEUR_INJOIGNABLE':
      return $localize`:@@diffuser.cause.serveur:serveur de messagerie injoignable`;
    default:
      return $localize`:@@diffuser.cause.autre:cause inconnue`;
  }
}

/** The tone of the state column: what the eye should catch first. */
export type TonEnvoi = 'ok' | 'echec' | 'attente' | 'neutre';

/**
 * « État de l'envoi »: how the latest planning mail went, and when. A fiche
 * without an address says so whatever the history — the next send will not
 * reach them either.
 */
export function deliveryState(ligne: LigneEnvoi, locale: string): { texte: string; ton: TonEnvoi } {
  const envoi = ligne.envoi;
  if (!ligne.email && (!envoi || envoi.statut !== 'ENVOYE')) {
    return { texte: $localize`:@@diffuser.etat.sansEmail:sans e-mail`, ton: 'attente' };
  }
  if (!envoi) {
    return { texte: $localize`:@@diffuser.etat.jamais:rien envoyé`, ton: 'neutre' };
  }
  const quand = shortDateTime(envoi.envoyeLe, locale);
  switch (envoi.statut) {
    case 'ENVOYE':
      return envoi.nature === 'RENVOI'
        ? { texte: $localize`:@@diffuser.etat.renvoye:renvoyé le ${quand}:quand:`, ton: 'ok' }
        : { texte: $localize`:@@diffuser.etat.envoye:envoyé le ${quand}:quand:`, ton: 'ok' };
    case 'ECHEC': {
      const cause = causeLabel(envoi.cause);
      return {
        texte: $localize`:@@diffuser.etat.echec:échec (${cause}:cause:) le ${quand}:quand:`,
        ton: 'echec',
      };
    }
    case 'SANS_EMAIL':
      return { texte: $localize`:@@diffuser.etat.sansEmail:sans e-mail`, ton: 'attente' };
    case 'EXCLU':
      return {
        texte: $localize`:@@diffuser.etat.differe:différé le ${quand}:quand:`,
        ton: 'attente',
      };
  }
}

/** « Rappel J-1 »: the night's reminder, sent or not. */
export function reminderLabel(ligne: LigneEnvoi, locale: string): string {
  if (!ligne.rappelVeilleLe) {
    return '—';
  }
  const quand = shortDateTime(ligne.rappelVeilleLe, locale);
  return ligne.rappelVeilleEchec
    ? $localize`:@@diffuser.rappel.echec:non envoyé ${quand}:quand:`
    : $localize`:@@diffuser.rappel.envoye:envoyé ${quand}:quand:`;
}

/** « Relance »: the latest reminder of the silent, by hand or by the night. */
export function relanceLabel(ligne: LigneEnvoi, locale: string): string {
  if (!ligne.relanceLe) {
    return '—';
  }
  const quand = shortDate(ligne.relanceLe, locale);
  return ligne.relanceEchec
    ? $localize`:@@diffuser.relance.echec:non relancé ${quand}:quand:`
    : $localize`:@@diffuser.relance.envoyee:relancé ${quand}:quand:`;
}

/** « Accusé »: confirmed, silent since, or nothing expected. */
export function acknowledgementLabel(ligne: LigneEnvoi, locale: string): string {
  if (ligne.confirmation === 'CONFIRME') {
    return ligne.confirmeLe
      ? $localize`:@@diffuser.accuse.confirmeLe:confirmé le ${shortDate(ligne.confirmeLe, locale)}:quand:`
      : $localize`:@@diffuser.accuse.confirme:confirmé`;
  }
  if (!ligne.affecte) {
    return '—';
  }
  return $localize`:@@diffuser.accuse.silencieux:silencieux`;
}
