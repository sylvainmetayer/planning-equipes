import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import {
  abonnementIcsUrl,
  espacePlanningIcsUrl,
  espacePlanningPdfUrl,
} from '../../core/api/espace-animateur-links';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { errorMessage } from '../../core/error-message';
import { PauseAnimateurView, PosteAnimateurView } from '../../core/models';
import { JourPlanning, isPasse, maintenantEffectif, repereMaintenant } from './espace-maintenant';
import { lienCarte } from './lien-carte';
import { bandeLabel } from '../../core/consigne-wording';

/**
 * The animateur's own planning (issue #165): their seats from the last
 * persisted solve, one card per day, with the teammates they will actually
 * work alongside — the same content as their PDF, always up to date.
 */
@Component({
  selector: 'app-espace-planning-page',
  imports: [DatePipe, MatButtonModule, MatCardModule, MatIconModule],
  templateUrl: './espace-planning-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EspacePlanningPage {
  protected readonly espace = inject(EspaceAnimateurService);

  /* ------------- Days with hours modified by a consigne (issue #4) ------------- */

  /** One line per consigne on a day this person works: the band, worded, and the motif. */
  protected readonly consignes = computed(() =>
    (this.espace.view()?.consignes ?? []).map((consigne) => ({
      date: consigne.date,
      bande: bandeLabel(consigne.fermetureDebut, consigne.fermetureFin),
      motif: consigne.motif,
    })),
  );

  /* --------- « J'ai lu et je serai là » (issue #293) ---------- */

  protected readonly confirmationEnCours = signal(false);
  /** Message of a failed confirmation, `null` while everything is fine. */
  protected readonly erreurConfirmation = signal<string | null>(null);

  /**
   * The button only exists once something has been published AND this person
   * actually holds a seat in it.
   *
   * `publieLe` alone is not enough: it is the edition's publication instant,
   * not « this person has assignments ». Someone with no seat would be shown
   * « J'ai lu et je serai là » under an empty planning, and their answer —
   * accepted by the server before this check existed — showed as « — » on the
   * admin column. Being asked nothing is the honest state here; the server
   * refuses it too, this only avoids offering the gesture.
   */
  protected readonly confirmationDemandee = computed(() => {
    const view = this.espace.view();
    return !!view?.publieLe && view.postes.length > 0 && view.statutConfirmation !== 'CONFIRME';
  });

  protected async confirmer(): Promise<void> {
    this.confirmationEnCours.set(true);
    this.erreurConfirmation.set(null);
    try {
      await this.espace.confirmerPlanning();
    } catch (error) {
      this.erreurConfirmation.set(errorMessage(error));
    } finally {
      this.confirmationEnCours.set(false);
    }
  }

  /** Direct download links — the token in the URL is the whole credential. */
  protected readonly lienPdf = computed(() => {
    const jeton = this.espace.jeton();
    return jeton ? espacePlanningPdfUrl(jeton) : null;
  });
  protected readonly lienIcs = computed(() => {
    const jeton = this.espace.jeton();
    return jeton ? espacePlanningIcsUrl(jeton) : null;
  });

  /* ---------- Permanent calendar subscription (issue #324) ---------- */

  /**
   * The subscription address, as an absolute URL. Absolute on purpose: it is
   * meant to be pasted into a calendar application, which has no page to
   * resolve a relative path against.
   *
   * A different token from the one in the address bar, and a path of its own:
   * this one opens the calendar and nothing else, which is what makes it
   * tolerable to hand out to a third-party application.
   */
  protected readonly urlAbonnement = computed(() => {
    const token = this.espace.view()?.abonnementToken;
    return token ? abonnementIcsUrl(window.location.origin, token) : null;
  });

  /**
   * The same address in the scheme calendar clients register for: clicking it
   * offers to subscribe instead of downloading a file once. The copy button
   * next to it exists because several clients (Google Calendar above all) ask
   * for the address to be pasted rather than clicked.
   */
  protected readonly lienWebcal = computed(() => {
    const url = this.urlAbonnement();
    return url ? url.replace(/^https?:/, 'webcal:') : null;
  });

  protected readonly abonnementCopie = signal(false);
  /**
   * The subscription address, its copy button and its replacement are folded
   * away by default. They are set once and never read again, and the band they
   * live in now sits above the planning: unfolded, they would push the first
   * day below the fold on a phone — the very defect moving the block up was
   * meant to fix.
   */
  protected readonly abonnementDeplie = signal(false);
  protected readonly rotationEnCours = signal(false);
  /** True once the confirm step is showing: rotating breaks the subscriptions already registered. */
  protected readonly rotationADemander = signal(false);
  /** Message of a failed rotation or copy, `null` while everything is fine. */
  protected readonly erreurAbonnement = signal<string | null>(null);

  /**
   * Folding the panel back also drops a pending « replace this address »
   * confirmation: reopening the panel must not land straight on a destructive
   * button the animateur no longer remembers asking for.
   */
  protected basculerDetailsAbonnement(): void {
    const deplie = !this.abonnementDeplie();
    this.abonnementDeplie.set(deplie);
    if (!deplie) {
      this.rotationADemander.set(false);
    }
  }

  protected async copierAbonnement(): Promise<void> {
    const url = this.urlAbonnement();
    if (!url) {
      return;
    }
    // Both outcomes of the previous attempt go, not just the error: a copy
    // that fails after one that worked would otherwise leave « Adresse
    // copiée » on screen next to « Copie impossible », and the fallback the
    // error asks for — select the address by hand — is exactly what the
    // stale success tells the animateur not to bother with.
    this.erreurAbonnement.set(null);
    this.abonnementCopie.set(false);
    try {
      await navigator.clipboard.writeText(url);
      this.abonnementCopie.set(true);
    } catch {
      // No clipboard (insecure context, refusal): say so rather than
      // pretending it worked — the address is displayed right above.
      this.erreurAbonnement.set(
        $localize`:@@espace.planning.abonnementCopieEchec:Copie impossible : sélectionnez l'adresse ci-dessus.`,
      );
    }
  }

  protected async regenererAbonnement(): Promise<void> {
    this.rotationEnCours.set(true);
    this.erreurAbonnement.set(null);
    try {
      await this.espace.regenererAbonnement();
      this.rotationADemander.set(false);
      this.abonnementCopie.set(false);
    } catch (error) {
      this.erreurAbonnement.set(errorMessage(error));
    } finally {
      this.rotationEnCours.set(false);
    }
  }

  /**
   * PDF and one-shot ICS only mean something once there is a planning to take a
   * photograph of. The subscription, on the contrary, is offered right away:
   * subscribing ahead of the publication is the good gesture — the feed fills
   * itself.
   */
  protected readonly telechargementsOfferts = computed(
    () => this.jours().length > 0 && !!this.lienPdf(),
  );

  /* ---------- What changed for me (issue #532) ---------- */

  /**
   * The sentences of the last publication that concerned this animateur, as
   * the server stored them at send time.
   *
   * Not recomputed here, and not re-worded: they are the exact lines their
   * mail carried — the only copy of which used to be that mail, lost, filed as
   * spam, or never received by whoever opens the link printed on their PDF.
   */
  protected readonly changements = computed(() => this.espace.view()?.changements ?? []);

  /**
   * Once « j'ai lu et je serai là » is answered, the same list steps back: it
   * is no longer something to act on, and leaving it in an alert box above a
   * planning already acknowledged would make the box mean nothing the next
   * time it appears.
   */
  protected readonly changementsActes = computed(
    () => this.espace.view()?.statutConfirmation === 'CONFIRME',
  );

  /**
   * Folded by default: a republication touching ten seats put ten lines above
   * the first day card on a phone. The title carries the count, so folded
   * still says that something changed and how much.
   */
  protected readonly changementsDeplies = signal(false);

  protected basculerChangements(): void {
    this.changementsDeplies.update((deplie) => !deplie);
  }

  /* ---------- The « now » marker (issue #535) ---------- */

  /**
   * The browser's clock, read once.
   *
   * The page has no polling and takes none (the espace shell's own choice), so
   * this is a photograph taken when the page opened — which is what a planning
   * read while walking to a stand needs. A reload moves it.
   */
  private readonly maintenant = signal(new Date());

  /**
   * `null` outside the event: no head block rather than a misleading one. On
   * the server's frozen date and time when a developer set them — the moment
   * jour J is on.
   */
  protected readonly repere = computed(() =>
    repereMaintenant(
      this.jours(),
      maintenantEffectif(
        this.maintenant(),
        this.espace.view()?.dateDuJourFigee ?? null,
        this.espace.view()?.heureDuJourFigee ?? null,
      ),
    ),
  );

  /** The seat the head block is about: the one being held, else the one to come. */
  protected readonly posteRepere = computed(() => {
    const repere = this.repere();
    return repere?.enCours ?? repere?.prochain ?? null;
  });

  /**
   * The day cards to show unfolded: today and everything after it — plus
   * yesterday while a shift of its own is still running past midnight, which
   * is what `jourPlancher` carries.
   */
  protected readonly joursCourants = computed(() => {
    const repere = this.repere();
    return repere
      ? this.jours().filter((jour) => !isPasse(jour, repere.jourPlancher))
      : this.jours();
  });

  /**
   * The days already lived. Folded away, never dropped: a planning that stops
   * short reads as a bug, and somebody checking what they did on Friday must
   * still be able to.
   */
  protected readonly joursPasses = computed(() => {
    const repere = this.repere();
    return repere ? this.jours().filter((jour) => isPasse(jour, repere.jourPlancher)) : [];
  });

  protected readonly passesDeplies = signal(false);

  /** Everything on screen: today and after, plus the elapsed days once unfolded. */
  protected readonly joursAffiches = computed(() =>
    this.passesDeplies() ? this.jours() : this.joursCourants(),
  );

  protected basculerJoursPasses(): void {
    this.passesDeplies.update((deplie) => !deplie);
  }

  protected isJourPasse(jour: JourPlanning): boolean {
    const repere = this.repere();
    return !!repere && isPasse(jour, repere.jourPlancher);
  }

  protected isAujourdhui(jour: JourPlanning): boolean {
    return jour.date === this.repere()?.aujourdhui;
  }

  /**
   * `10:00`, from the `10:00:00` the API sends.
   *
   * The server answers a `LocalTime`, seconds included; a planning read at a
   * glance has no use for them — the break line already trimmed them, the
   * shift line did not.
   */
  protected heure(valeur: string | null): string {
    return valeur ? valeur.slice(0, 5) : '';
  }

  /** `https://www.openstreetmap.org/…` when the stand's emplacement is geocoded (issue #534). */
  protected lienEmplacement(lieu: {
    emplacementLatitude: number | null;
    emplacementLongitude: number | null;
  }): string | null {
    return lienCarte(lieu.emplacementLatitude, lieu.emplacementLongitude);
  }

  protected readonly jours = computed<JourPlanning[]>(() => {
    const parJour = new Map<string, PosteAnimateurView[]>();
    for (const poste of this.espace.view()?.postes ?? []) {
      const date = poste.date ?? '';
      const existants = parJour.get(date);
      if (existants) {
        existants.push(poste);
      } else {
        parJour.set(date, [poste]);
      }
    }
    const pausesParJour = new Map<string, PauseAnimateurView[]>();
    for (const pause of this.espace.view()?.pauses ?? []) {
      const existantes = pausesParJour.get(pause.date);
      if (existantes) {
        existantes.push(pause);
      } else {
        pausesParJour.set(pause.date, [pause]);
      }
    }
    const jours: JourPlanning[] = Array.from(parJour.entries()).map(([date, postes]) => ({
      date,
      postes,
      repos: false,
      pauses: (pausesParJour.get(date) ?? []).sort((a, b) => a.debut.localeCompare(b.debut)),
    }));
    // Rest days take their chronological place among the worked ones: a day
    // silently missing reads as an oversight, an explicit « Repos » card as a
    // decision. The server sends none for an animateur without any seat.
    for (const date of this.espace.view()?.joursRepos ?? []) {
      jours.push({ date, postes: [], repos: true, pauses: [] });
    }
    return jours.sort((a, b) => a.date.localeCompare(b.date));
  });
}
