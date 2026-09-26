import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute } from '@angular/router';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { OutputPanel } from '../../shared/output-panel';
import { DocumentsPanel } from './documents-panel';
import { EnvoisTable } from './envois-table';
import { OngletDiffuser, readOngletDiffuser } from './onglet';
import { PublicationPanel } from './publication-panel';
import { PublicationSelection } from './publication-selection';

/**
 * « Diffuser »: what leaves the tool for real people, as two tabs chosen by
 * `?onglet=envoyer|documents`.
 *
 * <p>« Envoyer » puts the publication first — the button with its count and
 * its sentence, the review of what each person will read — and under it the
 * permanent table of who received which version, the state of each mail and
 * the gestures that follow (resend, remind, defer). « Documents » lists the
 * four files the plan becomes, each saying who it is for.</p>
 *
 * <p>The page owns `onglet`; the table writes its own `filtre` and `jour`
 * beside it, and the review keeps its `tri` and `mineurs` (ADR 0012).</p>
 */
@Component({
  selector: 'app-publication-page',
  imports: [
    DocumentsPanel,
    EnvoisTable,
    MatButtonToggleModule,
    MatIconModule,
    OutputPanel,
    PublicationPanel,
  ],
  providers: [PublicationSelection],
  template: `
    <h1 class="page-title" i18n="@@nav.link.diffuser">Diffuser</h1>
    <mat-button-toggle-group
      class="diffuser-onglets"
      [value]="onglet()"
      (change)="changerOnglet($event.value)"
      aria-label="Onglet de Diffuser"
      i18n-aria-label="@@diffuser.onglet.label"
    >
      <mat-button-toggle value="envoyer">
        <mat-icon>forward_to_inbox</mat-icon>
        <ng-container i18n="@@diffuser.onglet.envoyer">Envoyer</ng-container>
      </mat-button-toggle>
      <mat-button-toggle value="documents">
        <mat-icon>description</mat-icon>
        <ng-container i18n="@@diffuser.onglet.documents">Documents</ng-container>
      </mat-button-toggle>
    </mat-button-toggle-group>
    @if (onglet() === 'documents') {
      <app-documents-panel (reported)="output.set($event)" />
    } @else {
      <app-publication-panel (reported)="output.set($event)" (published)="bump()" />
      <app-envois-table [version]="version()" (reported)="output.set($event)" />
    }
    <app-output-panel [text]="output()" />
  `,
  styleUrl: './publication.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublicationPage {
  private readonly route = inject(ActivatedRoute);

  protected readonly output = signal('');
  protected readonly onglet = signal<OngletDiffuser>('envoyer');
  /** Bumped after a publication, so the table reads the new states. */
  protected readonly version = signal(0);

  constructor() {
    // Followed rather than read once: a link to another tab of this very
    // route reuses the component.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.onglet.set(readOngletDiffuser(params.get('onglet')));
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'envoyer' ? null : this.onglet(),
    }));
  }

  protected changerOnglet(onglet: OngletDiffuser): void {
    this.onglet.set(onglet);
  }

  protected bump(): void {
    this.version.update((n) => n + 1);
  }
}
