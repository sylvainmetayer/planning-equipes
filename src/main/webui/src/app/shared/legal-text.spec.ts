import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LegalText } from './legal-text';

describe('LegalText', () => {
  let fixture: ComponentFixture<LegalText>;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    fixture = TestBed.createComponent(LegalText);
  });

  async function render(text: string): Promise<HTMLElement> {
    fixture.componentRef.setInput('text', text);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function links(host: HTMLElement): HTMLAnchorElement[] {
    return Array.from(host.querySelectorAll('a'));
  }

  it('renders the sentence unchanged, spacing included', async () => {
    const text = 'Pas de travail de nuit pour un mineur (Code du travail art. L3163-1).';
    const host = await render(text);
    expect(host.textContent).toBe(text);
  });

  it('links each cited article to Légifrance, in a new tab', async () => {
    const host = await render(
      '35 heures (Code du travail art. L3162-1 ; art. D4153-3 avant 16 ans).',
    );
    expect(links(host).map((lien) => lien.textContent)).toEqual(['L3162-1', 'D4153-3']);
    expect(links(host)[0].getAttribute('href')).toContain('legifrance.gouv.fr');
    expect(links(host)[0].getAttribute('href')).toContain('L3162-1');
    expect(links(host)[0].target).toBe('_blank');
    expect(links(host)[0].rel).toContain('noopener');
  });

  it('names the destination for a screen reader, which hears the link alone', async () => {
    const host = await render('Repos quotidien de 11 h (art. L3131-1).');
    expect(links(host)[0].getAttribute('aria-label')).toContain('L3131-1');
    expect(links(host)[0].getAttribute('aria-label')).toContain('Légifrance');
  });

  it('adds no link to a description that cites no article', async () => {
    const host = await render('La charge de travail doit être répartie équitablement.');
    expect(links(host)).toEqual([]);
    expect(host.textContent).toBe('La charge de travail doit être répartie équitablement.');
  });

  it('renders nothing rather than "undefined" before a description arrives', async () => {
    const host = await render('');
    expect(host.textContent).toBe('');
  });

  it('never interprets the backend text as markup', async () => {
    const host = await render('<b>art. L3121-18</b>');
    expect(host.querySelector('b')).toBeNull();
    expect(host.textContent).toBe('<b>art. L3121-18</b>');
  });
});
