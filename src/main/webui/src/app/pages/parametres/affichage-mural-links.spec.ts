// « Dernier accès » of the wall display links: a television reads its link
// every minute, so the list must not keep the « Jamais » it was first read with.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AffichageMuralApi } from '../../core/api/affichage-mural-api';
import type { AffichageMuralLink } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { AffichageMuralLinks, LINKS_REFRESH_MS } from './affichage-mural-links';

function link(lastAccessAt: string | null): AffichageMuralLink {
  return {
    id: 1,
    libelle: 'TV PC sécurité',
    fullNames: false,
    restricted: false,
    emplacements: [],
    createdAt: '2026-07-08T08:00:00Z',
    lastAccessAt,
  } as AffichageMuralLink;
}

function text(element: HTMLElement): string {
  return element.textContent!.replace(/\s+/g, ' ').trim();
}

describe('AffichageMuralLinks', () => {
  let list: ReturnType<typeof vi.fn>;
  let notify: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    notify = vi.fn();
    list = vi
      .fn()
      .mockResolvedValueOnce([link(null)])
      .mockResolvedValue([link('2026-07-08T09:01:00Z')]);
    TestBed.configureTestingModule({
      imports: [AffichageMuralLinks],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AffichageMuralApi, useValue: { list } },
        { provide: NotificationService, useValue: { notify } },
        { provide: ConfirmService, useValue: { ask: vi.fn() } },
        {
          provide: ReferenceDataStore,
          useValue: { emplacements: signal([{ id: 'E1', nom: 'Hall' }]), reload: vi.fn() },
        },
      ],
    });
  });

  afterEach(() => vi.useRealTimers());

  it('reads the list again when « Actualiser » is pressed', async () => {
    const fixture = TestBed.createComponent(AffichageMuralLinks);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(text(fixture.nativeElement)).toContain('Jamais');

    const bouton = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find(
      (each) => text(each).includes('Actualiser'),
    )!;
    bouton.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(list).toHaveBeenCalledTimes(2);
    expect(text(fixture.nativeElement)).not.toContain('Jamais');
  });

  it('reads the list again every minute while it is on screen, and stops once gone', async () => {
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(AffichageMuralLinks);
    fixture.detectChanges();
    expect(list).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(LINKS_REFRESH_MS);
    expect(list).toHaveBeenCalledTimes(2);

    fixture.destroy();
    await vi.advanceTimersByTimeAsync(LINKS_REFRESH_MS * 2);
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('stays silent when a background read fails, and skips it while the page is hidden', async () => {
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(AffichageMuralLinks);
    fixture.detectChanges();
    list.mockRejectedValue(new Error('down'));

    await vi.advanceTimersByTimeAsync(LINKS_REFRESH_MS);
    expect(list).toHaveBeenCalledTimes(2);
    expect(notify).not.toHaveBeenCalled();

    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    await vi.advanceTimersByTimeAsync(LINKS_REFRESH_MS);
    expect(list).toHaveBeenCalledTimes(2);
    hidden.mockRestore();
    fixture.destroy();
  });

  it('drops an answer overtaken by a later read', async () => {
    vi.useFakeTimers();
    let answerPoll: (value: unknown) => void = () => undefined;
    list.mockReset();
    list
      .mockResolvedValueOnce([link('2026-07-08T09:01:00Z')])
      .mockImplementationOnce(() => new Promise((resolve) => (answerPoll = resolve)))
      .mockResolvedValueOnce([link('2026-07-08T09:05:00Z')]);
    const fixture = TestBed.createComponent(AffichageMuralLinks);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();

    // The minute's read goes out, then « Actualiser » answers first.
    await vi.advanceTimersByTimeAsync(LINKS_REFRESH_MS);
    const bouton = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find(
      (each) => text(each).includes('Actualiser'),
    )!;
    bouton.click();
    await vi.advanceTimersByTimeAsync(0);
    answerPoll([link(null)]);
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();

    expect(text(fixture.nativeElement)).not.toContain('Jamais');
    fixture.destroy();
  });
});
