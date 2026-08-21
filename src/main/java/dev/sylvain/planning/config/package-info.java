/**
 * Typed views over the application's own configuration, one interface per
 * {@code planning.*} / {@code observability.*} prefix.
 *
 * <p>These exist because {@code @ConfigProperty} scatters a group of related
 * settings across whichever classes happen to read them: the five
 * {@code planning.mcp.*} keys were declared twice, in two packages, and the three
 * {@code observability.*} ones likewise — two declarations that had to agree
 * on a default nothing checked. Worse, the same trap (<em>{@code Optional},
 * not {@code String}: SmallRye turns a blank value into null and fails
 * startup validation</em>) was re-discovered and re-commented at three
 * different injection points.</p>
 *
 * <p>With {@code @ConfigMapping} the {@code Optional} sits on the accessor,
 * where it belongs, the group has exactly one declaration, and a key that no
 * longer exists is a startup failure rather than a silent default. Quarkus
 * settings ({@code quarkus.*}) are <b>not</b> mapped here: they belong to the
 * framework, and the one or two we read are read where they are needed.</p>
 */
package dev.sylvain.planning.config;
