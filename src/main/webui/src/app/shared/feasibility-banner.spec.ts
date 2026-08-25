import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { FeasibilityBanner } from './feasibility-banner';
import type { CauseInfaisabilite, FeasibilityReport } from '../core/models';

function cause(overrides: Partial<CauseInfaisabilite> = {}): CauseInfaisabilite {
  return {
    type: 'CRENEAU_SOUS_EFFECTIF',
    severite: 'ELEVE',
    message: 'Il manque 2 animateurs le 2026-08-01.',
    creneauId: '12',
    date: '2026-08-01',
    heureDebut: '12:30',
    heureFin: '15:30',
    standIds: ['tir'],
    contrainteIds: [],
    demande: 6,
    capacite: 4,
    manque: 2,
    ...overrides
  };
}

function report(causes: CauseInfaisabilite[], overrides: Partial<FeasibilityReport> = {}): FeasibilityReport {
  return {
    feasible: false,
    manqueAnimateurs: 2,
    causes,
    totalCauses: causes.length,
    message: 'Planning non réalisable en l’état.',
    ...overrides
  };
}

describe('FeasibilityBanner', () => {
  let fixture: ComponentFixture<FeasibilityBanner>;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    fixture = TestBed.createComponent(FeasibilityBanner);
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('renders nothing on the nominal path', async () => {
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.feasibility-banner')).toBeNull();
  });

  it('stays silent for a feasible report', async () => {
    fixture.componentRef.setInput('report', report([], { feasible: true }));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.feasibility-banner')).toBeNull();
  });

  it('shows the overall message and one line per cause when infeasible', async () => {
    fixture.componentRef.setInput(
      'report',
      report([cause({ message: 'première cause' }), cause({ message: 'deuxième cause' })])
    );
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('.feasibility-banner')).not.toBeNull();
    expect(text()).toContain('Planning non réalisable');
    expect(fixture.nativeElement.querySelectorAll('li')).toHaveLength(2);
    expect(text()).toContain('première cause');
    expect(text()).toContain('deuxième cause');
  });

  it('marks a CRITIQUE cause apart from an ELEVE one', async () => {
    fixture.componentRef.setInput('report', report([cause({ severite: 'CRITIQUE' }), cause({ severite: 'ELEVE' })]));
    await fixture.whenStable();

    const severites = fixture.nativeElement.querySelectorAll('.feasibility-severite');
    expect(severites).toHaveLength(2);
    expect((severites[0] as HTMLElement).classList).toContain('feasibility-severite-critique');
    expect((severites[1] as HTMLElement).classList).not.toContain('feasibility-severite-critique');
  });

  it('lists at most five causes and announces the remaining ones', async () => {
    const causes = Array.from({ length: 8 }, (_, index) => cause({ message: `cause ${index}` }));
    fixture.componentRef.setInput('report', report(causes, { totalCauses: 23 }));
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelectorAll('li')).toHaveLength(5);
    const restantes = fixture.nativeElement.querySelector('.feasibility-restantes') as HTMLElement | null;
    expect(restantes).not.toBeNull();
    expect(restantes!.textContent).toContain('18');
  });

  it('says nothing about remaining causes when every one is listed', async () => {
    fixture.componentRef.setInput('report', report([cause()]));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.feasibility-restantes')).toBeNull();
  });

  it('falls back to the hard-score warning when the report is clean', async () => {
    fixture.componentRef.setInput('report', report([], { feasible: true }));
    fixture.componentRef.setInput('hardScore', -14);
    fixture.componentRef.setInput('hardIssues', [{ name: 'dureeHebdomadaireMax', matchCount: 3 }]);
    await fixture.whenStable();

    expect(text()).toContain('score dur');
    expect(text()).toContain('dureeHebdomadaireMax');
  });

  it('shows a single banner when the report is infeasible and the hard score negative', async () => {
    fixture.componentRef.setInput('report', report([cause()]));
    fixture.componentRef.setInput('hardScore', -14);
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelectorAll('.feasibility-banner')).toHaveLength(1);
    expect(text()).toContain('Planning non réalisable');
    expect(text()).not.toContain('score dur');
  });

  it('stays silent when the solve reached a zero hard score', async () => {
    fixture.componentRef.setInput('hardScore', 0);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.feasibility-banner')).toBeNull();
  });
});
