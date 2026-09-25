import { describe, expect, it } from 'vitest';
import { Animateur } from '../../core/models';
import { isAnimateurModified, readAnimateurDraft, toDraft } from './animateur-brouillon';

const RECORD: Animateur = {
  id: 'a1',
  prenom: 'Amélie',
  nom: 'Nothomb',
  dateNaissance: '1990-05-04',
  manager: false,
  email: null,
  competences: { ambiance: 'AUTONOME' },
  souhaits: [],
  joursIndisponibles: ['2026-07-14'],
  modifieLe: '2026-07-01T08:00:00Z',
};

describe('animateur-brouillon', () => {
  it('une fiche rouverte telle quelle n’est pas modifiée', () => {
    expect(isAnimateurModified(toDraft(RECORD), toDraft(RECORD))).toBe(false);
  });

  it('un champ tapé, une appréciation ou un jour ajoutés la modifient', () => {
    const initial = toDraft(RECORD);

    expect(isAnimateurModified(initial, { ...initial, email: 'a@b.fr' })).toBe(true);
    expect(
      isAnimateurModified(initial, {
        ...initial,
        competences: [...initial.competences, { typologie: 'expert', niveau: 'REFERENT' }],
      }),
    ).toBe(true);
    expect(
      isAnimateurModified(initial, {
        ...initial,
        joursIndisponibles: ['2026-07-14', '2026-07-15'],
      }),
    ).toBe(true);
  });

  it('la précondition portée ne compte pas comme une saisie', () => {
    const initial = toDraft(RECORD);

    expect(isAnimateurModified(initial, { ...initial, modifieLe: '2026-07-02T08:00:00Z' })).toBe(
      false,
    );
  });

  it('restores a draft of the same record', () => {
    const draft = { ...toDraft(RECORD), nom: 'Nothomb-Martin' };

    expect(readAnimateurDraft(structuredClone(draft), 'a1')).toEqual(draft);
  });

  it('ne restaure jamais le brouillon d’une autre fiche', () => {
    expect(readAnimateurDraft(toDraft(RECORD), 'a2')).toBeNull();
  });

  it('refuse ce qu’un formulaire ne saurait tenir', () => {
    expect(readAnimateurDraft(null, null)).toBeNull();
    expect(readAnimateurDraft({ ...toDraft(RECORD), manager: 'oui' }, 'a1')).toBeNull();
    expect(
      readAnimateurDraft(
        { ...toDraft(RECORD), competences: [{ typologie: 'ambiance', niveau: 'MAITRE' }] },
        'a1',
      ),
    ).toBeNull();
    expect(readAnimateurDraft({ ...toDraft(RECORD), souhaits: 'ambiance' }, 'a1')).toBeNull();
  });

  it('un brouillon de création se restaure sur le formulaire de création', () => {
    const draft = { ...toDraft(null), id: 'a9', prenom: 'Jo' };

    expect(readAnimateurDraft(draft, null)).toEqual(draft);
  });
});
