import { describe, expect, it } from 'vitest';
import { LigneEnvoi } from '../../core/models';
import {
  countByFilter,
  daysToAnnounce,
  deliveryState,
  filterEnvois,
  readFiltreEnvois,
  readJourEnvois,
  versionLabel,
} from './envois';

function ligne(partiel: Partial<LigneEnvoi>): LigneEnvoi {
  return {
    animateurId: 'a1',
    nomAffiche: 'Alice Martin',
    email: true,
    affecte: true,
    version: { snapshotId: 7, numero: 3, publieLe: '2026-09-01T08:01:00Z' },
    envoi: {
      nature: 'PUBLICATION',
      statut: 'ENVOYE',
      cause: null,
      envoyeLe: '2026-09-01T08:01:00Z',
      numero: 3,
    },
    rappelVeilleLe: null,
    rappelVeilleEchec: false,
    relanceLe: null,
    relanceEchec: false,
    confirmation: 'CONFIRME',
    confirmeLe: '2026-09-02T10:00:00Z',
    aPrevenir: false,
    differe: false,
    joursAPrevenir: [],
    ...partiel,
  };
}

describe('Diffuser table', () => {
  it('reads its view state tolerantly', () => {
    expect(readFiltreEnvois('echec')).toBe('echec');
    expect(readFiltreEnvois('nimporte')).toBe('tous');
    expect(readFiltreEnvois(null)).toBe('tous');
    expect(readJourEnvois('2026-09-06')).toBe('2026-09-06');
    expect(readJourEnvois('06/09')).toBeNull();
  });

  it('says « échec » on a failed mail, with its cause, never « envoyé »', () => {
    const echec = ligne({
      envoi: {
        nature: 'PUBLICATION',
        statut: 'ECHEC',
        cause: 'ADRESSE_REFUSEE',
        envoyeLe: '2026-09-01T08:01:00Z',
        numero: 3,
      },
    });

    const etat = deliveryState(echec, 'fr-FR');

    expect(etat.ton).toBe('echec');
    expect(etat.texte).toContain('échec');
    expect(etat.texte).toContain('adresse refusée');
  });

  it('says « sans e-mail » for a fiche without an address', () => {
    expect(deliveryState(ligne({ email: false, envoi: null }), 'fr-FR').texte).toBe('sans e-mail');
  });

  it('names the version as « v3 · 01/09 », and a dash for somebody never told', () => {
    expect(versionLabel(ligne({}), 'fr-FR')).toBe('v3 · 01/09');
    expect(versionLabel(ligne({ version: null }), 'fr-FR')).toBe('—');
  });

  it('filters and counts the same way', () => {
    const lignes = [
      ligne({ animateurId: 'ok' }),
      ligne({
        animateurId: 'ko',
        envoi: {
          nature: 'PUBLICATION',
          statut: 'ECHEC',
          cause: null,
          envoyeLe: '2026-09-01T08:01:00Z',
          numero: 3,
        },
      }),
      ligne({ animateurId: 'muet', confirmation: 'NON_VU', confirmeLe: null }),
      ligne({ animateurId: 'sans', email: false }),
      ligne({ animateurId: 'neuf', aPrevenir: true, joursAPrevenir: ['2026-09-06'] }),
      ligne({
        animateurId: 'report',
        aPrevenir: true,
        differe: true,
        joursAPrevenir: ['2026-09-07'],
      }),
    ];

    const comptes = countByFilter(lignes);

    expect(comptes).toEqual({
      tous: 6,
      'a-prevenir': 2,
      echec: 1,
      'sans-email': 1,
      silencieux: 1,
      differes: 1,
    });
    expect(filterEnvois(lignes, 'echec', null).map((each) => each.animateurId)).toEqual(['ko']);
    expect(filterEnvois(lignes, 'tous', '2026-09-06').map((each) => each.animateurId)).toEqual([
      'neuf',
    ]);
    expect(daysToAnnounce(lignes)).toEqual(['2026-09-06', '2026-09-07']);
  });
});
