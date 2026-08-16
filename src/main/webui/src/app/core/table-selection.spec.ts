import { signal } from '@angular/core';
import { describe, expect, it } from 'vitest';
import { TableSelection } from './table-selection';

describe('TableSelection', () => {
  it('démarre vide', () => {
    const selection = new TableSelection(signal(['a', 'b']));

    expect(selection.selectedIds()).toEqual([]);
    expect(selection.hasSelection()).toBe(false);
    expect(selection.allSelected()).toBe(false);
    expect(selection.partiallySelected()).toBe(false);
  });

  it('coche et décoche une ligne', () => {
    const selection = new TableSelection(signal(['a', 'b']));

    selection.toggle('a');
    expect(selection.isSelected('a')).toBe(true);
    expect(selection.count()).toBe(1);
    expect(selection.partiallySelected()).toBe(true);

    selection.toggle('a');
    expect(selection.isSelected('a')).toBe(false);
    expect(selection.count()).toBe(0);
  });

  it('rend les identifiants dans l’ordre d’affichage', () => {
    const selection = new TableSelection(signal([3, 1, 2]));

    selection.set(2, true);
    selection.set(3, true);

    expect(selection.selectedIds()).toEqual([3, 2]);
  });

  it('coche tout puis vide la sélection', () => {
    const selection = new TableSelection(signal(['a', 'b']));

    selection.toggleAll();
    expect(selection.allSelected()).toBe(true);
    expect(selection.partiallySelected()).toBe(false);

    selection.toggleAll();
    expect(selection.selectedIds()).toEqual([]);
  });

  // Une ligne supprimée ou masquée par un filtre ne doit jamais rester dans la
  // sélection : les pages ne font aucun ménage, l'intersection avec les lignes
  // affichées s'en charge.
  it('oublie une ligne qui disparaît de l’affichage', () => {
    const lignes = signal(['a', 'b']);
    const selection = new TableSelection(lignes);
    selection.toggleAll();

    lignes.set(['a']);

    expect(selection.selectedIds()).toEqual(['a']);
    expect(selection.allSelected()).toBe(true);
  });

  // ... mais la coche est conservée : si la ligne revient (filtre relâché),
  // elle revient sélectionnée plutôt que silencieusement désélectionnée.
  it('retrouve une ligne masquée quand elle est réaffichée', () => {
    const lignes = signal(['a', 'b']);
    const selection = new TableSelection(lignes);
    selection.toggleAll();

    lignes.set(['a']);
    lignes.set(['a', 'b']);

    expect(selection.selectedIds()).toEqual(['a', 'b']);
  });

  it('ne considère jamais une liste vide comme entièrement sélectionnée', () => {
    const selection = new TableSelection(signal<string[]>([]));

    selection.toggleAll();

    expect(selection.allSelected()).toBe(false);
    expect(selection.selectedIds()).toEqual([]);
  });
});
