import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { EditionsApi } from '../../core/api/editions-api';
import { EditionStore } from '../../core/edition.store';
import { errorPrefix } from '../../core/error-message';
import { DeltaChange, DeltaLine, DeltaSide } from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import {
  currentViewParams,
  keepViewInQueryParams,
  optionalParam,
} from '../../core/view-query-params';
import { OutputPanel } from '../../shared/output-panel';
import { StatusMessage } from '../../shared/status-message';
import {
  ENTITY_FAMILIES,
  EntityFamily,
  TIMESLOT_URL,
  animateurChangeLabel,
  changeLabel,
  entityLines,
  familyTitle,
  ficheUrl,
  fieldsLabel,
  flaggedByName,
  hours,
  matchLabel,
  matchedByName,
  ratio,
  signed,
  summaryParts,
  valueLabel,
  valueText,
} from './comparer-editions';

/**
 * « Comparer deux éditions » : what changed in the referential from edition
 * A to edition B — stands, animateurs, game categories, locations, day
 * templates, the grid aligned on the opening days, settings, adjustments —
 * and the Volumétrie of both side by side, read from
 * `GET /api/editions/{a}/delta/{b}`. Read-only.
 *
 * Served without a menu entry, opened from the Éditions page; `?a=&b=` keep
 * the pair (ADR 0012). Every line opens its fiche in the edition holding it,
 * which switches this browser's edition — the link says so. Animateurs are
 * named here, on the administrator's screen, and nowhere else: the CSV and
 * the assistant carry ids.
 */
