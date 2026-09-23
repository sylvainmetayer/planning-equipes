// The keyboard twin of the drag (RGAA 7.3): the dialog only answers « where
// to? », and refuses to answer before a destination is chosen.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DeplacementDialog, DeplacementDialogData } from './deplacement-dialog';

describe('DeplacementDialog', () => {
  let fixture: ComponentFixture<DeplacementDialog>;
  const close = vi.fn();

  async function render(data: DeplacementDialogData): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    fixture = TestBed.createComponent(DeplacementDialog);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  type Internals = { target: { set: (id: string | null) => void }; submit: () => void };

  beforeEach(() => close.mockReset());

  it('keeps « Déplacer » disabled until a destination is chosen, then answers it', async () => {
    const root = await render({
      title: 'Déplacer Alice',
      targets: [{ id: 'P2', label: '10:00–12:00 · Dixit — siège libre' }],
      targetLabel: 'Vers quel siège ?',
    });
    const submit = root.querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(root.querySelector('h2')!.textContent).toContain('Déplacer Alice');
    expect(submit.disabled).toBe(true);

    const page = fixture.componentInstance as unknown as Internals;
    page.target.set('P2');
    await fixture.whenStable();
    expect(submit.disabled).toBe(false);
    page.submit();

    expect(close).toHaveBeenCalledWith({ source: null, target: 'P2' });
  });

  it('says so when the day offers nowhere to go', async () => {
    const root = await render({ title: 'Déplacer Alice', targets: [], targetLabel: 'Vers ?' });

    expect(root.textContent).toContain('Aucune destination possible');
  });

  it('offers the choice of the shift only when there is one to make', async () => {
    const root = await render({
      title: 'Déplacer une vacation de Bob',
      sources: [
        { id: 'p1', label: 'Tir · 10:00 – 12:00' },
        { id: 'p2', label: 'Dixit · 14:00 – 16:00' },
      ],
      targets: [{ id: 'Alice', label: 'Alice' }],
      targetLabel: 'Vers qui ?',
    });
    expect(root.querySelector('mat-select')).not.toBeNull();

    const page = fixture.componentInstance as unknown as Internals;
    page.target.set('Alice');
    page.submit();
    expect(close).toHaveBeenCalledWith({ source: 'p1', target: 'Alice' });
  });
});
