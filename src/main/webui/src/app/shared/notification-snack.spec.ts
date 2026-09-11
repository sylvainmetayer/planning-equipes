import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_SNACK_BAR_DATA, MatSnackBarRef } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { NotificationSnack, NotificationSnackData } from './notification-snack';

function mount(data: NotificationSnackData) {
  const dismiss = vi.fn();
  const navigate = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: MAT_SNACK_BAR_DATA, useValue: data },
      { provide: MatSnackBarRef, useValue: { dismiss } },
      { provide: Router, useValue: { navigate } },
    ],
  });
  const fixture = TestBed.createComponent(NotificationSnack);
  fixture.detectChanges();
  const buttons = Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
  );
  return { fixture, dismiss, navigate, buttons };
}

describe('NotificationSnack', () => {
  const data: NotificationSnackData = {
    message: 'Enregistrement effectué — 1 point à vérifier.',
    lien: { route: '/animateurs', queryParams: { edit: 'A1' }, libelle: 'Voir la fiche' },
  };

  it('shows the sentence, the link and « Fermer »', () => {
    const { fixture, buttons } = mount(data);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('1 point à vérifier');
    expect(buttons.map((button) => button.textContent?.trim())).toEqual([
      'Voir la fiche',
      'Fermer',
    ]);
  });

  it('follows the link and closes the bar when the link is pressed', () => {
    const { buttons, dismiss, navigate } = mount(data);

    buttons[0].click();

    expect(dismiss).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith(['/animateurs'], { queryParams: { edit: 'A1' } });
  });

  it('only closes the bar when « Fermer » is pressed', () => {
    const { buttons, dismiss, navigate } = mount(data);

    buttons[1].click();

    expect(dismiss).toHaveBeenCalledOnce();
    expect(navigate).not.toHaveBeenCalled();
  });
});
