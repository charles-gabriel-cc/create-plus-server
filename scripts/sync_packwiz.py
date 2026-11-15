import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tomllib
import zipfile
import requests

MANAGED_FOLDERS = ("mods", "resourcepacks", "shaderpacks")
FOLDER_EXTENSIONS = {
    "mods": (".jar",),
    "resourcepacks": (".zip",),
    "shaderpacks": (".zip",),
}
IGNORED_SUFFIXES = (".disabled", ".tmp", ".bak", ".pw.toml")
CONFIG_TEXT_EXTENSIONS = (".toml", ".properties", ".json", ".json5", ".txt", ".cfg", ".snbt", ".mcmeta")

KNOWN_CLIENT_PREFIXES = (
    "asyncparticles",
    "badoptimizations",
    "controlling",
    "cutthrough",
    "detailarmorbar",
    "ding-",
    "eclipseui",
    "essential_",
    "gpubooster",
    "immediatelyfast",
    "ixeris",
    "kerria",
    "mousetweaks",
    "particleeffects",
    "resourcify",
    "searchables",
    "smartblockplacement",
    "toadlib",
    "updating world icon",
    "betterbiomeblend",
    "chat_heads",
    "colorwheel",
    "continuity",
    "dynamic-fps",
    "eatinganimation",
    "eg-inventory-blur",
    "entity_model_features",
    "entity_texture_features",
    "entityculling",
    "explosiveenhancement",
    "flerovium",
    "iris-",
    "lambdynamiclights",
    "modelfix",
    "moreculling",
    "notenoughanimations",
    "nowheel",
    "openui-mc",
    "polytone",
    "progresspeek",
    "reeses-sodium-options",
    "remi-",
    "roxy-",
    "smart_bounds",
    "smoothswapping",
    "sodium-extra",
    "sodium-neoforge",
    "sounds-",
    "statuseffectbars",
    "toomanyrecipeviewers",
    "voxy-",
    "yeetusexperimentus",
    "zume-",
)


def compute_hashes(path: str, algos: tuple[str, ...] = ("sha1", "sha512")) -> dict[str, str]:
    """Compute one or more hex digests for a file in a single pass."""
    hashers = {algo: hashlib.new(algo) for algo in algos}
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            for h in hashers.values():
                h.update(chunk)
    return {algo: h.hexdigest() for algo, h in hashers.items()}


def sha1_file(path: str) -> str:
    return compute_hashes(path, ("sha1",))["sha1"]


def has_client_only_jar_metadata(jar_path: str) -> bool:
    """Detect client-only mods from filename prefixes and embedded jar metadata."""
    fname = os.path.basename(jar_path).lower()
    if "automodpack" in fname:
        return False
    for prefix in KNOWN_CLIENT_PREFIXES:
        if fname.startswith(prefix):
            return True

    try:
        with zipfile.ZipFile(jar_path, "r") as z:
            names = z.namelist()
            if "fabric.mod.json" in names and "META-INF/neoforge.mods.toml" not in names:
                data = json.loads(z.read("fabric.mod.json").decode("utf-8", errors="ignore"))
                if data.get("environment") == "client":
                    return True
            if "META-INF/neoforge.mods.toml" in names:
                toml_text = z.read("META-INF/neoforge.mods.toml").decode("utf-8", errors="ignore")
                if "clientSideOnly=true" in toml_text.replace(" ", "") or "clientSideOnly = true" in toml_text:
                    return True
    except Exception:
        pass

    return False


