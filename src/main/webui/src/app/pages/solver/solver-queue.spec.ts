// The queue of planned solves: what each will do, to which edition, and the
// one gesture on it — removing a job before it starts.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { JobView } from '../../core/models';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverQueue } from './solver-queue';

function job(overrides: Partial<JobView> = {}): JobView {
  return {
    id: 'j1',
    type: 'SOLVE',
    status: 'QUEUED',
    editionId: 'festival-2026',
    editionNom: 'Festival 2026',
    secondsLimit: 600,
    submittedAt: '2026-08-01T11:00:00Z',
    startedAt: null,
    finishedAt: null,
    elapsedSeconds: 0,
    error: null,
    result: null,
    ...overrides,
  };
}

describe('SolverQueue', () => {
  const file = signal<JobView[]>([]);
  const jobs = { file: () => file(), retirerDeLaFile: vi.fn() };
  let fixture: ComponentFixture<SolverQueue>;
  let failed: string[];

  beforeEach(() => {
    file.set([]);
    jobs.retirerDeLaFile.mockReset();
    jobs.retirerDeLaFile.mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: SolverJobService, useValue: jobs }],
    });
  });

  function render(queued: JobView[]): HTMLElement {
    file.set(queued);
    fixture = TestBed.createComponent(SolverQueue);
    failed = [];
    fixture.componentInstance.failed.subscribe((message) => failed.push(message));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders nothing while the queue is empty', () => {
    const root = render([]);

    expect(root.textContent!.trim()).toBe('');
  });

  it('names what each planned job will do, and on which edition', () => {
    const root = render([
      job({ id: 'a', type: 'SOLVE', reamorcage: 'AUTO' }),
      job({
        id: 'b',
        type: 'SOLVE',
        reamorcage: 'AUCUN',
        editionNom: null,
        editionId: 'brouillon',
      }),
      job({ id: 'c', type: 'SOLVE_INCREMENTAL' }),
    ]);

    expect(root.querySelector('.solver-file-titre')!.textContent).toContain("File d'attente (3)");
    const lignes = Array.from(root.querySelectorAll('.solver-file-ligne')).map((ligne) => [
      ligne.querySelector('.solver-file-tache')!.textContent!.trim(),
      ligne.querySelector('.solver-file-edition')!.textContent!.trim(),
    ]);
    expect(lignes).toEqual([
      ['Calcul du planning', 'Festival 2026'],
      ['Calcul du planning (de zéro)', 'brouillon'],
      ['Replanification incrémentale', 'Festival 2026'],
    ]);
  });

  it('removes a job from the queue by its id', async () => {
    const root = render([job({ id: 'a' }), job({ id: 'b' })]);

    (root.querySelectorAll('button')[1] as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(jobs.retirerDeLaFile).toHaveBeenCalledExactlyOnceWith('b');
    expect(failed).toEqual([]);
  });

  it('hands the refusal to the page rather than swallowing it', async () => {
    jobs.retirerDeLaFile.mockRejectedValue(new Error('Déjà démarrée.'));
    const root = render([job({ id: 'a' })]);

    (root.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(failed).toHaveLength(1);
    expect(failed[0]).toContain('Déjà démarrée.');
  });
});
