import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { MentionsLegales } from '../../core/models';
import { ConditionsUtilisationPage } from './conditions-utilisation-page';

const VIDE: MentionsLegales = {
  editeur: '',
  directeurPublication: '',
  hebergeur: '',
  contact: '',
  responsableTraitement: '',
  baseLegale: '',
  conservation: '',
  mesureAudience: false,
  suiviErreurs: false,
};

function monter() {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: ApiService, useValue: { get: vi.fn(async () => VIDE) } },
    ],
  });
  return TestBed.createComponent(ConditionsUtilisationPage);
}

describe('ConditionsUtilisationPage', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('trace la ligne : l’outil calcule, l’organisation décide', async () => {
    const fixture = monter();
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('aide à la décision');
    expect(text).toContain('ni certification de conformité');
  });

  it('dit que l’organisateur reste l’employeur et le responsable du respect de la réglementation', async () => {
    const fixture = monter();
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('employeur ou le donneur d');
    expect(text).toContain('droit du travail');
    expect(text).toContain('travail des mineurs');
    expect(text).toContain('responsable des données');
  });

  it('limite la responsabilité sans prétendre écarter ce que la loi protège', async () => {
    // Une clause qui exclurait tout, y compris la faute lourde ou les droits
    // des personnes, serait réputée non écrite : la réserve fait partie de la
    // clause, pas de la décoration.
    const fixture = monter();
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain("en l'état");
    expect(text).toContain('faute lourde');
    expect(text).toContain('dommages corporels');
  });
});
