// A rendering test, against the project's taste for logic tests, because the
// defect it guards lives in the template and nowhere else: an `ngModel` inside
// a `<form>` with no `name` throws NG01352 at init, Angular Material then
// leaves the `mat-label` unrendered, and every field of the lock form loses its
// accessible name. Nothing else fails — the page still paints three boxes — so
// only the DOM tells the truth.

import { provideZonelessChangeDetection, signal, WritableSignal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { PlanningEvenement, VerrouillagePlanning } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { VerrouillagesPage } from './verrouillages-page';

const CRENEAUX = [
  { id: 1, date: '2026-07-10', heureDebut: '10:00', heureFin: '12:00' },
  { id: 2, date: '2026-07-11', heureDebut: '10:00', heureFin: '12:00' },
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
          reload: vi.fn(async () => undefined),
        },
      },
      {
        provide: VerrouillageStore,
        useValue: { verrouillages: signal([]), reload: vi.fn(async () => undefined) },
      },
      { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      { provide: NotificationService, useValue: { notify: vi.fn() } },
      { provide: ConfirmService, useValue: { ask: vi.fn(async () => true) } },
      { provide: PlanningStateService, useValue: { loadForDisplay: vi.fn(async () => null) } },
    ],
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
      root.querySelectorAll('form.form-grid mat-select, form.form-grid input[matInput]'),
    );
    for (const control of controls) {
      expect(control.getAttribute('name')).toBeTruthy();
    }
  });
});

