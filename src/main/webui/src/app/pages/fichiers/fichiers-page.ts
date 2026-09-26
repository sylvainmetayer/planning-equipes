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
import { ARCHIVE_FRAGMENT, ArchiveEvenementCard } from '../exports/archive-evenement-card';
import { ExporterPanel } from '../exports/exporter-panel';
import { ImporterPanel } from '../imports/importer-panel';
import { OngletFichiers, readOngletFichiers } from './fichiers';

/**
 * « Fichiers »: the two halves of one gesture — export, correct in the
 * spreadsheet, import back — and the archive that closes an edition, on one
 * page of three tabs chosen by `?onglet=importer|exporter|archive`.
 *
 * <p>« Importer » carries the referential imports, the scenario file, the
 * examples bundled with the application and the validator (its own card key,
 * `?cible=`); « Exporter » the CSV archive and the scenario file; « Archive »
 * the end-of-event ZIP the home screen links to once the event is past. The
 * former `/imports`, `/exports`, `/export-csv` and `/validateur-yaml`
 * addresses redirect here with their parameters.</p>
 *
 * <p>The page owns `onglet` and nothing else: the Importer tab writes its own
 * `cible` beside it (ADR 0012).</p>
 */
@Component({
  selector: 'app-fichiers-page',
  imports: [
    ArchiveEvenementCard,
    ExporterPanel,
    ImporterPanel,
    MatButtonToggleModule,
    MatIconModule,
  ],
  templateUrl: './fichiers-page.html',
  styleUrl: './fichiers-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partials it hosts.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FichiersPage {
  private readonly route = inject(ActivatedRoute);

  protected readonly onglet = signal<OngletFichiers>('importer');

  constructor() {
    // Followed rather than read once: « Aller à l'onglet Importer » and the
    // links of the other tabs navigate to this very route, and the router
    // reuses the component.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const demande = params.get('onglet');
      // The home screen's former link carried the archive as a fragment only.
      this.onglet.set(
        demande === null && this.route.snapshot.fragment === ARCHIVE_FRAGMENT
          ? 'archive'
          : readOngletFichiers(demande),
      );
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'importer' ? null : this.onglet(),
    }));
  }

  protected changerOnglet(onglet: OngletFichiers): void {
    this.onglet.set(onglet);
  }
}
