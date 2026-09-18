// How a consigne (issue #4) reads on every screen that marks a day as « sous
// consigne »: the band, a window, a date, the meal override. Shared by the
// Consignes page, the Journée card, the home banner, the badges of the
// Créneaux, Ouvertures and Jour J screens, the animateur timeline and the
// public espace — hence `core/`, not a page's folder.
//
// Hours come from the server as `HH:mm:ss`; an end of `null` reads
// « jusqu'à minuit », the convention of every dated window here. Everything
// calling `$localize` runs at call time, never at module scope.

import { jourSemaineDe, libelleJour, libelleJourSemaine } from './horaire-stand';
import { FenetreConsigne, RepasConsigne } from './models';

/** `2026-07-10` → `Vendredi 10/07`: a date as the table and the selectors say it. */
export function libelleDate(date: string): string {
  return `${libelleJourSemaine(jourSemaineDe(date))} ${libelleJour(date)}`;
}

/** `12:00:00` → `12h`, `12:30:00` → `12h30`: the way an organiser says an hour. */
export function heureLabel(heure: string): string {
  const [heures, minutes = '00'] = heure.split(':');
  return minutes === '00' ? `${Number(heures)}h` : `${Number(heures)}h${minutes}`;
}

/** `12h–18h`, or `12h–minuit` for an open end. */
export function bandeLabel(debut: string, fin: string | null): string {
  return `${heureLabel(debut)}–${fin ? heureLabel(fin) : minuitLabel()}`;
}

export function fenetreLabel(fenetre: FenetreConsigne): string {
  return bandeLabel(fenetre.debut, fenetre.fin);
}

function minuitLabel(): string {
  return $localize`:@@consignes.minuit:minuit`;
}

/** True when the consigne restates at least one meal window or the break — the chip's condition. */
export function repasSurcharge(repas: RepasConsigne | null): repas is RepasConsigne {
  return (
    repas !== null &&
    (repas.midiDebut !== null ||
      repas.midiFin !== null ||
      repas.soirDebut !== null ||
      repas.soirFin !== null ||
      repas.coupureMinutes !== null)
  );
}

/**
 * The tooltip of the chip: the justification, then what is restated —
 * « Les équipes mangent pendant la fermeture · soir 18h–22h · coupure 45 min ».
 */
export function repasLabel(repas: RepasConsigne): string {
  const parts = [repas.justification.trim()];
  if (repas.midiDebut && repas.midiFin) {
    parts.push(
      $localize`:@@consignes.repas.midi:midi ${bandeLabel(repas.midiDebut, repas.midiFin)}:bande:`,
    );
  }
  if (repas.soirDebut && repas.soirFin) {
    parts.push(
      $localize`:@@consignes.repas.soir:soir ${bandeLabel(repas.soirDebut, repas.soirFin)}:bande:`,
    );
  }
  if (repas.coupureMinutes !== null) {
    parts.push($localize`:@@consignes.repas.coupure:coupure ${repas.coupureMinutes}:minutes: min`);
  }
  return parts.filter(Boolean).join(' · ');
}
