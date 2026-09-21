import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  abonnementIcsUrl,
  espacePlanningIcsUrl,
  espacePlanningPdfUrl,
} from '../../core/api/espace-animateur-links';
import { parseDateKey } from '../../core/date-utils';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { errorMessage } from '../../core/error-message';
import { PauseAnimateurView, PosteAnimateurView } from '../../core/models';
import {
  aujourdhuiLocal,
  JourPlanning,
  maintenantEffectif,
  repereMaintenant,
} from './espace-maintenant';
import { lienCarte } from './lien-carte';
import { bandeLabel } from '../../core/consigne-wording';
import { typologieColorClass } from '../../core/typologie-colors';
import { keepViewInQueryParams } from '../../core/view-query-params';
import {
  axeFrise,
  dayHours,
  legendeTypologies,
  lignesFrise,
  pauseInsidePoste,
  statsPlanning,
} from './espace-apercu';
import { EQUIPE_NOMBREUSE, filterCoequipiers, coequipiersView } from './espace-coequipiers';
import { OngletEspace, readOngletEspace } from './espace-onglets';

/**
 * The animateur's own planning (issue #165): their seats from the last
 * persisted solve, with the teammates they will actually work alongside — the
 * same content as their PDF, always up to date.
 *
 * <p>Three tabs since issue #615, because this is the one screen of the product
 * read <b>during</b> the event, standing, on a phone, and one column of cards
 * from the first day to the last answered one question out of three. « Jour »
 * (the default) opens on today and carries the day strip, the state band and
 * the seats; « Aperçu » puts the whole fortnight on one screen; « Coéquipiers »
 * answers « suis-je avec quelqu'un ? ». The tab travels in `?onglet=` and the
 * day in `?jour=`, as everywhere else in this application (ADR 0012 / 0018), so
 * touching a row of the frieze opens that day and the address says which.</p>
 *
 * <p>Nothing was dropped on the way: the confirmation, what changed, the
 * agenda, the downloads and the échange button all kept their place under the
 * tab they belong to.</p>
 */
