// The espace's help is a FAQ read on a phone (RGAA 9.1): each question must be
// a heading a screen reader can list, not only a button inside an accordion.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { buildEspaceAideSections } from './espace-aide-content';
import { EspaceAidePage } from './espace-aide-page';

describe('EspaceAidePage — headings', () => {
  let fixture: ComponentFixture<EspaceAidePage>;

  async function render(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: EspaceAnimateurService, useValue: { jeton: signal('jeton-1') } },
      ],
    });
    fixture = TestBed.createComponent(EspaceAidePage);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('carries one <h1> naming the page', async () => {
    const root = await render();

    const titles = root.querySelectorAll('h1');
    expect(titles).toHaveLength(1);
    expect(titles[0].textContent!.trim()).toBe('Aide');
  });

  it('makes every question a level-2 heading wrapping its header button', async () => {
    const root = await render();

    const questions = root.querySelectorAll(
      'mat-expansion-panel > h2 > mat-expansion-panel-header',
    );
    expect(questions).toHaveLength(buildEspaceAideSections().length);
    // Projected into the panel's header slot, so it still toggles the panel.
    const first = questions[0] as HTMLElement;
    expect(first.getAttribute('role')).toBe('button');
    first.click();
    await fixture.whenStable();
    expect(first.getAttribute('aria-expanded')).toBe('true');
  });
});
