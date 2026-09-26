// The weight history of one rule, in the panel of its row on « Règles du
// planning »: every change of its weight or activation, the solves that
// followed with their score and this rule's violations, and a small chart of
// those violations with the changes as vertical markers. Juxtaposition, not
// causality — the referential may have moved between two solves as much as
// the weight did, and the view says so.

import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  resource,
} from '@angular/core';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { dosageLines, dosageSummary } from '../../core/dosage';
import { errorMessage } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { ResolutionUnderDosage } from '../../core/models';
import { errorText } from '../../core/resource-state';
import { StatusMessage } from '../../shared/status-message';
import { changeLabel, historyChart, historyItems, originLabel } from './weight-history';

@Component({
  selector: 'app-weight-history-view',
  imports: [MatProgressBarModule, StatusMessage],
  templateUrl: './weight-history-view.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WeightHistoryView {
  /** Technical name of the rule — the key of the API, never shown. */
  readonly name = input.required<string>();

  private readonly constraintsApi = inject(ConstraintsApi);

  private readonly history = resource({
    params: () => ({ name: this.name() }),
    loader: ({ params }) => this.constraintsApi.history(params.name),
  });
  protected readonly loading = this.history.isLoading;
  protected readonly error = errorText(this.history, errorMessage);

  protected readonly items = computed(() => {
    const history = this.history.hasValue() ? this.history.value() : null;
    return history ? historyItems(history) : [];
  });
  protected readonly chart = computed(() => historyChart(this.items()));
  protected readonly hasChanges = computed(() =>
    this.items().some((item) => item.kind === 'change'),
  );

  /** What the chart shows, for a screen reader: it is an image, the list below is the text. */
  protected readonly chartLabel = computed(() => {
    const chart = this.chart();
    if (!chart) {
      return '';
    }
    const values = chart.points.map((point) => point.violations).join(', ');
    return $localize`:@@weightHistory.chart.label:Écarts à la règle par résolution : ${values}:values:. ${chart.markers.length}:markers: changement(s) de réglage.`;
  });

  protected dateLabel(at: string): string {
    return at ? new Date(at).toLocaleString(intlLocale()) : '';
  }

  protected changeLabel = changeLabel;
  protected originLabel = originLabel;

  protected violationsLabel(resolution: ResolutionUnderDosage): string {
    return resolution.ruleViolations === null
      ? $localize`:@@weightHistory.violations.unmeasured:écarts non mesurés`
      : $localize`:@@weightHistory.violations:${resolution.ruleViolations}:count: écart(s) à cette règle`;
  }

  protected dosageLabel(resolution: ResolutionUnderDosage): string {
    return dosageSummary(resolution.dosage);
  }

  protected dosageDetail(resolution: ResolutionUnderDosage): string {
    return dosageLines(resolution.dosage).join(' · ');
  }
}
