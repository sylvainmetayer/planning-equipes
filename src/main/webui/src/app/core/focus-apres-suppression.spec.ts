// Accessibility net: deleting a repeatable row destroys the focused button, and
// the browser falls back to <body> — a keyboard user is thrown to the top of the
// document and a screen reader goes silent.

import { ApplicationRef, Component, Injector, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { focusApresSuppression } from './focus-apres-suppression';

@Component({ selector: 'app-fake-form', template: '' })
class FakeForm {}

function hostWith(html: string): HTMLElement {
  const host = document.createElement('div');
  host.innerHTML = html;
  document.body.appendChild(host);
  return host;
}

describe('focusApresSuppression', () => {
  let injector: Injector;
  let appRef: ApplicationRef;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const fixture = TestBed.createComponent(FakeForm);
    injector = fixture.componentRef.injector;
    appRef = TestBed.inject(ApplicationRef);
  });

  /** Lets `afterNextRender` run, which is what the helper schedules on. */
  async function render(): Promise<void> {
    appRef.tick();
    await Promise.resolve();
  }

  it('moves the focus to the requested element once the row is gone', async () => {
    const host = hostWith('<button class="ajouter">Ajouter un horaire</button>');

    focusApresSuppression(host, '.ajouter', injector);
    await render();

    expect(document.activeElement).toBe(host.querySelector('.ajouter'));
  });

  it('focuses the first match when a section repeats the same button', async () => {
    const host = hostWith(
      '<button class="ajouter" id="premier"></button><button class="ajouter" id="second"></button>',
    );

    focusApresSuppression(host, '.ajouter', injector);
    await render();

    expect((document.activeElement as HTMLElement).id).toBe('premier');
  });

  it('never steals the focus from another open dialog: it only looks inside its own host', async () => {
    const autreDialogue = hostWith('<button class="ajouter" id="autre"></button>');
    const monDialogue = hostWith('<button class="autre-chose"></button>');
    const dejaFocus = autreDialogue.querySelector<HTMLElement>('#autre')!;
    dejaFocus.focus();

    focusApresSuppression(monDialogue, '.ajouter', injector);
    await render();

    expect(document.activeElement).toBe(dejaFocus);
  });

  it('does nothing rather than throwing when the target no longer exists', async () => {
    const host = hostWith('<span>plus aucun bouton</span>');

    focusApresSuppression(host, '.ajouter', injector);

    await expect(render()).resolves.toBeUndefined();
  });
});
