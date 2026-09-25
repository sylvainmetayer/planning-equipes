import { describe, expect, it } from 'vitest';
import { ConsigneEdition } from '../../core/models';
import {
  toConsigneDraft,
  isConsigneModified,
  consigneDateOf,
  consigneRecordId,
  readConsigneDraft,
} from './consigne-brouillon';
import { ConsigneForm, StandForm, formVide } from './consignes';

const STAND: StandForm = {
  standId: 's1',
  standNom: 'Buvette',
  coche: true,
  minutesPerdues: 60,
  effectifHerite: 2,
  exceptionDatee: false,
  motif: null,
  preCoche: true,
  fenetres: [{ debut: '18:00', fin: '', effectif: '' }],
  effectifMax: 3,
};

function form(patch: Partial<ConsigneForm> = {}): ConsigneForm {
  return { ...formVide(), dates: ['2026-07-14'], ...patch };
}

describe('consigne-brouillon', () => {
  it('nomme la fiche par le mode et la date, et rien pour une nouvelle consigne', () => {
    const consigne = { date: '2026-07-14' } as ConsigneEdition;

    expect(consigneRecordId('poser', null)).toBeNull();
    expect(consigneRecordId('modifier', consigne)).toBe('modifier.2026-07-14');
    expect(consigneRecordId('prolonger', consigne)).toBe('prolonger.2026-07-14');
    expect(consigneDateOf('modifier.2026-07-14')).toBe('2026-07-14');
  });

  it('ne garde les lignes de stands qu’une fois qu’un geste les a touchées', () => {
    expect(toConsigneDraft(form({ stands: [STAND] }), false).stands).toBeNull();
    expect(toConsigneDraft(form({ stands: [STAND] }), true).stands).toEqual([STAND]);
  });

  it('la proposition du serveur qui arrive n’est pas une modification', () => {
    const initial = toConsigneDraft(form(), false);

    expect(isConsigneModified(initial, toConsigneDraft(form({ stands: [STAND] }), false))).toBe(
      false,
    );
  });

  it('un motif, une bande ou une date changés en sont une ; un geste sur un stand aussi', () => {
    const initial = toConsigneDraft(form(), false);

    expect(isConsigneModified(initial, toConsigneDraft(form({ motif: 'Canicule' }), false))).toBe(
      true,
    );
    expect(
      isConsigneModified(initial, toConsigneDraft(form({ fermetureDebut: '13:00' }), false)),
    ).toBe(true);
    expect(
      isConsigneModified(initial, toConsigneDraft(form({ dates: ['2026-07-15'] }), false)),
    ).toBe(true);
    expect(isConsigneModified(initial, toConsigneDraft(form({ stands: [STAND] }), true))).toBe(
      true,
    );
  });

  it('restores what it wrote, stand rows included', () => {
    const draft = toConsigneDraft(form({ motif: 'Canicule', stands: [STAND] }), true);

    expect(readConsigneDraft(structuredClone(draft))).toEqual(draft);
  });

  it('refuse ce qu’un formulaire ne saurait tenir', () => {
    const draft = toConsigneDraft(form(), false);

    expect(readConsigneDraft(undefined)).toBeNull();
    expect(readConsigneDraft({ ...draft, dates: '2026-07-14' })).toBeNull();
    expect(readConsigneDraft({ ...draft, repas: {} })).toBeNull();
    expect(readConsigneDraft({ ...draft, stands: [{ standId: 's1' }] })).toBeNull();
  });
});
