import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
from zipfile import ZIP_DEFLATED, ZipFile


SOURCE = Path(__file__).resolve().parent
PROFILE = SOURCE.parents[1]
DRIVER_VERSION = "3.5.8"
DRIVER_NAME = f"mariadb-java-client-{DRIVER_VERSION}.jar"
DRIVER_SHA256 = "6127dc7858047b3d4482899139640b0e2ab2b8abdeb708cfb8c011117771cddf"
DRIVER_URL = f"https://repo.maven.apache.org/maven2/org/mariadb/jdbc/mariadb-java-client/{DRIVER_VERSION}/{DRIVER_NAME}"
LIBRARIES = (
    "net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar",
    "net/neoforged/neoforge/21.1.266/neoforge-21.1.266-universal.jar",
    "net/neoforged/fancymodloader/loader/4.0.45/loader-4.0.45.jar",
    "net/neoforged/bus/8.0.5/bus-8.0.5.jar",
    "net/neoforged/mergetool/2.0.3/mergetool-2.0.3-api.jar",
    "net/fabricmc/sponge-mixin/0.16.5+mixin.0.8.7/sponge-mixin-0.16.5+mixin.0.8.7.jar",
    "org/joml/joml/1.10.8/joml-1.10.8.jar",
    "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
    "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar",
    "com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar",
    "com/mojang/authlib/6.0.54/authlib-6.0.54.jar",
    "com/mojang/datafixerupper/8.0.16/datafixerupper-8.0.16.jar",
    "com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar",
    "com/google/code/gson/gson/2.11.0/gson-2.11.0.jar",
)


def main():
    parser = argparse.ArgumentParser(description="Build Boss Arena Guard with JDK 21 and the Modrinth libraries.")
    parser.add_argument("--output", type=Path, default=PROFILE / "mods/boss-arena-guard-1.0.0.jar")
    parser.add_argument("--driver", type=Path, help="Use a local copy of the pinned JDBC driver.")
    parser.add_argument("--libraries", type=Path, default=PROFILE.parents[1] / "meta/libraries",
                        help="Path to the Modrinth libraries when building in a separate worktree.")
    arguments = parser.parse_args()

    classpath = [arguments.libraries / name for name in LIBRARIES]
    classpath.extend(PROFILE / "mods" / name for name in (
        "createlanterns-alpha-20.50.jar", "boss_checklist-neoforge-5.3.2.jar",
        "dungeon_difficulty-neoforge-3.9.2+1.21.1.jar",
    ))
    sources = sorted((SOURCE / "src/main/java").rglob("*.java"))
    resources = SOURCE / "src/main/resources"
    nested_driver = f"META-INF/jarjar/{DRIVER_NAME}"
    metadata = {"jars": [{
        "identifier": {"group": "org.mariadb.jdbc", "artifact": "mariadb-java-client"},
        "version": {"range": f"[{DRIVER_VERSION}]", "artifactVersion": DRIVER_VERSION},
        "path": nested_driver,
        "isObfuscated": False,
    }]}

    with tempfile.TemporaryDirectory(prefix="boss-arena-build-") as temporary:
        build = Path(temporary)
        driver = arguments.driver or build / DRIVER_NAME
        if arguments.driver is None:
            with urllib.request.urlopen(DRIVER_URL, timeout=30) as response:
                driver.write_bytes(response.read())
        if hashlib.sha256(driver.read_bytes()).hexdigest() != DRIVER_SHA256:
            raise SystemExit("MariaDB driver SHA-256 does not match the pinned version.")

        classpath.append(driver)
        missing = [str(path) for path in classpath if not path.is_file()]
        if missing:
            raise SystemExit("Missing build libraries:\n" + "\n".join(missing))
        classes = build / "classes"
        classes.mkdir()
        subprocess.run([
            "javac", "-encoding", "UTF-8", "--release", "21", "-proc:none",
            "-cp", os.pathsep.join(map(str, classpath)), "-d", str(classes), *map(str, sources),
        ], check=True)

        with tempfile.NamedTemporaryFile(dir=arguments.output.parent, suffix=".tmp", delete=False) as temporary_jar:
            jar_path = Path(temporary_jar.name)
        try:
            with ZipFile(jar_path, "w", ZIP_DEFLATED) as jar:
                for folder in (classes, resources):
                    for path in sorted(folder.rglob("*")):
                        if path.is_file():
                            jar.write(path, path.relative_to(folder).as_posix())
                jar.write(driver, nested_driver)
                jar.writestr("META-INF/jarjar/metadata.json", json.dumps(metadata))
            jar_path.replace(arguments.output)
        finally:
            jar_path.unlink(missing_ok=True)
    print(f"Compiled {len(sources)} Java files and packaged {arguments.output}")


if __name__ == "__main__":
    main()
