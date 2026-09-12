// The découpage-settings card of the Créneaux page: filled from the server,
// summarised in one live sentence, saved on submit — and read-only once the
// grid is declared as final vacations, since nothing is left to slice.

import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CreneauxApi } from '../../core/api/creneaux-api';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ParametresDecoupageCard } from './parametres-decoupage';

const PARAMETRES = {
  dureeVacationCibleMinutes: 240,
  dureeVacationMinMinutes: 120,
  dureeVacationMaxMinutes: 480,
  dureeChevauchementMinutes: 15,
  strategieCouverturePendantPause: 'FERMETURE',
  modeGrille: 'AMPLITUDES',
  dureeDecalageMaxMinutes: 60,
};

/** The page around the card, reduced to the two inputs it drives. */
@Component({
  imports: [ParametresDecoupageCard],
  template: '<app-parametres-decoupage [inactif]="inactif()" [verrouille]="verrouille()" />',
})
class Hote {
  readonly inactif = signal(false);
  readonly verrouille = signal(false);
}

describe('ParametresDecoupageCard', () => {
  let fixture: ComponentFixture<Hote>;
  let creneauxApi: {
    slicingParameters: ReturnType<typeof vi.fn>;
    saveSlicingParameters: ReturnType<typeof vi.fn>;
  };
  let notify: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    notify = vi.fn();
    creneauxApi = {
      slicingParameters: vi.fn(async () => ({ ...PARAMETRES })),
      saveSlicingParameters: vi.fn(async (body: unknown) => body),
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: CreneauxApi, useValue: creneauxApi },
        { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
        { provide: NotificationService, useValue: { notify } },
      ],
    });
  });

  async function rendre(options: { inactif?: boolean; verrouille?: boolean } = {}): Promise<void> {
    fixture = TestBed.createComponent(Hote);
    fixture.componentInstance.inactif.set(options.inactif ?? false);
    fixture.componentInstance.verrouille.set(options.verrouille ?? false);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function champ(name: string): HTMLInputElement {
    return racine().querySelector(`input[name="${name}"]`) as HTMLInputElement;
  }

  function saisir(name: string, valeur: string): void {
    const input = champ(name);
    input.value = valeur;
    input.dispatchEvent(new Event('input'));
  }

  function bouton(): HTMLButtonElement {
    return racine().querySelector('button[type="submit"]') as HTMLButtonElement;
  }

  /** The preview sentence, rebuilt on every keystroke. */
  function apercu(): string {
    return racine()
      .querySelector('form app-status-message')!
      .textContent!.replace(/\s+/g, ' ')
      .trim();
  }

  it('fills the form from the server and summarises it in one sentence', async () => {
    await rendre();

    expect(champ('dureeVacationCibleMinutes').value).toBe('240');
    expect(apercu()).toContain("des vacations d'environ 4 h");
    expect(apercu()).toContain('jamais plus de 8 h');
  });

  it('updates the summary while the settings are typed, before anything is saved', async () => {
    await rendre();

    saisir('dureeVacationCibleMinutes', '480');
    await fixture.whenStable();

    // The preview is a computed over an immutable signal: an in-place mutation
    // would leave this sentence stale in a zoneless app.
    expect(apercu()).toContain('environ 8 h');
    expect(creneauxApi.saveSlicingParameters).not.toHaveBeenCalled();
  });

  it('saves the edited settings and says so', async () => {
    await rendre();

    saisir('dureeChevauchementMinutes', '20');
    await fixture.whenStable();
    racine().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(creneauxApi.saveSlicingParameters).toHaveBeenCalledOnce();
    const [corps] = creneauxApi.saveSlicingParameters.mock.calls[0] as unknown as [
      { dureeChevauchementMinutes: number },
    ];
    expect(corps.dureeChevauchementMinutes).toBe(20);
    expect(notify.mock.calls.at(-1)![0].variant).toBe('success');
  });

  it('shows the settings read-only on a grid of final vacations, and says why', async () => {
    await rendre({ inactif: true });

    expect(racine().textContent).toContain('vacations finales');
    expect(champ('dureeVacationCibleMinutes').disabled).toBe(true);
    expect(bouton().disabled).toBe(true);

    racine().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(creneauxApi.saveSlicingParameters).not.toHaveBeenCalled();
  });

  it('holds the save back while a solve runs on the edition, but keeps the fields editable', async () => {
    await rendre({ verrouille: true });

    expect(champ('dureeVacationCibleMinutes').disabled).toBe(false);
    expect(bouton().disabled).toBe(true);
  });
});
