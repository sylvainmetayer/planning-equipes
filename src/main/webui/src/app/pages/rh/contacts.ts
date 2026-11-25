// The pure side of the contacts screens (the RH one and the animateur's own).

import { ApiError } from '../../core/api.service';
import { Avertissement, ContactsAnimateur, estJournalisable } from '../../core/models';

/** What the four inputs hold — texts, never `null` — and the stamp of the read they started from. */
export interface BrouillonContacts {
  urgenceNom: string;
  urgenceTelephone: string;
  representantNom: string;
  representantTelephone: string;
  modifieLe: string | null;
}

/** The four text fields of a draft, the ones an input writes. */
export type ChampContact = Exclude<keyof BrouillonContacts, 'modifieLe'>;

export function draftOf(contacts: ContactsAnimateur | null | undefined): BrouillonContacts {
  return {
    urgenceNom: contacts?.urgenceNom ?? '',
    urgenceTelephone: contacts?.urgenceTelephone ?? '',
    representantNom: contacts?.representantNom ?? '',
    representantTelephone: contacts?.representantTelephone ?? '',
    modifieLe: contacts?.modifieLe ?? null,
  };
}

/**
 * The stamp of the row as somebody else last wrote it, when a save was refused for being based on an old
 * read: carried into the draft, it makes the next save overwrite knowingly — what the refusal offers.
 */
export function staleStamp(error: unknown): string | null {
  return error instanceof ApiError && error.modificationConcurrente ? error.modifieLe : null;
}

/** The body sent: blanks stay blank, the server trims and drops them. */
export function contactsOf(brouillon: BrouillonContacts): ContactsAnimateur {
  return { ...brouillon };
}

/** Whether a legal representative is recorded: a name or a number. */
export function hasRepresentative(contacts: ContactsAnimateur): boolean {
  return Boolean(contacts.representantNom?.trim() || contacts.representantTelephone?.trim());
}

/** A `tel:` address from what a person typed: digits and a leading `+`. */
export function telHref(telephone: string): string {
  return `tel:${telephone.replace(/[^0-9+]/g, '')}`;
}

/**
 * The sentence the notification log keeps for a warning: the screen shows the text as sent, but
 * what says somebody is a minor does not outlive it (`docs/rgpd.md` §7).
 */
export function journalText(avertissement: Avertissement): string {
  return estJournalisable(avertissement) ? avertissement.message : '';
}
