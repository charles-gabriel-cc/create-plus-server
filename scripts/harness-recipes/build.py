import argparse
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


SOURCE = Path(__file__).resolve().parent / "src"
PROFILE = SOURCE.parents[2]

DISABLED_RECIPE = {
    "neoforge:conditions": [
        {"type": "neoforge:false"}
    ],
    "type": "minecraft:crafting_shapeless",
    "ingredients": [],
    "result": {"id": "minecraft:barrier"}
}

DISABLED_PATHS = (
    "data/adventurers_harness/recipe/iron_harness_arm.json",
    "data/adventurers_harness/recipe/iron_harness.json",
    "data/adventurers_harness/recipe/diamond_harness_arm.json",
    "data/adventurers_harness/recipe/diamond_harness.json",
    "data/adventurers_harness/recipe/netherite_harness_smithing.json",
)

HARDENED_RECIPES = {
    "data/create_adventurers_harness/recipe/andesite_harness_arm.json": {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": [
            "ISI",
            "CPC",
            "AGA"
        ],
        "key": {
            "I": {"item": "create:iron_sheet"},
            "S": {"item": "create:shaft"},
            "C": {"item": "create:andesite_casing"},
            "P": {"item": "create:mechanical_piston"},
            "A": {"item": "create:andesite_alloy"},
            "G": {"item": "create:large_cogwheel"}
        },
        "result": {"id": "create_adventurers_harness:andesite_harness_arm"}
    },
    "data/create_adventurers_harness/recipe/andesite_harness.json": {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": [
            "A A",
            "ABA",
            "CLC"
        ],
        "key": {
            "A": {"item": "create_adventurers_harness:andesite_harness_arm"},
            "B": {"item": "create:copper_backtank"},
            "C": {"item": "create:copper_sheet"},
            "L": {"item": "minecraft:leather_chestplate"}
        },
        "result": {"id": "create_adventurers_harness:andesite_harness"}
    },
    "data/create_adventurers_harness/recipe/brass_harness_arm.json": {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": [
            "BMB",
            "EAE",
            "CSC"
        ],
        "key": {
            "B": {"item": "create:brass_sheet"},
            "M": {"item": "create:precision_mechanism"},
            "E": {"item": "create:electron_tube"},
            "A": {"item": "create:mechanical_arm"},
            "C": {"item": "create:brass_casing"},
            "S": {"item": "create:sturdy_sheet"}
        },
        "result": {"id": "create_adventurers_harness:brass_harness_arm"}
    },
    "data/create_adventurers_harness/recipe/brass_harness.json": {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": [
            "AAA",
            "AHA",
            "SNS"
        ],
        "key": {
            "A": {"item": "create_adventurers_harness:brass_harness_arm"},
            "H": {"item": "create_adventurers_harness:andesite_harness"},
            "S": {"item": "create:sturdy_sheet"},
            "N": {"item": "create:netherite_backtank"}
        },
        "result": {"id": "create_adventurers_harness:brass_harness"}
    },
    "data/adventurers_harness/recipe/hud_visor.json": {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": [
            "BGB",
            "EME",
            " T "
        ],
        "key": {
            "B": {"item": "create:brass_sheet"},
            "G": {"item": "create:goggles"},
            "E": {"item": "create:electron_tube"},
            "M": {"item": "create:precision_mechanism"},
            "T": {"item": "minecraft:tinted_glass"}
        },
        "result": {"id": "adventurers_harness:hud_visor"}
    },
}


def main():
    parser = argparse.ArgumentParser(
        description="Package Create+ Harness Recipes (disables non-Create harnesses and hardens Create harness recipes)."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=PROFILE / "mods/createplus-harness-recipes-1.0.0.jar",
    )
    args = parser.parse_args()

    # Materialize recipes under src/ so they are inspectable in Git
    for rel in DISABLED_PATHS:
        target = SOURCE / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(DISABLED_RECIPE, indent=2) + "\n", encoding="utf-8")

    for rel, recipe in HARDENED_RECIPES.items():
        target = SOURCE / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(recipe, indent=2) + "\n", encoding="utf-8")

    files = sorted(path for path in SOURCE.rglob("*") if path.is_file())
    for path in files:
        if path.suffix == ".json":
            json.loads(path.read_text(encoding="utf-8"))

    with ZipFile(args.output, "w", ZIP_DEFLATED) as jar:
        for path in files:
            jar.write(path, path.relative_to(SOURCE).as_posix())
    print(f"Built {args.output} ({len(files)} files)")


if __name__ == "__main__":
    main()
