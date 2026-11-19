import csv
from pathlib import Path


def initial_sql() -> str:
    defaults = Path(__file__).resolve().parent / "src/main/resources/boss-arena-profiles.csv"
    with defaults.open(encoding="utf-8", newline="") as file:
        rows = []
        for row in csv.DictReader(file):
            ceiling = str(int(row["ceiling_y"])) if row["ceiling_y"] else "NULL"
            rows.append(
                f"('{row['boss_id']}', {int(row['radius'])}, {int(row['lower_offset'])}, "
                f"{int(row['upper_offset'])}, '{row['height_mode']}', {ceiling})"
            )
    return """CREATE TABLE IF NOT EXISTS boss_arena_profiles (
    boss_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    radius SMALLINT NOT NULL,
    lower_offset SMALLINT NOT NULL,
    upper_offset SMALLINT NOT NULL,
    height_mode VARCHAR(16) NOT NULL,
    ceiling_y SMALLINT NULL
) ENGINE=InnoDB;

INSERT IGNORE INTO boss_arena_profiles (boss_id, radius, lower_offset, upper_offset, height_mode, ceiling_y) VALUES
""" + ",\n".join(rows) + ";\n"


if __name__ == "__main__":
    print(initial_sql(), end="")
