# 0006 — Distinguer l'obligation légale de la politique de l'organisateur

- **Statut** : accepté, principe transverse
- **Date** : août 2026
- **Portée** : catalogue de contraintes, documentation, IHM

## Contexte

Le catalogue mélange deux familles de règles que rien ne distinguait au premier
regard :

- celles qui traduisent un **texte opposable** — durées maximales, repos
  quotidien et hebdomadaire, travail de nuit, jours fériés, dispositions propres
  aux jeunes travailleurs ;
- celles qui traduisent une **préférence de l'organisateur** — encadrement d'un
  mineur par un majeur, plafond d'heures sur l'ensemble de l'événement,
  enchaînement fermeture puis ouverture le lendemain.

La confusion est dangereuse dans les deux sens. Présenter une préférence comme
une obligation légale, c'est mentir à l'utilisateur et lui interdire un
arbitrage qui lui appartient. Présenter une obligation comme une préférence,
c'est lui laisser croire qu'il peut la désactiver sans conséquence.

## Décision

**Chaque contrainte déclare une catégorie qui dit sur quoi elle se fonde**, et
la documentation le rappelle quand ce n'est pas évident. Une règle sans
fondement légal n'est jamais rangée sous « Légal », même quand elle protège les
mineurs — la sécurité relève d'une catégorie distincte, et son intitulé le dit.

Corollaire tout aussi important : une règle qui *a* un fondement légal cite
l'article qui la fonde, dans sa description comme dans le code.

## Conséquences

- **Les catégories deviennent un contrat avec l'utilisateur**, pas un simple
  classement d'affichage. On ne peut plus en changer à la légère.
- Elles deviennent aussi un point d'appui exploitable : ce sont elles qui
  permettent de traiter différemment la désactivation d'une règle légale et
  celle d'une règle de confort — par exemple en exigeant une confirmation
  explicite pour la première.
- Une règle refusée reste une décision utile à consigner. Un plafond d'heures
  sur l'ensemble de l'événement a été écarté au motif qu'il était déjà couvert
  par les plafonds hebdomadaires : le prochain qui aura l'idée doit trouver la
  réponse, pas la reposer.
