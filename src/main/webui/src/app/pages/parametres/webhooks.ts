import { WebhookDeliveryStatus, WebhookFormat, WebhookRequest } from '../../core/models';

/**
 * The outgoing webhooks, the pure half: the vocabulary of the events and the
 * formats, and the request the form builds — tested without rendering.
 */

/** The events a webhook may subscribe to, in the order the form lists them. */
export const SUBSCRIBABLE_EVENTS = [
  'planning.publie',
  'echange.soumis',
  'echanges.en_attente',
  'disponibilites.declaree',
  'resolution.terminee',
  'sauvegarde.echec',
] as const;

export const WEBHOOK_FORMATS: WebhookFormat[] = [
  'GENERIC',
  'SLACK',
  'DISCORD',
  'MATRIX',
  'TELEGRAM',
];

/** What an event code says on screen; an unknown code is shown as is. */
export function eventLabel(code: string): string {
  switch (code) {
    case 'planning.publie':
      return $localize`:@@parametres.webhooks.evenement.planningPublie:Planning publié`;
    case 'echange.soumis':
      return $localize`:@@parametres.webhooks.evenement.echangeSoumis:Demande d'échange à trancher`;
    case 'echanges.en_attente':
      return $localize`:@@parametres.webhooks.evenement.echangesEnAttente:Échanges en attente depuis trop longtemps`;
    case 'disponibilites.declaree':
      return $localize`:@@parametres.webhooks.evenement.disponibilitesDeclaree:Disponibilités déclarées`;
    case 'resolution.terminee':
      return $localize`:@@parametres.webhooks.evenement.resolutionTerminee:Résolution terminée`;
    case 'sauvegarde.echec':
      return $localize`:@@parametres.webhooks.evenement.sauvegardeEchec:Échec de la sauvegarde nocturne`;
    case 'test':
      return $localize`:@@parametres.webhooks.evenement.test:Test`;
    default:
      return code;
  }
}

export function formatLabel(format: WebhookFormat): string {
  switch (format) {
    case 'GENERIC':
      return $localize`:@@parametres.webhooks.format.generic:Générique (JSON signé)`;
    case 'SLACK':
      return 'Slack';
    case 'DISCORD':
      return 'Discord';
    case 'MATRIX':
      return $localize`:@@parametres.webhooks.format.matrix:Matrix (pont hookshot)`;
    case 'TELEGRAM':
      return 'Telegram';
  }
}

export function statusLabel(status: WebhookDeliveryStatus): string {
  switch (status) {
    case 'PENDING':
      return $localize`:@@parametres.webhooks.statut.pending:En attente`;
    case 'DELIVERED':
      return $localize`:@@parametres.webhooks.statut.delivered:Livrée`;
    case 'FAILED':
      return $localize`:@@parametres.webhooks.statut.failed:Échec`;
    case 'ABANDONED':
      return $localize`:@@parametres.webhooks.statut.abandoned:Abandonnée`;
  }
}

/** Whether the address an admin types is itself the secret, never shown again. */
export function addressIsSecret(format: WebhookFormat): boolean {
  return format === 'SLACK' || format === 'DISCORD' || format === 'MATRIX';
}

/** What the form holds while it is being filled. */
export interface WebhookDraft {
  name: string;
  format: WebhookFormat;
  url: string;
  token: string;
  chatId: string;
  events: string[];
  active: boolean;
}

/**
 * Whether the draft can be sent. On an edit, a blank address or token keeps
 * the stored one — unless the format changed, which needs its own.
 */
export function draftComplete(draft: WebhookDraft, formatBefore: WebhookFormat | null): boolean {
  if (!draft.name.trim()) {
    return false;
  }
  const creating = formatBefore === null;
  const needsSecret = creating || formatBefore !== draft.format;
  if (draft.format === 'TELEGRAM') {
    return draft.chatId.trim() !== '' && (!needsSecret || draft.token.trim() !== '');
  }
  return !needsSecret || draft.url.trim() !== '';
}

/** The request a draft becomes: the fields the chosen format does not read are left out. */
export function toRequest(draft: WebhookDraft): WebhookRequest {
  const telegram = draft.format === 'TELEGRAM';
  return {
    name: draft.name.trim(),
    format: draft.format,
    url: telegram ? null : draft.url.trim(),
    token: telegram ? draft.token.trim() : null,
    chatId: telegram ? draft.chatId.trim() : null,
    events: SUBSCRIBABLE_EVENTS.filter((code) => draft.events.includes(code)),
    active: draft.active,
  };
}
