import os
import sys
import json
import time
import subprocess
import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sync_packwiz

SERVER_ID = os.environ.get("ENXADA_SERVER_ID", "b31b9e05")
API_KEY = os.environ.get("ENXADA_API_KEY", "").strip()
BASE_URL = f"https://painel.enxadahost.com/api/client/servers/{SERVER_ID}"

sha1_file = sync_packwiz.sha1_file


def load_mod_pw_sides(repo_root: str) -> dict[str, str]:
    """Map mod filename -> side ('client', 'server', 'both') from mods/*.pw.toml."""
    sides: dict[str, str] = {}
    for rel_path, data in sync_packwiz.scan_pw_files(repo_root).items():
        if rel_path.startswith("mods/") and data.get("filename"):
            sides[data["filename"]] = data.get("side", "both")
    return sides


def is_client_only_mod(
    jar_path: str,
    remote_srv_mods: dict,
    remote_cli_mods: dict,
    pw_sides: dict[str, str] | None = None,
) -> bool:
    fname_orig = os.path.basename(jar_path)
    fname = fname_orig.lower()
    if "automodpack" in fname:
        return False

    if pw_sides and pw_sides.get(fname_orig) == "client":
        return True

    # Fast path if already classified on server
    if fname_orig in remote_cli_mods:
        return True
    if fname_orig in remote_srv_mods:
        return False

    if sync_packwiz.has_client_only_jar_metadata(jar_path):
        return True

    # Fallback for newly added mods: query Modrinth API by SHA1
    try:
        sha1 = sha1_file(jar_path)
        r = requests.get(f"https://api.modrinth.com/v2/version_file/{sha1}", timeout=10)
        if r.status_code == 200:
            pid = r.json().get("project_id")
            if pid:
                rp = requests.get(f"https://api.modrinth.com/v2/project/{pid}", timeout=10)
                if rp.status_code == 200:
                    if rp.json().get("server_side") == "unsupported":
                        return True
    except Exception:
        pass

    return False


class EnxadaClient:
    def __init__(self, api_key: str):
        self.s = requests.Session()
        self.s.headers.update({
            "Authorization": f"Bearer {api_key}",
            "Accept": "application/json",
        })

    def list_dir(self, directory: str) -> dict[str, int]:
        r = self.s.get(f"{BASE_URL}/files/list", params={"directory": directory}, timeout=30)
        if r.status_code != 200:
            return {}
        out = {}
        for item in r.json().get("data", []):
            attr = item.get("attributes", {})
            if attr.get("is_file", True):
                out[attr["name"]] = attr.get("size", -1)
        return out

    def get_file_bytes(self, file_path: str) -> bytes | None:
        r = self.s.get(f"{BASE_URL}/files/contents", params={"file": file_path}, timeout=30)
        if r.status_code == 200:
            return r.content
        return None

    def write_file_bytes(self, file_path: str, content: bytes):
        r = self.s.post(
            f"{BASE_URL}/files/write",
            params={"file": file_path},
            data=content,
            headers={"Content-Type": "text/plain"},
            timeout=60,
        )
        r.raise_for_status()

    def upload_file(self, remote_dir: str, local_path: str):
        fname = os.path.basename(local_path)
        for attempt in range(3):
            try:
                r = self.s.get(f"{BASE_URL}/files/upload", timeout=30)
                r.raise_for_status()
                upload_url = r.json()["attributes"]["url"]
                with open(local_path, "rb") as f:
                    ru = requests.post(
                        upload_url,
                        params={"directory": remote_dir},
                        files={"files": (fname, f, "application/octet-stream")},
                        timeout=300,
                    )
                if ru.status_code in (200, 204):
                    return
            except Exception as e:
                print(f"  [retry {attempt+1}/3] Error uploading {fname}: {e}")
                time.sleep(2)
        raise RuntimeError(f"Failed to upload {local_path} to {remote_dir}")

    def delete_files(self, remote_dir: str, filenames: list[str]):
        if not filenames:
            return
        r = self.s.post(
            f"{BASE_URL}/files/delete",
            json={"root": remote_dir, "files": filenames},
            timeout=30,
        )
        r.raise_for_status()

    def send_command(self, cmd: str):
        return self.s.post(f"{BASE_URL}/command", json={"command": cmd}, timeout=15)

    def send_power(self, signal: str):
        return self.s.post(f"{BASE_URL}/power", json={"signal": signal}, timeout=15)


