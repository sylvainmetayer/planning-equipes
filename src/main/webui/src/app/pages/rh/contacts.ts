// The pure side of the contacts screens (the RH one and the animateur's own).

import { Avertissement, ContactsAnimateur, estJournalisable } from '../../core/models';

/** What the four inputs hold: texts, never `null`. */
export interface BrouillonContacts {
  urgenceNom: string;
  urgenceTelephone: string;
  representantNom: string;
  representantTelephone: string;
}

export function draftOf(contacts: ContactsAnimateur | null | undefined): BrouillonContacts {
  return {
    urgenceNom: contacts?.urgenceNom ?? '',
    urgenceTelephone: contacts?.urgenceTelephone ?? '',
    representantNom: contacts?.representantNom ?? '',
    representantTelephone: contacts?.representantTelephone ?? '',
  };
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
