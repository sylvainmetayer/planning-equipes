import { describe, expect, it } from 'vitest';
import type { ConfirmationView, DernierEnvoi, RapportRenvoi } from '../../core/models';
import { resumeRenvoi, temporaryFailures } from './renvoi-resume';

const NOMS: Record<string, string> = { a: 'Alice Martin', b: 'Bruno Petit', c: 'Chloé Durand' };
const nameOf = (id: string) => NOMS[id] ?? id;

function ligne(animateurId: string, dernierEnvoi: DernierEnvoi | null): ConfirmationView {
  return {
    animateurId,
    nomAffiche: nameOf(animateurId),
    statut: 'NON_VU',
    affecte: true,
    confirmeLe: null,
    relanceLe: null,
    dernierEnvoi,
  };
}

function echec(categorieEchec: DernierEnvoi['categorieEchec']): DernierEnvoi {
  return {
    type: 'RELANCE_NUIT',
    statut: 'ECHEC',
    categorieEchec,
    envoyeLe: '2026-07-01T20:00:00Z',
  };
}

function rapport(patch: Partial<RapportRenvoi> = {}): RapportRenvoi {
  return { renvoyes: [], echecs: [], nonRenvoyables: [], ...patch };
}

describe('temporaryFailures', () => {
  it('counts the failed sends a resend can retry, and never a refused address', () => {
    expect(
      temporaryFailures([
        ligne('a', echec('TEMPORAIRE')),
        ligne('b', echec('RELAIS_INJOIGNABLE')),
        ligne('c', echec('ADRESSE_REFUSEE')),
        ligne('d', { ...echec(null), statut: 'ENVOYE' }),
        ligne('e', null),
      ]),
    ).toBe(2);
  });

  it('counts nobody when every failure is a refused address', () => {
    expect(temporaryFailures([ligne('c', echec('ADRESSE_REFUSEE'))])).toBe(0);
  });
});

describe('resumeRenvoi', () => {
  it('counts what left again, with no details when everything did', () => {
    const resume = resumeRenvoi(rapport({ renvoyes: ['a', 'b'] }), nameOf);

    expect(resume.titre).toBe('2 envoi(s) reparti(s)');
    expect(resume.details).toBeUndefined();
    expect(resume.variant).toBe('success');
  });

  it('names who failed again and who was left alone with the reason', () => {
    const resume = resumeRenvoi(
      rapport({
        echecs: ['a'],
        nonRenvoyables: [
          { animateurId: 'b', motif: 'TYPE_NON_RENVOYABLE' },
          { animateurId: 'zz', motif: 'DEJA_CONFIRME' },
        ],
      }),
      nameOf,
    );

    expect(resume.details).toBe(
      "Nouvel échec : Alice Martin — Non renvoyés : Bruno Petit (code d'accès ou échange, à redemander), zz (déjà confirmé)",
    );
    expect(resume.variant).toBe('warning');
  });

  it('keeps the names out of the persisted journal', () => {
    const resume = resumeRenvoi(
      rapport({ echecs: ['a'], nonRenvoyables: [{ animateurId: 'b', motif: 'SANS_POSTE' }] }),
      nameOf,
    );

    expect(resume.detailsJournal).toBe('1 en échec, 1 non renvoyé(s)');
  });
});
