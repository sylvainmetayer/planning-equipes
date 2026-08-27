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
  suiviErreurs: false
};

function monter() {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: ApiService, useValue: { get: vi.fn(async () => VIDE) } }
    ]
  });
  return TestBed.createComponent(ConditionsUtilisationPage);
}

describe('ConditionsUtilisationPage', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('trace la ligne : l’outil calcule, l’organisation décide', async () => {
    const fixture = monter();
    await fixture.whenStable();

    const texte = fixture.nativeElement.textContent as string;
    expect(texte).toContain('aide à la décision');
    expect(texte).toContain("ni certification de conformité");
  });

  it('dit que l’organisateur reste l’employeur et le responsable du respect de la réglementation', async () => {
    const fixture = monter();
    await fixture.whenStable();

    const texte = fixture.nativeElement.textContent as string;
    expect(texte).toContain('employeur ou le donneur d');
    expect(texte).toContain('droit du travail');
    expect(texte).toContain('travail des mineurs');
    expect(texte).toContain('responsable des données');
  });

  it('limite la responsabilité sans prétendre écarter ce que la loi protège', async () => {
    // Une clause qui exclurait tout, y compris la faute lourde ou les droits
    // des personnes, serait réputée non écrite : la réserve fait partie de la
    // clause, pas de la décoration.
    const fixture = monter();
    await fixture.whenStable();

    const texte = fixture.nativeElement.textContent as string;
    expect(texte).toContain("en l'état");
    expect(texte).toContain('faute lourde');
    expect(texte).toContain('dommages corporels');
  });
});
