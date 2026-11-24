import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_TARGET = (
    REPO_ROOT.parent / "Create+ Dev"
    if (REPO_ROOT.parent / "Create+ Dev").is_dir()
    else REPO_ROOT.parent / "Create+ (1)"
)
SYSTEMS_BUILD = REPO_ROOT / "scripts/createplus-systems/build.py"
SYSTEMS_JAR = REPO_ROOT / "server-mods/createplus-systems-1.0.0.jar"


def load_env(env_path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not env_path.is_file():
        return values
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            values[k.strip()] = v.strip().strip('"').strip("'")
    return values


def fetch_db_config_from_enxadahost(target_dir: Path) -> bool:
    dest = target_dir / "createplus-db.json"
    if dest.exists():
        print(f"DB config already present: {dest}")
        return True

    env = load_env(REPO_ROOT / ".env")
    api_key = env.get("ENXADA_API_KEY", "").strip()
    server_id = env.get("ENXADA_SERVER_ID", "b31b9e05").strip()
    if not api_key:
        print("Warning: ENXADA_API_KEY not found in .env; skipping automatic createplus-db.json download.")
        return False

    try:
        import requests
    except ImportError:
        print("Warning: 'requests' not installed; skipping automatic createplus-db.json download.")
        return False

    url = f"https://painel.enxadahost.com/api/client/servers/{server_id}/files/contents"
    r = requests.get(
        url,
        params={"file": "/boss-arena-db.json"},
        headers={"Authorization": f"Bearer {api_key}", "Accept": "application/json"},
        timeout=20,
    )
    if r.status_code == 200 and r.content.strip():
        dest.write_bytes(r.content)
        print(f"Downloaded MariaDB credentials to {dest} (outside Git repo).")
        return True

    print(f"Warning: could not fetch /boss-arena-db.json from EnxadaHost (HTTP {r.status_code}).")
    return False


def main():
    parser = argparse.ArgumentParser(description="Build and sync Create+ Systems into a local Modrinth test profile.")
    parser.add_argument("--target", type=Path, default=DEFAULT_TARGET, help="Target Modrinth profile directory.")
    parser.add_argument("--no-build", action="store_true", help="Skip rebuilding createplus-systems JAR.")
    parser.add_argument("--no-db", action="store_true", help="Skip fetching createplus-db.json from EnxadaHost.")
    args = parser.parse_args()

    if not args.target.is_dir():
        print(f"Instancia de teste nao encontrada em: {args.target}")
        print("No Modrinth App, clique nos '...' de 'Create+' -> Duplicate, renomeie para 'Create+ Dev' e rode novamente.")
        sys.exit(1)

    if not args.no_build:
        subprocess.run([sys.executable, str(SYSTEMS_BUILD)], check=True)

    if not SYSTEMS_JAR.is_file():
        raise SystemExit(f"JAR nao encontrado: {SYSTEMS_JAR}")

    target_mods = args.target / "mods"
    target_mods.mkdir(parents=True, exist_ok=True)
    dest_jar = target_mods / SYSTEMS_JAR.name
    shutil.copy2(SYSTEMS_JAR, dest_jar)
    print(f"Copied {SYSTEMS_JAR.name} -> {dest_jar}")

    if not args.no_db:
        fetch_db_config_from_enxadahost(args.target)

    print("Instancia 'Create+ Dev' pronta para testar! Abra no Modrinth App e teste /saldo, /loja, /vender e /ah.")


if __name__ == "__main__":
    main()
