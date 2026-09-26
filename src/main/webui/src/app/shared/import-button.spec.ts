import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ImportButton } from './import-button';
import { ImportDialog, importTitle } from './import-dialog';

describe('ImportButton', () => {
  function monter(imported: boolean) {
    const closed = new Subject<boolean | undefined>();
    const open = vi.fn(() => ({
      componentInstance: { imported: signal(imported) },
      afterClosed: () => closed.asObservable(),
    }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: MatDialog, useValue: { open } }],
    });
    const fixture = TestBed.createComponent(ImportButton);
    fixture.componentRef.setInput('card', 'animateurs');
    fixture.detectChanges();
    const emitted = vi.fn();
    fixture.componentInstance.imported.subscribe(emitted);
    return { fixture, open, closed, emitted };
  }

  /** The screen stays put: the card opens over it, never a navigation to Fichiers. */
  it("opens the referential's import card in a dialog", () => {
    const { fixture, open } = monter(false);

    (fixture.nativeElement as HTMLElement).querySelector('button')!.click();

    expect(open).toHaveBeenCalledWith(
      ImportDialog,
      expect.objectContaining({ data: { card: 'animateurs' } }),
    );
  });

  it('tells the host once a write went through, however the dialog closed', () => {
    const { fixture, closed, emitted } = monter(true);

    (fixture.nativeElement as HTMLElement).querySelector('button')!.click();
    closed.next(undefined);

    expect(emitted).toHaveBeenCalledOnce();
  });

  it('says nothing when the dialog closed without a write', () => {
    const { fixture, closed, emitted } = monter(false);

    (fixture.nativeElement as HTMLElement).querySelector('button')!.click();
    closed.next(false);

    expect(emitted).not.toHaveBeenCalled();
  });

  it('titles the dialog in the words of the screen', () => {
    expect(importTitle('stands')).toBe('Importer des stands');
    expect(importTitle('animateurs')).toBe('Importer des animateurs');
  });
});