@Component({
  selector: 'app-espace-planning-page',
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    RouterLink,
  ],
  templateUrl: './espace-planning-page.html',
  styleUrl: './espace-planning-page.css',
  // Global by design (AGENTS.md): the espace's stylesheets are unscoped, like
  // the shell's own, so a selector means here what it meant as a partial.
  encapsulation: ViewEncapsulation.None,
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

  /**
   * The same planning folded onto one landscape sheet: a calendar on the
   * front, team-mates and places on the back. Offered beside the booklet
   * rather than instead of it — one is read page by page, the other fits in
   * a pocket, and which one that person wants is theirs to say.
   */
  protected readonly lienPdfFeuille = computed(() => {
    const jeton = this.espace.jeton();
    return jeton ? espacePlanningPdfUrl(jeton, 'feuille') : null;
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

  /* ---------------- The three tabs, and the day on screen (issue #615) --------------- */

  private readonly route = inject(ActivatedRoute, { optional: true });

  protected readonly onglet = signal<OngletEspace>('jour');

  /**
   * The day the « Jour » tab shows, `null` until the planning is read — the
   * espace opens on today during the event, on the first day before it, and
   * the address may name another one.
   */
  private readonly jourChoisi = signal<string | null>(null);

  constructor() {
    // Followed rather than read once, like every other tabbed page here: the
    // router reuses this component when one navigates to the espace again with
    // another tab — from the frieze, from a link printed on a PDF.
    this.route?.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.onglet.set(readOngletEspace(params.get('onglet')));
      const jour = params.get('jour');
      if (jour) {
        this.jourChoisi.set(jour);
      }
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'jour' ? null : this.onglet(),
      jour:
        this.jourAffiche()?.date === this.defaultDay() ? null : (this.jourAffiche()?.date ?? null),
    }));
  }

  protected changerOnglet(onglet: OngletEspace): void {
    this.onglet.set(onglet);
  }

  /**
   * Where the day strip opens: today while the event is on, otherwise the
   * first day still ahead. Once every day is behind, the <b>last</b> one —
   * « votre prochain poste » has nothing left to name, and the first day of a
   * finished event is the least useful answer there is.
   *
   * <p>The server stops sending a repère after the event, so « today » is
   * unknown then: an absent repère is what says the event is over, and the
   * fallback reads it that way rather than landing on day one.</p>
   */
  protected readonly defaultDay = computed(() => {
    const jours = this.jours();
    if (jours.length === 0) {
      return null;
    }
    // Today read directly, not through `repere()`: that one is null outside
    // the event — both before and after — and the two cases want opposite
    // answers. Comparing the date to the list tells them apart.
    const aujourdhui = this.aujourdhui();
    if (jours.some((jour) => jour.date === aujourdhui)) {
      return aujourdhui;
    }
    return jours.find((jour) => jour.date >= aujourdhui)?.date ?? jours[jours.length - 1].date;
  });

  /** The date the espace reads as today, the developer's frozen one included. */
  private readonly aujourdhui = computed(() =>
    aujourdhuiLocal(
      maintenantEffectif(
        this.maintenant(),
        this.espace.view()?.dateDuJourFigee ?? null,
        this.espace.view()?.heureDuJourFigee ?? null,
      ),
    ),
  );

  /** The day on screen: the one asked for while it exists, else the default one. */
  protected readonly jourAffiche = computed(() => {
    const jours = this.jours();
    const demande = this.jourChoisi();
    return (
      jours.find((jour) => jour.date === demande) ??
      jours.find((jour) => jour.date === this.defaultDay()) ??
      null
    );
  });

  protected choisirJour(date: string): void {
    this.jourChoisi.set(date);
  }

  /** Opens a day in the « Jour » tab — what touching a row of the frieze does. */
  protected openDay(date: string): void {
    this.choisirJour(date);
    this.onglet.set('jour');
  }

  /** The day before and the day after the one on screen, `null` at either end. */
  protected readonly jourPrecedent = computed(() => this.voisin(-1));
  protected readonly jourSuivant = computed(() => this.voisin(1));

  private voisin(pas: number): string | null {
    const jours = this.jours();
    const index = jours.findIndex((jour) => jour.date === this.jourAffiche()?.date);
    return index < 0 ? null : (jours[index + pas]?.date ?? null);
  }

  /** The chips of the day strip: one per day of the edition, rest days included. */
  protected readonly bandeJours = computed(() =>
    this.jours().map((jour) => ({
      date: jour.date,
      heures: dayHours(jour),
      repos: jour.repos || jour.postes.length === 0,
      aujourdhui: jour.date === this.repere()?.aujourdhui,
      consigne: this.datesSousConsigne().has(jour.date),
    })),
  );

  /** The dates a consigne governs, as a set — read by the strip and by the frieze. */
  private readonly datesSousConsigne = computed(
    () => new Set(this.consignes().map((consigne) => consigne.date)),
  );

  /** The consigne of the day on screen, `null` when its hours are the usual ones. */
  protected readonly consigneOfDay = computed(() => {
    const date = this.jourAffiche()?.date;
    return this.consignes().find((consigne) => consigne.date === date) ?? null;
  });

  /**
   * The seats already over: everything before the one being held, or before the
   * next one when none is.
   *
   * <p>Read off `repereMaintenant` rather than from a second reading of the
   * clock — the whole point of that helper is that « en cours », « prochain »
   * and « terminé » are one answer, and two readings of the hour would
   * eventually disagree on a shift that starts while the page is open.</p>
   */
  private readonly postesTermines = computed(() => {
    const repere = this.repere();
    const all = this.jours().flatMap((jour) => jour.postes);
    if (!repere) {
      return new Set<PosteAnimateurView>();
    }
    const premierVivant = repere.enCours ?? repere.prochain;
    const limite = premierVivant ? all.indexOf(premierVivant) : all.length;
    return new Set(all.slice(0, Math.max(limite, 0)));
  });

  /** True for a seat already over — the card is faded, never dropped. */
  protected isPosteTermine(poste: PosteAnimateurView): boolean {
    return this.postesTermines().has(poste);
  }

  protected isPosteOngoing(poste: PosteAnimateurView): boolean {
    return poste === this.repere()?.enCours;
  }

  /* -------------------------- « Aperçu » (issue #615) -------------------------- */

  protected readonly stats = computed(() => statsPlanning(this.jours()));
  protected readonly axe = computed(() => axeFrise(this.jours()));
  protected readonly frise = computed(() =>
    lignesFrise(
      this.jours(),
      this.axe(),
      this.repere()?.aujourdhui ?? null,
      this.datesSousConsigne(),
    ),
  );
  protected readonly legende = computed(() => legendeTypologies(this.jours()));

  /** `10:00` from the minutes a frieze graduation carries. */
  protected graduation(minutes: number): string {
    const heure = Math.floor(minutes / 60) % 24;
    return `${String(heure).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
  }

  /**
   * The colour class of a typologie — the same attribution as the admin views
   * and the individual PDF, derived from the typologie id itself rather than
   * from a table by stand name (`core/typologie-colors.ts`).
   */
  protected couleurTypologie(typologieId: string | null): string {
    return typologieColorClass(typologieId);
  }

  /* ----------------------- « Coéquipiers » (issue #615) ----------------------- */

  protected readonly recherche = signal('');

  private readonly teamView = computed(() => coequipiersView(this.jours()));

  protected readonly coequipiers = computed(() =>
    filterCoequipiers(this.teamView().coequipiers, this.recherche()),
  );

  protected readonly affluences = computed(() => this.teamView().affluences);

  /* ----------------------------- Shared helpers ----------------------------- */

  /** Hours worked on a day, as the day title and the frieze both print them. */
  protected dayHours(jour: JourPlanning): number {
    return dayHours(jour);
  }

  /**
   * What a screen reader announces for a chip of the day strip: the whole day,
   * spelled out. « lun. 14 / 3 h » is a glance, not a sentence.
   */
  protected libellePuce(puce: { date: string; heures: number; repos: boolean }): string {
    // `parseDateKey`, not `new Date(iso)`: the latter reads midnight UTC, and
    // west of it a screen reader would announce the day before the one the
    // chip shows.
    const jour = parseDateKey(puce.date).toLocaleDateString(undefined, {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
    });
    return puce.repos
      ? $localize`:@@espace.planning.bandeLabelRepos:${jour}:date:, repos`
      : $localize`:@@espace.planning.bandeLabelHeures:${jour}:date:, ${puce.heures}:heures: h`;
  }

  /** The same sentence for a row of the frieze, which is a button too. */
  protected libelleLigneFrise(ligne: { date: string; heures: number; repos: boolean }): string {
    return this.libellePuce(ligne);
  }

  /**
   * The breaks that fall inside one seat: same stand, and a window the seat
   * contains. They are read where they are taken — under the shift they cut
   * into — rather than gathered at the top of the day.
   */
  protected pausesWithinPoste(jour: JourPlanning, poste: PosteAnimateurView): PauseAnimateurView[] {
    return jour.pauses.filter((pause) => this.fallsWithin(pause, poste));
  }

  /**
   * The breaks of the day no seat on screen carries — a stand whose shift the
   * plan words differently, a window straddling two of them.
   *
   * <p>They close the day rather than being dropped: a legal break vanishing
   * from the one screen its holder reads is the defect, and no arrangement of
   * the cards is worth losing one over.</p>
   */
  protected pausesOrphelines(jour: JourPlanning): PauseAnimateurView[] {
    return jour.pauses.filter(
      (pause) => !jour.postes.some((poste) => this.fallsWithin(pause, poste)),
    );
  }

  private fallsWithin(pause: PauseAnimateurView, poste: PosteAnimateurView): boolean {
    return pauseInsidePoste(pause, poste);
  }

  /**
   * `10:00`, from the `10:00:00` the API sends.
   *
   * The server answers a `LocalTime`, seconds included; a planning read at a
   * glance has no use for them — the break line already trimmed them, the
   * shift line did not.
   */
  /**
   * A seat held by a crowd is named by its headcount, never by its roster —
   * the same rule the « Coéquipiers » tab applies, read from the same
   * threshold. Listing a hundred names answered nothing, and the string was
   * wide enough to push the whole page sideways on a phone.
   */
  protected equipeNombreuse(poste: { coequipiers: string[] }): boolean {
    return poste.coequipiers.length > EQUIPE_NOMBREUSE;
  }

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
