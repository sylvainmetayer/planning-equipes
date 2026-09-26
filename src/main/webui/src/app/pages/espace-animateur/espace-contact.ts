import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { ContactOrganisation, EspaceAnimateurView } from '../../core/models';

/**
 * The organisation's contact carried by the espace view — as Paramètres ›
 * Édition sets it —, trimmed; `null` when it names neither a phone nor an
 * address, so the espace never shows an empty « Organisation : ».
 */
export function contactOf(
  view: Pick<EspaceAnimateurView, 'contact'> | null,
): ContactOrganisation | null {
  const telephone = view?.contact?.telephone?.trim() || null;
  const email = view?.contact?.email?.trim() || null;
  return telephone || email ? { telephone, email } : null;
}

/**
 * « Organisation : 06 … · contact@… » — how to reach the people every
 * « rapprochez-vous de l'organisation » of the espace points at. In the page
 * footer (`inline` false) and right after those sentences (`inline`); renders
 * nothing when no contact is known.
 */
@Component({
  selector: 'app-espace-contact',
  imports: [MatIconModule],
  template: `@if (contact(); as contact) {
  <span class="espace-contact" [class.espace-contact-bloc]="!inline()">
    @if (!inline()) {
      <mat-icon inline aria-hidden="true">support_agent</mat-icon>
    }
    <span i18n="@@espace.contact.libelle">Organisation :</span>
    @if (contact.telephone; as tel) {
      <a [href]="'tel:' + tel">{{ tel }}</a>
    }
    @if (contact.telephone && contact.email) {
      <span aria-hidden="true">·</span>
    }
    @if (contact.email; as mail) {
      <a [href]="'mailto:' + mail">{{ mail }}</a>
    }
  </span>
}`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EspaceContact {
  private readonly espace = inject(EspaceAnimateurService);

  /** Right after a sentence rather than as the footer's own line. */
  readonly inline = input(false);

  protected readonly contact = computed(() => contactOf(this.espace.view()));
}
