import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile


SOURCE = Path(__file__).resolve().parent
PROFILE = SOURCE.parents[1]
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
    "io/netty/netty-common/4.1.97.Final/netty-common-4.1.97.Final.jar",
    "io/netty/netty-transport/4.1.97.Final/netty-transport-4.1.97.Final.jar",
)


def main():
    parser = argparse.ArgumentParser(description="Build Create+ Server Help with JDK 21.")
    parser.add_argument("--output", type=Path, default=PROFILE / "server-mods/createplus-server-help-1.0.0.jar")
    args = parser.parse_args()
    libraries = PROFILE.parents[1] / "meta/libraries"
    deps = [libraries / path for path in LIBRARIES]
    missing = [str(path) for path in deps if not path.is_file()]
    if missing:
        raise SystemExit("Missing build dependencies:\n" + "\n".join(missing))

    sources = sorted((SOURCE / "src/main/java").rglob("*.java"))
    resources = SOURCE / "src/main/resources"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="server-help-", dir=args.output.parent) as temporary:
        build = Path(temporary)
        classes = build / "classes"
        classes.mkdir()
        subprocess.run([
            "javac", "-encoding", "UTF-8", "--release", "21", "-proc:none",
            "-cp", os.pathsep.join(map(str, deps)), "-d", classes, *sources,
        ], check=True)
        artifact = build / args.output.name
        with ZipFile(artifact, "w", ZIP_DEFLATED) as jar:
            for folder in (classes, resources):
                for path in sorted(folder.rglob("*")):
                    if path.is_file():
                        jar.write(path, path.relative_to(folder).as_posix())
        artifact.replace(args.output)
    print(f"Built {args.output}")


if __name__ == "__main__":
    main()
