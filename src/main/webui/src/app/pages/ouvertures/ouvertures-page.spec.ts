// Rendering tests of the entry view: the moves live in `grille-horaires.ts`
// and are tested there; what is checked here is the wiring — one field per
// cell, a keystroke reaching the cells, a paste landing where the focus is,
// the save sending only what changed, and the lock while a solve runs.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { RapportOuvertures } from '../../core/models';
import { OuverturesPage } from './ouvertures-page';

/** Two stands, two days of two créneaux each (10-12 and 14-20), ids 1-2 then 3-4. */
function rapport(): RapportOuvertures {
  const jour = (date: string, jour: number, ids: [number, number]) => ({
    date,
    jour,
    heureDebut: '10:00',
    heureFin: '20:00',
    minutes: 480,
    nombreCreneaux: 2,
    creneaux: [
      { id: ids[0], heureDebut: '10:00', heureFin: '12:00', famille: 0, couverturePause: false },
      { id: ids[1], heureDebut: '14:00', heureFin: '20:00', famille: 0, couverturePause: false }
    ]
  });
  const cellule = (creneauId: number, effectif: number | null, partiel = false, horsFamille = false) => ({
    creneauId,
    effectif,
    partiel,
    horsFamille
  });
  const jourStand = (date: string, creneaux: ReturnType<typeof cellule>[]) => ({
    date,
    etat: 'OUVERT_TOTAL' as const,
    source: 'REGLE' as const,
    fenetres: [{ heureDebut: '10:00', heureFin: '20:00' }],
    minutesOuvertes: 480,
    minutesAmplitude: 480,
    postes: 2,
    creneaux
  });
  return {
    jours: [jour('2026-07-08', 1, [1, 2]), jour('2026-07-09', 2, [3, 4])],
    stands: [
      {
        standId: 'A',
        nom: 'Stand A',
        effectifMin: 2,
        jours: [jourStand('2026-07-08', [cellule(1, 2), cellule(2, 4)]), jourStand('2026-07-09', [cellule(3, 2), cellule(4, 4)])],
        minutesOuvertes: 960,
        postes: 4,
        modifieLe: '2026-09-06T10:00:00Z'
      },
      {
        standId: 'B',
        nom: 'Stand B',
        effectifMin: 1,
        jours: [jourStand('2026-07-08', [cellule(1, 1, true), cellule(2, null)]), jourStand('2026-07-09', [cellule(3, null), cellule(4, null)])],
        minutesOuvertes: 120,
        postes: 1,
        modifieLe: '2026-09-06T10:00:00Z'
      }
    ],
    standsJamaisOuverts: 0,
    postesTotal: 5,
    anomalies: []
  };
}

function mount(options: { vue?: string; editingLocked?: boolean; confirme?: boolean; rapport?: RapportOuvertures } = {}) {
  const get = vi.fn(async () => options.rapport ?? rapport());
  const put = vi.fn(async () => ({ stands: [{ standId: 'B', regles: 1, exceptions: 0, effectifMin: 3, effectifMax: 3, compacte: true, raison: '' }] }));
  const ask = vi.fn(async () => options.confirme ?? true);
  const notify = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ApiService, useValue: { get, put } },
      { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
      { provide: SolverJobService, useValue: { editingLocked: signal(options.editingLocked ?? false) } },
      { provide: ConfirmService, useValue: { ask } },
      { provide: NotificationService, useValue: { notify } },
      { provide: Location, useValue: { path: () => '/ouvertures', replaceState: vi.fn() } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(options.vue ? { vue: options.vue } : {}) } } }
    ]
  });
  const fixture = TestBed.createComponent(OuverturesPage);
  return { fixture, get, put, ask, notify };
}

