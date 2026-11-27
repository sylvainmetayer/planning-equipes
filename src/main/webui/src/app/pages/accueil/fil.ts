// The single feed of the home screen: the plan's alerts, the anomalies of the
// referential and the application's messages in one list, most serious first
// and, within a gravity, newest first. Pure functions, called at runtime so
// `$localize` resolves after the catalog is loaded.

import { AlerteView, CoherenceReport } from '../../core/models';
import { AppNotification, NotificationSeverity } from '../../core/notification.service';
import type { LienEtat } from './accueil';
import { coherenceLines, familyTitle } from './coherence';

/** Where an item comes from: the `?type=` of the address. */
export type FilType = 'alertes' | 'coherence' | 'messages';
export const FIL_TYPES: readonly FilType[] = ['alertes', 'coherence', 'messages'];

/** Hard blockers first, then warnings, then messages. */
export type FilGravite = 'blocage' | 'avertissement' | 'message';
const RANG: Record<FilGravite, number> = { blocage: 0, avertissement: 1, message: 2 };

export interface FilItem {
  /** The `track` key. */
  id: string;
  type: FilType;
  gravite: FilGravite;
  variante: NotificationSeverity;
  /** The person, the family of anomalies or the message's title the item opens with. */
  titre: string | null;
  texte: string;
  /** An address of the application given by the server (a weather alert's consigne). */
  href?: string;
  /** The screen that corrects the item. */
  lien?: LienEtat;
  /** When it happened, in ms; `null` for an anomaly, which has no instant of its own. */
  quand: number | null;
  /** A message of this browser's history: the only kind that is read and cleared. */
  message?: AppNotification;
}

/** The type asked by the address; anything else shows the whole feed. */
export function filType(valeur: string | null): FilType | null {
  return FIL_TYPES.find((type) => type === valeur) ?? null;
}

export function graviteLabel(gravite: FilGravite): string {
  switch (gravite) {
    case 'blocage':
      return $localize`:@@fil.gravite.blocage:Bloquant`;
    case 'avertissement':
      return $localize`:@@fil.gravite.avertissement:À vérifier`;
    case 'message':
      return $localize`:@@fil.gravite.message:Message`;
  }
}

const VARIANTE: Record<FilGravite, NotificationSeverity> = {
  blocage: 'alert',
  avertissement: 'warning',
  message: 'info',
};

const ALERTE_GRAVITE: Record<AlerteView['severite'], FilGravite> = {
  ALERTE: 'blocage',
  WARNING: 'avertissement',
  INFO: 'message',
};

/**
 * Everything the feed holds, sorted. A person is named as the screen already
 * names them — the alert's resolved name, else the id — and an anomaly speaks
 * of an animateur by id, as the report does.
 */
export function buildFil(
  alertes: readonly AlerteView[],
  coherence: CoherenceReport | null,
  messages: readonly AppNotification[],
): FilItem[] {
  const items: FilItem[] = [];
  for (const alerte of alertes) {
    const gravite = ALERTE_GRAVITE[alerte.severite];
    items.push({
      id: `alertes|${alerte.type}|${alerte.cle}`,
      type: 'alertes',
      gravite,
      variante: VARIANTE[gravite],
      titre: alerte.nomAffiche ?? alerte.animateurId,
      texte: alerte.libelle,
      href: alerte.lien ?? undefined,
      quand: Date.parse(alerte.declencheLe),
    });
  }
  coherenceLines(coherence?.anomalies ?? []).forEach((ligne, index) => {
    const gravite: FilGravite =
      ligne.gravite === 'BLOQUANT'
        ? 'blocage'
        : ligne.gravite === 'A_VERIFIER'
          ? 'avertissement'
          : 'message';
    items.push({
      id: `coherence|${index}`,
      type: 'coherence',
      gravite,
      variante: VARIANTE[gravite],
      titre: familyTitle(ligne.famille),
      texte: ligne.sentence,
      lien: ligne.lien,
      quand: null,
    });
  });
  // The history is what happened, not what is still open: always a message,
  // whatever the toast's severity was; its icon keeps it.
  for (const message of messages) {
    items.push({
      id: `messages|${message.id}`,
      type: 'messages',
      gravite: 'message',
      variante: message.severity,
      titre: message.title,
      texte: message.message,
      quand: message.timestamp,
      message,
    });
  }
  return items.sort(
    (gauche, droite) =>
      RANG[gauche.gravite] - RANG[droite.gravite] ||
      (droite.quand ?? Number.NEGATIVE_INFINITY) - (gauche.quand ?? Number.NEGATIVE_INFINITY) ||
      0,
  );
}

/** The feed narrowed to one type; `null` keeps everything. */
export function filtrerFil(items: readonly FilItem[], type: FilType | null): FilItem[] {
  return type ? items.filter((item) => item.type === type) : [...items];
}
