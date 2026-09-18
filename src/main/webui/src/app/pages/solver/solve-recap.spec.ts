// The recap under the launch buttons: where the last solve started from
// (issue #174), whom a publication would now disturb, what it replaced (issue
// #274) — and the way back when it made things worse. The facts come in as
// inputs; what is tested is the words, and the one action.

import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { InstantanePerimeError, PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { SolveRecap } from './solve-recap';

type RecapInternals = {
  reamorcageLabel: Signal<string>;
  impactLabel: Signal<string>;
  comparison: Signal<{ avant: string; apres: string } | null>;
  horsPlancherLabel: Signal<string>;
  restoring: Signal<boolean>;
  restore: () => Promise<void>;
};

describe('SolveRecap', () => {
  const snapshots = { restaurer: vi.fn() };
  const resolution = { reload: vi.fn() };
  const planningState = { set: vi.fn() };
  const problemes = { reload: vi.fn() };
  const confirm = { ask: vi.fn() };

  let fixture: ComponentFixture<SolveRecap>;
  let restored: string[];
  let failed: string[];

  beforeEach(() => {
    for (const stub of [
      snapshots.restaurer,
      resolution.reload,
      planningState.set,
      problemes.reload,
      confirm.ask,
    ]) {
      stub.mockReset();
    }
    resolution.reload.mockResolvedValue(undefined);
    problemes.reload.mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: PlanSnapshotStore, useValue: snapshots },
        { provide: PlanningResolutionStore, useValue: resolution },
        { provide: PlanningStateService, useValue: planningState },
        { provide: ProblemesStore, useValue: problemes },
        { provide: ConfirmService, useValue: confirm },
        { provide: SolverJobService, useValue: { editingLocked: () => false } },
      ],
    });
  });

  function createRecap(inputs: Record<string, unknown> = {}): RecapInternals {
    fixture = TestBed.createComponent(SolveRecap);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    restored = [];
    failed = [];
    fixture.componentInstance.restored.subscribe((message) => restored.push(message));
    fixture.componentInstance.failed.subscribe((message) => failed.push(message));
    fixture.detectChanges();
    return fixture.componentInstance as unknown as RecapInternals;
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  it('recaps a re-seeded solve, with the seats it had to leave free', () => {
    const recap = createRecap({
      reamorcage: { mode: 'PLAN_COURANT', postes: 12, postesLiberes: 0, postesPasses: 0 },
    });
    expect(recap.reamorcageLabel()).toBe('Point de départ : le plan enregistré, 12 postes repris.');

    fixture.componentRef.setInput('reamorcage', {
      mode: 'PLAN_COURANT',
      postes: 10,
      postesLiberes: 2,
      postesPasses: 0,
    });
    expect(recap.reamorcageLabel()).toContain('10 postes repris et 2 laissés libres');
  });

  it('names the past seats frozen as worked, only when the event is under way', () => {
    const recap = createRecap({
      reamorcage: { mode: 'PLAN_COURANT', postes: 12, postesLiberes: 0, postesPasses: 0 },
    });
    expect(recap.reamorcageLabel()).not.toContain('déjà commencés');
    fixture.componentRef.setInput('reamorcage', {
      mode: 'PLAN_COURANT',
      postes: 10,
      postesLiberes: 0,
      postesPasses: 7,
    });
    expect(recap.reamorcageLabel()).toContain(
      '7 postes déjà commencés, figés tels que travaillés.',
    );
  });

  it('recaps a cold start, and stays silent on a payload from before the feature', () => {
    const recap = createRecap({
      reamorcage: { mode: 'AUCUN', postes: 0, postesLiberes: 0, postesPasses: 0 },
    });
    expect(recap.reamorcageLabel()).toBe('Point de départ : aucun, calcul de zéro.');

    fixture.componentRef.setInput('reamorcage', null);
    expect(recap.reamorcageLabel()).toBe('');
  });

  it('says how many people the publication would inform, and nothing before any publication', () => {
    const recap = createRecap({ impact: { personnes: 12, publieLe: '2026-09-01T10:00:00Z' } });
    expect(recap.impactLabel()).toContain('12 personne(s) changeraient');

    fixture.componentRef.setInput('impact', { personnes: 0, publieLe: '2026-09-01T10:00:00Z' });
    expect(recap.impactLabel()).toContain('Personne ne change');

    fixture.componentRef.setInput('impact', null);
    expect(recap.impactLabel()).toBe('');
  });

  it('shows the run only once one has finished', () => {
    createRecap({ reamorcage: { mode: 'AUCUN', postes: 0, postesLiberes: 0, postesPasses: 0 } });
    expect(text()).not.toContain('Dernière exécution');

    fixture.componentRef.setInput('lastRunAt', '2026-08-01T12:00:00Z');
    fixture.detectChanges();
    expect(text()).toContain('Dernière exécution');
    expect(text()).toContain('Point de départ : aucun');
  });

  describe('the plan a solve replaced', () => {
    it('compares the score before and after the solve', () => {
      const recap = createRecap({
        previousPlan: { snapshotId: 12, score: '0hard/-6232medium/-920soft', degraded: true },
        score: '0hard/-7434medium/-564soft',
      });

      expect(recap.comparison()).toEqual({
        avant: '0hard/-6232medium/-920soft',
        apres: '0hard/-7434medium/-564soft',
      });
      expect(text()).toContain('Cette résolution a dégradé le plan enregistré.');
    });

    // The whole point is to be believed: flagging a solve that improved things
    // would train the user to ignore the flag.
    it('offers no way back when the solve improved the plan', () => {
      createRecap({
        previousPlan: { snapshotId: 12, score: '0hard/-9000medium/-999soft', degraded: false },
        score: '0hard/-7434medium/-564soft',
      });

      expect(text()).toContain('Avant :');
      expect(text()).not.toContain('dégradé');
      expect((fixture.nativeElement as HTMLElement).querySelector('button')).toBeNull();
    });

    // Half a comparison is worse than none: it would read as a score of zero.
    it('draws no comparison when the previous score is unknown', () => {
      const recap = createRecap({
        previousPlan: { snapshotId: 12, score: null, degraded: false },
        score: '0hard/0medium/0soft',
      });

      expect(recap.comparison()).toBeNull();
    });

    it('draws no comparison on the first solve of an edition', () => {
      const recap = createRecap({ previousPlan: null, score: '0hard/0medium/0soft' });

      expect(recap.comparison()).toBeNull();
      expect(text()).not.toContain('Avant :');
    });
  });

  // The score net of its floors (issue #495): said next to the raw one, and
  // only when a floor exists — repeating an equal figure would be noise.
  describe('the score net of its floors', () => {
    it('says what the run is worth once the constant is taken out', () => {
      const recap = createRecap({
        lastRunAt: '2026-08-01T12:00:00Z',
        score: '0hard/-6675medium/-564soft',
        scoreHorsPlancher: '0hard/-1675medium/-564soft',
      });

      expect(recap.horsPlancherLabel()).toContain('Hors plancher : 0hard/-1675medium/-564soft');
      expect(text()).toContain('Hors plancher');
    });

    it('stays silent when nothing is a floor, or when the score is unknown', () => {
      const recap = createRecap({
        lastRunAt: '2026-08-01T12:00:00Z',
        score: '0hard/-6675medium/-564soft',
        scoreHorsPlancher: '0hard/-6675medium/-564soft',
      });
      expect(recap.horsPlancherLabel()).toBe('');
      expect(text()).not.toContain('Hors plancher');

      fixture.componentRef.setInput('scoreHorsPlancher', null);
      expect(recap.horsPlancherLabel()).toBe('');
    });

    it('sits under the before/after comparison when there is one, and is said once', () => {
      createRecap({
        lastRunAt: '2026-08-01T12:00:00Z',
        previousPlan: { snapshotId: 12, score: '0hard/-7000medium/-920soft', degraded: false },
        score: '0hard/-6675medium/-564soft',
        scoreHorsPlancher: '0hard/-1675medium/-564soft',
      });

      expect(text().split('Hors plancher').length - 1).toBe(1);
    });
  });

  describe('going back to the previous plan', () => {
    const degraded = { snapshotId: 12, score: '0hard/-6232medium/-920soft', degraded: true };

    it('asks first, and does nothing when refused', async () => {
      confirm.ask.mockResolvedValue(false);
      const recap = createRecap({ previousPlan: degraded, score: '0hard/-7434medium/-564soft' });

      await recap.restore();

      expect(confirm.ask).toHaveBeenCalledWith(
        expect.objectContaining({ title: "Revenir au plan d'avant ?", danger: true }),
      );
      expect(snapshots.restaurer).not.toHaveBeenCalled();
      expect(restored).toEqual([]);
    });

    it('restores the snapshot, drops the cached planning and tells the page how many seats came back', async () => {
      confirm.ask.mockResolvedValue(true);
      snapshots.restaurer.mockResolvedValue({ affectations: 148 });
      const recap = createRecap({ previousPlan: degraded, score: '0hard/-7434medium/-564soft' });

      await recap.restore();

      expect(snapshots.restaurer).toHaveBeenCalledExactlyOnceWith(12);
      expect(resolution.reload).toHaveBeenCalledOnce();
      expect(planningState.set).toHaveBeenCalledExactlyOnceWith(null);
      expect(problemes.reload).toHaveBeenCalledOnce();
      expect(restored).toEqual([
        "148 affectation(s) restaurée(s) : le plan d'avant la résolution est de nouveau enregistré.",
      ]);
      expect(recap.restoring()).toBe(false);
    });

    it('reports a refusal and frees the button', async () => {
      confirm.ask.mockResolvedValue(true);
      snapshots.restaurer.mockRejectedValue(new Error('Instantané introuvable.'));
      const recap = createRecap({ previousPlan: degraded, score: '0hard/-7434medium/-564soft' });

      await recap.restore();

      expect(failed.at(-1)).toContain('Instantané introuvable.');
      expect(restored).toEqual([]);
      expect(recap.restoring()).toBe(false);
    });

    // Issue #170: the capture this button offers is the one taken just before
    // the solve, so any referential write since makes the server refuse it.
    // Without the second question, the one button that undoes a bad solve would
    // stop at a 409 with no way to say « quand même ».
    it('asks the staleness question before forcing a stale plan back', async () => {
      confirm.ask.mockResolvedValue(true);
      snapshots.restaurer
        .mockRejectedValueOnce(
          new InstantanePerimeError('Référentiel modifié', '2026-08-19T08:30:00Z'),
        )
        .mockResolvedValueOnce({ affectations: 148 });
      const recap = createRecap({ previousPlan: degraded, score: '0hard/-7434medium/-564soft' });

      await recap.restore();

      expect(confirm.ask).toHaveBeenCalledTimes(2);
      expect(String(confirm.ask.mock.calls[1][0].message)).toContain('référentiel');
      expect(snapshots.restaurer).toHaveBeenNthCalledWith(1, 12);
      expect(snapshots.restaurer).toHaveBeenNthCalledWith(2, 12, true);
      expect(restored).toEqual([
        "148 affectation(s) restaurée(s) : le plan d'avant la résolution est de nouveau enregistré.",
      ]);
    });

    it('writes nothing and reports nothing when the staleness question is declined', async () => {
      confirm.ask.mockResolvedValueOnce(true).mockResolvedValueOnce(false);
      snapshots.restaurer.mockRejectedValue(new InstantanePerimeError('Référentiel modifié', null));
      const recap = createRecap({ previousPlan: degraded, score: '0hard/-7434medium/-564soft' });

      await recap.restore();

      expect(snapshots.restaurer).toHaveBeenCalledExactlyOnceWith(12);
      expect(resolution.reload).not.toHaveBeenCalled();
      expect(restored).toEqual([]);
      expect(failed).toEqual([]);
      expect(recap.restoring()).toBe(false);
    });

    it('does nothing without a plan to go back to', async () => {
      const recap = createRecap({ previousPlan: null });

      await recap.restore();

      expect(confirm.ask).not.toHaveBeenCalled();
    });
  });
});
