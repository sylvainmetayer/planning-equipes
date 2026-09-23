import { Directive, ElementRef, OnInit, Renderer2, inject } from '@angular/core';

/**
 * The words a link opening a new window adds to its name. A function, not a
 * constant: `$localize` must run after `main.ts` has loaded the translations.
 */
export function newWindowNotice(): string {
  return $localize`:@@lien.nouvelleFenetre:(nouvelle fenêtre)`;
}

/**
 * The accessible name of a link that opens a new window and names itself with
 * an `aria-label` — which overrides its content, so the notice
 * {@link NewWindowLink} appends would never be read.
 */
export function newWindowLabel(label: string): string {
  return `${label} ${newWindowNotice()}`;
}

/**
 * Every `<a target="_blank">` says so (RGAA 13.2).
 *
 * A window that opens without warning disorients first those who do not see it
 * open: « Précédent » no longer leads back, and nothing said the context
 * changed. The notice is appended to the link as visually hidden text, so it
 * joins whatever the link's content already says — a label written in an i18n
 * message, an interpolated place name — without rewriting any of them. The eye
 * gets the `↗` that `pages.css` draws after a text link.
 *
 * A link named by an `aria-label` does not read its content: it carries
 * {@link newWindowLabel} in that label instead. `npm run new-window-check`
 * holds both halves.
 */
// The selector is the element itself, not an `app…` attribute: a notice each
// link had to opt into is the notice the audit found missing 31 times.
// eslint-disable-next-line @angular-eslint/directive-selector
@Directive({ selector: 'a[target="_blank"]' })
export class NewWindowLink implements OnInit {
  private readonly host = inject<ElementRef<HTMLAnchorElement>>(ElementRef);
  private readonly renderer = inject(Renderer2);

  ngOnInit(): void {
    const notice = this.renderer.createElement('span') as HTMLSpanElement;
    this.renderer.addClass(notice, 'visually-hidden');
    this.renderer.addClass(notice, 'new-window-notice');
    this.renderer.appendChild(notice, this.renderer.createText(` ${newWindowNotice()}`));
    this.renderer.appendChild(this.host.nativeElement, notice);
  }
}
