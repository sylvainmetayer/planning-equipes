// The Planning page's consigne line offers « Poser une consigne » on a day still to
// come only: the server lays a consigne on days strictly after its today, and
// a link it would refuse is not a link.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { ConsignesStore } from '../../core/consignes.store';
import { ConsigneLigne } from './consigne-ligne';

function mount(jour: string, aujourdhui: string | null): HTMLElement {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      {
        provide: ConsignesStore,
        useValue: {
          reload: vi.fn(async () => undefined),
          etat: () => ({ consignes: [], prereglages: [], aujourdhui, indicateurs: [] }),
          aujourdhui: () => aujourdhui,
          consigneOf: () => null,
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(ConsigneLigne);
  fixture.componentRef.setInput('jour', jour);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

function links(racine: HTMLElement): string[] {
  return [...racine.querySelectorAll('a')].map((a) => a.textContent?.trim() ?? '');
}

describe('ConsigneLigne', () => {
  it('offers to lay a consigne on a day to come', () => {
    expect(links(mount('2026-07-12', '2026-07-10'))[0]).toContain('Poser une consigne');
  });

  it('does not offer it on today, on a past day, nor before today is known', () => {
    expect(links(mount('2026-07-10', '2026-07-10'))[0]).not.toContain('Poser une consigne');
    expect(links(mount('2026-07-09', '2026-07-10'))[0]).not.toContain('Poser une consigne');
    expect(links(mount('2026-07-12', null))[0]).not.toContain('Poser une consigne');
  });
});
