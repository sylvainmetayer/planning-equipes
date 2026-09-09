// Pure logic of the history screen: what the filters keep, and how a line
// reads. No Angular here, so it is unit-tested without rendering anything.

import { EntreeHistorique } from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';

/** Which actors the list keeps. `TOUS` is the default: the history is read whole. */
export type FiltreActeur = 'TOUS' | 'ADMIN' | 'ANIMATEUR' | 'ANONYME' | 'ASSISTANT' | 'SYSTEME';

/** Which outcomes the list keeps. A refusal is often the line being looked for. */
export type FiltreResultat = 'TOUS' | 'SUCCES' | 'REFUS';

const ACTEURS: FiltreActeur[] = ['TOUS', 'ADMIN', 'ANIMATEUR', 'ANONYME', 'ASSISTANT', 'SYSTEME'];
const RESULTATS: FiltreResultat[] = ['TOUS', 'SUCCES', 'REFUS'];

/** Reads a filter off the URL, falling back to its default on anything unknown. */
export function lireFiltreActeur(valeur: string | null): FiltreActeur {
  return ACTEURS.includes(valeur as FiltreActeur) ? (valeur as FiltreActeur) : 'TOUS';
}

export function lireFiltreResultat(valeur: string | null): FiltreResultat {
  return RESULTATS.includes(valeur as FiltreResultat) ? (valeur as FiltreResultat) : 'TOUS';
}

/**
 * The lines a reader asked for. The free-text search covers what is on screen —
 * the sentence, the actor, the entity and its id — and nothing else: searching
 * a name that the table does not store would silently return nothing.
 */
export function filter(
  entrees: EntreeHistorique[],
  acteur: FiltreActeur,
  resultat: FiltreResultat,
  entite: string,
  recherche: string
): EntreeHistorique[] {
  return entrees.filter((entree) => {
    if (acteur !== 'TOUS' && entree.acteur !== acteur) {
      return false;
    }
    if (resultat !== 'TOUS' && entree.resultat !== resultat) {
      return false;
    }
    if (entite && entree.entite !== entite) {
      return false;
    }
    return correspondAuFiltre(recherche, [
      entree.libelle,
      entree.acteurNom ?? entree.acteurId ?? '',
      entree.entiteNom ?? '',
      entree.entiteId ?? '',
      entree.champs.join(' ')
    ]);
  });
}

/** The entity families present in what was loaded, so the filter offers only real ones. */
export function entitesPresentes(entrees: EntreeHistorique[]): string[] {
  return [...new Set(entrees.map((entree) => entree.entite).filter((entite): entite is string => !!entite))].sort();
}

/**
 * Who did it, in one readable phrase. An animateur still on the roster is
 * named; one deleted since is not, and that is the point of storing an id
 * rather than a name — so the id stands in rather than an empty cell.
 */
export function qui(entree: EntreeHistorique): string {
  if (entree.acteur === 'SYSTEME') {
    return $localize`:@@historique.acteur.systeme:Application`;
  }
  if (entree.acteur === 'ASSISTANT') {
    return $localize`:@@historique.acteur.assistant:Assistant (MCP)`;
  }
  if (entree.acteur === 'ANIMATEUR') {
    return entree.acteurNom ?? entree.acteurId ?? $localize`:@@historique.acteur.animateur:Animateur`;
  }
  if (entree.acteur === 'ANONYME') {
    return $localize`:@@historique.acteur.anonyme:Visiteur non identifié`;
  }
  return entree.acteurId ?? $localize`:@@historique.acteur.admin:Administration`;
}

/** What it bore upon, named when the referential still knows it. */
export function surQuoi(entree: EntreeHistorique): string {
  if (!entree.entiteId) {
    return '';
  }
  return entree.entiteNom ? `${entree.entiteNom} (${entree.entiteId})` : entree.entiteId;
}

/**
 * The calendar day an instant falls on **where the reader is**, as
 * `YYYY-MM-DD`.
 *
 * Not `survenuLe.slice(0, 10)`, which is the UTC date: the hour beside it is
 * rendered in the browser's zone, so in Europe/Paris an action at 00h30 on the
 * 8th (22:30Z on the 7th) would sit under « lundi 7 septembre » showing
 * « 00:30 ». Evening and night work is exactly when this screen is read.
 */
export function journeeLocale(iso: string): string {
  const date = new Date(iso);
  const mois = `${date.getMonth() + 1}`.padStart(2, '0');
  const jour = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${mois}-${jour}`;
}

/** Groups the lines by calendar day, newest first — an event week piles up hundreds. */
export function parJournee(entrees: EntreeHistorique[]): { jour: string; entrees: EntreeHistorique[] }[] {
  const journees = new Map<string, EntreeHistorique[]>();
  for (const entree of entrees) {
    const jour = journeeLocale(entree.survenuLe);
    const existantes = journees.get(jour);
    if (existantes) {
      existantes.push(entree);
    } else {
      journees.set(jour, [entree]);
    }
  }
  return [...journees.entries()].map(([jour, lignes]) => ({ jour, entrees: lignes }));
}
