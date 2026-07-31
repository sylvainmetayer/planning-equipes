#!/usr/bin/env python3
"""Génère un scénario de festival compatible avec scenario-complet.yaml.

Le fichier produit respecte la structure attendue par le chargeur Java
(PlanningService#chargerScenarioYaml) : les sections festival, creneaux,
emplacements, stands, animateurs et postes. Chaque poste référence un
standId et un creneauId existants et laisse animateurId vide (le solveur
l'affectera). Environ 80% des stands sont rattachés à un emplacement.

Le fichier est écrit dans le dossier "scenarios", à côté de
scenario-complet.yaml, afin d'être immédiatement sélectionnable depuis
l'interface (liste déroulante "Generate sample planning").
"""

import argparse
import random
from datetime import datetime, timedelta
from pathlib import Path

import yaml

# --- Valeurs autorisées par le modèle Java (doivent rester synchronisées avec
# les enums TypologieJeu / NiveauCompetence) ---
TYPOLOGIES = ["ROLE", "STRATEGIE", "COOPERATIF", "ENFANT", "ENIGME", "ADRESSE", "AMBIANCE"]
NIVEAUX = ["DEBUTANT", "AUTONOME", "REFERENT"]
# Part des animateurs qui sont managers (encadrent d'autres animateurs).
PART_MANAGERS = 0.1
# Part des stands rattachés à un emplacement géolocalisé (les autres restent
# sans emplacement, comme le permet le modèle Java).
PART_STANDS_AVEC_EMPLACEMENT = 0.8

# Emplacements géolocalisés dans un centre-ville fictif, mêmes lieux que le
# jeu de données de scenario-complet.yaml / V9__emplacement.sql.
EMPLACEMENTS = [
    {"id": "PLACE-DRAPEAU", "nom": "Place du Drapeau", "latitude": 46.6513, "longitude": 2.2492},
    {"id": "MAIRIE", "nom": "Mairie centrale", "latitude": 46.6490, "longitude": 2.2547},
    {"id": "CHATEAU", "nom": "Château", "latitude": 46.6517, "longitude": 2.2481},
    {"id": "PLACE-11-NOVEMBRE", "nom": "Place du 11 Novembre", "latitude": 46.6480, "longitude": 2.2555},
    {"id": "HALLE-AUX-GRAINS", "nom": "Halle aux Grains", "latitude": 46.6499, "longitude": 2.2529},
    {"id": "JARDIN-DUGUESCLIN", "nom": "Jardin Duguesclin", "latitude": 46.6528, "longitude": 2.2503},
    {"id": "PONT-MEDIEVAL", "nom": "Pont médiéval", "latitude": 46.6522, "longitude": 2.2469},
    {"id": "ESPLANADE-FRANCOIS-MITTERRAND", "nom": "Esplanade François Mitterrand",
     "latitude": 46.6472, "longitude": 2.2518},
]

# Créneaux journaliers, identiques à ceux de scenario-complet.yaml.
CRENEAUX_JOURNALIERS = [
    ("MATIN", "08:00", "12:00"),
    ("APREM", "14:00", "20:00"),
    ("SOIREE", "20:00", "00:00"),
]

PRENOMS = ["Nina", "Jules", "Berenice", "Iris", "Adrien", "Karim", "Tania",
           "Thibault", "Noemie", "Yasmine", "Olivier", "William", "Elise",
           "Wafa", "Emma", "Gabrielle", "Marc", "Diane", "Remi", "Sarah"]
NOMS = ["Blanc", "Roux", "Richard", "Henry", "Petit", "Michel", "Garcia",
        "Simon", "Muller", "Morin", "Fontaine", "Moreau", "David", "Leroy",
        "Perrin", "Rousseau", "Laurent", "Bernard", "Durand", "Thomas"]


