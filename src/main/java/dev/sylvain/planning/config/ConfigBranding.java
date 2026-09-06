package dev.sylvain.planning.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The identity this instance wears: the product name printed on every screen,
 * mail subject and document, the customer it is deployed for, and the images
 * and colours the interface and the PDFs are drawn with.
 *
 * <p>The commercial model is one instance per customer, so the brand is a
 * property of the <em>deployment</em>, not of the code: it comes from
 * environment variables, exactly like {@link ConfigMentionsLegales}, and never
 * from the database — there is no branding admin screen to keep in sync, and a
 * restart is the only moment a logo can change.</p>
 *
 * <p>Every value has a neutral, working default: an instance started with none
 * of these variables set is called "Planning Équipes", shows no logo at all
 * rather than someone else's, and draws its PDFs in a grey-blue palette that
 * belongs to nobody.</p>
 */
@ConfigMapping(prefix = "planning.branding")
public interface ConfigBranding {

    /** Product name: browser titles, mail subjects, PDF header, ICS producer, SQL dump header. */
    @WithDefault("Planning Équipes")
    String productName();

    /**
     * The customer this instance is deployed for, printed at the foot of the
     * PDFs ("Festival du jeu — Ville hôte", say). Blank
     * leaves the footer with only the generation date, which is honest rather
     * than wrong.
     */
    Optional<String> organisation();

    /**
     * URL of the logo the web interface shows in its toolbars and on the login
     * card. Relative to the application root ({@code logo.png} for a file
     * dropped next to the bundle) or absolute. Blank shows no image: the
     * product name alone, which is presentable and never someone else's mark.
     */
    Optional<String> logoUrl();

    /**
     * Accent colour of the web interface, any CSS colour. It feeds the
     * {@code --app-accent} custom property used by this application's own
     * stylesheets. Angular Material's own tonal palette is compiled into the
     * bundle by {@code mat.theme()} and does <b>not</b> follow it — recolouring
     * the Material components themselves needs a rebuild of
     * {@code src/material-theme.scss}.
     */
    Optional<String> accentColor();

    /**
     * URL of the deployment's mascot, shown full size by the Konami-code easter
     * egg. Same syntax as {@link #logoUrl()}. Blank disables the easter egg
     * outright: an empty frame is worse than no frame, and a mascot belongs to
     * a customer the way a logo does.
     */
    Optional<String> mascotUrl();

    /**
     * URL of the same mascot cut out small, spun in the toolbar while a solve
     * runs and stacked over the scroll hint. A separate image because it is
     * drawn at 20 to 24 pixels: the full-size illustration turns to mud there.
     * Blank falls back to a Material icon, which says the same thing without
     * borrowing anyone's mark.
     */
    Optional<String> mascotIconUrl();

    /**
     * Address the in-app help sends usage questions to (« Contact et support »).
     * Blank hides that paragraph and its link rather than printing an address
     * nobody answers: support is a service somebody actually renders, and a
     * white-label instance names its own.
     */
    Optional<String> supportEmail();

    Pdf pdf();

    /** What the exported documents are drawn with. */
    interface Pdf {

        /**
         * Logo of the PDF header. Either {@code classpath:/branding/xxx.png}
         * for an image bundled in the application, or a plain filesystem path
         * for one mounted next to the container. Blank prints the header
         * without any logo.
         */
        Optional<String> logo();

        /** Decorative band of the individual planning's first page, same syntax as {@link #logo()}. Blank prints none. */
        Optional<String> strip();

        Palette palette();
    }

    /**
     * The five colours the documents are built from, as CSS-style hex
     * ({@code #rrggbb}). Defaults are a neutral grey-blue.
     */
    interface Palette {

        /** Titles, names, table bodies: the darkest ink of the document. */
        @WithDefault("#1f2933")
        String headline();

        /** Secondary text: times, locations, teammates, footer. */
        @WithDefault("#6b7280")
        String muted();

        /** Brand accent: day badges, callout titles, card borders, shortfall warnings. */
        @WithDefault("#3a6ea5")
        String accent();

        /** Background of the statistic tiles and of the table header rows. */
        @WithDefault("#e4eaf1")
        String highlight();

        /** Background of the time pills, and colour of the table rules. */
        @WithDefault("#f1f4f8")
        String pill();
    }
}
