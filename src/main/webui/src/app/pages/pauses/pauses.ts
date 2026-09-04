// Builders of the « Pauses » screen: the day list, the rows of one day grouped
// by stand, and the counters — pure functions over `RapportPauses`, kept out
// of the component so grouping and wording are tested without rendering.
//
// The report answers per animateur; the organiser reads per stand: at 19:00
// on stand X, who steps out and who covers. Hence the regrouping here.

import { correspondAuFiltre } from '../../core/text-filter';
import { JourneeAnimateurPauses, PauseDueView, RapportPauses, RelaisView } from '../../core/models';

/** One day of the event the report has something to say about. */
export interface JourPauses {
  /** ISO date, the selector's value. */
  date: string;
  jour: number;
  /** `J3 — 2026-07-16`. */
  title: string;
}

/** One break to organise, flattened: who, when, where, with whom. */
export interface LignePause {
  animateurId: string;
  nomComplet: string;
  mineur: boolean;
  sequenceDebut: string;
  sequenceFin: string;
  sequenceMinutes: number;
  heureLimite: string;
  dureeMinutes: number;
  standId: string;
  standNom: string;
  relais: RelaisView[];
  relaisDisponible: boolean;
}

/** The breaks of one stand on the selected day, deadlines in order. */
export interface GroupeStand {
  standId: string;
  standNom: string;
  lignes: LignePause[];
  relaisManquants: number;
}

/** A scheduled gap of the selected day, with whom it belongs to. */
export interface LignePausePlanifiee {
  animateurId: string;
  nomComplet: string;
  debut: string;
  fin: string;
  minutes: number;
}

export interface SynthesePauses {
  animateurs: number;
  pauses: number;
  relaisManquants: number;
  planifiees: number;
}

/** `10:00:00` → `10:00`; leaves anything already short alone. */
export function heure(valeur: string | null | undefined): string {
  return valeur && valeur.length > 5 ? valeur.slice(0, 5) : (valeur ?? '');
}

/** The days the report covers, chronological — the selector's options. */
export function joursDuRapport(rapport: RapportPauses | null): JourPauses[] {
  const parDate = new Map<string, number>();
  for (const journee of rapport?.journees ?? []) {
    if (!parDate.has(journee.date)) {
      parDate.set(journee.date, journee.jour);
    }
  }
  return Array.from(parDate.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, jour]) => ({ date, jour, title: `J${jour} — ${date}` }));
}

function lignesDe(journee: JourneeAnimateurPauses): LignePause[] {
  const lignes: LignePause[] = [];
  for (const sequence of journee.sequences) {
    for (const pause of sequence.pausesDues) {
      lignes.push({
        animateurId: journee.animateurId,
        nomComplet: journee.nomComplet,
        mineur: journee.mineur,
        sequenceDebut: sequence.debut,
        sequenceFin: sequence.fin,
        sequenceMinutes: sequence.minutes,
        heureLimite: pause.heureLimite,
        dureeMinutes: pause.dureeMinutes,
        standId: pause.standId,
        standNom: pause.standNom,
        relais: pause.relais,
        relaisDisponible: pause.relaisDisponible
      });
    }
  }
  return lignes;
}

function correspond(ligne: LignePause, recherche: string): boolean {
  return correspondAuFiltre(recherche, [
    ligne.nomComplet,
    ligne.standNom,
    ...ligne.relais.map((relais) => relais.nomComplet)
  ]);
}

/**
 * The breaks of one day, grouped by stand and ordered by deadline inside each
 * group — the order the relays happen in. Stands short of a relay come first;
 * a stand whose every break has one still shows, so the organiser confirms
 * rather than guesses.
 */
export function groupesDuJour(
  rapport: RapportPauses | null,
  date: string | null,
  recherche = '',
  sansRelaisSeulement = false
): GroupeStand[] {
  if (!rapport || !date) {
    return [];
  }
  const parStand = new Map<string, GroupeStand>();
  for (const journee of rapport.journees) {
    if (journee.date !== date) {
      continue;
    }
    for (const ligne of lignesDe(journee)) {
      if (sansRelaisSeulement && ligne.relaisDisponible) {
        continue;
      }
      if (recherche.trim() !== '' && !correspond(ligne, recherche)) {
        continue;
      }
      let groupe = parStand.get(ligne.standId);
      if (!groupe) {
        groupe = { standId: ligne.standId, standNom: ligne.standNom, lignes: [], relaisManquants: 0 };
        parStand.set(ligne.standId, groupe);
      }
      groupe.lignes.push(ligne);
      if (!ligne.relaisDisponible) {
        groupe.relaisManquants++;
      }
    }
  }
  const groupes = Array.from(parStand.values());
  for (const groupe of groupes) {
    groupe.lignes.sort(
      (a, b) => a.heureLimite.localeCompare(b.heureLimite) || a.nomComplet.localeCompare(b.nomComplet)
    );
  }
  return groupes.sort(
    (a, b) =>
      b.relaisManquants - a.relaisManquants ||
      a.standNom.localeCompare(b.standNom, undefined, { sensitivity: 'base' })
  );
}

/** The gaps the grid already gives on that day, by start time then name. */
export function planifieesDuJour(
  rapport: RapportPauses | null,
  date: string | null,
  recherche = ''
): LignePausePlanifiee[] {
  if (!rapport || !date) {
    return [];
  }
  const lignes: LignePausePlanifiee[] = [];
  for (const journee of rapport.journees) {
    if (
      journee.date !== date ||
      (recherche.trim() !== '' && !correspondAuFiltre(recherche, [journee.nomComplet]))
    ) {
      continue;
    }
    for (const pause of journee.pausesPlanifiees) {
      lignes.push({
        animateurId: journee.animateurId,
        nomComplet: journee.nomComplet,
        debut: pause.debut,
        fin: pause.fin,
        minutes: pause.minutes
      });
    }
  }
  return lignes.sort((a, b) => a.debut.localeCompare(b.debut) || a.nomComplet.localeCompare(b.nomComplet));
}

/** The counters of the selected day: breaks to organise, of which without relay, over how many people. */
export function syntheseDuJour(rapport: RapportPauses | null, date: string | null): SynthesePauses {
  const synthese: SynthesePauses = { animateurs: 0, pauses: 0, relaisManquants: 0, planifiees: 0 };
  if (!rapport || !date) {
    return synthese;
  }
  const animateurs = new Set<string>();
  for (const journee of rapport.journees) {
    if (journee.date !== date) {
      continue;
    }
    for (const ligne of lignesDe(journee)) {
      animateurs.add(ligne.animateurId);
      synthese.pauses++;
      if (!ligne.relaisDisponible) {
        synthese.relaisManquants++;
      }
    }
    synthese.planifiees += journee.pausesPlanifiees.length;
  }
  synthese.animateurs = animateurs.size;
  return synthese;
}

/** The relays of a break, spelled out for a cell; empty when nobody. */
export function libelleRelais(pause: Pick<PauseDueView, 'relais'>): string {
  return pause.relais.map((relais) => relais.nomComplet).join(', ');
}
