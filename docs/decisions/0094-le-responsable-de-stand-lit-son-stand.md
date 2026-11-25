# 0094 — Le responsable de stand lit son stand : un droit de compte, des horaires sans nom par défaut

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : droits des comptes, planning publié, affichette
- **Prolonge** : [0093](0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md) (les comptes et leurs droits), [0072](0072-une-seule-edition-active.md) (l'édition active)

## Contexte

Les personnes qui tiennent un stand côté organisation veulent savoir quand il
est ouvert et où il reste des places à pourvoir, sans qu'on leur ouvre
l'administration ni qu'on leur donne les coordonnées de qui que ce soit. Deux
chemins étaient possibles : les faire passer par l'espace animateur (un jeton
dans une URL, le statut d'une fiche), ou leur donner un compte.

## Décision

1. **Un droit de plus sur un compte**, `RESPONSABLE_STAND`, accordé pour **une
   édition** et **un ou plusieurs de ses stands** (`habilitation_stand`).
   Un responsable se connecte comme tout organisateur, par le fournisseur OIDC
   ou un compte local : l'espace animateur reste celui des animateurs.
2. **Un rôle qui n'ouvre qu'un préfixe** : `responsable_stand` n'est dans
   aucune des politiques de l'administration, seulement dans `role-responsable`
   (`/api/responsable/*`). Tout le reste répond `403`.
3. **Lecture seule, édition active seulement, plan publié seulement.** La
   requête ne nomme ni édition ni stand : le service les déduit des droits en
   vigueur. Quand rien ne s'ouvre, la réponse est une vue vide, identique pour
   « pas à vous » et « n'existe pas ».
4. **Horaires et trous, sans nom, par défaut.** Les noms sont une décision de
   l'organisateur, prise quand le planning est stabilisé : un réglage par défaut
   de l'édition, une exception par stand dans les deux sens, chacun avec une
   date d'ouverture facultative avant laquelle seuls les horaires se lisent.
   Jamais d'adresse, de téléphone, d'âge ni de statut mineur.
5. **L'affichette est une autre mise en page de la même vue**, jamais une
   seconde lecture : elle ne peut pas dire plus que l'écran.
6. **Le périmètre d'un droit ne porte pas de clé étrangère vers `stand`**,
   comme `habilitation.edition_id` n'en porte pas vers `edition` : l'import d'un
   dump supprime puis recrée les stands, et une cascade effacerait les droits de
   l'instance qui reçoit. Un stand disparu n'ouvre rien.
7. **Les réglages de noms suivent l'édition** (export, duplication, suppression
   en cascade avec le stand), contrairement aux droits, qui sont ceux de
   personnes et ne se dupliquent pas.

## Alternatives écartées

- **Passer par l'espace animateur** : le jeton d'une fiche n'est pas un droit
  sur un stand, il ne se retire pas par édition ni ne s'expire, et mêler les
  deux ouvrait une porte vers les fiches.
- **Montrer les noms par défaut** : tant que le planning bouge, « deux
  animateurs sont prévus » est une promesse tenable, un nom ne l'est pas.
- **Un réglage par droit plutôt que par stand** : le choix d'afficher des noms
  est celui du stand et de la période, pas de la personne qui le regarde.

## Conséquences

- Accorder le droit demande l'édition et ses stands ; pour en changer, on retire
  le droit et on le redonne.
- Un responsable dont l'édition n'est pas (ou plus) active ne voit rien : la
  désactivation d'une édition coupe ses responsables sans autre geste.
- La relecture RGPD du registre est consignée dans `docs/rgpd.md` : une
  catégorie de destinataires interne de plus, bornée trois fois (stands, édition
  active, plan publié).