describe('VerrouillagesPage impact and list', () => {
  let fixture: ComponentFixture<VerrouillagesPage>;
  let verrous: {
    verrouillages: WritableSignal<VerrouillagePlanning[]>;
    reload: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
  };
  let confirm: { ask: ReturnType<typeof vi.fn> };
  let notify: ReturnType<typeof vi.fn>;
  const editingLocked = signal(false);

  const ANIMATEURS = [
    { id: 'a1', prenom: 'Amélie', nom: 'Nothomb' },
    { id: 'a2', prenom: 'Marcel', nom: 'Proust' },
  ];
  const STANDS = [{ id: 's1', nom: 'Loup-Garou' }];

  /** Three staffed seats: two on day 1 (one per animateur), one on day 2. */
  function planning(): PlanningEvenement {
    const jour1 = CRENEAUX[0];
    const jour2 = CRENEAUX[1];
    return {
      postes: [
        { id: 'p1', creneau: jour1, stand: STANDS[0], animateur: ANIMATEURS[0] },
        { id: 'p2', creneau: jour1, stand: STANDS[0], animateur: ANIMATEURS[1] },
        { id: 'p3', creneau: jour2, stand: STANDS[0], animateur: ANIMATEURS[0] },
        { id: 'p4', creneau: jour2, stand: STANDS[0], animateur: null },
      ],
    } as unknown as PlanningEvenement;
  }

  async function rendre(
    evenement: PlanningEvenement | null,
    verrouillages: VerrouillagePlanning[] = [],
  ): Promise<void> {
    editingLocked.set(false);
    notify = vi.fn();
    confirm = { ask: vi.fn(async () => true) };
    verrous = {
      verrouillages: signal(verrouillages),
      reload: vi.fn(async () => undefined),
      create: vi.fn(async () => []),
      remove: vi.fn(async () => undefined),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: ReferenceDataStore,
          useValue: {
            creneaux: signal(CRENEAUX),
            animateurs: signal(ANIMATEURS),
            stands: signal(STANDS),
            reload: vi.fn(async () => undefined),
          },
        },
        { provide: VerrouillageStore, useValue: verrous },
        { provide: SolverJobService, useValue: { editingLocked } },
        { provide: NotificationService, useValue: { notify } },
        { provide: ConfirmService, useValue: confirm },
        {
          provide: PlanningStateService,
          useValue: { loadForDisplay: vi.fn(async () => evenement) },
        },
      ],
    });
    fixture = TestBed.createComponent(VerrouillagesPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  /** Picks an option of a `mat-select` the way a user does: open, then click. */
  async function choisir(name: string, libelle: string): Promise<void> {
    const select = racine().querySelector(`mat-select[name="${name}"]`) as HTMLElement;
    (select.querySelector('.mat-mdc-select-trigger') as HTMLElement).click();
    await fixture.whenStable();
    const option = Array.from(document.querySelectorAll('mat-option')).find(
      (each) => each.textContent!.replace(/\s+/g, ' ').trim() === libelle,
    );
    expect(option, `option « ${libelle} » absente`).toBeDefined();
    (option as HTMLElement).click();
    await fixture.whenStable();
  }

  function message(): string {
    return racine().querySelector('app-status-message')!.textContent!.replace(/\s+/g, ' ').trim();
  }

  function boutonVerrouiller(): HTMLButtonElement {
    return Array.from(racine().querySelectorAll('mat-card-actions button')).find((each) =>
      each.textContent!.includes('Verrouiller'),
    ) as HTMLButtonElement;
  }

  it('stays silent, and refuses to lock, until a target is named', async () => {
    await rendre(planning());

    expect(message()).toBe('');
    expect(boutonVerrouiller().disabled).toBe(true);
  });

  it('counts the already-staffed seats a day-wide lock would freeze', async () => {
    await rendre(planning());

    await choisir('jour', '2026-07-10');

    // "Je verrouille" turns into "je fige deux sièges", before the solve.
    expect(message()).toContain('2 affectation(s) déjà enregistrée(s) seront figées');
    expect(boutonVerrouiller().disabled).toBe(false);
  });

  it('counts per animateur, and ignores the empty seats', async () => {
    await rendre(planning());

    await choisir('type', 'Animateur');
    await choisir('animateurId', 'Amélie Nothomb');

    // Two staffed seats for Amélie; the unstaffed one of day 2 counts for nobody.
    expect(message()).toContain('2 affectation(s)');
  });

  it('says plainly when the target covers nothing yet, instead of showing a bare zero', async () => {
    await rendre(planning());

    await choisir('type', 'Stand');
    await choisir('standId', 'Loup-Garou');
    expect(message()).toContain('3 affectation(s)');

    await rendre({ postes: [] } as unknown as PlanningEvenement);
    await choisir('jour', '2026-07-10');
    // No plan at all: silence rather than a misleading "0 affectation".
    expect(message()).toBe('');
  });

  it('sends only the field matching the chosen type, and clears the reason on success', async () => {
    await rendre(planning());

    await choisir('type', 'Créneau');
    await choisir('creneauId', '2026-07-10 10:00–12:00');
    const raison = racine().querySelector('input[name="raison"]') as HTMLInputElement;
    raison.value = 'Équipe fixée avec le client';
    raison.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    boutonVerrouiller().click();
    await fixture.whenStable();

    expect(verrous.create).toHaveBeenCalledWith({
      type: 'CRENEAU',
      animateurId: null,
      standId: null,
      creneauId: 1,
      jour: null,
      raison: 'Équipe fixée avec le client',
    });
    // Left filled, the reason would silently ride along on the next lock.
    expect((racine().querySelector('input[name="raison"]') as HTMLInputElement).value).toBe('');
    expect(notify.mock.calls.at(-1)![0].variant).toBe('success');
  });

  // A lock is never refused over what it freezes (décision 0003): it pins the
  // seats, it does not exempt them, and the warning is what says so.
  it('shows what the lock froze that already breaks a hard rule, and saves it anyway', async () => {
    await rendre(planning());
    verrous.create.mockResolvedValue([
      {
        type: 'VERROUILLAGE_SUR_VIOLATION_DURE',
        message: 'Le verrouillage V1 fige 2 situation(s)…',
      },
    ]);

    await choisir('jour', '2026-07-10');
    boutonVerrouiller().click();
    await fixture.whenStable();

    const snack = notify.mock.calls.at(-1)![0];
    expect(snack.variant).toBe('warning');
    expect(snack.timeout).toBe(0);
    expect(snack.message).toContain('V1');
  });

  it('reports a refused lock instead of pretending it was saved', async () => {
    await rendre(planning());
    verrous.create.mockRejectedValue(new Error('déjà verrouillé'));

    await choisir('jour', '2026-07-10');
    boutonVerrouiller().click();
    await fixture.whenStable();

    const dernier = notify.mock.calls.at(-1)![0];
    expect(dernier.variant).toBe('error');
    expect(dernier.message).toContain('déjà verrouillé');
  });

  it('names the target of each lock by its label, never by a raw id', async () => {
    await rendre(planning(), [
      {
        id: 1,
        type: 'ANIMATEUR',
        animateurId: 'a1',
        standId: null,
        creneauId: null,
        jour: null,
        raison: null,
      },
      {
        id: 2,
        type: 'STAND',
        animateurId: null,
        standId: 's1',
        creneauId: null,
        jour: null,
        raison: 'Demande du client',
      },
      {
        id: 3,
        type: 'JOUR',
        animateurId: null,
        standId: null,
        creneauId: null,
        jour: '2026-07-10',
        raison: null,
      },
      {
        id: 4,
        type: 'CRENEAU',
        animateurId: null,
        standId: null,
        creneauId: 1,
        creneauDate: '2026-07-10',
        creneauHeureDebut: '10:00',
        creneauHeureFin: '12:00',
        jour: null,
        raison: null,
      },
      {
        id: 5,
        type: 'ANIMATEUR_CRENEAU',
        animateurId: 'a2',
        standId: null,
        creneauId: 1,
        creneauDate: '2026-07-10',
        creneauHeureDebut: '10:00',
        creneauHeureFin: '12:00',
        jour: null,
        raison: null,
      },
    ] as unknown as VerrouillagePlanning[]);

    const cibles = Array.from(racine().querySelectorAll('tbody tr')).map((row) =>
      row.querySelectorAll('td')[1].textContent!.trim(),
    );
    expect(cibles).toEqual([
      'Amélie Nothomb',
      'Loup-Garou',
      '2026-07-10',
      '2026-07-10 10:00–12:00',
      'Marcel Proust · 2026-07-10 10:00–12:00',
    ]);
    // The type posed by an accepted échange is named too, not left blank.
    expect(racine().querySelectorAll('tbody tr')[4].querySelector('td')!.textContent!).toContain(
      'Animateur sur un créneau',
    );
  });

  it('keeps a lock whose vacation the grid no longer holds, and says it is waiting', async () => {
    // Issue #577: the créneau was deleted and never recreated. The lock used to
    // be cascaded away without a word, taking with it the promise a validated
    // échange made; it now waits, and the row says so.
    await rendre(planning(), [
      {
        id: 9,
        type: 'CRENEAU',
        animateurId: null,
        standId: null,
        creneauId: null,
        creneauDate: '2026-07-11',
        creneauHeureDebut: '14:00',
        creneauHeureFin: '18:00',
        jour: null,
        raison: null,
        vacationMissing: true,
      },
    ] as unknown as VerrouillagePlanning[]);

    const cellules = racine().querySelectorAll('tbody tr')[0].querySelectorAll('td');
    expect(cellules[1].textContent!.trim()).toBe('2026-07-11 14:00–18:00');
    expect(cellules[2].textContent!).toContain('En attente');
  });

  it('falls back to the stored id when the target no longer exists', async () => {
    await rendre(planning(), [
      {
        id: 1,
        type: 'ANIMATEUR',
        animateurId: 'disparu',
        standId: null,
        creneauId: null,
        jour: null,
        raison: null,
      },
    ] as unknown as VerrouillagePlanning[]);

    // A lock aimed at a deleted animateur must stay visible, and removable.
    expect(
      racine().querySelectorAll('tbody tr')[0].querySelectorAll('td')[1].textContent!.trim(),
    ).toBe('disparu');
  });

  it('never unlocks without an explicit confirmation', async () => {
    await rendre(planning(), [
      {
        id: 7,
        type: 'JOUR',
        animateurId: null,
        standId: null,
        creneauId: null,
        jour: '2026-07-10',
        raison: null,
      },
    ] as unknown as VerrouillagePlanning[]);
    confirm.ask.mockResolvedValue(false);

    (racine().querySelector('tbody .row-actions button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(verrous.remove).not.toHaveBeenCalled();

    confirm.ask.mockResolvedValue(true);
    (racine().querySelector('tbody .row-actions button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(verrous.remove).toHaveBeenCalledWith(7);
  });

  it('says the planning is fully re-optimisable when no lock is posed', async () => {
    await rendre(planning());

    expect(racine().querySelector('.empty-hint')!.textContent!).toContain(
      'le solveur peut réoptimiser tout le planning',
    );
  });

  it('disables locking and unlocking while a solve is running', async () => {
    await rendre(planning(), [
      {
        id: 7,
        type: 'JOUR',
        animateurId: null,
        standId: null,
        creneauId: null,
        jour: '2026-07-10',
        raison: null,
      },
    ] as unknown as VerrouillagePlanning[]);
    await choisir('jour', '2026-07-10');
    editingLocked.set(true);
    await fixture.whenStable();

    // A lock posed mid-solve would not be taken into account by the run anyway.
    expect(boutonVerrouiller().disabled).toBe(true);
    expect(
      (racine().querySelector('tbody .row-actions button') as HTMLButtonElement).disabled,
    ).toBe(true);
  });
});