@Component({
  selector: 'app-comparer-editions-page',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    OutputPanel,
    StatusMessage,
  ],
  templateUrl: './comparer-editions-page.html',
  styleUrl: './comparer-editions-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparerEditionsPage {
  protected readonly store = inject(EditionStore);
  private readonly api = inject(EditionsApi);
  private readonly router = inject(Router);

  private readonly params = currentViewParams();
  private readonly chosenReference = signal<string | null>(optionalParam(this.params.get('a')));
  private readonly chosenTarget = signal<string | null>(optionalParam(this.params.get('b')));

  /** B defaults to the edition this tab works in, A to the first other one. */
  protected readonly targetId = computed(
    () => this.chosenTarget() ?? this.store.courant()?.id ?? null,
  );
  protected readonly referenceId = computed(
    () =>
      this.chosenReference() ??
      this.store.editions().find((edition) => edition.id !== this.targetId())?.id ??
      null,
  );

  private readonly deltaResource = resource({
    params: () => {
      const reference = this.referenceId();
      const target = this.targetId();
      return reference && target ? { reference, target } : undefined;
    },
    loader: ({ params }) => this.api.delta(params.reference, params.target),
  });
  protected readonly delta = retainedValue(this.deltaResource);
  protected readonly chargement = this.deltaResource.isLoading;
  protected readonly erreur = errorText(this.deltaResource);

  protected readonly resume = computed(() => {
    const delta = this.delta();
    return delta ? summaryParts(delta) : [];
  });
  protected readonly nameMatches = computed(() => {
    const delta = this.delta();
    return delta ? matchedByName(delta) : [];
  });

  protected readonly exportBusy = signal(false);
  protected readonly output = signal('');

  protected readonly families = ENTITY_FAMILIES;
  protected readonly entityLines = entityLines;
  protected readonly familyTitle = familyTitle;
  protected readonly matchLabel = matchLabel;
  protected readonly flaggedByName = flaggedByName;
  protected readonly fieldsLabel = fieldsLabel;
  protected readonly valueLabel = valueLabel;
  protected readonly valueText = valueText;

  constructor() {
    keepViewInQueryParams(() => ({
      a: optionalParam(this.referenceId()),
      b: optionalParam(this.targetId()),
    }));
  }

  protected chooseReference(id: string): void {
    this.chosenReference.set(optionalParam(id));
  }

  protected chooseTarget(id: string): void {
    this.chosenTarget.set(optionalParam(id));
  }

  protected swap(): void {
    const reference = this.referenceId();
    this.chosenReference.set(this.targetId());
    this.chosenTarget.set(reference);
  }

  protected change(family: EntityFamily, change: DeltaChange): string {
    return family === 'ANIMATEUR' ? animateurChangeLabel(change) : changeLabel(change);
  }

  /** A line's label, its code beside it when it has one. */
  protected lineLabel(line: DeltaLine): string {
    const label = line.label ?? line.targetId ?? line.referenceId ?? '';
    return line.code && line.code !== label ? `${label} (${line.code})` : label;
  }

  /** The edition a line opens in: B, but A for a row B no longer has. */
  protected sideOf(
    line: { change: DeltaChange },
    delta: { reference: DeltaSide; target: DeltaSide },
  ) {
    return line.change === 'REMOVED' ? delta.reference : delta.target;
  }

  protected entityUrl(family: EntityFamily, line: DeltaLine): string | null {
    return ficheUrl(family, line.change === 'REMOVED' ? line.referenceId : line.targetId);
  }

  /** The grid of the edition holding the line: a timeslot has no fiche of its own. */
  protected readonly timeslotUrl = TIMESLOT_URL;

  /** Whether opening a line of `edition` switches this browser's edition — the link says so then only. */
  protected switchesEdition(edition: DeltaSide): boolean {
    return edition.id !== this.store.courant()?.id;
  }

  /**
   * Opens `url` in `edition`: a plain navigation when it is the one this tab
   * works in, a switch of this browser's edition otherwise.
   */
  protected open(edition: DeltaSide, url: string): void {
    if (!this.switchesEdition(edition)) {
      void this.router.navigateByUrl(url);
      return;
    }
    this.store.openIn(edition.id, url);
  }

  /** The Volumétrie of both editions, row by row, and the gap of B over A. */
  protected readonly volumes = computed(() => {
    const delta = this.delta();
    if (!delta) {
      return [];
    }
    const a = delta.referenceVolumes;
    const b = delta.targetVolumes;
    const row = (label: string, before: number, after: number, format: (v: number) => string) => ({
      label,
      reference: format(before),
      target: format(after),
      gap: signed(Math.round(after - before)),
    });
    return [
      row(
        $localize`:@@comparerEditions.volumes.sieges:Sièges à pourvoir`,
        a.posteCount,
        b.posteCount,
        String,
      ),
      row(
        $localize`:@@comparerEditions.volumes.heures:Heures à pourvoir`,
        a.hoursToFill,
        b.hoursToFill,
        hours,
      ),
      row(
        $localize`:@@comparerEditions.volumes.disponibles:Heures disponibles (plafond légal)`,
        a.hoursAvailable,
        b.hoursAvailable,
        hours,
      ),
      row(
        $localize`:@@comparerEditions.volumes.animateurs:Animateurs`,
        a.animateurCount,
        b.animateurCount,
        String,
      ),
      {
        label: $localize`:@@comparerEditions.volumes.taux:Taux de remplissage`,
        reference: ratio(a.fillRatio),
        target: ratio(b.fillRatio),
        gap:
          a.fillRatio === null || b.fillRatio === null
            ? '—'
            : $localize`:@@comparerEditions.volumes.points:${signed(Math.round((b.fillRatio - a.fillRatio) * 100))}:n: points`,
      },
    ];
  });

  protected async exporter(): Promise<void> {
    const reference = this.referenceId();
    const target = this.targetId();
    if (!reference || !target) {
      return;
    }
    this.exportBusy.set(true);
    this.output.set($localize`:@@comparerEditions.exporting:Construction de l'export CSV...`);
    try {
      this.output.set(await this.api.exportDeltaCsv(reference, target));
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.exportBusy.set(false);
    }
  }
}