def generer_scenario(date_debut_str, nb_jours, nb_stands, nb_animateurs, seed=None):
    if seed is not None:
        random.seed(seed)

    date_debut = datetime.strptime(date_debut_str, "%Y-%m-%d")

    data = {
        "festival": {"dateDebut": date_debut_str},
        "creneaux": [],
        "emplacements": [dict(emplacement) for emplacement in EMPLACEMENTS],
        "stands": [],
        "animateurs": [],
        "postes": [],
    }

    # --- 1. CRÉNEAUX ---
    for jour in range(1, nb_jours + 1):
        current_date = (date_debut + timedelta(days=jour - 1)).strftime("%Y-%m-%d")
        for suffixe, h_debut, h_fin in CRENEAUX_JOURNALIERS:
            data["creneaux"].append({
                "id": f"J{jour}-{suffixe}",
                "jour": jour,
                "date": current_date,
                "heureDebut": h_debut,
                "heureFin": h_fin,
            })

    # --- 2. STANDS ---
    for i in range(1, nb_stands + 1):
        effectif_min = random.randint(2, 4)
        effectif_max = effectif_min + random.randint(0, 2)
        typologies = random.sample(TYPOLOGIES, k=random.randint(1, 2))
        stand = {
            "id": f"STAND-{i:03d}",
            "nom": f"Stand {typologies[0].capitalize()} {i:03d}",
            "typologiesProposees": typologies,
            "effectifMin": effectif_min,
            "effectifMax": effectif_max,
            "reserveMajeurs": random.choice([True, False, False]),
        }
        if random.random() < PART_STANDS_AVEC_EMPLACEMENT:
            stand["emplacementId"] = random.choice(EMPLACEMENTS)["id"]
        data["stands"].append(stand)

    # --- 3. ANIMATEURS ---
    # Plage de dates pour des animateurs de 16 à 66 ans lors du festival.
    dob_start = datetime.strptime("1960-01-01", "%Y-%m-%d")
    dob_end = datetime.strptime("2010-12-31", "%Y-%m-%d")
    dob_delta_days = (dob_end - dob_start).days

    for i in range(1, nb_animateurs + 1):
        manager = random.random() < PART_MANAGERS
        # Une à trois compétences distinctes, chacune avec son niveau.
        nb_competences = random.randint(1, 3)
        competences = {
            typo: random.choice(NIVEAUX)
            for typo in random.sample(TYPOLOGIES, k=nb_competences)
        }

        jours_indispos = []
        nb_jours_indispo = random.randint(0, 3)
        if nb_jours_indispo:
            jours_indispos = sorted({
                (date_debut + timedelta(days=random.randint(0, nb_jours - 1))).strftime("%Y-%m-%d")
                for _ in range(nb_jours_indispo)
            })

        random_dob = dob_start + timedelta(days=random.randint(0, dob_delta_days))

        data["animateurs"].append({
            "id": f"A{i}",
            "prenom": random.choice(PRENOMS),
            "nom": random.choice(NOMS),
            "dateNaissance": random_dob.strftime("%Y-%m-%d"),
            "manager": manager,
            "competences": competences,
            "joursIndisponibles": jours_indispos,
        })

    # --- 4. POSTES ---
    # Pour chaque stand et chaque créneau, on crée effectifMin postes vides
    # (animateurId non renseigné) : ce sont les sièges que le solveur remplit.
    compteur_poste = 0
    for stand in data["stands"]:
        for creneau in data["creneaux"]:
            for _ in range(stand["effectifMin"]):
                compteur_poste += 1
                data["postes"].append({
                    "id": f"P{compteur_poste}",
                    "standId": stand["id"],
                    "creneauId": creneau["id"],
                    "animateurId": None,
                })

    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date-debut", default="2026-07-08", help="Date de début (YYYY-MM-DD)")
    parser.add_argument("--jours", type=int, default=15, help="Nombre de jours du festival")
    parser.add_argument("--stands", type=int, default=120, help="Nombre de stands")
    parser.add_argument("--animateurs", type=int, default=250, help="Nombre d'animateurs")
    parser.add_argument("--seed", type=int, default=None, help="Graine aléatoire (reproductibilité)")
    parser.add_argument("--nom", default="festival.yaml", help="Nom du fichier de scénario généré")
    args = parser.parse_args()

    data = generer_scenario(args.date_debut, args.jours, args.stands, args.animateurs, args.seed)

    # Écrit dans le dossier "scenarios", à côté de scenario-complet.yaml.
    # Le script vit dans src/main/resources ; scenario-complet.yaml dans
    # src/main/resources/scenarios.
    scenarios_dir = Path(__file__).resolve().parent / "scenarios"
    scenarios_dir.mkdir(parents=True, exist_ok=True)
    output_path = scenarios_dir / args.nom

    with output_path.open("w", encoding="utf-8") as f:
        yaml.dump(data, f, default_flow_style=False, sort_keys=False, allow_unicode=True)

    nb_stands_avec_emplacement = sum(1 for stand in data["stands"] if "emplacementId" in stand)
    print(f"Fichier {output_path} généré avec succès !")
    print(f"  {len(data['creneaux'])} créneaux, {len(data['emplacements'])} emplacements, "
          f"{len(data['stands'])} stands ({nb_stands_avec_emplacement} avec emplacement), "
          f"{len(data['animateurs'])} animateurs, {len(data['postes'])} postes.")


if __name__ == "__main__":
    main()
