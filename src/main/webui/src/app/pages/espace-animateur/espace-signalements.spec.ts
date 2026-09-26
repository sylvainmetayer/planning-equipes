import { describe, expect, it } from 'vitest';
import { SignalementView } from '../../core/models';
import {
  dayReported,
  motifLabel,
  objectLabel,
  reportsOfDay,
  seatReported,
  statutLabel,
} from './espace-signalements';

function signalement(partiel: Partial<SignalementView>): SignalementView {
  return {
    id: 1,
    portee: 'JOUR',
    date: '2026-07-11',
    creneauId: null,
    standId: null,
    standNom: null,
    heureDebut: null,
    heureFin: null,
    motif: null,
    statut: 'SIGNALE',
    signaleLe: '2026-07-10T08:00:00Z',
    traiteLe: null,
    ...partiel,
  };
}

describe('espace-signalements', () => {
  const poste = signalement({
    id: 2,
    portee: 'POSTE',
    creneauId: 7,
    standId: 's1',
    standNom: 'Cirque',
    heureDebut: '09:00:00',
    heureFin: '12:00:00',
  });

  it('shows the reports of a day, withdrawn ones left out', () => {
    const annule = signalement({ id: 3, statut: 'ANNULE' });
    expect(
      reportsOfDay([signalement({}), annule, poste], '2026-07-11').map((each) => each.id),
    ).toEqual([1, 2]);
    expect(reportsOfDay([signalement({})], '2026-07-12')).toEqual([]);
  });

  it('knows a day or a seat already reported, and only while open', () => {
    expect(dayReported([signalement({})], '2026-07-11')).toBe(true);
    expect(dayReported([signalement({ statut: 'CLASSE' })], '2026-07-11')).toBe(false);
    expect(seatReported([poste], 7, 's1')).toBe(true);
    expect(seatReported([poste], 7, 's2')).toBe(false);
  });

  it('words the object, the reason and the state', () => {
    expect(objectLabel(signalement({}))).toBe('Toute la journée');
    expect(objectLabel(poste)).toBe('Cirque, 09:00–12:00');
    expect(motifLabel('TRANSPORT')).toBe('Transport');
    expect(statutLabel('SIGNALE')).toContain('en attente');
    expect(statutLabel('TRAITE')).toContain('Pris en compte');
  });
});
