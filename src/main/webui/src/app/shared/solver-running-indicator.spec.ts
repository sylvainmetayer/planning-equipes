// The solver indicator borrows the deployment's mascot, and must not borrow
// anyone else's. The interesting case is the unconfigured one: an instance
// with no mascot still has to say "a solve is running", which is what the
// Material fallback is for.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { BRANDING, BRANDING_NEUTRE } from '../core/branding';
import { Branding } from '../core/models';
import { SolverJobService } from '../core/solver-job.service';
import { SolverRunningIndicator } from './solver-running-indicator';

function rendre(branding: Partial<Branding>) {
  const jobs = {
    activeJob: signal({ id: 'j1' }),
    file: signal([]),
    activeJobDescription: signal('Résolution en cours'),
  };
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: SolverJobService, useValue: jobs },
      { provide: BRANDING, useValue: { ...BRANDING_NEUTRE, ...branding } },
    ],
  });
  const fixture = TestBed.createComponent(SolverRunningIndicator);
  TestBed.tick();
  return fixture;
}

describe('SolverRunningIndicator', () => {
  it('spins the deployment mascot when one is configured', () => {
    const fixture = rendre({ mascotIconUrl: 'mascotte.png' });

    const image: HTMLImageElement | null =
      fixture.nativeElement.querySelector('img.solver-running-icon');

    expect(image?.getAttribute('src')).toBe('mascotte.png');
  });

  it('falls back to a Material icon rather than to somebody else mark', () => {
    const fixture = rendre({});

    expect(fixture.nativeElement.querySelector('img.solver-running-icon')).toBeNull();
    expect(fixture.nativeElement.querySelector('mat-icon.solver-running-fallback')).not.toBeNull();
  });
});
