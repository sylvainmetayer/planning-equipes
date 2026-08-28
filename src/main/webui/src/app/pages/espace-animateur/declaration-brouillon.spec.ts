import { describe, expect, it } from 'vitest';
import { DeclarationEspaceView, DeclarationView } from '../../core/models';
import {
  basculer,
  brouillonInitial,
  declarationModifiee,
  moisDeCollecte,
  versNouvelleDeclaration
} from './declaration-brouillon';

function vue(overrides: Partial<DeclarationEspaceView> = {}): DeclarationEspaceView {
  return {
    collecteOuverte: true,
    collecteDebut: null,
    collecteFin: null,
    joursEvenement: ['2026-07-10', '2026-07-11'],
    typologies: [{ id: 'T1', label: 'Jeux de plateau' }],
    joursActuels: [],
    souhaitsActuels: [],
    enAttente: null,
    historique: [],
    ...overrides
  };
}

function enAttente(overrides: Partial<DeclarationView> = {}): DeclarationView {
  return {
    id: 'D1',
    statut: 'EN_ATTENTE',
    joursIndisponibles: ['2026-07-10'],
    souhaits: ['T1'],
    souhaitsLabels: ['Jeux de plateau'],
    commentaire: null,
    commentaireAdmin: null,
    creeLe: '2026-06-01T10:00:00Z',
    decideLe: null,
    ...overrides
  };
}

describe('brouillonInitial', () => {
  it('opens on the pending proposal so a correction is not retyped', () => {
    const brouillon = brouillonInitial(vue({ enAttente: enAttente({ commentaire: 'je pars tôt' }) }));

    expect(brouillon.joursIndisponibles).toEqual(['2026-07-10']);
    expect(brouillon.souhaits).toEqual(['T1']);
    expect(brouillon.commentaire).toBe('je pars tôt');
  });

  it('otherwise opens on what the organisation currently holds', () => {
    // Une page blanche voudrait dire « je suis disponible tous les jours »,
    // ce que personne n'a voulu déclarer.
    const brouillon = brouillonInitial(vue({ joursActuels: ['2026-07-11'], souhaitsActuels: ['T1'] }));

    expect(brouillon.joursIndisponibles).toEqual(['2026-07-11']);
    expect(brouillon.souhaits).toEqual(['T1']);
    expect(brouillon.commentaire).toBe('');
  });

  it('never shares the arrays it opened on', () => {
    const source = vue({ joursActuels: ['2026-07-11'] });
    brouillonInitial(source).joursIndisponibles.push('2026-07-10');

    expect(source.joursActuels).toEqual(['2026-07-11']);
  });

  it('stays empty without a view', () => {
    expect(brouillonInitial(null)).toEqual({ joursIndisponibles: [], souhaits: [], commentaire: '' });
  });
});

describe('moisDeCollecte', () => {
  it('groups the event days by calendar month, in order', () => {
    const mois = moisDeCollecte(['2026-08-01', '2026-07-11', '2026-07-10']);

    expect(mois.map((bloc) => bloc.jours)).toEqual([['2026-07-10', '2026-07-11'], ['2026-08-01']]);
  });

  it('has nothing to show before the grid of créneaux exists', () => {
    expect(moisDeCollecte([])).toEqual([]);
  });
});

describe('basculer', () => {
  it('adds a value and keeps the list sorted', () => {
    expect(basculer(['2026-07-11'], '2026-07-10')).toEqual(['2026-07-10', '2026-07-11']);
  });

  it('removes a value already there', () => {
    expect(basculer(['2026-07-10', '2026-07-11'], '2026-07-10')).toEqual(['2026-07-11']);
  });
});

describe('declarationModifiee', () => {
  it('is false while the draft still says what is pending', () => {
    const source = vue({ enAttente: enAttente() });

    expect(declarationModifiee(source, brouillonInitial(source))).toBe(false);
  });

  it('is false while the draft still says what the fiche says', () => {
    const source = vue({ joursActuels: ['2026-07-10'], souhaitsActuels: ['T1'] });

    expect(declarationModifiee(source, brouillonInitial(source))).toBe(false);
  });

  it('ignores the order the days were ticked in', () => {
    const source = vue({ enAttente: enAttente({ joursIndisponibles: ['2026-07-10', '2026-07-11'] }) });

    expect(
      declarationModifiee(source, {
        joursIndisponibles: ['2026-07-11', '2026-07-10'],
        souhaits: ['T1'],
        commentaire: ''
      })
    ).toBe(false);
  });

  it('sees a day added, a wish dropped and a comment written', () => {
    const source = vue({ enAttente: enAttente() });

    expect(
      declarationModifiee(source, {
        joursIndisponibles: ['2026-07-10', '2026-07-11'],
        souhaits: ['T1'],
        commentaire: ''
      })
    ).toBe(true);
    expect(
      declarationModifiee(source, { joursIndisponibles: ['2026-07-10'], souhaits: [], commentaire: '' })
    ).toBe(true);
    expect(
      declarationModifiee(source, {
        joursIndisponibles: ['2026-07-10'],
        souhaits: ['T1'],
        commentaire: 'je pars tôt'
      })
    ).toBe(true);
  });

  it('sees « je suis disponible tous les jours » as a change worth sending', () => {
    // Retirer sa seule indisponibilité est une déclaration, pas un retour à
    // l'état initial : le bouton doit rester actif.
    const source = vue({ joursActuels: ['2026-07-10'] });

    expect(
      declarationModifiee(source, { joursIndisponibles: [], souhaits: [], commentaire: '' })
    ).toBe(true);
  });
});

describe('versNouvelleDeclaration', () => {
  it('sorts what leaves and drops a blank comment', () => {
    expect(
      versNouvelleDeclaration({
        joursIndisponibles: ['2026-07-11', '2026-07-10'],
        souhaits: ['T2', 'T1'],
        commentaire: '   '
      })
    ).toEqual({
      joursIndisponibles: ['2026-07-10', '2026-07-11'],
      souhaits: ['T1', 'T2'],
      commentaire: null
    });
  });

  it('trims a comment that says something', () => {
    expect(
      versNouvelleDeclaration({ joursIndisponibles: [], souhaits: [], commentaire: '  je pars tôt ' })
        .commentaire
    ).toBe('je pars tôt');
  });
});
