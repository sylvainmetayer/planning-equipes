// The single feed: what it gathers, how it is ordered, how it is narrowed.

import { describe, expect, it } from 'vitest';
import { AlerteView, CoherenceReport, ConstraintView } from '../../core/models';
import { AppNotification } from '../../core/notification.service';
import { buildFil, filterFeed, filType, graviteLabel } from './fil';

const alerte = (partial: Partial<AlerteView>): AlerteView => ({
  type: 'RAPPEL_VEILLE_INJOIGNABLE',
  cle: 'A1',
  declencheLe: '2026-07-10T18:00:00Z',
  libelle: 'Rappel impossible',
  severite: 'WARNING',
  animateurId: 'A1',
  nomAffiche: null,
  lien: null,
  ...partial,
});

const message = (partial: Partial<AppNotification>): AppNotification => ({
  id: 'm1',
  severity: 'info',
  title: 'Import terminé',
  message: '',
  timestamp: Date.parse('2026-07-11T08:00:00Z'),
  read: false,
  ...partial,
});

const rapport = (gravite: 'BLOQUANT' | 'A_VERIFIER' | 'INFORMATION'): CoherenceReport => ({
  bloquants: 0,
  aVerifier: 0,
  informations: 0,
  familles: [],
  anomalies: [
    {
      famille: 'ANIMATEURS',
      gravite,
      code: 'MINEUR_PENDANT_EVENEMENT',
      message: "L'animateur a-12 sera mineur pendant l'événement.",
      objet: 'ANIMATEUR',
      objetId: 'a-12',
      date: null,
    },
  ],
});

describe('buildFil', () => {
  it('orders by gravity, then newest first, the undated last', () => {
    const items = buildFil(
      [
        alerte({ cle: 'vieille', declencheLe: '2026-07-01T00:00:00Z' }),
        alerte({ cle: 'recente', declencheLe: '2026-07-09T00:00:00Z' }),
        alerte({ cle: 'dure', severite: 'ALERTE', declencheLe: '2026-06-01T00:00:00Z' }),
      ],
      rapport('A_VERIFIER'),
      [message({ severity: 'alert', title: 'Résolution impossible' })],
    );
    expect(items.map((item) => `${item.gravite}:${item.id}`)).toEqual([
      'blocage:alertes|RAPPEL_VEILLE_INJOIGNABLE|dure',
      'avertissement:alertes|RAPPEL_VEILLE_INJOIGNABLE|recente',
      'avertissement:alertes|RAPPEL_VEILLE_INJOIGNABLE|vieille',
      'avertissement:coherence|0',
      'message:messages|m1',
    ]);
  });

  it('maps the three gravities of an anomaly, and keeps the local history a message', () => {
    expect(buildFil([], rapport('BLOQUANT'), [])[0].gravite).toBe('blocage');
    expect(buildFil([], rapport('INFORMATION'), [])[0].gravite).toBe('message');
    // Only what the application recorded is a « Message »; the same gravity on an anomaly says what it is.
    expect(graviteLabel('message', 'messages')).toBe('Message');
    expect(graviteLabel('message', 'coherence')).toBe('Information');
    const [local] = buildFil([], null, [message({ severity: 'alert' })]);
    expect(local).toMatchObject({ gravite: 'message', variante: 'alert', type: 'messages' });
  });

  /** A person is named as the screen already names them: the resolved name, else the id. */
  it('names a person by the resolved name, or by the id', () => {
    const [nomme, anonyme, sansPersonne] = buildFil(
      [
        alerte({ cle: '1', nomAffiche: 'Camille Dupont', declencheLe: '2026-07-03T00:00:00Z' }),
        alerte({ cle: '2', declencheLe: '2026-07-02T00:00:00Z' }),
        alerte({ cle: '3', animateurId: null, declencheLe: '2026-07-01T00:00:00Z' }),
      ],
      null,
      [],
    );
    expect(nomme.titre).toBe('Camille Dupont');
    expect(anonyme.titre).toBe('A1');
    expect(sansPersonne.titre).toBeNull();
  });

  it("keeps a weather alert's address and an anomaly's screen", () => {
    const lien = '/regles?onglet=consignes&date=2026-07-15&nouvelle=1&prereglage=P1';
    const [meteo, anomalie] = buildFil(
      [alerte({ type: 'METEO_ALERTE', lien, animateurId: null })],
      rapport('A_VERIFIER'),
      [],
    );
    expect(meteo.href).toBe(lien);
    expect(anomalie.lien).toMatchObject({ route: '/animateurs', queryParams: { edit: 'a-12' } });
  });
});

describe('the hard breaches of the last solve', () => {
  const regle = (partial: Partial<ConstraintView>) =>
    ({ name: 'r', niveau: 'HARD', matchCount: 1, ...partial }) as ConstraintView;

  it('keeps only the hard rules in default, as blocking items linking to Problèmes', () => {
    const items = buildFil(
      [],
      null,
      [],
      [
        regle({ name: 'dure', libelleCourt: 'Dure' }),
        regle({ name: 'zero', matchCount: 0 }),
        regle({ name: 'molle', niveau: 'MEDIUM' }),
      ],
    );
    expect(items.map((item) => item.id)).toEqual(['blocages|dure']);
    expect(items[0]).toMatchObject({ type: 'blocages', gravite: 'blocage', titre: 'Dure' });
    expect(items[0].lien).toMatchObject({
      route: '/diagnostic',
      queryParams: { onglet: 'problemes' },
    });
  });

  it('is a type of the address', () => {
    expect(filType('blocages')).toBe('blocages');
  });
});

describe('the type filter', () => {
  const items = buildFil([alerte({})], rapport('BLOQUANT'), [message({})]);

  it('keeps one type, or everything when none is asked', () => {
    expect(filterFeed(items, 'coherence').map((item) => item.type)).toEqual(['coherence']);
    expect(filterFeed(items, null)).toHaveLength(3);
  });

  it('reads a known type from the address and ignores any other value', () => {
    expect(filType('alertes')).toBe('alertes');
    expect(filType('n-importe-quoi')).toBeNull();
    expect(filType(null)).toBeNull();
  });
});
