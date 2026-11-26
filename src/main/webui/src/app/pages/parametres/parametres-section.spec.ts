// « Paramètres › Légal » and « Organisation »: the section's settings on one
// form. Nothing is written before « Enregistrer », and the save re-reads the
// record so a field edited elsewhere meanwhile is not reset.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { NotificationService } from '../../core/notification.service';
import { ParametresSection, SectionReglages } from './parametres-section';

const LEGAUX = {
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
    api = {
      legalParameters: vi.fn(() => Promise.resolve({ ...LEGAUX })),
      saveLegalParameters: vi.fn((legaux: unknown) => Promise.resolve(legaux)),
      qualityParameters: vi.fn(() => Promise.resolve({ joursConsecutifsMax: 6 })),
      saveQualityParameters: vi.fn((qualite: unknown) => Promise.resolve(qualite)),
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ConstraintsApi, useValue: api },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
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

  it('saves over the record as it is stored now, through the legal section', async () => {
    await openSection('legal');
    const pause = champ('Durée de la pause légale');
    pause.value = '45';
    pause.dispatchEvent(new Event('input'));
    expect(fixture.componentInstance.dirty()).toBe(true);
    // Edited elsewhere between the opening and the save: kept.
    api['legalParameters'].mockResolvedValue({ ...LEGAUX, heureDebutSoiree: '19:00:00' });

    await fixture.componentInstance['save']();

    expect(api['saveLegalParameters']).toHaveBeenCalledExactlyOnceWith({
      ...LEGAUX,
      heureDebutSoiree: '19:00:00',
      dureePauseMinutes: 45,
    });
    expect(fixture.componentInstance.dirty()).toBe(false);
  });

  it('writes the organisation section through the quality thresholds', async () => {
    await openSection('organisation');
    const jours = champ("Jours travaillés d'affilée");
    jours.value = '5';
    jours.dispatchEvent(new Event('input'));

    await fixture.componentInstance['save']();

    expect(api['saveQualityParameters']).toHaveBeenCalledExactlyOnceWith({
      joursConsecutifsMax: 5,
    });
    expect(api['saveLegalParameters']).not.toHaveBeenCalled();
  });
});
