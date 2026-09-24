// The contract both shells keep with a keyboard and a screen reader on a
// change of page (RGAA 12.7): the focus goes to the content region, and the
// new page's title is spoken — the title the browser also puts in the tab.
// One place, so the admin shell and the espace animateur cannot drift apart
// on it (a title format that changes, a focus that moves too early).

import { LiveAnnouncer } from '@angular/cdk/a11y';
import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';

@Injectable({ providedIn: 'root' })
export class PageFocusService {
  private readonly title = inject(Title);
  private readonly announcer = inject(LiveAnnouncer);

  /**
   * Hands the focus to `main` without scrolling and speaks the page's title,
   * read up to the `—` that separates it from the deployment's name.
   */
  arriveOn(main: HTMLElement | undefined): void {
    main?.focus({ preventScroll: true });
    const titre = this.title.getTitle().split('—')[0].trim();
    if (titre) {
      this.announcer.announce(titre, 'polite');
    }
  }

  /** Skip link: `href="#contenu"` alone would move the caret but not the focus. */
  skipTo(event: Event, main: HTMLElement | undefined): void {
    event.preventDefault();
    main?.focus();
  }
}
