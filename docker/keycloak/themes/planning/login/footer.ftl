<#macro content>
<#--
  Le pied de carte du thème parent est vide et déclaré surchargeable : c'est le
  seul crochet de keycloak.v2 qui accepte du HTML sans réécrire template.ftl,
  et donc sans avoir à recomparer ce gabarit à chaque montée de version.

  On y pose la couleur d'accent lue dans l'environnement (KEYCLOAK_BRAND_ACCENT),
  en dernier dans le document : elle l'emporte sur planning.css ET sur brand.css.
  Seule une couleur hexadécimale passe — une variable d'environnement n'est pas
  un endroit d'où l'on injecte du CSS arbitraire dans une page de connexion.
-->
<#if properties.brandAccent?has_content && properties.brandAccent?matches("^#[0-9a-fA-F]{3,8}$")>
<style>:root { --planning-accent: ${properties.brandAccent}; }</style>
</#if>
</#macro>
