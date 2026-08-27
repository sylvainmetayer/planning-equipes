// Two halves. The first one is a logic test of the alert badges (the component
// is created, never rendered). The second one renders the page, because the
// claims that matter to a user live in the table and nowhere else: that "tout
// sélectionner" ticks the *displayed* rows and not the referential — the
// difference between deleting four animateurs and deleting a hundred and
// fifty — that the solver lock really greys the writing actions out, and that
// an empty table says why it is empty.

import { provideZonelessChangeDetection, signal, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { DetailDialog } from '../../shared/detail-dialog';
import { AnimateurFormDialog } from './animateur-form-dialog';
import { AnimateursPage } from './animateurs-page';
import type { Animateur, CauseInfaisabilite, FeasibilityReport } from '../../core/models';

function animateur(id: string, joursIndisponibles: string[]): Animateur {
  return {
    id,
    prenom: id,
    nom: id.toUpperCase(),
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles
  };
}

function cause(severite: CauseInfaisabilite['severite'], date: string): CauseInfaisabilite {
  return {
    type: 'CRENEAU_SOUS_EFFECTIF',
    severite,
    message: `Manque d'animateurs le ${date}.`,
    creneauId: '12',
    date,
    heureDebut: '12:30',
    heureFin: '15:30',
    standIds: ['tir'],
    contrainteIds: [],
    demande: 6,
    capacite: 4,
    manque: 2
  };
}

function report(causes: CauseInfaisabilite[]): FeasibilityReport {
  return {
    feasible: false,
    manqueAnimateurs: 2,
    causes,
    totalCauses: causes.length,
    message: 'Planning non réalisable en l’état.'
  };
}

/** Reaches the protected computed the template binds to. */
type PageInternals = { alerteParAnimateurId: Signal<Map<string, string>> };

describe('AnimateursPage alert badges', () => {
  let referenceData: ReferenceDataStore;
  let problemes: ProblemesStore;
  const api = { get: vi.fn(async () => report([])) };

  beforeEach(() => {
    api.get.mockReset();
    api.get.mockResolvedValue(report([]));
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        { provide: ApiService, useValue: api },
        { provide: ReferenceCrudService, useValue: { reload: vi.fn(async () => undefined) } },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked: () => false } },
        { provide: MatDialog, useValue: { open: vi.fn() } }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
    problemes = TestBed.inject(ProblemesStore);
  });

  function createPage(): PageInternals {
    return TestBed.createComponent(AnimateursPage).componentInstance as unknown as PageInternals;
  }

  it('flags nobody while no diagnostic is loaded', () => {
    referenceData.animateurs.set([animateur('alice', ['2026-08-01'])]);
    expect(createPage().alerteParAnimateurId().size).toBe(0);
  });

  it('flags an animateur unavailable on a day carrying a CRITIQUE cause', async () => {
    referenceData.animateurs.set([animateur('alice', ['2026-08-01']), animateur('bob', ['2026-08-05'])]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await problemes.reloadFeasibility();

    expect([...page.alerteParAnimateurId().keys()]).toEqual(['alice']);
    expect(page.alerteParAnimateurId().get('alice')).toContain('2026-08-01');
    expect(page.alerteParAnimateurId().get('alice')).toContain("Manque d'animateurs");
  });

  it('ignores a day that is only ELEVE', async () => {
    referenceData.animateurs.set([animateur('alice', ['2026-08-01'])]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('ELEVE', '2026-08-01')]));
    await problemes.reloadFeasibility();

    expect(page.alerteParAnimateurId().size).toBe(0);
  });

  it('ignores an animateur available on every critical day', async () => {
    referenceData.animateurs.set([animateur('alice', [])]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await problemes.reloadFeasibility();

    expect(page.alerteParAnimateurId().size).toBe(0);
  });

  it('recomputes when the roster changes', async () => {
    referenceData.animateurs.set([]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await problemes.reloadFeasibility();
    expect(page.alerteParAnimateurId().size).toBe(0);

    referenceData.animateurs.set([animateur('carole', ['2026-08-01'])]);
    expect(page.alerteParAnimateurId().has('carole')).toBe(true);
  });
});

