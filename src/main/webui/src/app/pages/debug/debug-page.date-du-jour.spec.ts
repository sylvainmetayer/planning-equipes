// The development-only "freeze today" field of the Débogage page (issue #297).
//
// Three things are worth a test here: the field only exists where the server
// says it may, it saves on change with no Validate button, and the deep link of
// the toolbar warning really lands on the control instead of on the page.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { DateMockService } from '../../core/date-mock.service';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { DebugPage } from './debug-page';

describe('DebugPage — date du jour', () => {
  let fixture: ComponentFixture<DebugPage>;
  let dates: {
    dateDuJour: ReturnType<typeof signal<string>>;
    modifiable: ReturnType<typeof signal<boolean>>;
    actif: () => boolean;
    set: ReturnType<typeof vi.fn>;
  };

  async function rendre(
    options: { modifiable?: boolean; dateDuJour?: string; focus?: string | null } = {}
  ): Promise<void> {
    const dateDuJour = signal(options.dateDuJour ?? '');
    const modifiable = signal(options.modifiable ?? true);
    dates = {
      dateDuJour,
      modifiable,
      actif: () => dateDuJour() !== '',
      set: vi.fn(async (valeur: string) => dateDuJour.set(valeur))
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: DateMockService, useValue: dates },
        {
          provide: ApiService,
          useValue: { get: vi.fn(async () => ({ adminEmail: null })), post: vi.fn() }
        },
        { provide: EditionStore, useValue: { courant: () => null } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: ConfirmationRecopie, useValue: { demander: vi.fn() } },
        { provide: InstantaneAvantAction, useValue: { proposer: vi.fn() } },
        { provide: PlanningStateService, useValue: { set: vi.fn() } },
        { provide: ReferenceDataStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: SolverSettingsService, useValue: { refresh: vi.fn(async () => undefined) } },
        { provide: ProblemesStore, useValue: { reloadFeasibility: vi.fn(async () => undefined) } },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, activeJobDescription: () => '' }
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: { get: () => options.focus ?? null } } }
        }
      ]
    });
    fixture = TestBed.createComponent(DebugPage);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function champ(): HTMLInputElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('input#date-du-jour');
  }

  beforeEach(async () => {
    await rendre();
  });

  it('offers the field, empty, when the server allows it', () => {
    expect(champ()).not.toBeNull();
    expect(champ()!.value).toBe('');
  });

  /**
   * The interface hiding the field is a courtesy; the guard is the server, which
   * refuses the write whatever the client believes. This only checks the
   * courtesy — the refusal is covered by `DateJourJResourceTest`.
   */
  it('does not offer the field at all outside development mode', async () => {
    await rendre({ modifiable: false });

    expect(champ()).toBeNull();
  });

  it('saves on change, with no Validate button', async () => {
    champ()!.value = '2026-07-08';
    champ()!.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(dates.set).toHaveBeenCalledWith('2026-07-08');
    const validate = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button')
    ).find((each) => /valider|enregistrer/i.test(each.textContent ?? ''));
    expect(validate).toBeUndefined();
  });

  it('clears the field back to the real clock', async () => {
    await rendre({ dateDuJour: '2026-07-08' });
    expect(champ()!.value).toBe('2026-07-08');

    champ()!.value = '';
    champ()!.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(dates.set).toHaveBeenCalledWith('');
  });

  it('warns, next to the field, that the date is frozen', async () => {
    await rendre({ dateDuJour: '2026-07-08' });

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Date figée');
  });

  /** A refusal belongs under the control it is about, not in a toast that scrolls away. */
  it('shows a refusal next to the field', async () => {
    dates.set = vi.fn(async () => {
      throw new Error('Figer la date du jour n’est possible qu’en mode développement');
    });

    champ()!.value = '2026-07-08';
    champ()!.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('mode développement');
  });

  /** What the toolbar warning links to: the control, focused, not just the page. */
  it('focuses the field when reached through the deep link', async () => {
    await rendre({ focus: 'date-du-jour' });

    expect(document.activeElement).toBe(champ());
  });

  it('leaves the focus alone on a plain visit', async () => {
    await rendre({ focus: null });

    expect(document.activeElement).not.toBe(champ());
  });
});