function root(fixture: ComponentFixture<OuverturesPage>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function champ(fixture: ComponentFixture<OuverturesPage>, standId: string, creneauId: number): HTMLInputElement {
  const input = root(fixture).querySelector<HTMLInputElement>(`[data-cellule="${standId}#${creneauId}"]`);
  expect(input, `case ${standId}#${creneauId}`).not.toBeNull();
  return input!;
}

function taper(input: HTMLInputElement, valeur: string): void {
  input.value = valeur;
  input.dispatchEvent(new Event('input'));
}

function bouton(fixture: ComponentFixture<OuverturesPage>, libelle: string): HTMLButtonElement {
  const trouve = Array.from(root(fixture).querySelectorAll('button')).find((each) => each.textContent?.includes(libelle));
  expect(trouve, `bouton « ${libelle} »`).toBeDefined();
  return trouve!;
}

describe('OuverturesPage — saisie', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => vi.restoreAllMocks());

  it('opens on the reading view, and on the entry view from ?vue=saisie', async () => {
    const lecture = mount();
    await lecture.fixture.whenStable();
    expect(root(lecture.fixture).querySelector('.grille-saisie')).toBeNull();
    expect(root(lecture.fixture).querySelector('.ouvertures-grille')).not.toBeNull();

    const saisie = mount({ vue: 'saisie' });
    await saisie.fixture.whenStable();
    expect(root(saisie.fixture).querySelector('.grille-saisie')).not.toBeNull();
  });

  it('renders one field per stand and créneau, filled from the report, partial cells marked', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    expect(root(fixture).querySelectorAll('.grille-saisie input')).toHaveLength(8);
    expect(champ(fixture, 'A', 2).value).toBe('4');
    expect(champ(fixture, 'B', 2).value).toBe('');
    expect(champ(fixture, 'B', 1).closest('td')!.classList.contains('cellule-partielle')).toBe(true);
    expect(champ(fixture, 'A', 1).closest('td')!.classList.contains('cellule-partielle')).toBe(false);
    // The créneau header row reads as hours, the day header spans its créneaux.
    const entetes = Array.from(root(fixture).querySelectorAll('.entete-creneau')).map((each) => each.textContent!.trim());
    expect(entetes).toEqual(['10-12', '14-20', '10-12', '14-20']);
    expect(root(fixture).querySelector('.entete-jour-saisie')!.getAttribute('colspan')).toBe('2');
  });

  it('shows a cell of another relay family inert: disabled, unsent, and explained', async () => {
    const rapportFamille = rapport();
    rapportFamille.stands[0].jours[0].creneaux[1].horsFamille = true;
    rapportFamille.stands[0].jours[0].creneaux[1].effectif = null;
    const { fixture, put } = mount({ vue: 'saisie', rapport: rapportFamille });
    await fixture.whenStable();

    const inerte = champ(fixture, 'A', 2);
    expect(inerte.disabled).toBe(true);
    expect(inerte.closest('td')!.classList.contains('cellule-inerte')).toBe(true);
    expect(inerte.getAttribute('title')).toContain('autre famille de relais');

    // Typing elsewhere still saves, and the inert cell is left out of the body.
    taper(champ(fixture, 'A', 1), '3');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    const [, corps] = put.mock.calls[0] as unknown as [string, { stands: { cellules: { creneauId: number }[] }[] }];
    expect(corps.stands[0].cellules.map((cellule) => cellule.creneauId)).toEqual([1, 3, 4]);
  });

  it('marks a typed cell and its row as modified, and counts the stand on the save button', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    taper(champ(fixture, 'B', 2), '3');
    await fixture.whenStable();

    expect(champ(fixture, 'B', 2).closest('td')!.classList.contains('cellule-modifiee')).toBe(true);
    expect(champ(fixture, 'B', 2).closest('tr')!.classList.contains('ligne-modifiee')).toBe(true);
    expect(champ(fixture, 'A', 2).closest('td')!.classList.contains('cellule-modifiee')).toBe(false);
    expect(bouton(fixture, 'Enregistrer').textContent).toContain('(1)');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(false);
  });

  it('moves the focus down on Enter, right on the arrow past the caret, and leaves a letter alone', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    const depart = champ(fixture, 'A', 1);
    depart.focus();
    depart.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    expect(document.activeElement).toBe(champ(fixture, 'B', 1));

    const droite = champ(fixture, 'B', 1);
    droite.setSelectionRange(droite.value.length, droite.value.length);
    droite.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
    expect(document.activeElement).toBe(champ(fixture, 'B', 2));

    const lettre = new KeyboardEvent('keydown', { key: 'a', bubbles: true, cancelable: true });
    champ(fixture, 'B', 2).dispatchEvent(lettre);
    expect(lettre.defaultPrevented).toBe(false);
  });

  it('lays a pasted block from the focused cell, and lets a single value paste as typed', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    const bloc = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    Object.defineProperty(bloc, 'clipboardData', { value: { getData: () => '5\t6\n7\t8' } });
    champ(fixture, 'A', 1).dispatchEvent(bloc);
    await fixture.whenStable();

    expect(bloc.defaultPrevented).toBe(true);
    expect([champ(fixture, 'A', 1).value, champ(fixture, 'A', 2).value]).toEqual(['5', '6']);
    expect([champ(fixture, 'B', 1).value, champ(fixture, 'B', 2).value]).toEqual(['7', '8']);

    const simple = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    Object.defineProperty(simple, 'clipboardData', { value: { getData: () => '9' } });
    champ(fixture, 'A', 1).dispatchEvent(simple);
    expect(simple.defaultPrevented).toBe(false);
  });

  it('copies a day onto the others from its header, and a row from the focused day', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    taper(champ(fixture, 'A', 3), '7');
    await fixture.whenStable();
    root(fixture).querySelectorAll<HTMLButtonElement>('.recopier-jour')[1].click();
    await fixture.whenStable();
    expect(champ(fixture, 'A', 1).value).toBe('7');
    expect(champ(fixture, 'B', 1).value).toBe('');

    champ(fixture, 'B', 4).focus();
    taper(champ(fixture, 'B', 4), '2');
    await fixture.whenStable();
    root(fixture).querySelectorAll<HTMLButtonElement>('.recopier-ligne')[1].click();
    await fixture.whenStable();
    expect(champ(fixture, 'B', 2).value).toBe('2');
    // The row copy touches its own row only.
    expect(champ(fixture, 'A', 2).value).toBe('4');
  });

  it('sends only the modified stands, each with all its cells, then reloads and reports', async () => {
    const { fixture, put, get, notify, ask } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    taper(champ(fixture, 'A', 2), '5');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    // Stand A has no partial cell: no confirmation asked.
    expect(ask).not.toHaveBeenCalled();
    expect(put).toHaveBeenCalledWith('/api/ouvertures-stands/grille', {
      stands: [
        {
          standId: 'A',
          modifieLe: '2026-09-06T10:00:00Z',
          cellules: [
            { creneauId: 1, effectif: 2 },
            { creneauId: 2, effectif: 5 },
            { creneauId: 3, effectif: 2 },
            { creneauId: 4, effectif: 4 }
          ]
        }
      ]
    });
    expect(get).toHaveBeenCalledTimes(2);
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
  });

  it('asks before flattening a stand the server reported partial, and writes nothing when refused', async () => {
    const { fixture, put, ask } = mount({ vue: 'saisie', confirme: false });
    await fixture.whenStable();

    taper(champ(fixture, 'B', 2), '3');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    expect(ask).toHaveBeenCalledOnce();
    expect((ask.mock.calls[0] as unknown as [{ message: string }])[0].message).toContain('B');
    expect(put).not.toHaveBeenCalled();
  });

  it('discards the changes on demand, and asks before leaving the entry view with some', async () => {
    const { fixture, ask } = mount({ vue: 'saisie', confirme: false });
    await fixture.whenStable();

    taper(champ(fixture, 'B', 2), '3');
    await fixture.whenStable();
    bouton(fixture, 'Annuler les modifications').click();
    await fixture.whenStable();
    expect(champ(fixture, 'B', 2).value).toBe('');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);

    taper(champ(fixture, 'B', 2), '3');
    await fixture.whenStable();
    (fixture.componentInstance as unknown as { changerVue(view: string): Promise<void> }).changerVue('CONSULTER');
    await fixture.whenStable();
    expect(ask).toHaveBeenCalledOnce();
    // Refused: still on the entry view, cells intact.
    expect(root(fixture).querySelector('.grille-saisie')).not.toBeNull();
    expect(champ(fixture, 'B', 2).value).toBe('3');
  });

  it('locks every field and the save while a solve is running, and says so', async () => {
    const { fixture } = mount({ vue: 'saisie', editingLocked: true });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect(Array.from(root(fixture).querySelectorAll<HTMLInputElement>('.grille-saisie input')).every((each) => each.disabled)).toBe(true);
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);
  });
});
