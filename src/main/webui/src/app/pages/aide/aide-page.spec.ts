// Deep links into the guide: `/aide#une-section` has to land on that section
// on a *direct* load — a pasted link, a bookmark, a refresh — and not only
// when the summary of the page is clicked.
//
// This is not free here. `index.html` declares `<base href="/">`, so a plain
// `href="#id"` navigates to `/#id`; and what scrolls is `mat-sidenav-content`,
// not the document, so Angular's own anchor scrolling would move the wrong
// element. The page therefore scrolls by hand, once the sections have
// rendered — which is exactly what breaks silently the day someone reads the
// fragment in a field initializer, on a DOM that has no section in it yet.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AidePage } from './aide-page';

/** jsdom implements neither of the two, and the page calls both. */
const scrollIntoView = vi.fn();

async function monterAvecFragment(fragment: string | null): Promise<ComponentFixture<AidePage>> {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { snapshot: { fragment } } }
    ]
  });
  const fixture = TestBed.createComponent(AidePage);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

describe('AidePage deep links', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
    scrollIntoView.mockClear();
    Element.prototype.scrollIntoView = scrollIntoView;
  });

  it('scrolls to the section named by the URL fragment on a direct load', async () => {
    const fixture = await monterAvecFragment('import-csv-animateurs');

    // The anchor the import screen links to really exists in the rendered DOM…
    const section = fixture.nativeElement.querySelector('#import-csv-animateurs');
    expect(section).not.toBeNull();
    // …and the page went to it by itself, without a click on the summary.
    expect(scrollIntoView).toHaveBeenCalled();
    expect(scrollIntoView.mock.instances[0]).toBe(section);
  });

  it('scrolls nowhere when the URL carries no fragment', async () => {
    await monterAvecFragment(null);

    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  /** A stale link to a renamed section must not throw, just do nothing. */
  it('ignores a fragment naming no section', async () => {
    await monterAvecFragment('section-qui-nexiste-plus');

    expect(scrollIntoView).not.toHaveBeenCalled();
  });
});
