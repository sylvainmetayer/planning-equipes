// Le clavier des tables de référence : ce qui bouge le focus, ce qui ouvre,
// ce qui sélectionne — et surtout ce qui n'est pas consommé, parce qu'une
// touche avalée ici est un raccourci global qui disparaît sans un mot.

import { WritableSignal, computed, signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { TableSelection } from './table-selection';
import { ROW_INDEX_ATTRIBUTE, TableNavigation, nextRowIndex } from './table-navigation';

interface Ligne {
  id: string;
}

function lignes(...ids: string[]): Ligne[] {
  return ids.map((id) => ({ id }));
}

/**
 * Table jetable dans le DOM : `moveTo` déplace le focus du navigateur, ce qui
 * ne demande ni composant ni rendu Angular — seulement des éléments focusables
 * portant l'attribut que la navigation cherche.
 */
function hote(total: number): HTMLElement {
  const element = document.createElement('div');
  element.innerHTML = Array.from(
    { length: total },
    (_, index) => `<div ${ROW_INDEX_ATTRIBUTE}="${index}" tabindex="-1"></div>`,
  ).join('');
  document.body.appendChild(element);
  return element;
}

interface Contexte {
  navigation: TableNavigation<Ligne, string>;
  rows: WritableSignal<Ligne[]>;
  selection: TableSelection<string>;
  ouvertes: Ligne[];
  annonces: string[];
  element: HTMLElement;
}

function contexte(...ids: string[]): Contexte {
  const rows = signal(lignes(...ids));
  const selection = new TableSelection<string>(computed(() => rows().map((ligne) => ligne.id)));
  const ouvertes: Ligne[] = [];
  const annonces: string[] = [];
  const element = hote(ids.length);
  const navigation = new TableNavigation<Ligne, string>({
    rows,
    id: (ligne) => ligne.id,
    host: () => element,
    selection,
    open: (ligne) => {
      ouvertes.push(ligne);
      return true;
    },
    announcer: { announce: (message: string) => annonces.push(message) },
  });
  return { navigation, rows, selection, ouvertes, annonces, element };
}

/** Frappe sur la ligne `index`, comme le ferait le `(keydown)` du `<tr>`. */
function frapper(
  ctx: Contexte,
  index: number,
  key: string,
  modifiers: Partial<KeyboardEventInit> = {},
): KeyboardEvent {
  const cible = ctx.element.querySelector<HTMLElement>(`[${ROW_INDEX_ATTRIBUTE}="${index}"]`);
  const event = new KeyboardEvent('keydown', {
    key,
    bubbles: true,
    cancelable: true,
    ...modifiers,
  });
  cible?.addEventListener('keydown', (e) => ctx.navigation.onKeydown(e as KeyboardEvent, index), {
    once: true,
  });
  cible?.dispatchEvent(event);
  return event;
}

/** Comme {@link frapper}, mais sur une navigation montée pour le test. */
function frappeSur(
  ctx: Contexte,
  index: number,
  navigation: TableNavigation<Ligne, string>,
  key: string,
): KeyboardEvent {
  const cible = ctx.element.querySelector<HTMLElement>(`[${ROW_INDEX_ATTRIBUTE}="${index}"]`)!;
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
  cible.addEventListener('keydown', (e) => navigation.onKeydown(e as KeyboardEvent, index), {
    once: true,
  });
  cible.dispatchEvent(event);
  return event;
}

function indexFocalise(): string | null {
  return document.activeElement?.getAttribute(ROW_INDEX_ATTRIBUTE) ?? null;
}

describe('nextRowIndex', () => {
  it('descend et remonte d’un cran', () => {
    expect(nextRowIndex('ArrowDown', 0, 3)).toBe(1);
    expect(nextRowIndex('ArrowUp', 2, 3)).toBe(1);
  });

  it('bute sur les bornes plutôt que de boucler', () => {
    expect(nextRowIndex('ArrowUp', 0, 3)).toBe(0);
    expect(nextRowIndex('ArrowDown', 2, 3)).toBe(2);
  });

  it('va à la première et à la dernière ligne', () => {
    expect(nextRowIndex('Home', 2, 3)).toBe(0);
    expect(nextRowIndex('End', 0, 3)).toBe(2);
  });

  it('ne bouge pas sur une table vide', () => {
    expect(nextRowIndex('ArrowDown', 0, 0)).toBeNull();
    expect(nextRowIndex('Home', 0, 0)).toBeNull();
  });

  it('laisse passer les touches qui ne sont pas les siennes', () => {
    expect(nextRowIndex('/', 0, 3)).toBeNull();
    expect(nextRowIndex('g', 0, 3)).toBeNull();
    expect(nextRowIndex('ArrowRight', 0, 3)).toBeNull();
  });
});

describe('TableNavigation', () => {
  it('offre la première ligne à la tabulation tant que rien n’a été focalisé', () => {
    const ctx = contexte('a', 'b', 'c');

    expect(ctx.navigation.index()).toBe(0);
    expect(ctx.navigation.isCurrent(0)).toBe(true);
    expect(ctx.navigation.isCurrent(1)).toBe(false);
  });

  it('ne propose aucune ligne quand la table est vide', () => {
    const ctx = contexte();

    expect(ctx.navigation.index()).toBe(-1);
    expect(ctx.navigation.isCurrent(0)).toBe(false);
  });

  it('déplace le focus d’une ligne à l’autre aux flèches', () => {
    const ctx = contexte('a', 'b', 'c');

    frapper(ctx, 0, 'ArrowDown');
    expect(ctx.navigation.index()).toBe(1);
    expect(indexFocalise()).toBe('1');

    frapper(ctx, 1, 'ArrowUp');
    expect(ctx.navigation.index()).toBe(0);
    expect(indexFocalise()).toBe('0');
  });

  it('reste sur la dernière ligne en bas et sur la première en haut', () => {
    const ctx = contexte('a', 'b');

    frapper(ctx, 0, 'ArrowUp');
    expect(ctx.navigation.index()).toBe(0);

    frapper(ctx, 1, 'ArrowDown');
    expect(ctx.navigation.index()).toBe(1);
  });

  it('saute à la première et à la dernière ligne', () => {
    const ctx = contexte('a', 'b', 'c');

    frapper(ctx, 0, 'End');
    expect(ctx.navigation.index()).toBe(2);

    frapper(ctx, 2, 'Home');
    expect(ctx.navigation.index()).toBe(0);
  });

  it('ouvre la ligne focalisée sur Entrée', () => {
    const ctx = contexte('a', 'b');

    const event = frapper(ctx, 1, 'Enter');

    expect(ctx.ouvertes).toEqual([{ id: 'b' }]);
    expect(event.defaultPrevented).toBe(true);
  });

  it('bascule la sélection sur Espace, et retient la barre d’espace de la page', () => {
    const ctx = contexte('a', 'b');

    const event = frapper(ctx, 1, ' ');
    expect(ctx.selection.selectedIds()).toEqual(['b']);
    // Sans preventDefault, l'Espace ferait aussi défiler la page d'un écran.
    expect(event.defaultPrevented).toBe(true);

    frapper(ctx, 1, ' ');
    expect(ctx.selection.selectedIds()).toEqual([]);
  });

  // Le service de raccourcis globaux s'arrête sur un événement déjà consommé :
  // avaler ces touches ici ferait disparaître « / », « ? », « g » et Ctrl+K
  // dès que le focus est dans un tableau.
  it('laisse passer les touches des raccourcis globaux', () => {
    const ctx = contexte('a', 'b');

    for (const key of ['/', '?', 'g', 'Escape', 'Tab']) {
      expect(frapper(ctx, 0, key).defaultPrevented).toBe(false);
    }
    expect(frapper(ctx, 0, 'k', { ctrlKey: true }).defaultPrevented).toBe(false);
    expect(frapper(ctx, 0, 'Enter', { ctrlKey: true }).defaultPrevented).toBe(false);
    expect(ctx.ouvertes).toEqual([]);
  });

  // Un `keydown` remonte : Espace sur la case à cocher de la ligne et Entrée
  // sur son bouton « Supprimer » arrivent tous les deux au `<tr>`.
  it('ignore une frappe venue d’un contrôle de la ligne', () => {
    const ctx = contexte('a', 'b');
    const ligne = ctx.element.querySelector<HTMLElement>(`[${ROW_INDEX_ATTRIBUTE}="0"]`)!;
    const bouton = document.createElement('button');
    ligne.appendChild(bouton);
    ligne.addEventListener('keydown', (e) => ctx.navigation.onKeydown(e as KeyboardEvent, 0));

    const event = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true });
    bouton.dispatchEvent(event);

    expect(event.defaultPrevented).toBe(false);
    expect(ctx.selection.selectedIds()).toEqual([]);
  });

  // Le tri réordonne les lignes sous le focus : c'est la ligne qui compte, pas
  // son rang.
  it('suit la ligne focalisée quand le tri la déplace', () => {
    const ctx = contexte('a', 'b', 'c');
    frapper(ctx, 0, 'ArrowDown');
    expect(ctx.navigation.index()).toBe(1);

    ctx.rows.set(lignes('c', 'b', 'a'));

    expect(ctx.navigation.index()).toBe(1);

    ctx.rows.set(lignes('b', 'a', 'c'));
    expect(ctx.navigation.index()).toBe(0);
  });

  // Sans repli, plus aucune ligne ne porterait `tabindex="0"` et le tableau
  // sortirait de l'ordre de tabulation : la seule porte d'entrée, disparue.
  it('retombe sur la ligne qui a pris la place quand le filtre efface la focalisée', () => {
    const ctx = contexte('a', 'b', 'c');
    frapper(ctx, 0, 'ArrowDown');

    ctx.rows.set(lignes('a', 'c'));

    expect(ctx.navigation.index()).toBe(1);
  });

  it('borne le repli à la dernière ligne restante', () => {
    const ctx = contexte('a', 'b', 'c');
    frapper(ctx, 0, 'End');

    ctx.rows.set(lignes('a'));

    expect(ctx.navigation.index()).toBe(0);
  });

  // Le repli ne peut pas être le rang figé au moment du focus : entre-temps un
  // tri a déplacé la ligne, et c'est de sa place *à ce moment-là* que le focus
  // doit repartir quand un filtre l'efface.
  it('replie sur le rang que la ligne occupait après le tri, pas sur celui du focus', () => {
    const ctx = contexte('a', 'b', 'c');
    ctx.navigation.onFocus(0);

    // Le tri envoie « a » en dernier : le tableau se redessine, donc le rang
    // courant est lu.
    ctx.rows.set(lignes('b', 'c', 'a'));
    expect(ctx.navigation.index()).toBe(2);

    // Le filtre efface « a » : le focus reste en bas, sur la ligne qui a pris
    // sa place, et ne remonte pas en tête du tableau.
    ctx.rows.set(lignes('b', 'c'));
    expect(ctx.navigation.index()).toBe(1);
  });

  // Le repli d'une ancre jamais affichée depuis sa prise de focus reste le rang
  // qu'elle avait alors : la valeur précédente appartient à une autre ancre.
  it('n’emprunte pas le rang de repli de l’ancre précédente', () => {
    const ctx = contexte('a', 'b', 'c');
    ctx.navigation.onFocus(0);
    expect(ctx.navigation.index()).toBe(0);

    ctx.navigation.onFocus(2);
    ctx.rows.set(lignes('a', 'b'));

    expect(ctx.navigation.index()).toBe(1);
  });

  // Une touche consommée sans que personne n'agisse est un raccourci mort et
  // muet : la page ne fait rien et ne dit rien.
  it('laisse passer Entrée quand la page refuse d’ouvrir la ligne', () => {
    const ctx = contexte('a', 'b');
    const refus = vi.fn().mockReturnValue(false);
    const navigation = new TableNavigation<Ligne, string>({
      rows: ctx.rows,
      id: (ligne) => ligne.id,
      host: () => ctx.element,
      selection: ctx.selection,
      open: refus,
    });
    const event = frappeSur(ctx, 0, navigation, 'Enter');

    expect(refus).toHaveBeenCalledWith({ id: 'a' });
    expect(event.defaultPrevented).toBe(false);
  });

  it('laisse passer Entrée sur une table sans ouverture câblée', () => {
    const ctx = contexte('a', 'b');
    const navigation = new TableNavigation<Ligne, string>({
      rows: ctx.rows,
      id: (ligne) => ligne.id,
      host: () => ctx.element,
      selection: ctx.selection,
    });

    expect(frappeSur(ctx, 0, navigation, 'Enter').defaultPrevented).toBe(false);
  });

  // Sans sélection à basculer, l'Espace ne gagne rien et la page perd son
  // défilement d'un écran.
  it('rend l’Espace à la page quand la table n’a pas de sélection', () => {
    const ctx = contexte('a', 'b');
    const navigation = new TableNavigation<Ligne, string>({
      rows: ctx.rows,
      id: (ligne) => ligne.id,
      host: () => ctx.element,
      open: () => true,
    });

    expect(frappeSur(ctx, 0, navigation, ' ').defaultPrevented).toBe(false);
    expect(ctx.selection.selectedIds()).toEqual([]);
  });

  // `aria-selected` n'est pas lisible sur la ligne d'un `role="table"` : c'est
  // la région live qui porte le changement d'état.
  it('annonce la ligne cochée puis décochée', () => {
    const ctx = contexte('a', 'b');

    frapper(ctx, 1, ' ');
    frapper(ctx, 1, ' ');

    expect(ctx.annonces).toEqual(['Ligne cochée.', 'Ligne décochée.']);
  });

  it('n’ouvre rien et ne sélectionne rien sur une table vide', () => {
    const ctx = contexte();
    const open = vi.fn();
    const navigation = new TableNavigation<Ligne, string>({
      rows: ctx.rows,
      id: (ligne) => ligne.id,
      host: () => ctx.element,
      open,
    });

    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    Object.defineProperty(event, 'target', { value: ctx.element });
    Object.defineProperty(event, 'currentTarget', { value: ctx.element });
    navigation.onKeydown(event, 0);

    expect(open).not.toHaveBeenCalled();
    expect(event.defaultPrevented).toBe(false);
  });

  it('retient la ligne atteinte par la tabulation ou par un clic', () => {
    const ctx = contexte('a', 'b', 'c');

    ctx.navigation.onFocus(2);

    expect(ctx.navigation.index()).toBe(2);
  });

  it('focalise la ligne courante quand on entre par le filtre', () => {
    const ctx = contexte('a', 'b', 'c');
    ctx.navigation.onFocus(1);

    expect(ctx.navigation.focusCurrent()).toBe(true);

    expect(document.activeElement?.getAttribute(ROW_INDEX_ATTRIBUTE)).toBe('1');
  });

  it('entre sur la première ligne tant que rien n’a été focalisé', () => {
    const ctx = contexte('a', 'b');

    expect(ctx.navigation.focusCurrent()).toBe(true);

    expect(document.activeElement?.getAttribute(ROW_INDEX_ATTRIBUTE)).toBe('0');
  });

  it('n’entre nulle part dans une table vide, et le dit', () => {
    const ctx = contexte();

    expect(ctx.navigation.focusCurrent()).toBe(false);
  });
});
