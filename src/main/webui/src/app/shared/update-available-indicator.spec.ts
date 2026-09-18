// The toolbar hint that a newer release exists: absent until the check says
// so, then an icon whose tooltip names the version and whose link opens its
// release notes without carrying this page's address along.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { AvailableUpdate, UpdateCheckService } from '../core/update-check.service';
import { UpdateAvailableIndicator } from './update-available-indicator';

describe('UpdateAvailableIndicator', () => {
  let fixture: ComponentFixture<UpdateAvailableIndicator>;
  const check = vi.fn();

  async function rendre(update: AvailableUpdate | null): Promise<void> {
    check.mockClear();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: UpdateCheckService, useValue: { available: signal(update), check } },
      ],
    });
    fixture = TestBed.createComponent(UpdateAvailableIndicator);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function lien(): HTMLAnchorElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('a');
  }

  it('lance la vérification dès qu’il est posé dans la barre', async () => {
    await rendre(null);

    expect(check).toHaveBeenCalledOnce();
  });

  it('n’affiche rien tant qu’aucune version plus récente n’est connue', async () => {
    await rendre(null);

    expect(lien()).toBeNull();
  });

  it('nomme la version disponible et mène à sa release', async () => {
    await rendre({ version: 'v1.1.0', url: 'https://github.com/x/y/releases/tag/v1.1.0' });

    const a = lien();
    expect(a).not.toBeNull();
    expect(a?.getAttribute('href')).toBe('https://github.com/x/y/releases/tag/v1.1.0');
    expect(a?.getAttribute('aria-label')).toContain('v1.1.0');
    expect(a?.getAttribute('rel')).toContain('noopener');
    expect(a?.getAttribute('rel')).toContain('noreferrer');
  });
});
