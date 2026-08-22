// A rendering test, against the project's taste for logic tests, because the
// defect it guards lives in the template and nowhere else: an `ngModel` inside
// a `<form>` with no `name` throws NG01352 at init, Angular Material then
// leaves the `mat-label` unrendered, and every field of the lock form loses its
// accessible name. Nothing else fails — the page still paints three boxes — so
// only the DOM tells the truth.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { VerrouillagesPage } from './verrouillages-page';

const CRENEAUX = [
  { id: 1, date: '2026-07-10', heureDebut: '10:00', heureFin: '12:00' },
  { id: 2, date: '2026-07-11', heureDebut: '10:00', heureFin: '12:00' }
];

function mount() {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      {
        provide: ReferenceDataStore,
        useValue: {
          creneaux: signal(CRENEAUX),
          animateurs: signal([]),
          stands: signal([]),
          reload: vi.fn(async () => undefined)
        }
      },
      {
        provide: VerrouillageStore,
        useValue: { verrouillages: signal([]), reload: vi.fn(async () => undefined) }
      },
      { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      { provide: NotificationService, useValue: { notify: vi.fn() } },
      { provide: ConfirmService, useValue: { ask: vi.fn(async () => true) } },
      { provide: PlanningStateService, useValue: { loadForDisplay: vi.fn(async () => null) } }
    ]
  });
  return TestBed.createComponent(VerrouillagesPage);
}

/**
 * The name a screen reader — and `getByLabel` — would announce for a control,
 * resolved through `aria-labelledby` the way Angular Material wires a
 * `mat-label` to the control of its `mat-form-field`.
 */
function accessibleName(root: HTMLElement, control: Element): string {
  // Material names a mat-select through aria-labelledby, and a plain matInput
  // through a <label for>. Both must resolve to something for the field to be
  // reachable by its label.
  const ids = control.getAttribute('aria-labelledby')?.split(/\s+/) ?? [];
  const byLabelledBy = ids
    .map((id) => root.querySelector(`[id="${id}"]`)?.textContent?.trim() ?? '')
    .join(' ')
    .trim();
  if (byLabelledBy !== '') {
    return byLabelledBy;
  }
  const id = control.getAttribute('id');
  return id ? (root.querySelector(`label[for="${id}"]`)?.textContent?.trim() ?? '') : '';
}

describe('VerrouillagesPage lock form', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('labels every field of the form, so each control can be named and aimed at', async () => {
    const fixture = mount();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    const form = root.querySelector('form.form-grid');
    expect(form).not.toBeNull();
    const controls = Array.from(form!.querySelectorAll('mat-select, input[matInput]'));
    // Type, the target field for the selected type, and the reason.
    expect(controls).toHaveLength(3);
    for (const control of controls) {
      expect(accessibleName(root, control)).not.toBe('');
    }
  });

  it('opens on the JOUR type and names its day field "Journée"', async () => {
    const fixture = mount();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    // The default type drives which target field is rendered; the e2e suite
    // reaches this very select by its label.
    const selects = Array.from(root.querySelectorAll('form.form-grid mat-select'));
    const names = selects.map((select) => accessibleName(root, select));
    expect(names).toEqual(['Type', 'Journée']);
  });

  it('binds each control to a named form control, which is what makes the label render', async () => {
    const fixture = mount();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    // The root cause guard: NG01352 is thrown per unnamed ngModel, and it is
    // that throw — not a styling choice — that swallows the labels above.
    const controls = Array.from(
      root.querySelectorAll('form.form-grid mat-select, form.form-grid input[matInput]')
    );
    for (const control of controls) {
      expect(control.getAttribute('name')).toBeTruthy();
    }
  });
});