describe('AnimateursPage table', () => {
  let referenceData: ReferenceDataStore;
  let fixture: ComponentFixture<AnimateursPage>;
  let dialog: { open: ReturnType<typeof vi.fn> };
  let confirm: { ask: ReturnType<typeof vi.fn> };
  let notify: ReturnType<typeof vi.fn>;
  let api: { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn> };
  const editingLocked = signal(false);

  function personne(id: string, overrides: Partial<Animateur> = {}): Animateur {
    return { ...animateur(id, []), ...overrides };
  }

  async function rendre(animateurs: Animateur[]): Promise<void> {
    referenceData.animateurs.set(animateurs);
    fixture = TestBed.createComponent(AnimateursPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  /** Text of every body row, cell by cell. */
  function lignes(): string[][] {
    return Array.from(racine().querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent!.trim())
    );
  }

  /** Action buttons of one row, by their accessible name. */
  function action(indexLigne: number, nom: string): HTMLButtonElement {
    const boutons = Array.from(
      racine().querySelectorAll('tbody tr')[indexLigne].querySelectorAll('.row-actions button')
    );
    const bouton = boutons.find((each) => each.getAttribute('aria-label') === nom);
    expect(bouton, `action « ${nom} » absente`).toBeDefined();
    return bouton as HTMLButtonElement;
  }

  async function filtrer(texte: string): Promise<void> {
    const input = racine().querySelector('app-table-filter input') as HTMLInputElement;
    input.value = texte;
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    editingLocked.set(false);
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
    confirm = { ask: vi.fn(async () => false) };
    notify = vi.fn();
    // The referential endpoints answer a list; only /api/feasibility answers a report.
    api = {
      get: vi.fn(async (url: string) => (url.includes('feasibility') ? report([]) : [])),
      post: vi.fn(async () => undefined)
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        { provide: ApiService, useValue: api },
        { provide: ReferenceCrudService, useValue: { reload: vi.fn(async () => undefined), remove: vi.fn(async () => true), removeMany: vi.fn(async () => 0) } },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked } },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: { notify } }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  it('renders one row per animateur, with the derived majority and the appreciation summary', async () => {
    await rendre([
      personne('alice', { prenom: 'Amélie', nom: 'Nothomb', dateNaissance: '1990-05-04', manager: true, competences: { ambiance: 'REFERENT' } }),
      personne('bob', { prenom: 'Bob', nom: 'Ados', dateNaissance: '2015-01-01' })
    ]);

    expect(racine().querySelector('h1')!.textContent!).toContain('Animateurs (2)');
    // Majority is derived from the birth date, never stored.
    expect(lignes()[0].slice(1, 6)).toEqual(['alice', 'Amélie Nothomb', 'Oui', 'Oui', 'ambiance: REFERENT']);
    expect(lignes()[1].slice(1, 6)).toEqual(['bob', 'Bob Ados', 'Non', 'Non', '—']);
  });

  it('says the majority is unknown rather than guessing it without a birth date', async () => {
    await rendre([personne('alice', { dateNaissance: null })]);

    expect(lignes()[0][3]).toBe('—');
  });

  it('sorts on the majority column, both ways', async () => {
    await rendre([
      personne('mineur', { dateNaissance: '2015-01-01' }),
      personne('inconnu', { dateNaissance: null }),
      personne('majeur', { dateNaissance: '1990-01-01' })
    ]);

    const entete = racine().querySelector('th.mat-sort-header, th[mat-sort-header]') as HTMLElement;
    (entete.querySelector('.mat-sort-header-container') as HTMLElement).click();
    await fixture.whenStable();
    expect(lignes().map((row) => row[1])).toEqual(['majeur', 'mineur', 'inconnu']);

    (entete.querySelector('.mat-sort-header-container') as HTMLElement).click();
    await fixture.whenStable();
    expect(lignes().map((row) => row[1])).toEqual(['inconnu', 'mineur', 'majeur']);
  });

  it('narrows the table on the quick filter, and says when nothing matches', async () => {
    await rendre([personne('alice', { prenom: 'Amélie', nom: 'Nothomb' }), personne('bob', { prenom: 'Bob', nom: 'Ados' })]);

    await filtrer('nothomb');
    expect(lignes().map((row) => row[1])).toEqual(['alice']);

    await filtrer('zzz');
    expect(lignes()).toEqual([]);
    expect(racine().querySelector('.empty-hint')!.textContent!.trim()).toBe('Aucune ligne ne correspond au filtre.');
  });

  it('says the referential is empty, not that the filter matched nothing', async () => {
    await rendre([]);

    expect(racine().querySelector('.empty-hint')!.textContent!.trim()).toBe('Aucun animateur pour le moment.');
  });

  it('ticks only the displayed rows on "tout sélectionner", and warns that the scope is filtered', async () => {
    await rendre([personne('alice', { nom: 'Nothomb' }), personne('bob', { nom: 'Ados' })]);

    await filtrer('nothomb');
    (racine().querySelector('thead mat-checkbox input') as HTMLInputElement).click();
    await fixture.whenStable();

    // The whole point of the warning: this is one click away from a bulk delete.
    expect(racine().querySelector('app-bulk-actions-bar .bulk-bar-count')!.textContent!.trim()).toBe(
      '1 élément(s) sélectionné(s)'
    );
    expect(racine().querySelector('.bulk-bar-scope')).not.toBeNull();
  });

  it('shows no bulk bar until something is ticked', async () => {
    await rendre([personne('alice')]);

    expect(racine().querySelector('app-bulk-actions-bar')).toBeNull();

    (racine().querySelector('tbody mat-checkbox input') as HTMLInputElement).click();
    await fixture.whenStable();
    expect(racine().querySelector('app-bulk-actions-bar')).not.toBeNull();
  });

  it('flags an unavailability falling on a structurally understaffed day, for a screen reader too', async () => {
    await rendre([personne('alice', { joursIndisponibles: ['2026-08-01'] })]);
    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await TestBed.inject(ProblemesStore).reloadFeasibility();
    await fixture.whenStable();

    const icone = racine().querySelector('.row-problem-icon')!;
    expect(icone.getAttribute('aria-label')).toContain('2026-08-01');
  });

  it('offers the espace link only to the animateurs who have one', async () => {
    await rendre([personne('alice', { accessToken: 'jeton-1' }), personne('bob')]);

    expect(action(0, "Copier le lien de son espace animateur").disabled).toBe(false);
    expect(action(1, "Copier le lien de son espace animateur").disabled).toBe(true);
  });

  it('greys out every writing action while a solve is running, but not the read-only ones', async () => {
    await rendre([personne('alice', { accessToken: 'jeton-1' })]);
    editingLocked.set(true);
    await fixture.whenStable();

    expect(action(0, 'Modifier').disabled).toBe(true);
    expect(action(0, 'Supprimer').disabled).toBe(true);
    expect(action(0, 'Régénérer le lien de son espace').disabled).toBe(true);
    expect((racine().querySelector('mat-card-actions button') as HTMLButtonElement).disabled).toBe(true);
    // Reading a row and copying a link change nothing: locking them would only
    // punish the user for the solver's duration.
    expect(action(0, 'Consulter le détail').disabled).toBe(false);
    expect(action(0, "Copier le lien de son espace animateur").disabled).toBe(false);
  });

  it('opens the read-only detail, and hands over to the form when the user asks to edit', async () => {
    await rendre([personne('alice', { prenom: 'Amélie', nom: 'Nothomb' })]);
    dialog.open.mockReturnValue({ afterClosed: () => of('edit') });

    action(0, 'Consulter le détail').click();
    await fixture.whenStable();

    expect(dialog.open.mock.calls[0][0]).toBe(DetailDialog);
    expect(dialog.open.mock.calls[0][1].data.title).toBe('Amélie Nothomb');
    expect(dialog.open.mock.calls[1][0]).toBe(AnimateurFormDialog);
    expect(dialog.open.mock.calls[1][1].data.animateur.id).toBe('alice');
  });

  it('regenerates an espace token only after an explicit confirmation', async () => {
    await rendre([personne('alice', { accessToken: 'jeton-1' })]);

    action(0, 'Régénérer le lien de son espace').click();
    await fixture.whenStable();
    // Refused: the already-printed PDFs must keep working.
    expect(api.post).not.toHaveBeenCalled();

    confirm.ask.mockResolvedValue(true);
    action(0, 'Régénérer le lien de son espace').click();
    await fixture.whenStable();
    expect(api.post).toHaveBeenCalledWith('/api/animateurs/alice/token', null);
    expect(notify.mock.calls.at(-1)![0].variant).toBe('success');
  });

  it('reports the failure, and the link itself, when the clipboard refuses', async () => {
    await rendre([personne('alice', { accessToken: 'jeton-1' })]);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn(async () => Promise.reject(new Error('denied'))) }
    });

    action(0, "Copier le lien de son espace animateur").click();
    await fixture.whenStable();

    // A silent failure would leave the user thinking the link is in their buffer.
    const dernier = notify.mock.calls.at(-1)![0];
    expect(dernier.variant).toBe('warning');
    expect(dernier.message).toContain('/animateur/jeton-1');
  });
});
