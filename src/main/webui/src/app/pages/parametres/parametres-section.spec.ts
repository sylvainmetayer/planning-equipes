// « Paramètres › Légal » and « Organisation »: the section's settings on one
// form. Nothing is written before « Enregistrer », and the save carries the
// `modifieLe` the section was loaded with: a section written since is a 409,
// answered by overwriting (the record re-read, no stamp), reloading or neither.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, CODE_MODIFICATION_CONCURRENTE } from '../../core/api.service';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ParametresSection, SectionReglages } from './parametres-section';

const STAMP = '2026-11-26T10:00:00Z';

const LEGAUX = {
  modifieLe: STAMP,
  dureeHebdomadaireMaxMinutes: 2880,
  dureeHebdomadaireMaxMineurMinutes: 2100,
  reposQuotidienMinimalMinutes: 660,
  dureePauseMinutes: 30,
  coupureRepasMinutes: 60,
  coupureRepasMidiDebut: '12:00:00',
  coupureRepasMidiFin: '14:00:00',
  coupureRepasSoirDebut: '19:00:00',
  coupureRepasSoirFin: '21:00:00',
  heureDebutSoiree: '20:00:00',
  dureeVacationMaxMinutes: 360,
};

describe('ParametresSection', () => {
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let confirm: { askThreeWay: ReturnType<typeof vi.fn> };
  let fixture: ComponentFixture<ParametresSection>;

  async function openSection(section: SectionReglages): Promise<void> {
    fixture = TestBed.createComponent(ParametresSection);
    fixture.componentRef.setInput('section', section);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function champ(libelle: string): HTMLInputElement {
    const nom = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.parametres-section-champ'),
    ).find((each) => each.textContent!.includes(libelle))!;
    return nom.querySelector('input')!;
  }

  beforeEach(() => {
    confirm = { askThreeWay: vi.fn() };
    api = {
      legalParameters: vi.fn(() => Promise.resolve({ ...LEGAUX })),
      saveLegalParameters: vi.fn((legaux: unknown) => Promise.resolve(legaux)),
      qualityParameters: vi.fn(() => Promise.resolve({ joursConsecutifsMax: 6, modifieLe: STAMP })),
      saveQualityParameters: vi.fn((qualite: unknown) => Promise.resolve(qualite)),
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ConstraintsApi, useValue: api },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
  });

  it('shows the legal settings and writes nothing until « Enregistrer »', async () => {
    await openSection('legal');

    expect(champ('Durée de la pause légale').value).toBe('30');
    expect(champ('Durée hebdomadaire maximale, majeurs').value).toBe('48');
    expect(fixture.componentInstance.dirty()).toBe(false);
    expect(api['saveLegalParameters']).not.toHaveBeenCalled();
  });

  function typePause(): void {
    const pause = champ('Durée de la pause légale');
    pause.value = '45';
    pause.dispatchEvent(new Event('input'));
  }

  const STALE = new ApiError(409, 'conflict', 'Modifiée.', CODE_MODIFICATION_CONCURRENTE);

  it('saves the record as loaded under its stamp, through the legal section', async () => {
    await openSection('legal');
    typePause();
    expect(fixture.componentInstance.dirty()).toBe(true);

    await fixture.componentInstance['save']();

    expect(api['saveLegalParameters']).toHaveBeenCalledExactlyOnceWith({
      ...LEGAUX,
      dureePauseMinutes: 45,
      modifieLe: STAMP,
    });
    expect(fixture.componentInstance.dirty()).toBe(false);
  });

  it('overwrites a section written since: the record re-read, sent without a stamp', async () => {
    await openSection('legal');
    typePause();
    api['saveLegalParameters'].mockRejectedValueOnce(STALE);
    confirm.askThreeWay.mockResolvedValue(true);
    api['legalParameters'].mockResolvedValue({
      ...LEGAUX,
      heureDebutSoiree: '19:00:00',
      modifieLe: '2026-11-26T11:00:00Z',
    });

    await fixture.componentInstance['save']();

    expect(api['saveLegalParameters']).toHaveBeenLastCalledWith({
      ...LEGAUX,
      heureDebutSoiree: '19:00:00',
      dureePauseMinutes: 45,
      modifieLe: null,
    });
    expect(fixture.componentInstance.dirty()).toBe(false);
  });

  it('reloads on request and drops what was typed, nothing having been written', async () => {
    await openSection('legal');
    typePause();
    api['saveLegalParameters'].mockRejectedValueOnce(STALE);
    confirm.askThreeWay.mockResolvedValue(false);
    const reads = api['legalParameters'].mock.calls.length;

    await fixture.componentInstance['save']();

    expect(api['saveLegalParameters']).toHaveBeenCalledOnce();
    expect(api['legalParameters'].mock.calls.length).toBe(reads + 1);
    expect(fixture.componentInstance.dirty()).toBe(false);
  });

  it('keeps what was typed when the dialog is dismissed, and says the refusal', async () => {
    await openSection('legal');
    typePause();
    api['saveLegalParameters'].mockRejectedValueOnce(STALE);
    confirm.askThreeWay.mockResolvedValue(null);

    await fixture.componentInstance['save']();
    fixture.detectChanges();

    expect(api['saveLegalParameters']).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.dirty()).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Modifiée.');
  });

  it('writes the organisation section through the quality thresholds', async () => {
    await openSection('organisation');
    const jours = champ("Jours travaillés d'affilée");
    jours.value = '5';
    jours.dispatchEvent(new Event('input'));

    await fixture.componentInstance['save']();

    expect(api['saveQualityParameters']).toHaveBeenCalledExactlyOnceWith({
      joursConsecutifsMax: 5,
      modifieLe: STAMP,
    });
    expect(api['saveLegalParameters']).not.toHaveBeenCalled();
  });

  it('offers the meal-break placement as a choice and saves the one picked', async () => {
    api['qualityParameters'].mockResolvedValue({
      placementRepas: 'FIN_MIDI_DEBUT_SOIR',
      modifieLe: STAMP,
    });
    await openSection('organisation');
    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      'select[data-field="placementRepas"]',
    )!;
    expect(Array.from(select.options).map((option) => option.value)).toEqual([
      'FIN_MIDI_DEBUT_SOIR',
      'DEBUT',
      'FIN',
      'AUCUN',
    ]);
    expect(select.value).toBe('FIN_MIDI_DEBUT_SOIR');

    select.value = 'AUCUN';
    select.dispatchEvent(new Event('change'));
    await fixture.componentInstance['save']();

    expect(api['saveQualityParameters']).toHaveBeenCalledExactlyOnceWith({
      placementRepas: 'AUCUN',
      modifieLe: STAMP,
    });
  });

  it('saves through « Enregistrer » without a native submit reloading the page', async () => {
    await openSection('legal');
    const pause = champ('Durée de la pause légale');
    pause.value = '45';
    pause.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const form = (fixture.nativeElement as HTMLElement).querySelector('form')!;
    const submit = new SubmitEvent('submit', { cancelable: true });

    form.dispatchEvent(submit);
    await fixture.whenStable();

    expect(submit.defaultPrevented).toBe(true);
    expect(api['saveLegalParameters']).toHaveBeenCalledOnce();
  });

  it('sends nothing for an emptied or out-of-range value, says why and focuses it', async () => {
    await openSection('legal');
    const hebdo = champ('Durée hebdomadaire maximale, majeurs');
    hebdo.value = '60';
    hebdo.dispatchEvent(new Event('input'));
    const pause = champ('Durée de la pause légale');
    pause.value = '';
    pause.dispatchEvent(new Event('input'));

    await fixture.componentInstance['save']();
    fixture.detectChanges();

    expect(api['saveLegalParameters']).not.toHaveBeenCalled();
    expect(api['legalParameters']).toHaveBeenCalledOnce();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[role="alert"]')!.textContent).toContain(
      "Rien n'a été enregistré",
    );
    // The first field of the form in error, in reading order.
    expect(document.activeElement).toBe(hebdo);
    expect(hebdo.getAttribute('aria-invalid')).toBe('true');
    expect(pause.getAttribute('aria-invalid')).toBe('true');
    expect(document.getElementById(hebdo.getAttribute('aria-describedby')!)!.textContent).toContain(
      'entre 1 et 48',
    );
  });

  it('shows the server refusal', async () => {
    await openSection('organisation');
    api['saveQualityParameters'].mockRejectedValue(new Error('Le facteur de détour est refusé.'));
    const jours = champ("Jours travaillés d'affilée");
    jours.value = '5';
    jours.dispatchEvent(new Event('input'));

    await fixture.componentInstance['save']();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')!.textContent,
    ).toContain('Le facteur de détour est refusé.');
    expect(fixture.componentInstance.dirty()).toBe(true);
  });
});
