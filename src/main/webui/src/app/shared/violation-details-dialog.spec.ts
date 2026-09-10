// The popup an admin opens from a violated hard constraint to find out *who*
// is concerned. Its whole job is to list the server's violation lines and to
// say when that list is not complete: the backend caps them
// (PlanningService.MAX_VIOLATIONS_PAR_CONTRAINTE), so a dialog showing ten
// lines for a constraint violated forty times, without saying so, lets the user
// believe they have fixed everything after ten corrections.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { ViolationDetailsData, ViolationDetailsDialog } from './violation-details-dialog';

function monter(data: ViolationDetailsData) {
  const close = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MatDialogRef, useValue: { close } },
      { provide: MAT_DIALOG_DATA, useValue: data },
    ],
  });
  const fixture = TestBed.createComponent(ViolationDetailsDialog);
  fixture.detectChanges();
  return { close, racine: fixture.nativeElement as HTMLElement };
}

const COMPLET: ViolationDetailsData = {
  constraintName: 'Repos entre deux vacations',
  description: 'Un animateur doit disposer de 11 h entre deux journées.',
  matchCount: 2,
  violations: ['Amélie Nothomb — 14/07', 'Marcel Proust — 15/07'],
};

describe('ViolationDetailsDialog', () => {
  it('names the constraint and explains it in plain language', () => {
    const { racine } = monter(COMPLET);

    expect(racine.querySelector('h2')!.textContent!.trim()).toBe('Repos entre deux vacations');
    expect(racine.querySelector('.violation-details-description')!.textContent!.trim()).toBe(
      'Un animateur doit disposer de 11 h entre deux journées.',
    );
  });

  it('lists one line per violation', () => {
    const { racine } = monter(COMPLET);

    expect(
      Array.from(racine.querySelectorAll('.violation-details-list li')).map((each) =>
        each.textContent!.trim(),
      ),
    ).toEqual(['Amélie Nothomb — 14/07', 'Marcel Proust — 15/07']);
  });

  it('stays silent about truncation when every violation is shown', () => {
    const { racine } = monter(COMPLET);

    expect(racine.querySelector('.violation-details-truncated')).toBeNull();
  });

  it('says the list is capped when the server sent fewer lines than matches', () => {
    const { racine } = monter({ ...COMPLET, matchCount: 40 });

    const note = racine
      .querySelector('.violation-details-truncated')!
      .textContent!.replace(/\s+/g, ' ')
      .trim();
    expect(note).toBe('Affichage limité aux 2 premières occurrences sur 40.');
  });

  it('renders an empty list rather than a stale one when there is nothing to show', () => {
    const { racine } = monter({ ...COMPLET, matchCount: 0, violations: [] });

    expect(racine.querySelectorAll('.violation-details-list li')).toHaveLength(0);
    expect(racine.querySelector('.violation-details-truncated')).toBeNull();
  });

  it('closes on the dismiss button', () => {
    const { racine, close } = monter(COMPLET);

    (racine.querySelector('mat-dialog-actions button') as HTMLButtonElement).click();
    expect(close).toHaveBeenCalledOnce();
  });
});
