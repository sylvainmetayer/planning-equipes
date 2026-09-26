// « Je ne pourrai pas être là » (issue #533), the pure side: which of my
// reports a day shows, whether the day or a seat is already reported, and the
// words each state and reason put on screen.

import { SignalementView, StatutSignalement } from '../../core/models';

export { motifLabel } from '../../core/signalement-wording';

/** My reports of one day, withdrawn ones left out: what the day card says. */
export function reportsOfDay(
  signalements: readonly SignalementView[],
  date: string,
): SignalementView[] {
  return signalements.filter((each) => each.date === date && each.statut !== 'ANNULE');
}

/** An open report already covers the whole day: nothing more to report on it. */
export function dayReported(signalements: readonly SignalementView[], date: string): boolean {
  return signalements.some(
    (each) => each.date === date && each.portee === 'JOUR' && each.statut === 'SIGNALE',
  );
}

/** An open report already names this seat. */
export function seatReported(
  signalements: readonly SignalementView[],
  creneauId: number,
  standId: string,
): boolean {
  return signalements.some(
    (each) =>
      each.portee === 'POSTE' &&
      each.creneauId === creneauId &&
      each.standId === standId &&
      each.statut === 'SIGNALE',
  );
}

/** Where a report stands, in the animateur's words. */
export function statutLabel(statut: StatutSignalement): string {
  switch (statut) {
    case 'SIGNALE':
      return $localize`:@@espace.signalement.statut.signale:Transmis à l'organisation, en attente`;
    case 'TRAITE':
      return $localize`:@@espace.signalement.statut.traite:Pris en compte : votre absence est notée`;
    case 'CLASSE':
      return $localize`:@@espace.signalement.statut.classe:Lu par l'organisation, sans changement`;
    case 'ANNULE':
      return $localize`:@@espace.signalement.statut.annule:Annulé`;
  }
}

/** What was reported: « Toute la journée » or « Stand 07, 09:00–12:00 ». */
export function objectLabel(signalement: SignalementView): string {
  if (signalement.portee === 'JOUR') {
    return $localize`:@@espace.signalement.objet.jour:Toute la journée`;
  }
  const debut = signalement.heureDebut?.slice(0, 5) ?? '';
  const fin = signalement.heureFin?.slice(0, 5) ?? '';
  return `${signalement.standNom ?? signalement.standId ?? ''}, ${debut}–${fin}`;
}
