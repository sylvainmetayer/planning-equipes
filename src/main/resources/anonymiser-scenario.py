#!/usr/bin/env python3
"""Dérive une fixture versionnable d'un scénario portant des données réelles.

Les scénarios réels (`docs/reel-*.yaml`, `scenarios/reel-*.yaml`) sont exclus
du dépôt par `.gitignore` : ils portent des prénoms, des noms et des lieux
réels. Ce script en produit une copie que l'on peut committer, et qui reste
utilisable comme jeu de démonstration et comme test de convergence.

Ne sont réécrits que les identifiants : prénoms et noms des animateurs, ids et
noms des emplacements et des stands, nom de l'édition. Tout ce que le solveur
lit — dates, heures, effectifs, compétences, horaires, typologies, paramètres —
est recopié tel quel, parce qu'une fixture qui ne converge pas comme sa source
ne teste pas ce qu'elle prétend tester.

Deux précautions dictées par le solveur :

- les coordonnées sont TRANSLATÉES EN LONGITUDE, pas supprimées. Une contrainte
  de qualité pénalise deux emplacements distants de plus d'un seuil
  (`QualiteConstraints`, `Emplacement.distanceMetresVers`) : les retirer
  changerait le score. À latitude et delta de longitude inchangés, la haversine
  rend exactement les mêmes distances ;
- les ids anonymes sont NUMÉROTÉS DANS L'ORDRE D'APPARITION et l'ordre des
  sections est préservé. Timefold épingle `randomSeed=0`, mais un tri ou un
  hachage s'appuyant sur les ids ferait diverger la trajectoire de recherche ;
  à cardinalité et ordre identiques, il n'y a rien à faire diverger.

Les commentaires de la source sont perdus (SnakeYAML/PyYAML ne fait pas de
round-trip) : l'en-tête ci-dessous les remplace, et dit que le fichier est
généré.

Usage :

    python3 src/main/resources/anonymiser-scenario.py \\
        docs/reel-1708.yaml \\
        src/main/resources/scenarios/festival-realiste.yaml \\
        --edition-id festival-realiste --edition-nom "Festival réaliste"
"""

import argparse
import sys

import yaml

# Centre-ville -> centre de la France, à latitude constante pour que les distances
# entre emplacements soient rigoureusement conservées.
DECALAGE_LONGITUDE = 2.5

ENTETE = """# yaml-language-server: $schema=../../../../docs/schema/scenario-schema.json
# Fixture anonymisée, GÉNÉRÉE par src/main/resources/anonymiser-scenario.py
# depuis un scénario réel hors dépôt. Ne pas éditer ici ce qui devrait l'être
# dans la source : la prochaine régénération écrase ce fichier.
#
# Seuls les identifiants ont changé — prénoms/noms des animateurs, ids et noms
# des emplacements et des stands, nom de l'édition. Dates, heures, effectifs,
# compétences, horaires et paramètres sont ceux de l'original, et les
# coordonnées sont translatées en longitude à latitude constante : la
# contrainte de distance entre emplacements voit exactement les mêmes valeurs.
# Cette fixture doit donc converger comme sa source.
"""


def anonymiser(donnees, edition_id, edition_nom):
    emplacements = {}
    for i, emplacement in enumerate(donnees.get("emplacements", []), start=1):
        nouveau = f"EMP-{i:02d}"
        emplacements[emplacement["id"]] = nouveau
        emplacement["id"] = nouveau
        emplacement["nom"] = f"Emplacement {i:02d}"
        if emplacement.get("longitude") is not None:
            emplacement["longitude"] = round(emplacement["longitude"] + DECALAGE_LONGITUDE, 4)

    for i, stand in enumerate(donnees.get("stands", []), start=1):
        stand["id"] = f"STAND-{i:02d}"
        stand["nom"] = f"Stand {i:02d}"
        if stand.get("emplacementId") is not None:
            stand["emplacementId"] = emplacements[stand["emplacementId"]]

    for animateur in donnees.get("animateurs", []):
        # Le nom reprend l'id : une violation affichée « Animateur A17 (A17) »
        # reste lisible dans un échec de test, sans rien identifier.
        animateur["prenom"] = "Animateur"
        animateur["nom"] = animateur["id"]

    donnees["edition"] = {"id": edition_id, "nom": edition_nom}
    return donnees


def main():
    parseur = argparse.ArgumentParser(description=__doc__,
                                      formatter_class=argparse.RawDescriptionHelpFormatter)
    parseur.add_argument("source", help="Scénario réel à anonymiser (hors dépôt)")
    parseur.add_argument("cible", help="Fixture à écrire")
    parseur.add_argument("--edition-id", required=True)
    parseur.add_argument("--edition-nom", required=True)
    args = parseur.parse_args()

    with open(args.source, encoding="utf-8") as fichier:
        donnees = yaml.safe_load(fichier)

    anonymiser(donnees, args.edition_id, args.edition_nom)

    with open(args.cible, "w", encoding="utf-8") as fichier:
        fichier.write(ENTETE)
        yaml.safe_dump(donnees, fichier, sort_keys=False, allow_unicode=True,
                       default_flow_style=False, width=100)

    resume = {cle: (len(valeur) if isinstance(valeur, (list, dict)) else valeur)
              for cle, valeur in donnees.items()}
    print(f"{args.cible} : {resume}", file=sys.stderr)


if __name__ == "__main__":
    main()
