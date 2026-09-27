// The one-line state of a guichet: the collection has no « open today » flag
// of its own, so the line judges its dates here — on the server's today, a
// simulated clock included, the day the foire's line is judged on server-side.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DisponibilitesApi } from '../core/api/disponibilites-api';
import { EchangesApi } from '../core/api/echanges-api';
import { DateMockService } from '../core/date-mock.service';
import { NotificationService } from '../core/notification.service';
import { GuichetEtat } from './guichet-etat';

async function renderCollection(serverDate: string): Promise<string> {
  return (await mountCollection(signal(serverDate))).text();
}

async function mountCollection(serverDate: ReturnType<typeof signal<string>>) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [GuichetEtat],
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      {
        provide: DisponibilitesApi,
        useValue: {
          configuration: vi.fn(async () => ({
            collecteOuverte: true,
            debut: '2026-07-01',
            fin: '2026-07-10',
          })),
        },
      },
      { provide: EchangesApi, useValue: {} },
      { provide: NotificationService, useValue: { notify: vi.fn() } },
      { provide: DateMockService, useValue: { dateDuJour: serverDate } },
    ],
  });
  const fixture = TestBed.createComponent(GuichetEtat);
  fixture.componentRef.setInput('guichet', 'collecte');
  fixture.detectChanges();
  await new Promise((resolve) => setTimeout(resolve));
  await fixture.whenStable();
  return {
    text: () => {
      fixture.detectChanges();
      return ((fixture.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
    },
  };
}

describe('GuichetEtat', () => {
  afterEach(() => vi.useRealTimers());

  it("judges the collection's dates on the server's today, not the browser's", async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 8, 27, 10, 0));

    expect(await renderCollection('2026-07-05')).toContain("Collecte ouverte jusqu'au 10/07");
    // The real clock: the browser's date answers, past the window here.
    expect(await renderCollection('')).toContain('hors de ses dates');
  });

  it('judges again when the server date arrives after the configuration', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 8, 27, 10, 0));
    const serverDate = signal('');
    const guichet = await mountCollection(serverDate);
    expect(guichet.text()).toContain('hors de ses dates');

    serverDate.set('2026-07-05');

    expect(guichet.text()).toContain("Collecte ouverte jusqu'au 10/07");
  });
});
