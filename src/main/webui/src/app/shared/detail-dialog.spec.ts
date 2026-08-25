// The read-only "consultation" view every reference page opens, and the only
// place the user reads a row without risking a change to it.
//
// It is generic — the sections come from the caller — so what has to be proved
// here is the layout contract the four `<entity>-detail.ts` builders rely on:
// a labelled value renders as `<dt>`/`<dd>`, a `chips` row renders one chip per
// entry (never the raw array), and the "Modifier" button obeys the solver lock,
// since editing while a solve reads the data is exactly what that lock exists
// to prevent.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatTooltip } from '@angular/material/tooltip';
import { By } from '@angular/platform-browser';
import { describe, expect, it, vi } from 'vitest';
import { SolverJobService } from '../core/solver-job.service';
import { DetailData, DetailDialog } from './detail-dialog';

function monter(data: DetailData, options: { editingLocked?: boolean } = {}) {
  const close = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: SolverJobService, useValue: { editingLocked: signal(options.editingLocked ?? false) } },
      { provide: MatDialogRef, useValue: { close } },
      { provide: MAT_DIALOG_DATA, useValue: data }
    ]
  });
  const fixture = TestBed.createComponent(DetailDialog);
  fixture.detectChanges();
  return { fixture, close, racine: fixture.nativeElement as HTMLElement };
}

const DONNEES: DetailData = {
  title: 'Loup-Garou',
  subtitle: 'Stand s42',
  sections: [
    {
      title: 'Identité',
      rows: [
        { label: 'Nom', value: 'Loup-Garou' },
        { label: 'E-mail', value: 'aucun', muted: true }
      ]
    },
    {
      title: 'Appréciation',
      rows: [{ label: 'Typologies', chips: ['Ambiance', 'Expert'] }]
    }
  ]
};

/** The tooltip text bound on the edit button, shown or not. */
function infobulle(fixture: ComponentFixture<DetailDialog>): string {
  const bouton = fixture.debugElement.queryAll(By.directive(MatTooltip))[0];
  return bouton.injector.get(MatTooltip).message;
}

/** The dialog's own action buttons, in template order. */
function actions(racine: HTMLElement): HTMLButtonElement[] {
  return Array.from(racine.querySelectorAll('mat-dialog-actions button'));
}

describe('DetailDialog', () => {
  it('renders the title, the subtitle and one section per entry', () => {
    const { racine } = monter(DONNEES);

    expect(racine.querySelector('h2')!.textContent!.trim()).toBe('Loup-Garou');
    expect(racine.querySelector('.detail-subtitle')!.textContent!.trim()).toBe('Stand s42');
    expect(Array.from(racine.querySelectorAll('.detail-section-title')).map((each) => each.textContent!.trim())).toEqual(
      ['Identité', 'Appréciation']
    );
  });

  it('omits the subtitle line entirely when there is none, rather than leaving an empty paragraph', () => {
    const { racine } = monter({ title: 'Sans sous-titre', sections: [] });

    expect(racine.querySelector('.detail-subtitle')).toBeNull();
  });

  it('pairs every label with its value', () => {
    const { racine } = monter(DONNEES);

    const identite = racine.querySelector('.detail-section')!;
    expect(Array.from(identite.querySelectorAll('dt')).map((each) => each.textContent!.trim())).toEqual([
      'Nom',
      'E-mail'
    ]);
    expect(identite.querySelectorAll('dd')[0].textContent!.trim()).toBe('Loup-Garou');
  });

  it('mutes only the rows marked as hints', () => {
    const { racine } = monter(DONNEES);

    const valeurs = Array.from(racine.querySelectorAll('.detail-section')[0].querySelectorAll('dd'));
    expect(valeurs[0].classList.contains('detail-muted')).toBe(false);
    expect(valeurs[1].classList.contains('detail-muted')).toBe(true);
  });

  it('renders a chips row as one chip per entry, never as a joined string', () => {
    const { racine } = monter(DONNEES);

    const chips = Array.from(racine.querySelectorAll('mat-chip'));
    expect(chips.map((each) => each.textContent!.trim())).toEqual(['Ambiance', 'Expert']);
  });

  it('closes with "edit" when the user asks to edit, so the caller opens its own form', () => {
    const { racine, close } = monter(DONNEES);

    actions(racine)[1].click();
    expect(close).toHaveBeenCalledWith('edit');
  });

  it('disables the edit button and says why while a solve is running', () => {
    const { fixture, racine } = monter(DONNEES, { editingLocked: true });

    expect(actions(racine)[1].disabled).toBe(true);
    // A disabled button with no explanation reads as a bug; the tooltip is the
    // only thing that tells the user to wait for the solve.
    expect(infobulle(fixture)).toContain('résolution du solveur');
  });

  it('leaves the edit button available, and unexplained, when no solve is running', () => {
    const { fixture, racine } = monter(DONNEES);

    expect(actions(racine)[1].disabled).toBe(false);
    expect(infobulle(fixture)).toBe('');
  });
});
