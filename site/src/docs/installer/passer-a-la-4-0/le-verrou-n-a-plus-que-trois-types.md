---
ordre: 180
---

### Le verrou n'a plus que trois types

- **Avant (3.x)** : un verrou pouvait cibler un animateur sur tout l'événement (`ANIMATEUR`), un stand sur tout l'événement (`STAND`), une journée (`JOUR`), un créneau pour tous les stands (`CRENEAU`) ou un animateur sur un créneau (`ANIMATEUR_CRENEAU`, posé aussi par un échange accepté).
- **Après (4.0)** : trois types ([ADR 0109](repo:docs/decisions/0109-trois-types-de-verrou.md)) : animateur + créneau (`ANIMATEUR_CRENEAU`), stand + journée (`STAND_JOUR`), journée (`JOUR`). La migration `V151` convertit chaque ancien verrou en ce qu'il figeait sur le plan enregistré, pour que ce plan ne bouge pas à la prochaine résolution :

| Ancien verrou | Devient |
| --- | --- |
| `ANIMATEUR` | un verrou animateur + créneau par créneau où l'animateur tient une place |
| `STAND` | un verrou stand + journée par jour où le stand a des places |
| `CRENEAU` | un verrou animateur + créneau par animateur qui tient une place sur ce créneau |
| `JOUR`, `ANIMATEUR_CRENEAU` | inchangés |

  Un verrou dont le plan n'a aucune place correspondante ne figeait rien et disparaît. La règle `animateurVerrouilleFige` est retirée : un animateur ne se verrouille plus « partout », donc plus rien n'interdit de lui donner une place ailleurs, et les places vides ne se figent pas (comme avant). Le nombre de règles passe de 47 à 46. L'outil MCP `verrouiller` garde son nom ; son argument `type` prend `ANIMATEUR_CRENEAU`, `STAND_JOUR` ou `JOUR`, et l'API refuse les anciennes valeurs en `400`. La section `plan:` d'un fichier YAML (celle de la copie avant vidage) ne porte plus que ces trois types ; un fichier qui en nomme un ancien est lu avec la même conversion, et chaque verrou converti est signalé par un avertissement.
- **Ce que vous faites** : rien pour vos verrous. Les verrouillages des anciennes éditions sont convertis de même. Un script ou une invite qui posait un verrou `ANIMATEUR`, `STAND` ou `CRENEAU` doit poser un verrou par créneau ou par journée. Une copie avant vidage prise en 3.x qui en contient reste restaurable : ses verrous sont convertis à la lecture, de la même façon, sans que la copie rangée soit réécrite.
