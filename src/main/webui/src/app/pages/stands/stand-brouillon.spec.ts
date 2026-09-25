import { describe, expect, it } from 'vitest';
import type { HoraireStand, Stand } from '../../core/models';
import { readStandDraft, isStandModified } from './stand-brouillon';
import { StandDraft, toDraft } from './stand-draft';

const RULE: HoraireStand = {
  id: 1,
  mode: 'OUVERTURE',
  jours: 'TOUS',
  joursSemaine: [],
  dateDebut: null,
  dateFin: null,
  dates: [],
  fenetres: [{ heureDebut: '10:00', heureFin: '18:00' }],
  motif: null,
};

const STAND = {
  id: 's1',
  nom: 'Buvette',
  effectifMin: 1,
  effectifMax: 3,
  typologiesProposees: ['ambiance'],
  horaires: [RULE],
  modifieLe: '2026-07-01T08:00:00Z',
} as Stand;

function withRule(draft: StandDraft, patch: Partial<StandDraft['horaires'][number]>): StandDraft {
  return { ...draft, horaires: [{ ...draft.horaires[0], ...patch }] };
}

describe('stand-brouillon', () => {
  it('un stand rouvert tel quel n’est pas modifié', () => {
    expect(isStandModified(toDraft(STAND), toDraft(STAND))).toBe(false);
  });

  it('déplier une règle ou passer à sa vue détaillée n’est pas une saisie', () => {
    const initial = toDraft(STAND);

    expect(isStandModified(initial, withRule(initial, { deplie: true, detail: true }))).toBe(false);
  });

  it('une ligne retapée à l’identique n’est pas une saisie', () => {
    const initial = toDraft(STAND);

    expect(isStandModified(initial, withRule(initial, { saisie: '10:00-18:00' }))).toBe(false);
  });

  it('une ligne en cours de frappe, encore illisible, en est une', () => {
    const initial = toDraft(STAND);

    expect(isStandModified(initial, withRule(initial, { saisie: '10:0' }))).toBe(true);
  });

  it('un nom, un effectif ou une fenêtre changés en sont une', () => {
    const initial = toDraft(STAND);

    expect(isStandModified(initial, { ...initial, nom: 'Buvette 2' })).toBe(true);
    expect(isStandModified(initial, { ...initial, effectifMax: 4 })).toBe(true);
    expect(
      isStandModified(
        initial,
        withRule(initial, { fenetres: [{ heureDebut: '09:00', heureFin: '18:00' }] }),
      ),
    ).toBe(true);
  });

  it('restores the draft as it was left, typed line included', () => {
    const draft = withRule(toDraft(STAND), { saisie: '10:0', deplie: true });
    // What the storage hands back: the draft once serialized, not a structured clone.
    const serialized = JSON.stringify(draft);
    const stored: unknown = JSON.parse(serialized);

    expect(readStandDraft(stored, 's1')).toEqual(draft);
  });

  it('ne restaure jamais le brouillon d’un autre stand', () => {
    expect(readStandDraft(toDraft(STAND), 's2')).toBeNull();
  });

  it('restores a draft saved before stands carried a code, with an empty one', () => {
    const { code: _code, ...ancien } = toDraft({ ...STAND, code: 'BUVETTE' });

    expect(readStandDraft(JSON.parse(JSON.stringify(ancien)), 's1')?.code).toBe('');
    expect(readStandDraft({ ...ancien, code: 42 }, 's1')).toBeNull();
  });

  it('refuse ce qu’un formulaire ne saurait tenir', () => {
    expect(readStandDraft('texte', null)).toBeNull();
    expect(readStandDraft({ ...toDraft(STAND), niveauEffort: 'EXTREME' }, 's1')).toBeNull();
    expect(readStandDraft({ ...toDraft(STAND), horaires: [{}] }, 's1')).toBeNull();
    expect(readStandDraft({ ...toDraft(STAND), ouvertures: 'aucune' }, 's1')).toBeNull();
  });
});
