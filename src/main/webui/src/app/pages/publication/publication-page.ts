import { ChangeDetectionStrategy, Component, signal, ViewEncapsulation } from '@angular/core';
import { OutputPanel } from '../../shared/output-panel';
import { PublicationPanel } from './publication-panel';

/**
 * « Publication »: what leaves the tool for real people — the documents to
 * print or to archive, and the sending of the persisted plan to the only
 * people whose schedule changed.
 *
 * <p>A screen of its own, under « Solveur » in the Planning menu: this is the
 * next step, not a card on the page that computes. It used to sit at the
 * bottom of the Solveur screen, which one opens while a solve is running — the
 * worst place for the action that addresses people.</p>
 *
 * <p>The page only hosts the panel: the panel owns the publication preview,
 * reads it back after every send, and tells the page what to write in its
 * output panel.</p>
 */
@Component({
  selector: 'app-publication-page',
  imports: [OutputPanel, PublicationPanel],
  template: `
    <h1 class="page-title" i18n="@@nav.link.publication">Publication</h1>
    <app-publication-panel (reported)="output.set($event)" />
    <app-output-panel [text]="output()" />
  `,
  styleUrl: './publication.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublicationPage {
  protected readonly output = signal('');
}
