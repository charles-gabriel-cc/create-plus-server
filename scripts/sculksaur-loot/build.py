import argparse
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


SOURCE = Path(__file__).resolve().parent / "src"
PROFILE = SOURCE.parents[2]


def main():
    parser = argparse.ArgumentParser(description="Package Create+ Sculksaur Loot, a data-only mod (no Java).")
    parser.add_argument("--output", type=Path, default=PROFILE / "mods/createplus-sculksaur-loot-1.0.0.jar")
    args = parser.parse_args()
    files = sorted(path for path in SOURCE.rglob("*") if path.is_file())
    for path in files:
        if path.suffix == ".json":
            json.loads(path.read_text(encoding="utf-8"))  # a malformed modifier is only logged by the game, then skipped
    with ZipFile(args.output, "w", ZIP_DEFLATED) as jar:
        for path in files:
            jar.write(path, path.relative_to(SOURCE).as_posix())
    print(f"Built {args.output}")


if __name__ == "__main__":
    main()