def toml_quote(value: str) -> str:
    escaped = (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\b", "\\b")
        .replace("\f", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    )
    return f'"{escaped}"'


def slugify_name(name: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9._-]+", "-", name.strip().lower()).strip("-")
    return slug or "mod"


def resolve_safe_target(repo_root: str, folder: str, filename: str) -> str | None:
    if not filename:
        return None
    folder_abs = os.path.abspath(os.path.join(repo_root, folder))
    target_abs = os.path.abspath(os.path.join(folder_abs, filename))
    if os.path.commonpath([folder_abs, target_abs]) != folder_abs or target_abs == folder_abs:
        return None
    return target_abs


def git_run(repo_root: str, args: list[str]) -> tuple[int, bytes]:
    res = subprocess.run(
        ["git", *args],
        cwd=repo_root,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    return res.returncode, res.stdout


def has_git_head(repo_root: str) -> bool:
    code, _ = git_run(repo_root, ["rev-parse", "--verify", "HEAD"])
    return code == 0


def git_file_in_head(repo_root: str, rel_path: str) -> bool:
    code, _ = git_run(repo_root, ["cat-file", "-e", f"HEAD:{rel_path}"])
    return code == 0


def git_show_bytes(repo_root: str, rel_path: str) -> bytes | None:
    code, out = git_run(repo_root, ["show", f"HEAD:{rel_path}"])
    return out if code == 0 else None


def get_git_pw_changes(repo_root: str) -> tuple[set[str], set[str], set[str]]:
    """Return (added, modified, deleted) sets of relative .pw.toml paths."""
    added: set[str] = set()
    modified: set[str] = set()
    deleted: set[str] = set()

    code, out = git_run(
        repo_root,
        ["status", "--porcelain", "-z", "--no-renames", "-uall", "--", *MANAGED_FOLDERS],
    )
    if code != 0 or not out:
        return added, modified, deleted

    for entry in out.split(b"\x00"):
        if len(entry) < 4:
            continue
        xy = entry[:2].decode("utf-8", errors="replace")
        rel_path = entry[3:].decode("utf-8", errors="replace").replace("\\", "/")
        if not rel_path.endswith(".pw.toml"):
            continue
        if "D" in xy:
            deleted.add(rel_path)
        elif xy == "??" or "A" in xy:
            added.add(rel_path)
        elif "M" in xy or "T" in xy:
            modified.add(rel_path)

    return added, modified, deleted


def load_pw_toml_bytes(raw: bytes) -> dict:
    return tomllib.loads(raw.decode("utf-8"))


def scan_pw_files(repo_root: str) -> dict[str, dict]:
    """Return mapping of rel_path -> parsed dict for all .pw.toml files."""
    found: dict[str, dict] = {}
    for folder in MANAGED_FOLDERS:
        folder_path = os.path.join(repo_root, folder)
        if not os.path.isdir(folder_path):
            continue
        for fname in sorted(os.listdir(folder_path)):
            if not fname.endswith(".pw.toml"):
                continue
            fpath = os.path.join(folder_path, fname)
            if not os.path.isfile(fpath):
                continue
            rel_path = f"{folder}/{fname}"
            try:
                with open(fpath, "rb") as f:
                    found[rel_path] = load_pw_toml_bytes(f.read())
            except Exception as e:
                print(f"Warning: failed to parse {rel_path}: {e}")
    return found


def download_file(
    session: requests.Session,
    url: str,
    dest_path: str,
    hash_format: str = "",
    expected_hash: str = "",
) -> None:
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    part_path = dest_path + ".part"
    hasher = hashlib.new(hash_format) if hash_format in hashlib.algorithms_guaranteed and expected_hash else None

    try:
        with session.get(url, stream=True, timeout=120) as r:
            r.raise_for_status()
            with open(part_path, "wb") as f:
                for chunk in r.iter_content(chunk_size=65536):
                    if chunk:
                        f.write(chunk)
                        if hasher is not None:
                            hasher.update(chunk)
        if hasher is not None:
            actual = hasher.hexdigest().lower()
            if actual != expected_hash.lower():
                raise RuntimeError(
                    f"Hash mismatch for {os.path.basename(dest_path)}: expected {expected_hash}, got {actual}"
                )
        os.replace(part_path, dest_path)
    finally:
        if os.path.exists(part_path):
            os.remove(part_path)


def verify_file_hash(path: str, hash_format: str, expected_hash: str) -> bool:
    if not expected_hash or hash_format not in hashlib.algorithms_guaranteed:
        return True
    actual = compute_hashes(path, (hash_format,))[hash_format]
    return actual.lower() == expected_hash.lower()


def ensure_local_binaries_from_pw_toml(
    repo_root: str,
    session: requests.Session | None = None,
    allow_prune: bool = False,
) -> tuple[set[str], set[str], set[str]]:
    """
    Ensure local binaries reflect Packwiz CLI changes (.pw.toml additions/updates/removals)
    and download any missing binaries referenced by .pw.toml files.
    Returns (added_pw, modified_pw, deleted_pw).
    """
    session = session or requests.Session()
    git_ready = has_git_head(repo_root)
    added_pw, modified_pw, deleted_pw = get_git_pw_changes(repo_root) if git_ready else (set(), set(), set())

    # 1. Handle .pw.toml deleted via packwiz remove
    for rel_path in sorted(deleted_pw):
        old_bytes = git_show_bytes(repo_root, rel_path)
        if not old_bytes:
            continue
        try:
            old_data = load_pw_toml_bytes(old_bytes)
        except Exception:
            continue
        folder = rel_path.split("/", 1)[0]
        old_target = resolve_safe_target(repo_root, folder, old_data.get("filename", ""))
        if old_target and os.path.isfile(old_target):
            os.remove(old_target)
            print(f"[packwiz -> disk] Removed {folder}/{old_data['filename']}")

    # 2. Handle .pw.toml modified via packwiz update (remove old version file if filename changed)
    pw_files = scan_pw_files(repo_root)
    for rel_path in sorted(modified_pw):
        if rel_path not in pw_files or not git_ready:
            continue
        old_bytes = git_show_bytes(repo_root, rel_path)
        if not old_bytes:
            continue
        try:
            old_data = load_pw_toml_bytes(old_bytes)
        except Exception:
            continue
        folder = rel_path.split("/", 1)[0]
        old_name = old_data.get("filename", "")
        new_name = pw_files[rel_path].get("filename", "")
        if old_name and old_name != new_name:
            old_target = resolve_safe_target(repo_root, folder, old_name)
            if old_target and os.path.isfile(old_target):
                os.remove(old_target)
                print(f"[packwiz -> disk] Removed old version {folder}/{old_name}")

    # 3. Download binaries for new/updated .pw.toml or missing non-pruned entries
    for rel_path, data in pw_files.items():
        folder = rel_path.split("/", 1)[0]
        fname = data.get("filename", "")
        target = resolve_safe_target(repo_root, folder, fname)
        if not target:
            continue

        dl = data.get("download", {})
        url = dl.get("url", "")
        h_fmt = dl.get("hash-format", "")
        h_val = dl.get("hash", "")
        if not url:
            continue

        is_cli_changed = rel_path in added_pw or rel_path in modified_pw
        exists_on_disk = os.path.isfile(target)

        should_download = False
        if exists_on_disk:
            if is_cli_changed and not verify_file_hash(target, h_fmt, h_val):
                should_download = True
        else:
            if is_cli_changed or not allow_prune or not git_ready or not git_file_in_head(repo_root, f"{folder}/{fname}"):
                should_download = True

        if should_download:
            print(f"[packwiz -> disk] Downloading {folder}/{fname}...")
            download_file(session, url, target, h_fmt, h_val)

    return added_pw, modified_pw, deleted_pw


def prune_stale_pw_files(
    repo_root: str,
    added_pw: set[str],
    modified_pw: set[str],
) -> None:
    """Remove .pw.toml metafiles whose binary was deleted locally (e.g. via Modrinth App)."""
    if not has_git_head(repo_root):
        return
    pw_files = scan_pw_files(repo_root)
    for rel_path, data in pw_files.items():
        if rel_path in added_pw or rel_path in modified_pw:
            continue
        folder = rel_path.split("/", 1)[0]
        fname = data.get("filename", "")
        target = resolve_safe_target(repo_root, folder, fname)
        if not target or os.path.isfile(target):
            continue
        if git_file_in_head(repo_root, f"{folder}/{fname}"):
            os.remove(os.path.join(repo_root, rel_path))
            print(f"[disk -> packwiz] Removed stale metafile {rel_path}")


def write_pw_toml(pw_path: str, name: str, filename: str, side: str, url: str, hash_format: str, file_hash: str, mod_id: str, version_id: str) -> None:
    lines = [
        f"name = {toml_quote(name)}",
        f"filename = {toml_quote(filename)}",
        f"side = {toml_quote(side)}",
        "",
        "[download]",
        f"url = {toml_quote(url)}",
        f"hash-format = {toml_quote(hash_format)}",
        f"hash = {toml_quote(file_hash)}",
        "",
        "[update]",
        "[update.modrinth]",
        f"mod-id = {toml_quote(mod_id)}",
        f"version = {toml_quote(version_id)}",
        "",
    ]
    with open(pw_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def list_active_binaries(repo_root: str) -> list[tuple[str, str, str]]:
    """Return list of (folder, filename, abs_path) for active binaries on disk."""
    items: list[tuple[str, str, str]] = []
    for folder in MANAGED_FOLDERS:
        folder_path = os.path.join(repo_root, folder)
        if not os.path.isdir(folder_path):
            continue
        exts = FOLDER_EXTENSIONS[folder]
        for fname in sorted(os.listdir(folder_path)):
            if fname.startswith(".") or fname.endswith(IGNORED_SUFFIXES):
                continue
            if not fname.lower().endswith(exts):
                continue
            fpath = os.path.join(folder_path, fname)
            if os.path.isfile(fpath):
                items.append((folder, fname, fpath))
    return items


def generate_missing_pw_files(repo_root: str, session: requests.Session) -> None:
    """Create or update .pw.toml metafiles for local binaries matched on Modrinth."""
    pw_files = scan_pw_files(repo_root)
    pw_by_target: dict[tuple[str, str], tuple[str, dict]] = {}
    pw_by_project: dict[tuple[str, str], tuple[str, dict]] = {}

    for rel_path, data in pw_files.items():
        folder = rel_path.split("/", 1)[0]
        fname = data.get("filename", "")
        if fname:
            pw_by_target[(folder, fname)] = (rel_path, data)
        mod_id = data.get("update", {}).get("modrinth", {}).get("mod-id", "")
        if mod_id:
            pw_by_project[(folder, mod_id)] = (rel_path, data)

    candidates: list[tuple[str, str, str, str, str]] = []
    for folder, fname, fpath in list_active_binaries(repo_root):
        existing = pw_by_target.get((folder, fname))
        if existing is not None:
            _, data = existing
            dl = data.get("download", {})
            if verify_file_hash(fpath, dl.get("hash-format", ""), dl.get("hash", "")):
                continue
        digests = compute_hashes(fpath, ("sha1", "sha512"))
        candidates.append((folder, fname, fpath, digests["sha1"], digests["sha512"]))

    if not candidates:
        return

    print(f"[disk -> packwiz] Querying Modrinth for {len(candidates)} local file(s)...")
    ver_map: dict[str, dict] = {}
    all_sha1 = [c[3] for c in candidates]
    for i in range(0, len(all_sha1), 200):
        chunk = all_sha1[i : i + 200]
        r = session.post(
            "https://api.modrinth.com/v2/version_files",
            json={"hashes": chunk, "algorithm": "sha1"},
            timeout=30,
        )
        if r.status_code == 200:
            ver_map.update(r.json())

    proj_ids = sorted({v["project_id"] for v in ver_map.values() if v.get("project_id")})
    projs: dict[str, dict] = {}
    for i in range(0, len(proj_ids), 100):
        chunk = proj_ids[i : i + 100]
        r = session.get(
            "https://api.modrinth.com/v2/projects",
            params={"ids": json.dumps(chunk)},
            timeout=30,
        )
        if r.status_code == 200:
            for p in r.json():
                projs[p["id"]] = p

    created_count = 0
    for folder, fname, fpath, h_sha1, h_sha512 in candidates:
        ver = ver_map.get(h_sha1)
        if not ver:
            continue
        proj = projs.get(ver.get("project_id", ""))
        if not proj:
            continue

        files = ver.get("files", [])
        if not files:
            continue
        file_obj = next((f for f in files if f.get("hashes", {}).get("sha1") == h_sha1), None)
        if file_obj is None:
            file_obj = next((f for f in files if f.get("primary")), files[0])

        url = file_obj.get("url", "")
        if not url:
            continue
        mr_hashes = file_obj.get("hashes", {})
        if "sha512" in mr_hashes:
            h_fmt, h_val = "sha512", mr_hashes["sha512"]
        elif "sha1" in mr_hashes:
            h_fmt, h_val = "sha1", mr_hashes["sha1"]
        else:
            h_fmt, h_val = "sha512", h_sha512

        env = ver.get("environment")
        ss = proj.get("server_side")
        if folder == "mods":
            if "automodpack" in fname.lower():
                side = "both"
            elif has_client_only_jar_metadata(fpath) or env in ("client_only", "singleplayer_only") or ss == "unsupported":
                side = "client"
            else:
                side = "both"
        else:
            side = "client" if ss == "unsupported" else "both"

        slug = proj.get("slug") or slugify_name(proj.get("title", fname))
        target_rel = f"{folder}/{slug}.pw.toml"
        target_pw_path = os.path.join(repo_root, target_rel)

        # If updating an existing project metafile whose old binary is gone, clean up old path if slug differs
        prev = pw_by_project.get((folder, proj["id"]))
        if prev is not None:
            prev_rel, prev_data = prev
            prev_target = resolve_safe_target(repo_root, folder, prev_data.get("filename", ""))
            if prev_rel != target_rel and (not prev_target or not os.path.isfile(prev_target)):
                prev_pw_path = os.path.join(repo_root, prev_rel)
                if os.path.isfile(prev_pw_path):
                    os.remove(prev_pw_path)
            elif prev_rel == target_rel and prev_target and os.path.isfile(prev_target) and prev_data.get("filename") != fname:
                # Avoid overwriting an unrelated active binary's metafile on rare slug collision
                target_rel = f"{folder}/{slugify_name(os.path.splitext(fname)[0])}.pw.toml"
                target_pw_path = os.path.join(repo_root, target_rel)

        write_pw_toml(
            target_pw_path,
            name=proj.get("title") or slug,
            filename=fname,
            side=side,
            url=url,
            hash_format=h_fmt,
            file_hash=h_val,
            mod_id=proj["id"],
            version_id=ver["id"],
        )
        pw_by_project[(folder, proj["id"])] = (target_rel, {"filename": fname})
        created_count += 1

    if created_count:
        print(f"[disk -> packwiz] Generated/updated {created_count} .pw.toml metafile(s).")


def escape_packwizignore_path(rel_path: str) -> str:
    """Escape regex metacharacters so sabhiram/go-gitignore matches literal filenames."""
    for ch in ("+", "(", ")", "[", "]", "{", "}", "^", "$"):
        rel_path = rel_path.replace(ch, "\\" + ch)
    return rel_path


def write_packwizignore(repo_root: str) -> None:
    pw_files = scan_pw_files(repo_root)
    covered_binaries: set[tuple[str, str]] = set()
    for rel_path, data in pw_files.items():
        folder = rel_path.split("/", 1)[0]
        fname = data.get("filename", "")
        target = resolve_safe_target(repo_root, folder, fname)
        if target and os.path.isfile(target):
            covered_binaries.add((folder, fname))

    uncovered_rel_paths: list[str] = []
    for folder, fname, _ in list_active_binaries(repo_root):
        if (folder, fname) not in covered_binaries:
            uncovered_rel_paths.append(f"{folder}/{fname}")

    lines = [
        "# Managed by scripts/sync_packwiz.py (keep LF line endings)",
        "*",
        "!mods",
        "!mods/**",
        "!resourcepacks",
        "!resourcepacks/**",
        "!shaderpacks",
        "!shaderpacks/**",
        "!config",
        "!config/**",
        "",
        "mods/.connector",
        "mods/.connector/**",
        "mods/*.disabled",
        "mods/*.tmp",
        "mods/*.bak",
        "shaderpacks/*",
        "!shaderpacks/*.pw.toml",

        "",
        "config/*.bak",
        "config/**/*.bak",
        "config/xaero",
        "config/xaero/**",
        "config/xaerohud.txt",
        "config/xaeropatreon.txt",
        "config/spark/tmp",
        "config/spark/tmp/**",
        "config/spark/tmp-client",
        "config/spark/tmp-client/**",
        ".prelaunch_ok",
        "",
        "mods/*.jar",
        "resourcepacks/*.zip",
    ]
    if uncovered_rel_paths:
        lines.append("")
        lines.append("# Non-Modrinth files included directly in Packwiz index / .mrpack overrides:")
        for rel in sorted(uncovered_rel_paths):
            lines.append("!" + escape_packwizignore_path(rel))

    with open(os.path.join(repo_root, ".packwizignore"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def _is_ignored_config_rel(rel_path: str) -> bool:
    parts = rel_path.split("/")
    if rel_path.endswith(".bak"):
        return True
    if len(parts) >= 2 and parts[0] == "config":
        if parts[1] in ("xaero", "xaerohud.txt", "xaeropatreon.txt"):
            return True
        if parts[1] == "spark" and len(parts) >= 3 and parts[2] in ("tmp", "tmp-client"):
            return True
    return False


def normalize_config_line_endings(repo_root: str) -> int:
    """Convert CRLF to LF in tracked config text files so disk bytes match Git blobs."""
    config_root = os.path.join(repo_root, "config")
    if not os.path.isdir(config_root):
        return 0

    converted = 0
    for root, dirs, files in os.walk(config_root):
        rel_dir = os.path.relpath(root, repo_root).replace("\\", "/")
        if rel_dir == "config/xaero" or rel_dir.startswith("config/xaero/"):
            dirs[:] = []
            continue
        if rel_dir in ("config/spark/tmp", "config/spark/tmp-client"):
            dirs[:] = []
            continue
        for fname in files:
            if not fname.lower().endswith(CONFIG_TEXT_EXTENSIONS):
                continue
            fpath = os.path.join(root, fname)
            rel_path = os.path.relpath(fpath, repo_root).replace("\\", "/")
            if _is_ignored_config_rel(rel_path):
                continue
            with open(fpath, "rb") as f:
                raw = f.read()
            if b"\r\n" in raw:
                with open(fpath, "wb") as f:
                    f.write(raw.replace(b"\r\n", b"\n"))
                converted += 1
    if converted:
        print(f"Normalized CRLF -> LF in {converted} config file(s).")
    return converted


def refresh_index_python(repo_root: str) -> None:
    """Pure-Python fallback for `packwiz refresh` when packwiz binary is absent (e.g. CI)."""
    pw_files = scan_pw_files(repo_root)
    covered_binaries = {
        f"{rel_path.split('/', 1)[0]}/{data.get('filename', '')}"
        for rel_path, data in pw_files.items()
        if data.get("filename")
    }

    entries: list[tuple[str, str, bool]] = []
    for folder in MANAGED_FOLDERS:
        for rel_path in sorted(pw_files):
            if rel_path.startswith(folder + "/"):
                abs_path = os.path.join(repo_root, rel_path)
                digest = compute_hashes(abs_path, ("sha256",))["sha256"]
                entries.append((rel_path, digest, True))
        for f_folder, fname, fpath in list_active_binaries(repo_root):
            if f_folder != folder:
                continue
            rel_path = f"{folder}/{fname}"
            if rel_path not in covered_binaries:
                digest = compute_hashes(fpath, ("sha256",))["sha256"]
                entries.append((rel_path, digest, False))

    config_root = os.path.join(repo_root, "config")
    if os.path.isdir(config_root):
        for root, dirs, files in os.walk(config_root):
            rel_dir = os.path.relpath(root, repo_root).replace("\\", "/")
            if rel_dir == "config/xaero" or rel_dir.startswith("config/xaero/"):
                dirs[:] = []
                continue
            if rel_dir in ("config/spark/tmp", "config/spark/tmp-client"):
                dirs[:] = []
                continue
            dirs.sort()
            for fname in sorted(files):
                fpath = os.path.join(root, fname)
                rel_path = os.path.relpath(fpath, repo_root).replace("\\", "/")
                if _is_ignored_config_rel(rel_path):
                    continue
                digest = compute_hashes(fpath, ("sha256",))["sha256"]
                entries.append((rel_path, digest, False))

    entries.sort(key=lambda item: item[0])
    lines = ['hash-format = "sha256"', ""]
    for rel_path, digest, is_meta in entries:
        lines.append("[[files]]")
        lines.append(f"file = {toml_quote(rel_path)}")
        lines.append(f"hash = {toml_quote(digest)}")
        if is_meta:
            lines.append("metafile = true")
        lines.append("")

    index_path = os.path.join(repo_root, "index.toml")
    with open(index_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))

    index_hash = compute_hashes(index_path, ("sha256",))["sha256"]
    pack_path = os.path.join(repo_root, "pack.toml")
    with open(pack_path, "r", encoding="utf-8") as f:
        pack_text = f.read()
    updated_pack_text = re.sub(
        r'(?m)^hash\s*=\s*"[^"]*"',
        f'hash = "{index_hash}"',
        pack_text,
        count=1,
    )
    with open(pack_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(updated_pack_text)


def refresh_packwiz_index(repo_root: str) -> None:
    index_path = os.path.join(repo_root, "index.toml")
    if not os.path.exists(index_path):
        with open(index_path, "w", encoding="utf-8", newline="\n") as f:
            f.write('hash-format = "sha256"\nfiles = []\n')

    packwiz_bin = shutil.which("packwiz")
    if packwiz_bin:
        subprocess.run([packwiz_bin, "refresh"], cwd=repo_root, check=True)
    else:
        refresh_index_python(repo_root)


def export_mrpack(repo_root: str, output_path: str, session: requests.Session | None = None) -> None:
    """Build a Modrinth .mrpack archive directly from pack.toml, index.toml, and local files."""
    session = session or requests.Session()
    with open(os.path.join(repo_root, "pack.toml"), "rb") as f:
        pack_data = tomllib.load(f)
    index_rel = pack_data.get("index", {}).get("file", "index.toml")
    with open(os.path.join(repo_root, index_rel), "rb") as f:
        index_data = tomllib.load(f)

    manifest_files: list[dict] = []
    override_entries: list[tuple[str, str]] = []

    for item in index_data.get("files", []):
        rel_path = item["file"]
        is_meta = item.get("metafile", False)
        abs_path = os.path.join(repo_root, rel_path)

        if is_meta:
            with open(abs_path, "rb") as f:
                pw_data = tomllib.load(f)
            folder = rel_path.split("/", 1)[0]
            fname = pw_data["filename"]
            bin_path = resolve_safe_target(repo_root, folder, fname)
            if not bin_path:
                raise RuntimeError(f"Unsafe filename in {rel_path}: {fname}")

            dl = pw_data.get("download", {})
            url = dl.get("url", "")
            if not os.path.isfile(bin_path):
                if not url:
                    raise RuntimeError(f"Missing binary and no download URL in {rel_path}")
                download_file(session, url, bin_path, dl.get("hash-format", ""), dl.get("hash", ""))

            digests = compute_hashes(bin_path, ("sha1", "sha512"))
            file_size = os.path.getsize(bin_path)
            side = pw_data.get("side", "both")
            if side == "client":
                env = {"client": "required", "server": "unsupported"}
            elif side == "server":
                env = {"client": "unsupported", "server": "required"}
            else:
                env = {"client": "required", "server": "required"}

            manifest_files.append(
                {
                    "path": f"{folder}/{fname}",
                    "hashes": {
                        "sha1": digests["sha1"],
                        "sha512": digests["sha512"],
                    },
                    "env": env,
                    "downloads": [url] if url else [],
                    "fileSize": file_size,
                }
            )
        else:
            if os.path.isfile(abs_path):
                override_entries.append((rel_path, abs_path))

    for extra_name in ("packwiz-installer-bootstrap.jar", "atualizar-mods.bat"):
        extra_abs = os.path.join(repo_root, extra_name)
        if os.path.isfile(extra_abs) and (extra_name, extra_abs) not in override_entries:
            override_entries.append((extra_name, extra_abs))

    manifest_files.sort(key=lambda entry: entry["path"])
    manifest = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": pack_data.get("version", "1.0.0"),
        "name": pack_data.get("name", "Create+"),
        "files": manifest_files,
        "dependencies": pack_data.get("versions", {}),
    }

    out_abs = os.path.abspath(output_path)
    out_dir = os.path.dirname(out_abs)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    with zipfile.ZipFile(out_abs, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("overrides/", b"")
        zf.writestr("modrinth.index.json", json.dumps(manifest, indent=4).encode("utf-8"))
        for rel_path, abs_path in sorted(override_entries):
            zf.write(abs_path, f"overrides/{rel_path}")

    print(
        f"Exported {output_path}: {len(manifest_files)} Modrinth file(s), "
        f"{len(override_entries)} override file(s)."
    )


def sync_packwiz(repo_root: str, allow_prune: bool = True) -> None:
    normalize_config_line_endings(repo_root)
    session = requests.Session()
    added_pw, modified_pw, _ = ensure_local_binaries_from_pw_toml(
        repo_root, session=session, allow_prune=allow_prune
    )
    if allow_prune:
        prune_stale_pw_files(repo_root, added_pw, modified_pw)
    generate_missing_pw_files(repo_root, session=session)
    write_packwizignore(repo_root)
    refresh_packwiz_index(repo_root)


def main() -> None:
    parser = argparse.ArgumentParser(description="Two-way sync between Modrinth App files and Packwiz.")
    parser.add_argument("--export-mrpack", metavar="PATH", help="Export a .mrpack archive after syncing.")
    parser.add_argument("--no-prune", action="store_true", help="Do not delete .pw.toml when local binaries are absent.")
    parser.add_argument("--prune", action="store_true", help="Force pruning stale .pw.toml files even in CI.")
    args = parser.parse_args()

    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    is_ci = os.environ.get("GITHUB_ACTIONS") == "true"
    allow_prune = True if args.prune else (False if (args.no_prune or is_ci) else True)

    sync_packwiz(repo_root, allow_prune=allow_prune)
    if args.export_mrpack:
        export_mrpack(repo_root, args.export_mrpack)


if __name__ == "__main__":
    main()
