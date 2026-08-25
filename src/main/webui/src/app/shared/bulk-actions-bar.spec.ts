// The bar that appears above a ticked selection on the five reference pages,
// and from which a user deletes rows in bulk.
//
// Two claims are worth freezing. The count must match the selection — it is the
// only number the user reads before confirming a bulk delete — and the
// "(lignes affichées par le filtre uniquement)" warning must be there whenever
// a filter narrows the table, because "tout sélectionner" then means the
// visible rows and not the referential. Losing that sentence turns a scoped
// delete into a surprise.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BulkActionsBar } from './bulk-actions-bar';

let fixture: ComponentFixture<BulkActionsBar>;

function racine(): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function boutons(): HTMLButtonElement[] {
  return Array.from(racine().querySelectorAll('button'));
}

function bouton(libelle: string): HTMLButtonElement {
  const trouve = boutons().find((each) => each.textContent!.includes(libelle));
  expect(trouve, `bouton « ${libelle} » absent`).toBeDefined();
  return trouve!;
}

function rendre(entrees: Record<string, unknown> = {}): void {
  fixture.componentRef.setInput('count', 3);
  for (const [nom, valeur] of Object.entries(entrees)) {
    fixture.componentRef.setInput(nom, valeur);
  }
  fixture.detectChanges();
}

describe('BulkActionsBar', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    fixture = TestBed.createComponent(BulkActionsBar);
  });

  it('announces how many rows are selected, as a live region', () => {
    rendre({ count: 12 });

    expect(racine().querySelector('.bulk-bar')!.getAttribute('role')).toBe('status');
    expect(racine().querySelector('.bulk-bar-count')!.textContent!.trim()).toBe('12 élément(s) sélectionné(s)');
  });

  it('follows the count when the selection changes', () => {
    rendre({ count: 1 });
    fixture.componentRef.setInput('count', 4);
    fixture.detectChanges();

    expect(racine().querySelector('.bulk-bar-count')!.textContent!.trim()).toBe('4 élément(s) sélectionné(s)');
  });

  it('warns that the selection is scoped to the filtered rows, and only then', () => {
    rendre({ filtre: false });
    expect(racine().querySelector('.bulk-bar-scope')).toBeNull();

    fixture.componentRef.setInput('filtre', true);
    fixture.detectChanges();
    expect(racine().querySelector('.bulk-bar-scope')!.textContent!.trim()).toBe(
      '(lignes affichées par le filtre uniquement)'
    );
  });

  it('hides the bulk edit on the pages whose entities share no editable field', () => {
    rendre({ editable: false });

    expect(boutons().some((each) => each.textContent!.includes('Modifier la sélection'))).toBe(false);
    // Deleting stays available: only the edit is entity-dependent.
    expect(bouton('Supprimer la sélection')).toBeDefined();
  });

  it('emits one event per action', () => {
    rendre({ editable: true });
    const emis: string[] = [];
    fixture.componentInstance.edit.subscribe(() => emis.push('edit'));
    fixture.componentInstance.remove.subscribe(() => emis.push('remove'));
    fixture.componentInstance.clear.subscribe(() => emis.push('clear'));

    bouton('Modifier la sélection').click();
    bouton('Supprimer la sélection').click();
    bouton('Tout désélectionner').click();

    expect(emis).toEqual(['edit', 'remove', 'clear']);
  });

  it('disables the writing actions while a solve is running, but never the deselect', () => {
    rendre({ disabled: true });

    expect(bouton('Modifier la sélection').disabled).toBe(true);
    expect(bouton('Supprimer la sélection').disabled).toBe(true);
    // Clearing a selection writes nothing: locking it would trap the user.
    expect(bouton('Tout désélectionner').disabled).toBe(false);
  });
});