def main():
    dry_run = "--dry-run" in sys.argv
    if not API_KEY:
        print("ERROR: ENXADA_API_KEY environment variable is required.")
        sys.exit(1)

    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    sync_packwiz.ensure_local_binaries_from_pw_toml(repo_root, allow_prune=False)
    pw_sides = load_mod_pw_sides(repo_root)
    client = EnxadaClient(API_KEY)

    remote_sha1 = {}
    cnt_bytes = client.get_file_bytes("/automodpack/host-modpack/automodpack-content.json")
    if cnt_bytes:
        try:
            cnt_json = json.loads(cnt_bytes.decode("utf-8"))
            for item in cnt_json.get("list", []):
                f_rel = item.get("file", "")
                if f_rel and item.get("sha1"):
                    remote_sha1[f_rel] = item["sha1"]
        except Exception as e:
            print(f"Warning: could not parse remote automodpack-content.json: {e}")

    remote_srv_mods = client.list_dir("/mods")
    remote_cli_mods = client.list_dir("/automodpack/host-modpack/main/mods")
    remote_rpacks = client.list_dir("/automodpack/host-modpack/main/resourcepacks")
    remote_spacks = client.list_dir("/automodpack/host-modpack/main/shaderpacks")

    local_mods_dir = os.path.join(repo_root, "mods")
    expected_srv_mods = {}
    expected_cli_mods = {}

    if os.path.exists(local_mods_dir):
        for fname in sorted(os.listdir(local_mods_dir)):
            if not fname.endswith(".jar"):
                continue
            fpath = os.path.join(local_mods_dir, fname)
            if not os.path.isfile(fpath):
                continue
            if is_client_only_mod(fpath, remote_srv_mods, remote_cli_mods, pw_sides=pw_sides):
                expected_cli_mods[fname] = fpath
            else:
                expected_srv_mods[fname] = fpath

    # Server-only mods (e.g. DirectAuth) live outside mods/ so they never load in the client instance.
    server_only_dir = os.path.join(repo_root, "server-mods")
    if os.path.isdir(server_only_dir):
        for fname in sorted(os.listdir(server_only_dir)):
            fpath = os.path.join(server_only_dir, fname)
            if fname.endswith(".jar") and os.path.isfile(fpath):
                expected_srv_mods[fname] = fpath

    print(f"Classified local mods: {len(expected_srv_mods)} server/both mods, {len(expected_cli_mods)} client-only mods.")

    server_mods_changed = False
    any_modpack_changed = False

    # Sync /mods (server mods)
    to_delete_srv = [f for f in remote_srv_mods if f.endswith(".jar") and f not in expected_srv_mods]
    if to_delete_srv:
        print(f"Deleting {len(to_delete_srv)} removed server mods: {to_delete_srv}")
        if not dry_run:
            client.delete_files("/mods", to_delete_srv)
        server_mods_changed = True
        any_modpack_changed = True

    for fname, fpath in expected_srv_mods.items():
        local_sz = os.path.getsize(fpath)
        local_hash = sha1_file(fpath)
        rem_sz = remote_srv_mods.get(fname)
        rem_hash = remote_sha1.get(f"/mods/{fname}")
        needs_upload = (rem_sz is None) or (rem_sz != local_sz) or (rem_hash is not None and rem_hash != local_hash)
        if needs_upload:
            print(f"Uploading server mod -> /mods/{fname} ({local_sz} bytes)")
            if not dry_run:
                client.upload_file("/mods", fpath)
            server_mods_changed = True
            any_modpack_changed = True

    # Sync /automodpack/host-modpack/main/mods (client-only mods)
    to_delete_cli = [f for f in remote_cli_mods if f.endswith(".jar") and f not in expected_cli_mods]
    if to_delete_cli:
        print(f"Deleting {len(to_delete_cli)} removed client mods: {to_delete_cli}")
        if not dry_run:
            client.delete_files("/automodpack/host-modpack/main/mods", to_delete_cli)
        any_modpack_changed = True

    for fname, fpath in expected_cli_mods.items():
        local_sz = os.path.getsize(fpath)
        local_hash = sha1_file(fpath)
        rem_sz = remote_cli_mods.get(fname)
        rem_hash = remote_sha1.get(f"/mods/{fname}")
        needs_upload = (rem_sz is None) or (rem_sz != local_sz) or (rem_hash is not None and rem_hash != local_hash)
        if needs_upload:
            print(f"Uploading client mod -> /automodpack/host-modpack/main/mods/{fname} ({local_sz} bytes)")
            if not dry_run:
                client.upload_file("/automodpack/host-modpack/main/mods", fpath)
            any_modpack_changed = True

    # Sync resourcepacks and shaderpacks
    for sub, rem_map in [("resourcepacks", remote_rpacks), ("shaderpacks", remote_spacks)]:
        local_sub = os.path.join(repo_root, sub)
        rem_dir = f"/automodpack/host-modpack/main/{sub}"
        expected_sub = {}
        if os.path.exists(local_sub):
            for fname in sorted(os.listdir(local_sub)):
                fpath = os.path.join(local_sub, fname)
                if os.path.isfile(fpath) and not fname.startswith("."):
                    expected_sub[fname] = fpath

        to_del = [f for f in rem_map if f not in expected_sub]
        if to_del:
            print(f"Deleting from {rem_dir}: {to_del}")
            if not dry_run:
                client.delete_files(rem_dir, to_del)
            any_modpack_changed = True

        for fname, fpath in expected_sub.items():
            local_sz = os.path.getsize(fpath)
            local_hash = sha1_file(fpath)
            rem_sz = rem_map.get(fname)
            rem_hash = remote_sha1.get(f"/{sub}/{fname}")
            if (rem_sz is None) or (rem_sz != local_sz) or (rem_hash is not None and rem_hash != local_hash):
                print(f"Uploading {sub} -> {rem_dir}/{fname} ({local_sz} bytes)")
                if not dry_run:
                    client.upload_file(rem_dir, fpath)
                any_modpack_changed = True

    # Sync changed config/ files from latest git commit if running in git
    ftbquests_changed = False
    other_config_changed = False
    try:
        diff_out = subprocess.check_output(
            ["git", "diff", "--name-status", "HEAD~1", "HEAD", "--", "config/"],
            cwd=repo_root,
            text=True,
            stderr=subprocess.DEVNULL,
        )
        for line in diff_out.splitlines():
            parts = line.strip().split("\t")
            if len(parts) >= 2:
                status, rel_path = parts[0], parts[-1]
                rel_norm = rel_path.replace("\\", "/")
                if "config/ftbquests/" in rel_norm:
                    ftbquests_changed = True
                else:
                    other_config_changed = True

                if status.startswith("D"):
                    parent = "/" + os.path.dirname(rel_norm)
                    fname = os.path.basename(rel_norm)
                    print(f"Deleting removed config on server: {rel_norm}")
                    if not dry_run:
                        client.delete_files(parent, [fname])
                else:
                    local_cfg = os.path.join(repo_root, rel_norm)
                    if os.path.isfile(local_cfg):
                        print(f"Uploading changed config to server: /{rel_norm}")
                        if not dry_run:
                            with open(local_cfg, "rb") as f:
                                client.write_file_bytes(f"/{rel_norm}", f.read())
    except Exception:
        pass

    if not dry_run:
        if server_mods_changed:
            print("Server mods changed -> Sending 'automodpack generate' and restarting server...")
            client.send_command("automodpack generate")
            time.sleep(3)
            client.send_power("restart")
        elif any_modpack_changed:
            print("Client mods/packs changed -> Sending 'automodpack generate' to server console...")
            client.send_command("automodpack generate")
            if ftbquests_changed:
                print("FTB Quests also changed -> Sending 'ftbquests reload' to server console...")
                client.send_command("ftbquests reload")
        elif ftbquests_changed:
            print("Only FTB Quests changed -> Sending 'ftbquests reload' to server console (no restart needed)...")
            client.send_command("ftbquests reload")
        else:
            print("All files on EnxadaHost server are up to date!")


if __name__ == "__main__":
    main()
