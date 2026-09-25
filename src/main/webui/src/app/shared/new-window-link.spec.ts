// RGAA 13.2: a link opening a new window says so, in its accessible name.

import { ChangeDetectionStrategy, Component, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { NewWindowLink, newWindowLabel } from './new-window-link';

@Component({
  imports: [NewWindowLink],
  template: `
    <a id="blank" href="https://example.org" target="_blank" rel="noopener">{{ place }}</a>
    <a id="same" href="/aide">Aide</a>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {
  place = 'Salle des fêtes';
}

async function render(): Promise<HTMLElement> {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  const fixture = TestBed.createComponent(Host);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('NewWindowLink', () => {
  it('appends the notice to a link opening a new window, after its own content', async () => {
    const root = await render();

    const link = root.querySelector('#blank')!;
    expect(link.textContent!.replace(/\s+/g, ' ').trim()).toBe(
      'Salle des fêtes (nouvelle fenêtre)',
    );
    expect(link.lastElementChild!.classList).toContain('visually-hidden');
  });

  it('leaves an ordinary link alone', async () => {
    const root = await render();

    expect(root.querySelector('#same')!.textContent).toBe('Aide');
  });

  it('carries the notice into a label that would otherwise hide it', () => {
    expect(newWindowLabel('Consulter l’article L3121-20')).toBe(
      'Consulter l’article L3121-20 (nouvelle fenêtre)',
    );
  });
});
