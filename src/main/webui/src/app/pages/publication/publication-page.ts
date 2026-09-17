import { ChangeDetectionStrategy, Component, signal, ViewEncapsulation } from '@angular/core';
import { OutputPanel } from '../../shared/output-panel';
import { PublicationPanel } from './publication-panel';

/**
 * « Publication » : ce qui sort de l'outil vers de vraies personnes — les
 * documents à imprimer ou à archiver, et l'envoi du planning enregistré aux
 * seules personnes dont l'emploi du temps a changé.
 *
 * <p>Un écran à soi, sous « Solveur » dans le menu Planning : c'est l'étape
 * d'après, pas une carte de la page qui calcule. Elle vivait en bas de l'écran
 * Solveur, qu'on ouvre pendant qu'une résolution tourne — le pire endroit pour
 * l'action qui s'adresse à des gens.</p>
 *
 * <p>La page ne fait qu'accueillir le panneau : il possède l'aperçu de
 * publication, le relit après chaque envoi, et dit à la page ce qu'elle doit
 * écrire dans son panneau de sortie.</p>
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
