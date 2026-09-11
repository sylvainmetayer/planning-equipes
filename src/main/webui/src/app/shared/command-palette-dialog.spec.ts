import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CommandPaletteDialog } from './command-palette-dialog';
import { ReferenceDataStore } from '../core/reference-data.store';
import { Animateur } from '../core/models';

function animateur(id: string, prenom: string, nom: string): Animateur {
  return {
    id,
    prenom,
    nom,
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
  };
}

async function monter(): Promise<{
  fixture: ComponentFixture<CommandPaletteDialog>;
  dialogRef: { close: ReturnType<typeof vi.fn> };
  store: { reload: ReturnType<typeof vi.fn> };
}> {
  const dialogRef = { close: vi.fn() };
  const store = {
    animateurs: signal<Animateur[]>([animateur('a1', 'Amélie', 'Durand')]),
    stands: signal([]),
    creneaux: signal([]),
    reload: vi.fn().mockResolvedValue(undefined),
  };
  TestBed.configureTestingModule({
    imports: [CommandPaletteDialog],
    providers: [
      provideZonelessChangeDetection(),
      { provide: MatDialogRef, useValue: dialogRef },
      { provide: ReferenceDataStore, useValue: store },
    ],
  });
  const fixture = TestBed.createComponent(CommandPaletteDialog);
  await fixture.whenStable();
  return { fixture, dialogRef, store };
}

function saisir(fixture: ComponentFixture<CommandPaletteDialog>, text: string): HTMLInputElement {
  const champ = fixture.nativeElement.querySelector('input') as HTMLInputElement;
  champ.value = text;
  champ.dispatchEvent(new Event('input'));
  return champ;
}

function options(fixture: ComponentFixture<CommandPaletteDialog>): HTMLElement[] {
  return [...fixture.nativeElement.querySelectorAll('[role="option"]')] as HTMLElement[];
}

describe('CommandPaletteDialog', () => {
  beforeEach(() => {
    // jsdom lays nothing out, so it implements no scrolling. Keeping the
    // highlighted row visible is real behaviour of the palette; only its
    // effect is untestable here.
    Element.prototype.scrollIntoView = vi.fn();
  });

  it('lists the pages of the application before anything is typed', async () => {
    const { fixture } = await monter();
    expect(options(fixture).length).toBeGreaterThan(10);
  });

  it('wires the field and the list as a combobox, so a screen reader follows the highlight', async () => {
    const { fixture } = await monter();
    const champ = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    expect(champ.getAttribute('role')).toBe('combobox');
    expect(champ.getAttribute('aria-controls')).toBe('palette-resultats');
    expect(champ.getAttribute('aria-activedescendant')).toBe('palette-option-0');
    expect(fixture.nativeElement.querySelector('[role="listbox"]')).not.toBeNull();
    expect(options(fixture)[0].getAttribute('aria-selected')).toBe('true');
  });

  it('narrows to the matching entries as the query is typed', async () => {
    const { fixture } = await monter();
    saisir(fixture, 'amelie');
    await fixture.whenStable();
    const libelles = options(fixture).map((option) => option.textContent?.trim());
    expect(libelles.some((libelle) => libelle?.includes('Amélie Durand'))).toBe(true);
  });

  it('moves the highlight with the arrows, and wraps around', async () => {
    const { fixture } = await monter();
    const champ = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    champ.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }),
    );
    await fixture.whenStable();
    expect(champ.getAttribute('aria-activedescendant')).toBe('palette-option-1');
    champ.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true }),
    );
    champ.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true }),
    );
    await fixture.whenStable();
    expect(champ.getAttribute('aria-activedescendant')).toBe(
      `palette-option-${options(fixture).length - 1}`,
    );
  });

  it('resets the highlight to the first result on every new query', async () => {
    const { fixture } = await monter();
    const champ = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    champ.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }),
    );
    await fixture.whenStable();
    saisir(fixture, 'animateurs');
    await fixture.whenStable();
    expect(champ.getAttribute('aria-activedescendant')).toBe('palette-option-0');
  });

  it('closes with the highlighted entry on Enter, and navigates nothing itself', async () => {
    const { fixture, dialogRef } = await monter();
    saisir(fixture, 'animateurs');
    await fixture.whenStable();
    const champ = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    champ.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    );
    expect(dialogRef.close).toHaveBeenCalledWith(expect.objectContaining({ route: '/animateurs' }));
  });

  it('closes with the clicked entry', async () => {
    const { fixture, dialogRef } = await monter();
    saisir(fixture, 'amelie');
    await fixture.whenStable();
    const ligne = options(fixture).find((option) => option.textContent?.includes('Amélie'));
    ligne?.click();
    expect(dialogRef.close).toHaveBeenCalledWith(
      expect.objectContaining({ route: '/timeline', queryParams: { animateur: 'a1' } }),
    );
  });

  it('says so rather than showing an empty box when nothing matches', async () => {
    const { fixture } = await monter();
    saisir(fixture, 'zzzzzz');
    await fixture.whenStable();
    expect(options(fixture)).toHaveLength(0);
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain(
      'Aucun résultat',
    );
  });

  it('loads the referential it searches only when the store is still empty', async () => {
    const { store } = await monter();
    expect(store.reload).not.toHaveBeenCalled();
  });
});
