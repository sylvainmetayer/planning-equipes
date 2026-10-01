import { describe, expect, it } from 'vitest';
import { draftComplete, toRequest, WebhookDraft } from './webhooks';

const draft = (champs: Partial<WebhookDraft>): WebhookDraft => ({
  name: 'Salon orga',
  format: 'GENERIC',
  url: '',
  token: '',
  chatId: '',
  events: [],
  active: true,
  ...champs,
});

describe('webhook form', () => {
  it('needs an address to create a generic or chat webhook', () => {
    expect(draftComplete(draft({}), null)).toBe(false);
    expect(draftComplete(draft({ url: 'https://n8n.example.org/webhook/x' }), null)).toBe(true);
    expect(draftComplete(draft({ format: 'SLACK', url: 'https://hooks.slack.com/x' }), null)).toBe(
      true,
    );
  });

  it('keeps the stored secret on an edit that leaves it blank, not on a change of format', () => {
    expect(draftComplete(draft({ format: 'SLACK' }), 'SLACK')).toBe(true);
    expect(draftComplete(draft({ format: 'SLACK' }), 'GENERIC')).toBe(false);
  });

  it('needs a chat, and a token unless it is kept, for Telegram', () => {
    expect(draftComplete(draft({ format: 'TELEGRAM', token: '1:abc' }), null)).toBe(false);
    expect(draftComplete(draft({ format: 'TELEGRAM', token: '1:abc', chatId: '-100' }), null)).toBe(
      true,
    );
    expect(draftComplete(draft({ format: 'TELEGRAM', chatId: '-100' }), 'TELEGRAM')).toBe(true);
  });

  it('sends only the fields its format reads, events in the vocabulary order', () => {
    expect(
      toRequest(
        draft({
          name: ' Bot ',
          format: 'TELEGRAM',
          url: 'https://ignored',
          token: ' 1:abc ',
          chatId: ' -100 ',
          events: ['sauvegarde.echec', 'planning.publie', 'inconnu'],
        }),
      ),
    ).toEqual({
      name: 'Bot',
      format: 'TELEGRAM',
      url: null,
      token: '1:abc',
      chatId: '-100',
      events: ['planning.publie', 'sauvegarde.echec'],
      active: true,
    });
    expect(toRequest(draft({ url: ' https://x ', token: 'ignored' }))).toMatchObject({
      url: 'https://x',
      token: null,
      chatId: null,
    });
  });
});
