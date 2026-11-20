#!/usr/bin/env python3
"""
Gera o livro de quests do Create+ (Minecraft 1.21.1, FTB Quests versão 13).
Use --output para gerar uma cópia de teste fora do perfil.
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import io
import json
import os
import re
import tomllib
import zipfile
from dataclasses import dataclass, field
from graphlib import TopologicalSorter
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
MODS_DIR = ROOT / "mods"
QUESTS_DIR = ROOT / "config" / "ftbquests" / "quests"
# O FTB guarda progresso por ID. Este mapa vincula as quests antigas às suas chaves e tarefas.
LEGACY_IDS = json.loads(Path(__file__).with_name("ftb_quest_legacy_ids.json").read_text(encoding="utf-8"))

# Itens vanilla 1.21.1 verificados usados como icones/tarefas/recompensas.
VANILLA_ITEMS = {
    "minecraft:wet_sponge",
    "minecraft:acacia_log",
    "minecraft:amethyst_shard",
    "minecraft:andesite",
    "minecraft:anvil",
    "minecraft:apple",
    "minecraft:armor_stand",
    "minecraft:bamboo",
    "minecraft:barrel",
    "minecraft:beacon",
    "minecraft:bee_nest",
    "minecraft:bell",
    "minecraft:blaze_powder",
    "minecraft:blaze_rod",
    "minecraft:bone",
    "minecraft:book",
    "minecraft:bookshelf",
    "minecraft:bow",
    "minecraft:bread",
    "minecraft:brick",
    "minecraft:bricks",
    "minecraft:bucket",
    "minecraft:cake",
    "minecraft:campfire",
    "minecraft:candle",
    "minecraft:carrot",
    "minecraft:cartography_table",
    "minecraft:chain",
    "minecraft:chest",
    "minecraft:chiseled_bookshelf",
    "minecraft:clock",
    "minecraft:coal",
    "minecraft:cobblestone",
    "minecraft:comparator",
    "minecraft:compass",
    "minecraft:conduit",
    "minecraft:cooked_beef",
    "minecraft:copper_ingot",
    "minecraft:crafting_table",
    "minecraft:crying_obsidian",
    "minecraft:dark_oak_log",
    "minecraft:diamond",
    "minecraft:diamond_axe",
    "minecraft:diamond_boots",
    "minecraft:diamond_chestplate",
    "minecraft:diamond_helmet",
    "minecraft:diamond_horse_armor",
    "minecraft:diamond_leggings",
    "minecraft:diamond_pickaxe",
    "minecraft:diamond_sword",
    "minecraft:dragon_breath",
    "minecraft:dragon_head",
    "minecraft:dried_kelp",
    "minecraft:elytra",
    "minecraft:emerald",
    "minecraft:enchanted_book",
    "minecraft:enchanted_golden_apple",
    "minecraft:enchanting_table",
    "minecraft:end_crystal",
    "minecraft:end_stone",
    "minecraft:ender_chest",
    "minecraft:ender_eye",
    "minecraft:ender_pearl",
    "minecraft:experience_bottle",
    "minecraft:feather",
    "minecraft:filled_map",
    "minecraft:firework_rocket",
    "minecraft:fishing_rod",
    "minecraft:flint_and_steel",
    "minecraft:furnace",
    "minecraft:glass",
    "minecraft:glass_bottle",
    "minecraft:glow_ink_sac",
    "minecraft:glowstone",
    "minecraft:glowstone_dust",
    "minecraft:gold_ingot",
    "minecraft:golden_apple",
    "minecraft:golden_carrot",
    "minecraft:golden_helmet",
    "minecraft:grass_block",
    "minecraft:gunpowder",
    "minecraft:hay_block",
    "minecraft:heart_of_the_sea",
    "minecraft:honey_bottle",
    "minecraft:honeycomb",
    "minecraft:hopper",
    "minecraft:ice",
    "minecraft:iron_axe",
    "minecraft:iron_bars",
    "minecraft:iron_block",
    "minecraft:iron_boots",
    "minecraft:iron_chestplate",
    "minecraft:iron_helmet",
    "minecraft:iron_ingot",
    "minecraft:iron_pickaxe",
    "minecraft:iron_sword",
    "minecraft:item_frame",
    "minecraft:jukebox",
    "minecraft:jungle_sapling",
    "minecraft:kelp",
    "minecraft:knowledge_book",
    "minecraft:lantern",
    "minecraft:lapis_lazuli",
    "minecraft:lava_bucket",
    "minecraft:lead",
    "minecraft:leather",
    "minecraft:lectern",
    "minecraft:lightning_rod",
    "minecraft:map",
    "minecraft:moss_block",
    "minecraft:mossy_cobblestone",
    "minecraft:name_tag",
    "minecraft:nether_brick",
    "minecraft:nether_bricks",
    "minecraft:nether_star",
    "minecraft:nether_wart",
    "minecraft:netherite_ingot",
    "minecraft:netherite_scrap",
    "minecraft:netherite_upgrade_smithing_template",
    "minecraft:netherrack",
    "minecraft:note_block",
    "minecraft:oak_boat",
    "minecraft:oak_log",
    "minecraft:oak_planks",
    "minecraft:oak_sapling",
    "minecraft:obsidian",
    "minecraft:painting",
    "minecraft:paper",
    "minecraft:piston",
    "minecraft:player_head",
    "minecraft:prismarine_shard",
    "minecraft:pumpkin_pie",
    "minecraft:quartz",
    "minecraft:rail",
    "minecraft:raw_copper",
    "minecraft:raw_iron",
    "minecraft:recovery_compass",
    "minecraft:red_bed",
    "minecraft:redstone",
    "minecraft:redstone_block",
    "minecraft:redstone_lamp",
    "minecraft:redstone_torch",
    "minecraft:repeater",
    "minecraft:sand",
    "minecraft:sandstone",
    "minecraft:sculk_sensor",
    "minecraft:sea_lantern",
    "minecraft:shears",
    "minecraft:shield",
    "minecraft:shulker_box",
    "minecraft:slime_ball",
    "minecraft:smithing_table",
    "minecraft:smoker",
    "minecraft:snowball",
    "minecraft:soul_lantern",
    "minecraft:soul_sand",
    "minecraft:spyglass",
    "minecraft:stick",
    "minecraft:stone",
    "minecraft:stone_bricks",
    "minecraft:string",
    "minecraft:sugar",
    "minecraft:sweet_berries",
    "minecraft:tnt",
    "minecraft:torch",
    "minecraft:totem_of_undying",
    "minecraft:trident",
    "minecraft:trial_key",
    "minecraft:water_bucket",
    "minecraft:wheat",
    "minecraft:white_bed",
    "minecraft:white_wool",
    "minecraft:wind_charge",
    "minecraft:witch_spawn_egg",
    "minecraft:writable_book",
    "minecraft:written_book",
    "minecraft:zinc_ingot" if False else "minecraft:gold_nugget",
}


class IdAlloc:
    def __init__(self, prefix_hex_nibble: int):
        self.base = prefix_hex_nibble << 60
        self.counter = 0

    def next(self) -> str:
        self.counter += 1
        return f"{self.base + self.counter:016X}"


def stable_id(prefix: int, key: str, legacy: int = 0) -> str:
    if legacy:
        return f"{prefix:X}{legacy:015X}"
    digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
    return f"{prefix:X}F{digest[:14].upper()}"


def snbt_str(s: str) -> str:
    escaped = (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "")
    )
    return f'"{escaped}"'


def quest_text(text: str) -> str:
    # O renderizador do FTB reserva & para códigos de cor e estilo.
    return re.sub(r"(?<!\\)&(?![0-9a-fklmnorz]|#[0-9a-fA-F]{6})", lambda _: r"\&", text)


def snbt_float_d(v: float) -> str:
    if float(int(v)) == float(v):
        return f"{int(v)}.0d"
    return f"{v}d"


def write_snbt_file(path: Path, lines: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = "\n".join(lines) + "\n"
    path.write_text(text, encoding="utf-8", newline="\n")


def scan_installed_mods() -> tuple[dict[str, dict[str, Any]], set[str]]:
    """Escaneia todos os .jar em mods/ (incluindo JIJ e Fabric jars) e extrai metadados e itens validos."""
    mods_info: dict[str, dict[str, Any]] = {}
    all_items: set[str] = set(VANILLA_ITEMS)

    def _scan_zip(zf: zipfile.ZipFile, jar_label: str, top_level: bool) -> None:
        nl = zf.namelist()
        entries: list[dict[str, Any]] = []
        if "META-INF/neoforge.mods.toml" in nl:
            parsed = tomllib.loads(zf.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace"))
            entries = parsed.get("mods", [])
        elif "META-INF/mods.toml" in nl:
            parsed = tomllib.loads(zf.read("META-INF/mods.toml").decode("utf-8", "replace"))
            entries = parsed.get("mods", [])
        elif "fabric.mod.json" in nl:
            parsed = json.loads(zf.read("fabric.mod.json").decode("utf-8", "replace"))
            entries = [
                {
                    "modId": parsed.get("id", ""),
                    "displayName": parsed.get("name", parsed.get("id", "")),
                    "description": parsed.get("description", ""),
                }
            ]

        for m in entries:
            mid = m.get("modId", "").strip()
            if not mid or mid in ("minecraft", "neoforge", "forge"):
                continue

            model_items = {
                n.split("/")[-1][:-5]
                for n in nl
                if n.startswith(f"assets/{mid}/models/item/") and n.endswith(".json")
            }
            item_defs = {
                n.split("/")[-1][:-5]
                for n in nl
                if n.startswith(f"assets/{mid}/items/") and n.endswith(".json")
            }
            recipe_items: set[str] = set()
            for n in nl:
                if (n.startswith(f"data/{mid}/recipe/") or n.startswith(f"data/{mid}/recipes/")) and n.endswith(".json"):
                    try:
                        rdata = json.loads(zf.read(n).decode("utf-8", "replace"))
                        res = rdata.get("result")
                        if isinstance(res, dict) and isinstance(res.get("id"), str):
                            recipe_items.add(res["id"])
                        elif isinstance(res, str):
                            recipe_items.add(res)
                    except Exception:
                        pass

            lang_items: set[str] = set()
            for lp in (f"assets/{mid}/lang/en_us.json", f"assets/{mid}/lang/pt_br.json"):
                if lp in nl:
                    try:
                        ld = json.loads(zf.read(lp).decode("utf-8", "replace"))
                        for k in ld:
                            if (k.startswith(f"item.{mid}.") or k.startswith(f"block.{mid}.")) and k.count(".") == 2:
                                lang_items.add(k.split(".")[2])
                    except Exception:
                        pass

            # Modelos e traduções também incluem itens condicionais.
            candidates = (model_items | item_defs)
            verified_short = (candidates & lang_items) if lang_items else candidates
            full_verified = {f"{mid}:{x}" for x in (verified_short | candidates)}
            for ri in recipe_items:
                if ":" in ri and ri.startswith(f"{mid}:"):
                    full_verified.add(ri)

            all_items.update(full_verified)

            if mid not in mods_info:
                mods_info[mid] = {
                    "modId": mid,
                    "displayName": m.get("displayName", mid),
                    "description": (m.get("description", "") or "").strip().replace("\r", "").replace("\n", " "),
                    "jars": [jar_label],
                    "top_level": top_level,
                    "verified_preferred": sorted(f"{mid}:{x}" for x in verified_short if not x.endswith(("_incomplete", "_unfinished")) and x not in {"block", "item", "2", "core", "head", "body", "base"}),
                    "all_models": sorted(full_verified),
                }
            else:
                mods_info[mid]["jars"].append(jar_label)
                mods_info[mid]["top_level"] = mods_info[mid]["top_level"] or top_level
                mods_info[mid]["verified_preferred"] = sorted(
                    set(mods_info[mid]["verified_preferred"])
                    | {f"{mid}:{x}" for x in verified_short if x not in {"block", "item", "2", "core", "head", "body", "base"}}
                )
                mods_info[mid]["all_models"] = sorted(set(mods_info[mid]["all_models"]) | full_verified)

        for n in nl:
            if (n.startswith("META-INF/jarjar/") or n.startswith("META-INF/jars/")) and n.endswith(".jar"):
                try:
                    with zipfile.ZipFile(io.BytesIO(zf.read(n))) as iz:
                        _scan_zip(iz, jar_label + "!" + os.path.basename(n), False)
                except Exception:
                    pass

    for p in sorted(glob.glob(str(MODS_DIR / "*.jar"))):
        try:
            with zipfile.ZipFile(p) as z:
                _scan_zip(z, os.path.basename(p), True)
        except Exception as e:
            print(f"Aviso ao ler {p}: {e}")

    # Tambem adicionar caso especial para jars nao-padrao (ex: Essential) se nao tiverem toml na raiz
    return mods_info, all_items


@dataclass
class TaskSpec:
    kind: str  # "item", "checkmark", "dimension", "advancement", "kill"
    value: str = ""
    count: int = 1
    title: str = ""  # usado em checkmark/dimension


@dataclass
class RewardSpec:
    kind: str  # "xp" or "item"
    xp: int = 25
    item: str = ""
    count: int = 1


@dataclass
class QuestSpec:
    key: str
    title: str
    subtitle: str
    desc: list[str]
    x: float
    y: float
    icon: str | None = None
    shape: str = "circle"
    size: float = 1.0
    deps: list[str] = field(default_factory=list)
    tasks: list[TaskSpec] = field(default_factory=list)
    rewards: list[RewardSpec] = field(default_factory=list)
    mod_refs: list[str] = field(default_factory=list)
    optional: bool = False


@dataclass
class ChapterSpec:
    filename: str
    group_key: str
    title: str
    subtitle: str
    icon: str
    default_shape: str = "circle"
    quests: list[QuestSpec] = field(default_factory=list)


def item_q(
    key: str,
    title: str,
    subtitle: str,
    item_id: str,
    x: float,
    y: float,
    *,
    desc: list[str],
    count: int = 1,
    deps: list[str] | None = None,
    xp: int = 25,
    reward_item: tuple[str, int] | None = None,
    shape: str = "circle",
    size: float = 1.0,
    extra_items: list[tuple[str, int]] | None = None,
    mod_refs: list[str] | None = None,
    optional: bool = False,
) -> QuestSpec:
    tasks = [TaskSpec(kind="item", value=item_id, count=count)]
    if extra_items:
        for eid, ec in extra_items:
            tasks.append(TaskSpec(kind="item", value=eid, count=ec))
    rewards = [RewardSpec(kind="xp", xp=xp)]
    if reward_item:
        rewards.append(RewardSpec(kind="item", item=reward_item[0], count=reward_item[1]))
    return QuestSpec(
        key=key,
        title=title,
        subtitle=subtitle,
        desc=desc,
        x=x,
        y=y,
        icon=item_id,
        shape=shape,
        size=size,
        deps=deps or [],
        tasks=tasks,
        rewards=rewards,
        optional=optional,
        mod_refs=mod_refs or [item_id.split(":")[0]],
    )


def check_q(
    key: str,
    title: str,
    subtitle: str,
    icon_id: str,
    x: float,
    y: float,
    *,
    desc: list[str],
    task_title: str = "Marcar como lido / entendido",
    deps: list[str] | None = None,
    xp: int | None = 15,
    reward_item: tuple[str, int] | None = None,
    shape: str = "circle",
    size: float = 1.0,
    mod_refs: list[str] | None = None,
    optional: bool = False,
) -> QuestSpec:
    rewards = [RewardSpec(kind="xp", xp=xp)] if xp is not None else []
    if reward_item:
        rewards.append(RewardSpec(kind="item", item=reward_item[0], count=reward_item[1]))
    return QuestSpec(
        key=key,
        title=title,
        subtitle=subtitle,
        desc=desc,
        x=x,
        y=y,
        icon=icon_id,
        shape=shape,
        size=size,
        deps=deps or [],
        tasks=[TaskSpec(kind="checkmark", title=task_title)],
        rewards=rewards,
        optional=optional,
        mod_refs=mod_refs or [],
    )


def dim_q(
    key: str,
    title: str,
    subtitle: str,
    dim_id: str,
    icon_id: str,
    x: float,
    y: float,
    *,
    desc: list[str],
    deps: list[str] | None = None,
    xp: int = 100,
    reward_item: tuple[str, int] | None = None,
    shape: str = "octagon",
    size: float = 1.5,
    mod_refs: list[str] | None = None,
    optional: bool = False,
) -> QuestSpec:
    rewards = [RewardSpec(kind="xp", xp=xp)]
    if reward_item:
        rewards.append(RewardSpec(kind="item", item=reward_item[0], count=reward_item[1]))
    return QuestSpec(
        key=key,
        title=title,
        subtitle=subtitle,
        desc=desc,
        x=x,
        y=y,
        icon=icon_id,
        shape=shape,
        size=size,
        deps=deps or [],
        tasks=[TaskSpec(kind="dimension", value=dim_id, title=f"Entrar em {dim_id}")],
        rewards=rewards,
        optional=optional,
        mod_refs=mod_refs or [],
    )


# Dicionario de descricoes curadas em PT-BR para TODOS os mods do pack (inclusive tecnicos/libs)
CURATED_MOD_DESCRIPTIONS: dict[str, tuple[str, str, str]] = {
    # modid: (Categoria, Icone tematico se nao houver item proprio, Descricao PT-BR)
    "aeronautics": ("Aeronautica & Fisica", "aeronautics:andesite_propeller", "Adiciona dirigiveis, helices, levitite e aeronaves com fisica real baseada no Create."),
    "aeronautics_bundled": ("Aeronautica & Fisica", "aeronautics:aviators_goggles", "Pacote unificado do Create Aeronautics (aeronaves, offroad e simulacao fisica)."),
    "aeronautics_utility_objects": ("Aeronautica & Fisica", "aeronautics_utility_objects:universal_joint", "Juntas universais, pistoes hidraulicos e ligacoes cineticas para veiculos e aeronaves."),
    "aeroclaims": ("Aeronautica & Fisica", "aeroclaims:claim_block", "Sistema de reivindicacao e protecao de contraptions do Aeronautics integrado ao OPAC/FTB."),
    "aeroencasedpipe": ("Aeronautica & Fisica", "aeroencasedpipe:white_envelope_encased_pipe", "Tubos de fluidos encapsulados nos envelopes de balao do Create Aeronautics."),
    "aeroworks": ("Aeronautica & Fisica", "aeroworks:control_desk", "Cabines, joysticks, giroscopios e modulos de controle para aeronaves."),
    "aileron": ("Aeronautica & Fisica", "minecraft:elytra", "Reformula a mecanica da Elytra com curvas suaves, correntes termicas e fogueiras propulsoras."),
    "alloyed": ("Engenharia Create", "alloyed:steel_ingot", "Adiciona ligas metalurgicas de Bronze e Aco ao Create, com ferramentas, blocos e engrenagens."),
    "amendments": ("Decoracao & Vanilla+", "minecraft:lantern", "Melhorias visuais e funcionais para blocos vanilla: lanternas na parede, potes, bolos e cranios."),
    "another_furniture": ("Decoracao & Vanilla+", "another_furniture:oak_chair", "Moveis decorativos em estilo vanilla: cadeiras, sofas, mesas, gavetas, prateleiras e cortinas."),
    "apollib": ("Biblioteca", "minecraft:bookshelf", "Biblioteca de configuracao usada pelos mods do autor Apollo."),
    "appleskin": ("Interface & QoL", "minecraft:apple", "Exibe saturacao, exaustao e quanto de fome/vida cada alimento restaura no HUD."),
    "architectury": ("Biblioteca", "minecraft:crafting_table", "API multiplataforma obrigatoria para rodar FTB Quests, FTB Library e outros mods."),
    "armorstatues": ("Decoracao & Vanilla+", "minecraft:armor_stand", "Menu completo para posar Suportes de Armadura com precisao (clique com Shift+Botao Direito)."),
    "astemirlib_re": ("Biblioteca", "minecraft:bookshelf", "Biblioteca de entidades, animacoes e utilitarios compartilhados."),
    "asyncparticles": ("Performance & Render", "minecraft:blaze_powder", "Processa particulas de forma assincrona para evitar quedas de FPS em fabricas gigantes."),
    "atmosphere": ("Biblioteca", "minecraft:feather", "Biblioteca para sistemas de clima e atmosfera localizados."),
    "automodpack": ("Infraestrutura Pack", "minecraft:chest", "Mod utilitario de sincronizacao automatica de mods no cliente/servidor."),
    "automodpack_mod": ("Infraestrutura Pack", "minecraft:ender_chest", "Modulo principal de transferencia do AutoModpack."),
    "axesareweapons": ("Combate & QoL", "minecraft:iron_axe", "Permite encantar machados com Saque (Looting) e remove desgaste extra em combate."),
    "azimuth": ("Engenharia Create", "azimuth:ponder_coordinate", "Biblioteca de extensao e ferramentas visuais de Ponder para addons do Create."),
    "badoptimizations": ("Performance & Server", "minecraft:redstone", "Otimizacoes de CPU no tick do jogo (luz do ceu, calculos de debug e sons) sem alterar graficos."),
    "ballastmod": ("Aeronautica & Fisica", "ballastmod:ballast_stones", "Blocos de lastro ajustavel para equilibrar peso e centro de massa em naves/barcos."),
    "balm": ("Biblioteca", "minecraft:bookshelf", "Camada de abstracao usada por mods do Blay (Waystones/Crafting Tweaks/etc.)."),
    "beekeeperhut": ("Mundo & Estruturas", "minecraft:bee_nest", "Adiciona a estrutura Cabana do Apicultor do Friends&Foes pelos biomas."),
    "bellsandwhistles": ("Ferrovias Create", "bellsandwhistles:headlight", "Pecas esteticas para trens do Create: farois, degraus, paineis de metro e limpa-trilhos."),
    "betterbiomereblend": ("Performance & Render", "minecraft:grass_block", "Melhora drasticamente o algoritmo e o raio de suavizacao de cores entre biomas."),
    "betterdeserttemples": ("Mundo & Estruturas", "minecraft:sandstone", "YUNG's: Transforma os Templos do Deserto em masmorras ricas com salas secretas e desafios."),
    "betterdungeons": ("Mundo & Estruturas", "minecraft:mossy_cobblestone", "YUNG's: Substitui as masmorras pequenas por grandes catacumbas e fortalezas subterraneas."),
    "betterendisland": ("Mundo & Estruturas", "minecraft:end_crystal", "YUNG's: Ilha do Dragao completamente reconstruida com novos pilares, templo e luta reformulada."),
    "betterfortresses": ("Mundo & Estruturas", "minecraft:nether_bricks", "YUNG's: Fortalezas do Nether colossais com varios andares, biblioteca, salas de lava e espolios."),
    "betterjungletemples": ("Mundo & Estruturas", "minecraft:jungle_sapling", "YUNG's: Templos da Selva gigantes com armadilhas reais e quebra-cabecas."),
    "bettermineshafts": ("Mundo & Estruturas", "minecraft:rail", "YUNG's: Minas Abandonadas tematizadas por bioma, mais seguras, bonitas e exploraveis."),
    "betteroceanmonuments": ("Mundo & Estruturas", "minecraft:sea_lantern", "YUNG's: Monumentos Oceanicos muito mais profundos com novas salas subaquaticas."),
    "betterstrongholds": ("Mundo & Estruturas", "minecraft:ender_eye", "YUNG's: Fortalezas do Portal do End gigantescas com prisoes, bibliotecas e armaria."),
    "betterwitchhuts": ("Mundo & Estruturas", "minecraft:witch_spawn_egg", "YUNG's: Cabanas da Bruxa reformuladas com subsolo, caldeirao e circulo de cogumelos."),
    "biolith": ("Mundo & Biblioteca", "minecraft:grass_block", "Biblioteca de geracao de mundos e integracao de biomas."),
    "bits_n_bobs": ("Engenharia Create", "bits_n_bobs:andesite_tiles", "Adiciona cadeiras coloridas, placas Nixie grandes, blocos ornamentais e engrenagens flangeadas."),
    "bookshelf": ("Biblioteca", "minecraft:bookshelf", "Biblioteca base exigida por diversos mods do modpack."),
    "archers": ("Combate", "archers:iron_spear", "Arcos, lanças e equipamento de arqueiro."),
    "bards_rpg": ("Combate", "bards_rpg:golden_lyre", "Instrumentos e habilidades de suporte para bardos."),
    "berserker_rpg": ("Combate", "berserker_rpg:iron_berserker_axe", "Machados e equipamentos de berserker."),
    "brick_and_mortar": ("Decoracao & Vanilla+", "brick_and_mortar:black_ash_bricks", "Tijolos, azulejos e fornada especializada com dezenas de variantes de construcao."),
    "c2me": ("Performance & Server", "minecraft:compass", "Concurrent Chunk Management Engine: gera e carrega chunks usando varios nucleos da CPU."),
    "cardboarded_conveynience": ("Engenharia Create", "create:cardboard_block", "Permite se disfarcar de encomenda de papelao e viajar pelas esteiras aereas (Chain Conveyors)!"),
    "forcemaster_rpg": ("Combate", "forcemaster_rpg:iron_knuckle", "Armas e habilidades para o Forcemaster."),
    "lne_archers": ("Exploração", "minecraft:bow", "Integra arqueiros ao loot de estruturas."),
    "lne_paladins": ("Exploração", "minecraft:shield", "Integra paladinos ao loot de estruturas."),
    "lne_rogues": ("Exploração", "minecraft:iron_sword", "Integra ladinos ao loot de estruturas."),
    "rogues": ("Combate", "rogues:flint_dagger", "Armas e habilidades de ladinos e guerreiros."),
    "wizards": ("Combate", "wizards:staff_arcane", "Cajados e feitiços elementais."),
    "carryon": ("Sobrevivencia & QoL", "minecraft:chest", "Segure Shift + Botao Direito com as maos vazias para carregar baus cheios, fornalhas e animais!"),
    "carryonaerocompat": ("Compatibilidade", "minecraft:chest", "Permite usar o Carry On dentro de veiculos e naves do Create Aeronautics/Sable."),
    "cerulean": ("Performance & Server", "minecraft:knowledge_book", "Otimiza o sistema interno de Advancements para eliminar travamentos ao ganhar conquistas."),
    "chat_heads": ("Interface & QoL", "minecraft:player_head", "Mostra o rosto da skin de cada jogador ao lado das mensagens no chat."),
    "chunky": ("Performance & Server", "minecraft:filled_map", "Ferramenta de pre-geracao de chunks no servidor para evitar lag durante a exploracao."),
    "cloth_config": ("Biblioteca", "minecraft:paper", "Biblioteca de telas de configuracao usada por varios mods."),
    "codecui": ("Biblioteca", "minecraft:redstone", "Geracao automatica de menus de configuracao via DataFixerUpper codecs."),
    "collective": ("Biblioteca", "minecraft:bookshelf", "Biblioteca compartilhada obrigatoria para Tree Harvester e mods da serie Serilum."),
    "colorwheel": ("Performance & Shaders", "minecraft:glowstone", "Ponte essencial para rodar Flywheel (Create) com shaders do Iris em alto FPS."),
    "commongroovylibrary": ("Biblioteca", "minecraft:paper", "Biblioteca de suporte para mods baseados em GroovyModLoader."),
    "companion": ("Sobrevivencia & QoL", "minecraft:bone", "Impede dano acidental de aliados em seus pets domados e os ajuda a atravessar portais."),
    "compat_manager": ("Biblioteca", "minecraft:comparator", "Gerenciador leve de compatibilidades entre addons."),
    "configureddefaults": ("Infraestrutura Pack", "minecraft:writable_book", "Aplica automaticamente as configuracoes padrao ideais do Create+ sem sobrescrever suas edicoes."),
    "connectiblechains": ("Decoracao & Vanilla+", "minecraft:chain", "Ligue cercas e postes usando correntes pendentes (Botao Direito com Correntes)!"),
    "connector": ("Compatibilidade", "minecraft:repeater", "Sinytra Connector: permite rodar mods Fabric selecionados dentro do NeoForge 1.21.1."),
    "continuity": ("Visual & Texturas", "minecraft:glass", "Texturas conectadas estilo OptiFine para vidros, blocos lisos e resource packs."),
    "controlling": ("Interface & QoL", "minecraft:compass", "Adiciona barra de busca por nome e tecla no menu de Atalhos (Keybinds)."),
    "copycats": ("Decoracao Create", "copycats:copycat_block", "Create: Copycats+: dezenas de blocos camaleao (lajes, vigas, escadas, paineis) que copiam qualquer textura!"),
    "coroutil": ("Biblioteca", "minecraft:paper", "Biblioteca utilitaria de particulas, clima e entidades."),
    "create": ("Engenharia Create", "create:cogwheel", "O coracao do modpack! Engrenagens, eixos, cinetica, fabricas, trens e logistica Create 6.0."),
    "create_access_denied": ("Engenharia Create", "create:brass_table_cloth", "Controle de acesso por jogador/time para redes de estoque e encomendas do Create 6.0."),
    "create_aeronautics_toolgun": ("Aeronautica & Fisica", "create_aeronautics_toolgun:magnetic_gun", "Arma magnetica (Toolgun) para construir, mover, clonar e empacotar veiculos fisicos."),
    "create_ca": ("Engenharia Create", "create_ca:brass_gearbox", "Adiciona Brass Gearbox, embreagens invertidas, Chain Drives de latao e Bacia de Latao."),
    "create_dragons_plus": ("Engenharia Create", "create_dragons_plus:dragon_breath_bucket", "Automacao de Bafo do Dragao, fluidos de corantes e bicos ejetores especializados."),
    "create_enchantment_industry": ("Engenharia Create", "create_enchantment_industry:blaze_enchanter", "Automatize XP liquido, desencantamento, impressao de livros e forja de encantamentos!"),
    "create_factory": ("Engenharia Create", "create_factory:chocolate_waffle", "Linha alimenticia industrial do Create: geleias, rocamboles, waffles, macas caramelizadas e potes."),
    "create_flight_control": ("Aeronautica & Fisica", "create_flight_control:flight_control_computer", "Computadores de voo, piloto automatico (altitude/atitude), vetores de empuxo e ganchos de pouso."),
    "create_pattern_schematics": ("Engenharia Create", "create_pattern_schematics:pattern_schematic", "Schematics inteligentes para replicar padroes repetitivos (tuneis, pontes, muros)."),
    "create_power_loader": ("Engenharia Create", "create_power_loader:andesite_chunk_loader", "Chunk Loaders movidos a energia rotacional (SU) para manter fabricas e trens ativos."),
    "create_radar": ("Artilharia & Radares", "create_radar:radar_bearing", "Sistemas de radar, sonar, monitores de alvos e mira automatica para canhoes!"),
    "createbigcannons": ("Artilharia & Combate", "createbigcannons:cannon_mount", "Fundicao de ferro/aco/bronze, canhoes artilheiros montaveis, autocanhoes e projeteis AP/HE."),
    "createcircuitboard": ("Engenharia Create", "createcircuitboard:circuit_board", "Placas de circuito logico programaveis integradas aos Redstone Links do Create."),
    "createdieselgenerators": ("Energia & Combustiveis", "createdieselgenerators:diesel_engine", "Extracao de petroleo com Pumpjacks, torres de destilacao, biodiesel e motores a diesel."),
    "createframed": ("Decoracao Create", "createframed:andesite_alloy_window", "Variantes coloridas e emolduradas de vidros, portas e blocos de papelao para Create."),
    "createlanterns": ("Decoracao & Energia", "createlanterns:gold_display_case", "Lanternas, luz liquida e baterias decorativas estilizadas para o Create."),
    "createprism": ("Decoracao Create", "createprism:andesite_clear_glass_casing", "Casings de vidro limpo e iluminados de Andesito, Cobre, Latao e Ferroviario."),
    "createpropulsion": ("Aeronautica & Fisica", "createpropulsion:ion_thruster", "Propulsores ionicos/liquidos, geradores de plasma, cabos e sensores opticos no Sable."),
    "createrailwaysnavigator": ("Ferrovias Create", "createrailwaysnavigator:navigator", "Navegador de rotas ferroviarias e paineis eletronicos de horarios de estacoes."),
    "createshufflefilter": ("Engenharia Create", "createshufflefilter:shuffle_filter", "Filtro aleatorio com peso ajustavel para Deployers e funis do Create."),
    "createthreadedtrains": ("Performance & Trens", "create:track", "Processa a logica da rede de trens em thread paralela para evitar lag com muitos comboios."),
    "createthrusters": ("Aeronautica & Fisica", "createthrusters:advanced_contraption_controller", "Create Gadgets & Gizmos: controladores de naves, propulsores RCS, ailerons e alternadores."),
    "createthrusters_bundled": ("Aeronautica & Fisica", "createthrusters:diagnostic_tablet", "Pacote unificado do Create Gadgets & Gizmos."),
    "cutthrough": ("Combate & QoL", "minecraft:diamond_sword", "Permite acertar mobs atraves de grama alta, flores e cipós sem quebrar as plantas."),
    "deployer": ("Engenharia Create", "deployer:rare_liukrast_package", "Biblioteca e utilitarios adicionais para sistemas de encomendas e addons do Create."),
    "detailab": ("Interface & QoL", "minecraft:diamond_chestplate", "Barra de armadura detalhada mostrando exatamente o tipo e material equipado."),
    "ding": ("Interface & QoL", "minecraft:bell", "Toca um som de sino quando o Minecraft termina de carregar o menu ou entra no servidor."),
    "distractingtrims": ("Combate & QoL", "minecraft:golden_helmet", "Acabamentos (Trims) de ouro em qualquer armadura distraem Piglins assim como pecas de ouro."),
    "dragonlib": ("Biblioteca", "dragonlib:dragon", "Biblioteca de suporte multi-loader exigida pelo Create Railways Navigator."),
    "dynamic_fps": ("Performance & Render", "minecraft:clock", "Reduz automaticamente o uso de GPU/CPU quando a janela do Minecraft esta minimizada."),
    "easyanvils": ("Sobrevivencia & QoL", "minecraft:anvil", "Melhora bigornas: itens permanecem nela se voce fechar, permite colorir nomes e editar com facilidade."),
    "eatinganimation": ("Visual & Imersao", "minecraft:bread", "Animacoes exclusivas passo-a-passo ao comer comidas ou beber pocoes na mao."),
    "eclipseui": ("Biblioteca", "minecraft:painting", "Biblioteca de interfaces modernas usada por mods do pack."),
    "ecologics": ("Mundo & Biomas", "ecologics:coconut", "Enriquece biomas vanilla com coqueiros, nogueiras, pinguins, esquilos, camelos e castelos de areia."),
    "emi": ("Interface & Receitas", "minecraft:crafting_table", "Visualizador rapido de receitas e usos (teclas R = receita, U = onde usa, arvore de craft!)."),
    "emi_loot": ("Interface & Receitas", "minecraft:chest", "Mostra dentro do EMI as tabelas de loot de baus, drops de mobs e pesca."),
    "ends_delight": ("Gastronomia", "ends_delight:end_stove", "Addon culinario de Farmer's Delight para o The End com receitas de Chorus e Dragao."),
    "entity_model_features": ("Visual & Texturas", "minecraft:armor_stand", "Suporte a modelos 3D customizados de entidades (CEM) em Resource Packs."),
    "entity_texture_features": ("Visual & Texturas", "minecraft:item_frame", "Suporte a texturas emissivas, piscantes e variantes para entidades em Resource Packs."),
    "entityculling": ("Performance & Render", "minecraft:spyglass", "Oculta entidades e Block Entities atras de paredes usando path-tracing assincrono (muito mais FPS!)."),
    "escalated": ("Engenharia Create", "escalated:metal_walkway_steps", "Escadas rolantes e esteiras transportadoras para movimentacao elegante de jogadores."),
    "euphoria_patcher": ("Visual & Shaders", "minecraft:glowstone", "Aplica os patches extras da comunidade Euphoria aos shaders Complementary."),
    "explosiveenhancement": ("Visual & Imersao", "minecraft:tnt", "Efeitos visuais aprimorados de explosoes com onda de choque, fagulhas e fumaca realista."),
    "extendedwrenches": ("Engenharia Create", "extendedwrenches:extended_wrench", "Personalize a aparencia, cabo, cabeca e engrenagens da sua Chave Inglesa do Create."),
    "extra_gauges": ("Engenharia Create", "extra_gauges:logic_gauge", "Novos medidores de fabrica para Create 6.0 (logicos, contadores, filtros, expressoes)."),
    "fabric_api": ("Compatibilidade", "minecraft:repeater", "Forgified Fabric API: camada base de compatibilidade para mods Fabric rodando no NeoForge."),
    "farmersdelight": ("Gastronomia", "farmersdelight:cooking_pot", "Agricultura aconchegante e culinaria rica: fogao, panela, tabua de corte, solo rico e banquetes!"),
    "fastpaintings": ("Performance & Render", "minecraft:painting", "Converte quadros em modelos estaticos super leves para o FPS nao cair perto de galerias."),
    "ferritecore": ("Performance & Memoria", "minecraft:iron_block", "Reduz drasticamente o consumo de memoria RAM do cliente e do servidor."),
    "flerovium": ("Performance & Render", "minecraft:emerald", "Otimiza o pipeline de renderizacao de itens e blocos para ganho extra de FPS."),
    "flowerymooblooms": ("Mundo & Mobs", "friendsandfoes:buttercup", "Adiciona variantes de Moobloom (vacas floridas) para praticamente todas as flores do jogo!"),
    "flywheel": ("Performance & Create", "create:flywheel", "Motor de renderizacao instanciada de altissima performance criado para maquinas do Create."),
    "friendsandfoes": ("Mundo & Mobs", "friendsandfoes:totem_of_freezing", "Traz os mobs esquecidos das votacoes: Copper Golem, Glare, Tuff Golem, Iceologer, Crab e Wildfire."),
    "ftbfiltersystem": ("FTB Quests", "ftbfiltersystem:smart_filter", "Sistema inteligente de filtros compostos por tags, mod, NBT e nome."),
    "ftblibrary": ("FTB Quests", "ftblibrary:icon_item", "Biblioteca de interface e utilitarios para os mods FTB."),
    "ftbquests": ("FTB Quests", "ftbquests:book", "Sistema de missoes e guias interativos! Voce esta usando ele agora mesmo."),
    "ftbteams": ("FTB Quests", "minecraft:player_head", "Permite criar times (/ftbteams) para compartilhar progresso de quests com os amigos."),
    "ftbxmodcompat": ("FTB Quests", "minecraft:comparator", "Integracao automatica do FTB Quests com EMI, Lootr e Open Parties & Claims."),
    "fzzy_config": ("Biblioteca", "minecraft:paper", "Motor de configuracao automatica cliente-servidor usado por mods do pack."),
    "gadgetsngizmos": ("Aeronautica & Fisica", "createthrusters:aileron_bearing", "Biblioteca compartilhada de mecanicas do Gadgets & Gizmos."),
    "geckolib": ("Biblioteca", "minecraft:feather", "Motor de animacoes 3D por keyframes usado por entidades e blocos customizados."),
    "gml": ("Biblioteca", "minecraft:writable_book", "GroovyModLoader: suporte a mods escritos em Groovy."),
    "gpu_booster": ("Performance & Render", "minecraft:redstone_block", "Aplica recursos modernos de OpenGL 4+ para acelerar renderizacao na placa de video."),
    "ichunutil": ("Biblioteca", "minecraft:paper", "Biblioteca base exigida por mods da familia iChun."),
    "immediatelyfast": ("Performance & Render", "minecraft:lightning_rod", "Otimiza renderizacao imediata (HUD, textos, mapas, entidades e inventarios) aumentando FPS."),
    "iris": ("Visual & Shaders", "minecraft:spyglass", "Suporte nativo e otimizado a Shader Packs modernos com alta compatibilidade com Sodium."),
    "ixeris": ("Performance & Input", "minecraft:compass", "Processa eventos de mouse e teclado em thread separada para resposta mais fluida."),
    "jei": ("Compatibilidade", "minecraft:crafting_table", "Stub de compatibilidade JEI fornecido pelo TMRV para addons que consultam receitas no EMI."),
    "kerria": ("Performance & Render", "minecraft:redstone_torch", "Acelera a atualizacao de texturas animadas na VRAM (lava, agua, engrenagens)."),
    "kiwi": ("Biblioteca", "minecraft:bookshelf", "Biblioteca de utilitarios e configuracao para varios mods NeoForge."),
    "konkrete": ("Biblioteca", "minecraft:stone_bricks", "Biblioteca base para UI e utilitarios."),
    "kotlinforforge": ("Biblioteca", "minecraft:emerald", "Runtime da linguagem Kotlin para mods NeoForge/Create."),
    "lambdynlights": ("Visual & Iluminacao", "minecraft:torch", "Iluminacao dinamica: segurar tochas, lanternas ou itens em chamas ilumina o caminho em tempo real."),
    "let_it_walk": ("Aeronautica & Fisica", "let_it_walk:articulation_unit", "Construa robos e veiculos caminhantes com cinematica inversa real usando Sable + Create!"),
    "lithium": ("Performance & Server", "minecraft:redstone", "Otimiza logica do servidor e tick (IA de mobs, colisao, funis, redstone) sem quebrar mecanicas vanilla."),
    "lithostitched": ("Mundo & Biblioteca", "minecraft:stone", "Melhorias de compatibilidade para geracao de biomas e estruturas."),
    "lodestone": ("Biblioteca", "minecraft:amethyst_shard", "Biblioteca de efeitos visuais e utilitarios."),
    "lootr": ("Multiplayer & Exploracao", "lootr:trophy", "Gera loot individual em todos os baus de estruturas! Cada amigo recebe seus proprios tesouros."),
    "map_atlases": ("Exploracao & Mapas", "map_atlases:atlas", "Crie um Atlas combinando mapas vazios para carregar um livro de mapas completo no inventario."),
    "minersdelight": ("Gastronomia", "minersdelight:copper_pot", "Receitas para mineradores no Farmer's Delight: panela de cobre, caneca, cenouras da caverna e sopas."),
    "modelfix": ("Visual & Texturas", "minecraft:item_frame", "Elimina pequenas frestas transparentes nos cantos de itens segurados na mao e modelos."),
    "modernfix": ("Performance & Memoria", "minecraft:anvil", "Grandes melhorias de carregamento, uso de memoria RAM e correcoes de bugs de engine."),
    "modmenu": ("Compatibilidade", "minecraft:paper", "Ponte de telas de configuracao de mods Fabric adaptadas ao NeoForge."),
    "moonlight": ("Biblioteca", "moonlight:placeable_item", "Biblioteca essencial do Supplementaries, Amendments e Smarter Farmers."),
    "more_sounds": ("Audio & Imersao", "minecraft:note_block", "Expande o mod Sounds adicionando efeitos sonoros personalizados para blocos de mods."),
    "moreculling": ("Performance & Render", "minecraft:glass", "Evita renderizar faces escondidas de folhas, molduras e blocos translucidos para ganhar FPS."),
    "mousetweaks": ("Interface & QoL", "minecraft:chest", "Arraste itens rapidamente pelo inventario, use o scroll do mouse para mover stacks item por item."),
    "mru": ("Biblioteca", "minecraft:bookshelf", "Biblioteca de utilitarios usada pelos mods da familia Mineblock/Cassian."),
    "mynethersdelight": ("Gastronomia", "mynethersdelight:nether_bricks_stove", "Culinaria infernal do Nether para Farmer's Delight: fogao de Nether Brick, hoglin, ghast e pimentas."),
    "neobeefix": ("Correcao de Bugs", "minecraft:honeycomb", "Corrige problemas classicos de pathfinding e comportamento de abelhas no 1.21.1."),
    "nerb": ("Performance & Receitas", "minecraft:knowledge_book", "Remove o livro de receitas pesado do vanilla (substituido com folga pelo EMI), liberando RAM/CPU."),
    "netherite_horse_armor": ("Equipamentos", "netherite_horse_armor:netherite_horse_armor", "Melhore armaduras de cavalo de diamante na mesa de ferraria com lingote de Netherite!"),
    "netherportalfix": ("Multiplayer & QoL", "minecraft:obsidian", "Garante que entrar e voltar pelo Portal do Nether leve sempre ao portal correto no multiplayer."),
    "notenoughanimations": ("Visual & Imersao", "minecraft:player_head", "Adiciona animacoes realistas em terceira pessoa ao segurar mapas, comer, navegar de barco e mais."),
    "nowheel": ("Performance & Create", "create:large_cogwheel", "Otimiza drasticamente Flywheel + Entity Culling para fabricas enormes do Create rodarem lisas."),
    "numismatics": ("Economia & Create", "numismatics:cog", "Moedas mecanicas, cartoes bancarios, terminais de banco e lojas automaticas integradas ao Create!"),
    "octolib": ("Biblioteca", "minecraft:paper", "Biblioteca compartilhada de recursos."),
    "offroad": ("Aeronautica & Fisica", "offroad:tire", "Pneus de varios tamanhos, montagens de roda e brocas pesadas para carros e caminhoras fisicas."),
    "openpartiesandclaims": ("Multiplayer & Protecao", "minecraft:shield", "Sistema de Parties e reivindicacao de chunks para proteger bases e compartilhar mapas com amigos."),
    "particle_effects": ("Visual & Imersao", "minecraft:experience_bottle", "Exibe particulas tematizadas exclusivas sobre voce para cada efeito de pocao ativo."),
    "pehkui": ("Biblioteca & Fisica", "minecraft:feather", "API de redimensionamento de entidades usada por mods fisicos."),
    "pocket": ("Engenharia Create", "pocket:compression_gun", "Create: Pocket Sized: encolha contraptions, gerencie espaco reduzido e carregue mini-engenharias!"),
    "polytone": ("Visual & Texturas", "minecraft:painting", "Motor customizavel de cores, mapas de luz e som para Resource Packs."),
    "ponder": ("Engenharia Create", "create:wrench", "Sistema interativo de tutoriais 3D animado! Segure [W] sobre qualquer peca do Create no inventario."),
    "potionstacks": ("Sobrevivencia & QoL", "minecraft:honey_bottle", "Permite empilhar pocoes ate 16 unidades no mesmo slot do inventario."),
    "powerchip": ("Engenharia Create", "powerchip:chip", "Chips e componentes compactos aninhados para o sistema de circuitos do Power Grid."),
    "powergrid": ("Energia & Eletricidade", "powergrid:electric_motor", "Eletricidade completa para o Create: geradores, fios, transformadores, lampadas, baterias e multimetro!"),
    "prickle": ("Biblioteca", "minecraft:paper", "Biblioteca de configuracao JSON usada por mods."),
    "progresspeek": ("Interface & QoL", "minecraft:compass", "Exibe barra de progresso verde do carregamento do Minecraft direto no icone da barra de tarefas do Windows."),
    "puzzleslib": ("Biblioteca", "minecraft:bookshelf", "Biblioteca base usada por Easy Anvils, Armor Statues, Cut Through e outros mods de Fuzs."),
    "quark": ("Decoracao & Vanilla+", "quark:pickarang", "Dezenas de melhorias vanilla+: trowel de construcao, baus por tipo de madeira, pickarang, abaco e muito mais."),
    "quick_pack": ("Performance & Loader", "minecraft:paper", "Acelera a leitura de datapacks e resource packs durante a inicializacao."),
    "railways": ("Ferrovias Create", "railways:conductor_whistle", "Create: Steam 'n' Rails: apito, bonés condutores, acopladores, monotrilhos, bitola larga/estreita e vagoes."),
    "reeses_sodium_options": ("Interface & Config", "minecraft:compass", "Reorganiza a tela de configuracoes graficas do Sodium para ficar limpa e navegavel."),
    "reliable_advancements": ("Interface & QoL", "minecraft:knowledge_book", "Melhora a interface de conquistas (Advancements) com visualizacao limpa."),
    "remi": ("Interface & Receitas", "minecraft:crafting_table", "Reliable EMI: aprimora opcoes, favoritar e controle do catalogo de receitas EMI."),
    "resourcefulconfig": ("Biblioteca", "minecraft:paper", "Biblioteca de menus de configuracao multi-plataforma."),
    "resourcefullib": ("Biblioteca", "minecraft:bookshelf", "Biblioteca base utilizada pelo Friends&Foes e derivados."),
    "resourcify": ("Interface & Packs", "minecraft:painting", "Navegador in-game direto para baixar Resource Packs e Shader Packs do Modrinth/CurseForge."),
    "ritchiesprojectilelib": ("Artilharia & Fisica", "createbigcannons:ap_shell", "Motor de fisica balistica para projeteis de longa distancia do Create Big Cannons."),
    "roxy": ("Performance & Render", "minecraft:spyglass", "Ponte NeoForge para carregar o Voxy (renderizacao de distancias extremas LoD)."),
    "sable": ("Aeronautica & Fisica", "minecraft:elytra", "Motor revolucionario de sub-niveis fisicos moveis para naves, robos, trens e veiculos em tempo real!"),
    "sable_beyond": ("Aeronautica & Fisica", "minecraft:compass", "Extensoes de compatibilidade e melhorias para estruturas fisicas no Sable."),
    "sablecompanion": ("Aeronautica & Fisica", "minecraft:iron_ingot", "Biblioteca leve de integracao para mods suportarem contraptions do Sable."),
    "sablephysicscompat": ("Aeronautica & Fisica", "minecraft:stone", "Adiciona propriedades fisicas corretas (massa/atrito) aos blocos de outros mods no Sable."),
    "scholar": ("Interface & Livros", "minecraft:writable_book", "Edicao de livros em duas paginas, formatacao facil, livros coloridos e animacao ao ler."),
    "seamlesssleep": ("Sobrevivencia & QoL", "minecraft:red_bed", "Transicao suave da noite para o amanhecer quando todos os jogadores dormem na cama."),
    "searchables": ("Interface & QoL", "minecraft:compass", "Adiciona filtros de pesquisa inteligentes com autocompletar nos menus."),
    "simulated": ("Aeronautica & Fisica", "simulated:physics_assembler", "Create Simulated: montadores fisicos, sensores de altitude/giro, motores portateis e transmissoes."),
    "simulated_gauges": ("Engenharia Create", "simulated_gauges:altitude_gauge", "Medidores especiais (altitude, velocidade, gimbal) ligando Create Simulated ao Extra Gauges."),
    "simulatedcoasters": ("Ferrovias Create", "simulatedcoasters:coaster_cart", "Monte montanhas-russas reais em cima da fisica do Sable com carrinhos e trilhos proprios!"),
    "sliceanddice": ("Gastronomia & Create", "sliceanddice:slicer", "Conecta Create com Farmer's Delight: Fatiador Mecanico na tabua e Aspersores automáticos!"),
    "smart_bounds": ("Performance & Create", "create:andesite_casing", "Otimiza limites de renderizacao de Block Entities do Create para melhorar FPS em fabricas."),
    "smartblockplacement": ("Construcao & QoL", "minecraft:stone_bricks", "Construa rapidamente enfileirando blocos com precisao sem errar alinhamento."),
    "smarterfarmers": ("Mundo & Aldeoes", "minecraft:wheat", "Aldeoes fazendeiros plantam e colhem culturas de mods como Farmer's Delight automaticamente."),
    "smoothswapping": ("Interface & QoL", "minecraft:chest", "Animacao suave de movimento de itens ao clicar/transferir no inventario."),
    "sodium": ("Performance & Render", "minecraft:diamond", "Principal motor de renderizacao ultra-rapido do Minecraft, multiplicando o FPS."),
    "sodium_extra": ("Performance & Render", "minecraft:emerald", "Controles extras de animacoes, nuvens, clima e detalhes graficos para o Sodium."),
    "sophisticatedbackpacks": ("Armazenamento & Exploracao", "sophisticatedbackpacks:backpack", "Mochilas aprimoraveis ate Netherite com mesa de craft, imas, compactacao e forno embutidos!"),
    "sophisticatedcore": ("Biblioteca", "sophisticatedcore:xp_bucket", "Nucleo de upgrades e armazenamento compartilhado pelo Sophisticated Backpacks."),
    "sounds": ("Audio & Imersao", "minecraft:jukebox", "Reformula efeitos sonoros dinamicos ao abrir inventarios, caminhar, usar itens e interagir no mundo."),
    "spark": ("Performance & Diagnostico", "minecraft:redstone", "Profiler profissional de servidor e cliente (/spark tps, /spark profiler) para detectar gargalos."),
    "statuseffectbars": ("Interface & QoL", "minecraft:experience_bottle", "Mostra uma barra de duracao restante sob os icones de efeitos de pocao na tela."),
    "structure_layout_optimizer": ("Performance & Mundo", "minecraft:compass", "Acelera a geracao de estruturas complexas (jigsaw) ao explorar novos chunks."),
    "struts": ("Biblioteca", "create:metal_girder", "Biblioteca de vigas e trelicas estruturais para addons do Create."),
    "supplementaries": ("Decoracao & Vanilla+", "supplementaries:globe", "Dezenas de blocos funcionais incriveis: Globo, Ampulheta, Saco, Cofre, Alvo, Torneira e Placa Negra!"),
    "tectonic": ("Mundo & Terreno", "minecraft:stone", "Reforma completa do relevo do Overworld com montanhas epicas, rios subterraneos e oceanos profundos."),
    "terralith": ("Mundo & Biomas", "minecraft:oak_sapling", "Quase 100 novos biomas impressionantes usando apenas blocos vanilla (sem pesar no inventario)!"),
    "toadlib": ("Biblioteca", "minecraft:bookshelf", "Biblioteca de suporte para mods."),
    "toomanyrecipeviewers": ("Compatibilidade", "minecraft:crafting_table", "Traduz receitas do JEI automaticamente para aparecerem no EMI sem conflitos."),
    "trashslot": ("Interface & QoL", "minecraft:lava_bucket", "Slot de lixeira integrado direto na tela do inventario para descartar itens indesejados."),
    "treeharvester": ("Sobrevivencia & QoL", "minecraft:diamond_axe", "Agache (Shift) com um machado e quebre um tronco para derrubar a arvore inteira e replantar a muda!"),
    "txnilib": ("Biblioteca", "minecraft:paper", "Biblioteca de utilitarios TxniLib."),
    "updatingworldicon": ("Interface & QoL", "minecraft:painting", "Atualiza automaticamente a miniatura do mundo ao sair do singleplayer."),
    "veil": ("Biblioteca Grafica", "minecraft:beacon", "Motor grafico avancado de shaders e efeitos visuais exigido pelo Sable."),
    "voicechat": ("Multiplayer & Audio", "minecraft:note_block", "Simple Voice Chat por proximidade! Aperte [V] no jogo para configurar microfone e grupos."),
    "voxy": ("Performance & Render", "minecraft:spyglass", "Renderiza distancias absurdamente longas usando Levels of Detail (LoDs) em altissimo FPS!"),
    "xaerominimap": ("Exploracao & Mapas", "minecraft:compass", "Minimapa em tempo real com radar de entidades/jogadores e waypoints (atalhos padrao [B] para criar ponto)."),
    "xaeroworldmap": ("Exploracao & Mapas", "minecraft:map", "Mapa mundi completo explorado conforme voce viaja! Aperte [M] para abrir."),
    "yeetusexperimentus": ("Interface & QoL", "minecraft:paper", "Remove o aviso chato de 'Configuracoes Experimentais' ao entrar no mundo."),
    "yet_another_config_lib_v3": ("Biblioteca", "minecraft:writable_book", "YACL v3: biblioteca moderna de telas de configuracao para mods NeoForge."),
    "yungsapi": ("Biblioteca", "minecraft:bookshelf", "Biblioteca base obrigatoria para toda a colecao YUNG's Better Structures."),
    "yungsextras": ("Mundo & Estruturas", "minecraft:cobblestone", "YUNG's: Adiciona pequenos detalhes e mini-estruturas vanilla espalhadas pelo Overworld e Nether."),
    "zeta": ("Biblioteca", "minecraft:bookshelf", "Biblioteca modular obrigatoria para o funcionamento do Quark."),
    "zfastnoise": ("Performance & Mundo", "minecraft:sand", "Otimizacao rapida do algoritmo FastNoise para acelerar a geracao de terreno."),
    "zume": ("Interface & QoL", "minecraft:spyglass", "Zoom suave estilo OptiFine configuravel (padrao na tecla [Z] ou [C], veja em Controles)."),
}


def pick_best_icon_for_mod(mid: str, mods_info: dict[str, dict[str, Any]], all_items: set[str]) -> str:
    if mid in CURATED_MOD_DESCRIPTIONS:
        cand = CURATED_MOD_DESCRIPTIONS[mid][1]
        if cand in all_items:
            return cand
    info = mods_info.get(mid, {})
    for candidate in info.get("verified_preferred", []):
        if candidate in all_items:
            return candidate
    for candidate in info.get("all_models", []):
        if candidate in all_items:
            return candidate
    return "minecraft:book"


def build_chapters(mods_info: dict[str, dict[str, Any]], all_items: set[str]) -> list[ChapterSpec]:
    # ------------------------------------------------------------------
    # GRUPO 1: Inicio & Sobrevivencia (01)
    # ------------------------------------------------------------------
    ch01 = ChapterSpec(
        filename="01_primeiros_passos",
        group_key="grp_inicio",
        title="Primeiros Passos & Sobrevivência",
        subtitle="Guia inicial, mochilas, proteção de terreno, mapas e qualidade de vida no Create+.",
        icon="ftbquests:book",
        default_shape="rsquare",
        quests=[
            check_q(
                'welcome', 'Bem-vindo ao Create+!', 'Seu guia completo do modpack', 'ftbquests:book', 0.0, 0.0,
                shape='hexagon',
                size=1.8,
                xp=50,
                reward_item=('minecraft:bread', 8),
                mod_refs=['ftbquests', 'ftblibrary', 'ftbteams'],
                desc=[
                    'Comece pela sobrevivência e pelos primeiros mecanismos do Create. As linhas entre as quests indicam o que abre o próximo passo.',
                    'Use &e[R]&r no EMI para ver uma receita e &e[U]&r para ver os usos do item. Nas peças do Create, segure &e[W]&r para abrir o Ponder.',
                    'Use &e/ftbteams&r para jogar com progresso compartilhado. As trilhas de culinária, construção e chefes podem ser feitas em paralelo.',
                ],
            ),
            item_q(
                'starter_iron', 'Primeiro ferro', 'Fundição e primeiras ferramentas', 'minecraft:iron_ingot', 0.0, -2.0,
                deps=['welcome'],
                xp=20,
                mod_refs=['minecraft'],
                desc=[
                    'Minere ferro bruto e derreta em uma fornalha. Com ferro você pode fazer balde, escudo e as primeiras peças do Create.',
                ],
            ),
            item_q(
                "bp_basic",
                "Mochila de Aventureiro",
                "Sophisticated Backpacks — Espaco portatil",
                "sophisticatedbackpacks:backpack",
                -2.5,
                2.0,
                deps=["welcome"],
                xp=30,
                reward_item=("minecraft:leather", 4),
                desc=[
                    "Nunca mais falte espaco na mineracao!",
                    "A mochila do &bSophisticated Backpacks&r pode ser equipada no slot de costas ou colocada no chao (Shift + Botao Direito).",
                    "Ela aceita varios modulos internos (upgrades): imas de itens, alimentacao automatica, mesa de craft e compactacao!",
                ],
            ),
            item_q(
                "bp_iron",
                "Mochila de Ferro",
                "Upgrade de capacidade Tier 2",
                "sophisticatedbackpacks:iron_backpack",
                -2.5,
                3.5,
                deps=["bp_basic"],
                xp=40,
                desc=["Melhora os slots de inventario e adiciona espaco para mais upgrades internos."],
            ),
            item_q(
                'bp_gold', 'Mochila de Ouro', 'Upgrade de capacidade Tier 3', 'sophisticatedbackpacks:gold_backpack', -2.5, 5.0,
                deps=['bp_iron'],
                optional=True,
                xp=60,
                mod_refs=['sophisticatedbackpacks'],
                desc=[
                    'Mais espaco ainda para longas expedicoes e obras com o Create.',
                ],
            ),
            item_q(
                'bp_diamond', 'Mochila de Diamante', 'Upgrade de capacidade Tier 4', 'sophisticatedbackpacks:diamond_backpack', -2.5, 6.5,
                deps=['bp_gold'],
                optional=True,
                xp=100,
                mod_refs=['sophisticatedbackpacks'],
                desc=[
                    'Quase um bau duplo inteiro nas costas, com multiplos slots de upgrades.',
                ],
            ),
            item_q(
                'bp_netherite', 'Mochila de Netherite', 'A prova de lava — Capacidade maxima', 'sophisticatedbackpacks:netherite_backpack', -2.5, 8.0,
                deps=['bp_diamond', 'exp_dim_nether'],
                optional=True,
                shape='octagon',
                size=1.3,
                xp=200,
                reward_item=('minecraft:netherite_scrap', 1),
                mod_refs=['sophisticatedbackpacks'],
                desc=[
                    'Nao queima na lava e oferece armazenamento portatil absurdo!',
                ],
            ),
            item_q(
                'bp_upgrades_util', 'Melhore sua mochila', 'Coleta automática e alimentação', 'sophisticatedbackpacks:pickup_upgrade', -4.5, 3.5,
                extra_items=[('sophisticatedbackpacks:feeding_upgrade', 1)],
                deps=['bp_basic'],
                optional=True,
                xp=75,
                mod_refs=['sophisticatedbackpacks', 'sophisticatedcore'],
                desc=[
                    'Instale Pickup Upgrade para recolher itens na mochila ou Feeding Upgrade para comer automaticamente. Magnet e Compacting são opções para depois.',
                ],
            ),
            item_q(
                'bp_deposit', 'Deposito Rapido em Baus', 'Sophisticated Backpacks — Deposit Upgrade', 'sophisticatedbackpacks:deposit_upgrade', -4.5, 5.0,
                deps=['bp_upgrades_util'],
                optional=True,
                xp=50,
                mod_refs=['sophisticatedbackpacks'],
                desc=[
                    'Com um clique (Shift + Botao Direito no bau), descarrega da mochila tudo que combina com o bau!',
                ],
            ),
            item_q(
                "map_atlas",
                "Atlas de Mapas",
                "Map Atlases — Navegacao estilosa",
                "map_atlases:atlas",
                0.0,
                2.0,
                deps=["welcome"],
                xp=40,
                reward_item=("minecraft:map", 2),
                mod_refs=["map_atlases"],
                desc=[
                    "O &bMap Atlases&r reune dezenas de mapas em um unico livro.",
                    "Basta carregar mapas vazios na mochila ou no Atlas para ir revelando a regiao ao caminhar!",
                ],
            ),
            check_q(
                'lootr_chest_guide', 'Baús individuais do Lootr', 'Tesouros em estruturas', 'minecraft:chest', 2.5, 2.0,
                deps=['welcome'],
                optional=True,
                task_title='Marcar como lido',
                xp=None,
                mod_refs=['lootr'],
                desc=[
                    'Em estruturas, baús do Lootr geram um inventário para cada jogador. Marque após abrir o primeiro.',
                ],
            ),
            check_q(
                "opac_claims",
                "Protecao de Base e Parties",
                "Open Parties and Claims",
                "minecraft:shield",
                2.5,
                3.5,
                deps=["welcome"],
                xp=35,
                mod_refs=["openpartiesandclaims"],
                desc=[
                    "Proteja suas fabricas contra explosoes, griefers e crie grupos com seus amigos:",
                    "- Use os comandos &e/openpac-parties&r ou o atalho do menu para criar sua Party e convidar amigos.",
                    "- No menu de Claims (ou tecla de atalho configurada em Controles), clique com o mouse nos chunks da sua base para protege-los.",
                    "- Voce tambem pode dar forceload em chunks importantes dentro da cota do servidor.",
                ],
            ),
            check_q(
                'maps_xaero', 'Minimapa & Mapa Mundi', "Xaero's Minimap e World Map", 'minecraft:compass', 0.0, 3.5,
                deps=['welcome'],
                xp=25,
                mod_refs=['xaerominimap', 'xaeroworldmap'],
                desc=[
                    'Controles padrao do mapa:',
                    '- &e[M]&r abre o Mapa Mundi completo com zoom e marcações.',
                    '- &e[B]&r cria um Waypoint rapido na sua posicao atual.',
                    "- &e[Y]&r abre as configuracoes do Xaero's Minimap para personalizar tamanho, radar e estilo.",
                ],
            ),
            check_q(
                "voice_chat_q",
                "Chat de Voz por Proximidade",
                "Simple Voice Chat — Tecla [V]",
                "minecraft:note_block",
                4.5,
                2.0,
                deps=["welcome"],
                xp=30,
                mod_refs=["voicechat"],
                desc=[
                    "O modpack possui &bSimple Voice Chat&r configurado e ativo no servidor!",
                    "- Aperte &e[V]&r no jogo para escolher microfone, ativar Push-to-Talk ou ativacao por voz, testar audio e criar salas de grupo.",
                    "- Quem esta perto no mundo ouve sua voz direcionalmente em 3D!",
                ],
            ),
            check_q(
                "qol_carry_tree",
                "Mecanicas de Campo: Carry On & Tree Harvester",
                "Carregue baus e corte arvores inteiras",
                "minecraft:diamond_axe",
                -4.5,
                2.0,
                deps=["welcome"],
                xp=30,
                mod_refs=["carryon", "treeharvester"],
                desc=[
                    "&bCarry On&r: com as DUAS maos vazias, segure &eShift + Botao Direito&r em um bau, fornalha ou mob pequeno (porco, ovelha, galinha) para carrega-lo no colo!",
                    "",
                    "&bTree Harvester&r: segure &eShift&r enquanto quebra o bloco base de uma arvore com um machado para derrubar todos os troncos e folhas de uma vez!",
                ],
            ),
            check_q(
                "qol_ui_suite",
                "Ferramentas de Inventario & Qualidade de Vida",
                "EMI, TrashSlot, Easy Anvils, Armor Statues, Seamless Sleep",
                "minecraft:anvil",
                4.5,
                3.5,
                deps=["welcome"],
                xp=30,
                mod_refs=[
                    "emi",
                    "emi_loot",
                    "remi",
                    "toomanyrecipeviewers",
                    "jei",
                    "searchables",
                    "controlling",
                    "trashslot",
                    "mousetweaks",
                    "easyanvils",
                    "seamlesssleep",
                    "armorstatues",
                    "potionstacks",
                    "cutthrough",
                    "companion",
                    "netherportalfix",
                ],
                desc=[
                    "Atalhos e recursos essenciais do seu inventario:",
                    "- &bEMI&r: pesquise qualquer item; &e[R]&r ve receita, &e[U]&r ve usos, &e[A]&r adiciona aos favoritos.",
                    "- &bTrashSlot&r: use o icone de lixeira na borda do inventario (ou &eDelete&r) para apagar lixo.",
                    "- &bEasy Anvils&r: itens continuam dentro da bigorna ao sair dela e permite formatar cores.",
                    "- &bArmor Statues&r: Shift + Botao Direito no Suporte de Armadura abre editor de poses.",
                    "- &bPotion Stacks&r: pocoes acumulam ate 16 no mesmo slot.",
                    "- &bCut Through&r: bata em mobs atraves de grama alta sem cortar o mato.",
                ],
            ),
        ],
    )

    # ------------------------------------------------------------------
    # GRUPO 2: Engenharia Create (02, 03, 04, 05)
    # ------------------------------------------------------------------
    ch02 = ChapterSpec(
        filename="02_create_era_andesito",
        group_key="grp_create",
        title="Create: Era do Andesito",
        subtitle="Use rotação para prensar sua primeira chapa e monte uma oficina que se abastece pela esteira.",
        icon="create:andesite_alloy",
        quests=[
            item_q(
                'cr_andesite_alloy', 'Liga de Andesito', 'Base de toda a era inicial do Create', 'create:andesite_alloy', 0.0, 0.0,
                count=8,
                deps=['starter_iron'],
                shape='hexagon',
                size=1.5,
                xp=30,
                reward_item=('create:andesite_alloy', 8),
                mod_refs=['create'],
                desc=[
                    'Combine &bAndesito&r com pepitas de Ferro ou Zinco na bancada — ou melhor ainda, no Misturador depois para render o dobro!',
                ],
            ),
            item_q("cr_shaft", "Eixos (Shafts)", "Transmitindo rotacao em linha reta", "create:shaft", 2.0, 0.0, count=8, deps=["cr_andesite_alloy"], xp=20, desc=["Shafts transmitem rotacao sem alterar velocidade nem direcao."]),
            item_q("cr_cogs", "Engrenagens Pequenas & Grandes", "Alterando velocidade (RPM) e direcao", "create:cogwheel", 4.0, 0.0, count=4, extra_items=[("create:large_cogwheel", 2)], deps=["cr_shaft"], xp=30, desc=["Conecte uma Engrenagem Grande em uma Pequena para DOBRAR o RPM (reduzindo torque), ou o inverso para aumentar a forca!"]),
            item_q("cr_wrench", "Chave Inglesa do Create", "A ferramenta mais importante do engenheiro", "create:wrench", 2.0, -2.0, deps=["cr_andesite_alloy"], xp=30, desc=["Rotaciona blocos do Create, configura pistas/filtros e desmonta maquinas instantaneamente com &eShift + Botao Direito&r."]),
            item_q("cr_goggles", "Oculos de Engenheiro", "Veja SU, RPM e fluidos nos blocos!", "create:goggles", 4.0, -2.0, deps=["cr_wrench"], xp=50, desc=["Equipe no capacete para enxergar o estresse (SU), RPM, direcao de eixos e conteudo de tanques/tubos ao olhar para qualquer maquina."]),
            item_q("cr_hand_crank", "Manivela Manual", "Energia bruta com a forca do braco", "create:hand_crank", 0.0, 2.0, deps=["cr_andesite_alloy"], xp=20, desc=["Gire manualmente para testar maquinas antes de montar geracao automatica."]),
            item_q(
                'cr_water_wheel', 'Energia hidráulica', 'Sua primeira fonte contínua de rotação', 'create:water_wheel', 2.0, 2.0,
                deps=['cr_shaft'],
                xp=50,
                mod_refs=['create'],
                desc=[
                    'Conduza água contra a Water Wheel e use um eixo para levar a rotação às máquinas. A versão grande é uma opção quando precisar de mais capacidade.',
                ],
            ),
            item_q(
                'cr_windmill', 'Moinho de Vento', 'Engenharia eolica de alta capacidade', 'create:windmill_bearing', 2.0, 4.0,
                extra_items=[('create:white_sail', 8)],
                deps=['cr_cogs'],
                xp=75,
                mod_refs=['create'],
                desc=[
                    'Quanto mais &bSails&r (velas) acopladas ao Windmill Bearing (ate 128 blocos), maior a energia (ate &a8192 SU&r)! Nao precisa nem usar Super Cola nas velas.',
                ],
            ),
            item_q("cr_millstone", "Moinho de Pedra (Millstone)", "Moagem inicial de graos e minerios", "create:millstone", 6.0, 0.0, deps=["cr_cogs"], xp=40, desc=["Transforma trigo em farinha, ossos em farinha de osso com bonus e flores em mais corantes."]),
            QuestSpec(
                "cr_press", "A primeira chapa", "Teste a prensa no depósito",
                [
                    "Coloque a prensa dois blocos acima de um Depot, deixando um bloco de ar entre eles. Ligue a prensa a uma fonte de rotação. Uma manivela já serve para o primeiro teste.",
                    "Ponha um lingote de cobre no Depot e recolha a chapa. Use chapas de cobre para fazer tubos e um tanque quando for montar a bomba.",
                    "O avanço é registrado quando a prensa processa um item. Coloque você mesmo a prensa para que o Create associe o avanço ao seu jogador.",
                ],
                6.0, 2.0,
                icon="create:mechanical_press",
                deps=["cr_cogs"],
                tasks=[
                    TaskSpec("item", "create:mechanical_press"),
                    TaskSpec("item", "create:depot"),
                    TaskSpec("advancement", "create:mechanical_press"),
                ],
                rewards=[RewardSpec("xp", 50), RewardSpec("item", item="create:iron_sheet", count=4)],
                mod_refs=["create"],
            ),
            item_q("cr_mixer", "Misturador Mecanico + Bacia", "Receitas automatizadas e ligas", "create:mechanical_mixer", 8.0, 2.0, extra_items=[("create:basin", 1)], deps=["cr_press"], xp=60, desc=["Junto a uma Basin abaixo, mistura ingredientes e dobra o rendimento da Liga de Andesito."]),
            item_q(
                'cr_blaze_burner', 'Capture um Blaze', 'Calor para o misturador', 'create:blaze_burner', 10.0, 2.0,
                deps=['cr_mixer', 'exp_dim_nether'],
                shape='octagon',
                size=1.3,
                xp=100,
                reward_item=('create:blaze_cake', 1),
                mod_refs=['create'],
                desc=[
                    'Faça um Empty Blaze Burner e use-o em um Blaze ou no gerador de Blazes de uma fortaleza. Coloque o queimador sob uma bacia e dê combustível ao Blaze.',
                ],
            ),
            item_q(
                'cr_encased_fan', 'Ventilador Encaixotado', 'Lavar, defumar, fundir e assombrar em massa', 'create:encased_fan', 6.0, -2.0,
                deps=['cr_cogs', 'cr_water_wheel'],
                xp=45,
                mod_refs=['create'],
                desc=[
                    'Assopre atraves de: &9Agua&r (lavar cascalho -> pepitas de ferro!), &6Fogo&r (defumar), &cLava&r (fundir stacks sem carvao!) ou &5Fogo das Almas&r (assombrar).',
                ],
            ),
            item_q(
                'cr_belt', 'Leve itens pela esteira', 'Conecte dois eixos para transportar uma carga', 'create:belt_connector', 8.0, -2.0,
                deps=['cr_shaft'],
                xp=45,
                mod_refs=['create'],
                desc=[
                    'Ponha dois eixos paralelos na mesma altura e use um Belt Connector no primeiro e depois no segundo. Ligue um deles à rotação para mover a esteira.',
                    'Passe a esteira por baixo da prensa para que os lingotes cheguem sozinhos.',
                ],
            ),
            item_q(
                "cr_funnels_chutes", "Uma entrada e uma saída", "Dois funis conectam os baús à esteira",
                "create:andesite_funnel", 10.0, -2.0,
                count=2, deps=["cr_belt"], xp=40,
                desc=[
                    "Faça dois funis de andesito. Um tira lingotes do baú de entrada e o outro recolhe chapas no baú de saída.",
                    "Confira as setas e use a chave inglesa para inverter o sentido. O Ponder dos funis mostra como encaixá-los nos baús junto à esteira.",
                ],
            ),
            check_q(
                "cr_workshop_line", "Sua primeira linha de chapas", "Do baú de lingotes ao baú de chapas",
                "create:copper_sheet", 8.0, 0.0,
                deps=["cr_press", "cr_funnels_chutes"], xp=60, optional=True,
                task_title="O baú de saída recebeu uma chapa produzida pela linha",
                desc=[
                    "Ligue a prensa e a esteira a uma fonte contínua, como uma roda d'água ou um moinho. A prensa fica dois blocos acima da esteira.",
                    "Coloque um lingote de cobre no baú de entrada. Acompanhe o lingote saindo pelo funil, passando pela prensa e chegando como chapa ao baú de saída.",
                    "Marque a tarefa depois de ver a chapa no baú. Use as chapas da oficina para fabricar os tubos e o tanque do próximo projeto.",
                ],
            ),
            item_q(
                'cr_farm_machines', 'Colheita mecânica', 'Serra e colhedora', 'create:mechanical_saw', 8.0, 4.0,
                extra_items=[('create:mechanical_harvester', 1)],
                deps=['cr_contraptions'],
                xp=75,
                mod_refs=['create'],
                desc=[
                    'Experimente uma serra para madeira ou uma colhedora em uma estrutura móvel. Use broca e arado conforme a fazenda precisar.',
                ],
            ),
            item_q(
                'cr_copper_fluids', 'Bombeie e armazene fluidos', 'Bomba, tubulação e tanque', 'create:mechanical_pump', 6.0, 6.0,
                extra_items=[('create:fluid_pipe', 1), ('create:fluid_tank', 1)],
                deps=['cr_press'],
                xp=80,
                mod_refs=['create'],
                desc=[
                    'Use as chapas de cobre da prensa para fabricar tubos e um tanque. As receitas aparecem no EMI.',
                    'Gire a bomba e confira o sentido com a chave. Ligue o tanque às máquinas com Fluid Pipes. Spout, Item Drain e Hose Pulley servem para transferências mais específicas.',
                ],
            ),
            item_q(
                'cr_diving_gear', 'Equipamento de Mergulho de Cobre', "Respire indefinidamente debaixo d'agua ou na lava", 'create:copper_backtank', 8.0, 6.0,
                extra_items=[('create:copper_diving_helmet', 1), ('create:copper_diving_boots', 1)],
                deps=['cr_copper_fluids'],
                optional=True,
                xp=75,
                mod_refs=['create'],
                desc=[
                    'Encha o Backtank conectado a um eixo rotativo e use com o Capacete de Mergulho!',
                ],
            ),
            item_q(
                'cr_contraptions', 'Sua primeira máquina móvel', 'Cola e rolamento mecânico', 'create:super_glue', 10.0, 4.0,
                extra_items=[('create:mechanical_bearing', 1)],
                deps=['cr_cogs', 'cr_water_wheel'],
                xp=90,
                mod_refs=['create'],
                desc=[
                    'Cole os blocos da estrutura com Super Glue e monte-os em um Mechanical Bearing. A contraption gira sem mover o motor.',
                ],
            ),
            item_q(
                'cr_elevators_schematics', 'Elevador mecânico', 'Transporte vertical', 'create:elevator_pulley', 12.0, 4.0,
                deps=['cr_contraptions'],
                xp=90,
                mod_refs=['create'],
                desc=[
                    'Use Elevator Pulley com contatos de redstone nos andares. Rope Pulley faz uma plataforma mais simples. A Schematicannon fica disponível como ferramenta de construção.',
                ],
            ),
        ],
    )

    ch03 = ChapterSpec(
        filename="03_create_era_latao_e_logistica",
        group_key="grp_create",
        title="Create: Era do Latão, Trens & Logística 6.0",
        subtitle="Mecanismos de precisao, caldeiras a vapor, ferrovias e a rede completa de pacotes do Create 6.0.",
        icon="create:precision_mechanism",
        quests=[
            item_q(
                'br_rose_quartz', 'Quartzo Rosa Polido & Tubo de Eletrons', 'Circuitos analogicos do Create', 'create:polished_rose_quartz', 0.0, 0.0,
                extra_items=[('create:electron_tube', 2)],
                deps=['cr_press', 'exp_dim_nether'],
                shape='hexagon',
                size=1.4,
                xp=45,
                mod_refs=['create'],
                desc=[
                    'Combine Quartzo do Nether com Redstone para criar Rose Quartz, depois lixe com Lixa (Sand Paper).',
                ],
            ),
            item_q(
                'br_brass', 'Primeiro latão', 'Mistura aquecida e carcaça de latão', 'create:brass_ingot', 2.0, 0.0,
                count=4,
                extra_items=[('create:brass_casing', 1)],
                deps=['cr_blaze_burner'],
                xp=50,
                mod_refs=['create'],
                desc=[
                    'Aqueça &bCobre + Zinco&r na Basin usando um Blaze Burner!',
                ],
            ),
            item_q(
                'br_deployer', 'Deployer', 'Automatize a montagem e o uso de itens', 'create:deployer', 4.0, 0.0,
                deps=['br_brass', 'br_rose_quartz'],
                xp=65,
                mod_refs=['create'],
                desc=[
                    'Use a Brass Hand na receita do Deployer. Depois alimente a máquina com rotação e dê a ela o item que deve aplicar durante a montagem.',
                ],
            ),
            item_q("br_precision", "Mecanismo de Precisao", "O coracao das maquinas avancadas", "create:precision_mechanism", 6.0, 0.0, deps=["br_deployer"], shape="octagon", size=1.3, xp=120, reward_item=("create:precision_mechanism", 1), desc=["Montagem sequencial (5 ciclos): Prensa + Engrenagem Pequena + Engrenagem Grande + Pepita de Ferro sobre uma Chapa de Ouro!"]),
            item_q(
                'br_mechanical_crafting', 'Montagem mecânica', 'Monte sua bancada de crafters', 'create:mechanical_crafter', 6.0, -2.0,
                count=3,
                deps=['br_brass', 'br_rose_quartz'],
                xp=65,
                mod_refs=['create'],
                desc=[
                    'Oriente os Mechanical Crafters em direção à saída. Monte a grade do tamanho da receita, preencha os espaços vazios com tampas e alimente a rede com rotação.',
                ],
            ),
            item_q(
                'br_crafter_crusher', 'Trituração em escala', 'Um par de rodas de trituração', 'create:crushing_wheel', 8.0, 0.0,
                count=2,
                deps=['br_mechanical_crafting'],
                xp=120,
                mod_refs=['create'],
                desc=[
                    'Monte a receita em Mechanical Crafters. Instale as duas rodas lado a lado girando em sentidos opostos e alimente a abertura entre elas.',
                ],
            ),
            item_q("br_rsc_arm", "Controlador de Velocidade & Braco Mecanico", "Ajuste fino de RPM (1 a 256) e bracos roboticos", "create:rotation_speed_controller", 6.0, 2.0, extra_items=[("create:mechanical_arm", 1)], deps=["br_precision"], xp=90, desc=["Com uma Large Cogwheel encaixada acima, defina exatamente de -256 a +256 RPM com um clique!"]),
            item_q(
                'br_smart_logistics', 'Logística com filtro', 'Seleção de itens e sinais de inventário', 'create:brass_funnel', 4.0, 2.0,
                extra_items=[('create:smart_chute', 1), ('create:content_observer', 1)],
                deps=['br_brass', 'br_rose_quartz', 'cr_funnels_chutes'],
                xp=100,
                mod_refs=['create'],
                desc=[
                    'Filtre a entrada e a saída com Brass Funnel e Smart Chute. O Content Observer emite um sinal ao detectar itens no inventário configurado.',
                ],
            ),
            item_q(
                'br_steam_engine', 'Motor a Vapor Industrial', 'Energia cinetica colossal (ate 294.912 SU!)', 'create:steam_engine', 2.0, 2.5,
                deps=['cr_blaze_burner', 'cr_copper_fluids'],
                shape='diamond',
                size=1.4,
                xp=120,
                reward_item=('create:steam_whistle', 1),
                mod_refs=['create'],
                desc=[
                    'Empilhe Fluid Tanks, bombeie agua constante, coloque Blaze Burners embaixo e conecte &bSteam Engines&r aos tanques!',
                ],
            ),
            item_q(
                'br_trains', 'Primeiro trem', 'Trilhos, estação e controles', 'create:track', 2.0, 4.5,
                count=8,
                extra_items=[('create:track_station', 2), ('create:controls', 1)],
                deps=['br_precision'],
                shape='hexagon',
                size=1.4,
                xp=130,
                mod_refs=['create'],
                desc=[
                    'Estenda os trilhos e use a estação para montar um trem com bogies e controles. Sinais e horários entram quando houver mais estações ou composições.',
                ],
            ),
            item_q(
                'cr6_cardboard', 'Envie seus primeiros pacotes', 'Embale, enderece e receba', 'create:packager', 8.0, 2.5,
                extra_items=[('create:cardboard', 4), ('create:package_frogport', 2)],
                deps=['cr_belt'],
                shape='rsquare',
                size=1.3,
                xp=120,
                mod_refs=['create'],
                desc=[
                    'O Packager transforma itens do inventário em pacotes. Configure o endereço do Frogport e use esteiras ou correntes para levar as caixas ao destino.',
                ],
            ),
            item_q(
                'cr6_network', 'Rede de estoque', 'Ligue inventários e peça itens a distância', 'create:stock_ticker', 10.0, 2.5,
                extra_items=[('create:stock_link', 2), ('create:chain_conveyor', 4)],
                deps=['cr6_cardboard', 'br_rose_quartz'],
                shape='octagon',
                size=1.4,
                xp=150,
                mod_refs=['create', 'cardboarded_conveynience', 'create_access_denied'],
                desc=[
                    'Monte um Stock Link com um Packager e conecte-o à rede do Stock Ticker. Use correntes para o transporte das caixas e adicione o Redstone Requester quando precisar automatizar pedidos.',
                ],
            ),
        ],
    )

    ch04 = ChapterSpec(
        filename="04_create_ferrovias_e_circuitos",
        group_key="grp_create",
        title="Addons: Ferrovias, Circuitos & Utilitários",
        subtitle="Steam 'n' Rails, Navegador, Montanhas-Russas, Circuitos, Gauges, Pocket Sized e Chunk Loaders.",
        icon="railways:conductor_whistle",
        quests=[
            item_q(
                'snr_conductor', "Steam 'n' Rails: Condutores e Handcar", 'Bone de condutor, apito e vagoneta manual', 'railways:conductor_whistle', 0.0, 0.0,
                extra_items=[('railways:black_conductor_cap', 1), ('railways:handcar', 1)],
                deps=['br_trains'],
                optional=True,
                shape='hexagon',
                size=1.4,
                xp=60,
                mod_refs=['railways', 'createthreadedtrains'],
                desc=[
                    'Construa um &bConductor&r com o bone, ou atravesse os trilhos pedalando um &bHandcar&r! O addon &eThreaded Trains&r acelera todo o servidor.',
                ],
            ),
            item_q(
                'snr_tracks', 'Controle ferroviário', 'Acoplamento e sinalização', 'railways:track_coupler', 2.0, 0.0,
                extra_items=[('railways:semaphore', 1)],
                deps=['snr_conductor'],
                optional=True,
                xp=90,
                mod_refs=['railways'],
                desc=[
                    'Instale um Track Coupler para separar composições. Use Semaphore para exibir a indicação de um sinal de trem.',
                ],
            ),
            item_q(
                'snr_carts', 'Banco sobre trilhos', 'Transporte de passageiros', 'railways:benchcart', 4.0, 0.0,
                deps=['snr_conductor'],
                optional=True,
                xp=60,
                mod_refs=['railways'],
                desc=[
                    'Coloque um Benchcart no trilho para levar passageiros. O Steam ’n’ Rails também oferece carrinhos e acessórios temáticos.',
                ],
            ),
            item_q(
                'crn_nav', 'Informação ao passageiro', 'Navegador e painel', 'createrailwaysnavigator:navigator', 2.0, 2.0,
                extra_items=[('createrailwaysnavigator:advanced_display_panel', 2)],
                deps=['br_trains', 'br_mechanical_crafting', 'br_precision'],
                optional=True,
                xp=80,
                mod_refs=['createrailwaysnavigator'],
                desc=[
                    'Consulte destinos e horários no Navigator. Configure o Advanced Display Panel para anunciar a estação e as próximas partidas.',
                ],
            ),
            item_q(
                'baw_deco', 'Detalhes da locomotiva', 'Farol e anteparo', 'bellsandwhistles:headlight', 4.0, 2.0,
                extra_items=[('bellsandwhistles:andesite_pilot', 1)],
                deps=['br_trains'],
                optional=True,
                xp=60,
                mod_refs=['bellsandwhistles'],
                desc=[
                    'Monte um farol e um anteparo na composição já planejada. Outras formas e acabamentos aparecem como opções do Bells & Whistles.',
                ],
            ),
            item_q(
                'cr_power_loader', 'Carregamento de chunks', 'Prepare um carregador de andesito', 'create_power_loader:empty_andesite_chunk_loader', 0.0, 2.5,
                deps=['cr_andesite_alloy', 'exp_dim_nether'],
                optional=True,
                xp=90,
                mod_refs=['create_power_loader'],
                desc=[
                    'Fabrique a versão vazia e instale a carga indicada no EMI ou no Ponder. Consulte as regras do servidor antes de manter máquinas e áreas carregadas.',
                ],
            ),
            item_q(
                'cr_gauges_all', 'Medidores de automação', 'Lógica e contagem', 'extra_gauges:logic_gauge', 2.0, 4.0,
                extra_items=[('extra_gauges:counter_gauge', 1)],
                deps=['br_precision'],
                optional=True,
                xp=80,
                mod_refs=['extra_gauges', 'simulated_gauges'],
                desc=[
                    'Configure Logic Gauge e Counter Gauge junto a uma linha de produção. Sensores de voo ficam na trilha de aeronáutica.',
                ],
            ),
            item_q(
                'cr_circuits', 'Circuitos de redstone', 'Placa e monitor', 'createcircuitboard:circuit_board', 4.0, 4.0,
                extra_items=[('createcircuitboard:circuit_monitor', 1)],
                deps=['br_brass', 'cr6_network'],
                optional=True,
                xp=80,
                mod_refs=['createcircuitboard', 'powerchip'],
                desc=[
                    'Monte a placa e conecte o monitor para acompanhar circuitos. Power Chip é uma opção separada para transportar lógica.',
                ],
            ),
            item_q(
                'cr_ca_addon', 'Transmissões de latão', 'Caixa de engrenagens e embreagem invertida', 'create_ca:brass_gearbox', 6.0, 3.0,
                extra_items=[('create_ca:inverted_clutch', 1)],
                deps=['br_brass'],
                optional=True,
                xp=70,
                mod_refs=['create_ca', 'escalated'],
                desc=[
                    'Use a Brass Gearbox para rotear eixos. Teste a Inverted Clutch com redstone para controlar um ramal da fábrica.',
                ],
            ),
            item_q(
                'cr_tools_addon', 'Filtro de seleções', 'Use o Shuffle Filter na construção', 'createshufflefilter:shuffle_filter', 6.0, 4.5,
                deps=['br_brass'],
                optional=True,
                xp=65,
                mod_refs=['extendedwrenches', 'createshufflefilter', 'create_pattern_schematics', 'deployer'],
                desc=[
                    'Configure o Shuffle Filter para escolher materiais durante a construção. Consulte no EMI ferramentas de alcance ou esquemas de padrões.',
                ],
            ),
            item_q(
                'cr_pocket', 'Contraptions portáteis', 'Compression Gun', 'pocket:compression_gun', 8.0, 3.0,
                deps=['br_precision', 'br_crafter_crusher', 'exp_dim_end'],
                optional=True,
                xp=80,
                mod_refs=['pocket'],
                desc=[
                    'Confira a receita no EMI. A Compression Gun exige uma Precision Mechanism, uma Sturdy Sheet e Levitite Blend. Use o Ponder antes de compactar uma estrutura.',
                ],
            ),
            item_q("sim_coasters", "Create: Coasters Simulated", "Montanhas-russas reais no Sable!", "simulatedcoasters:coaster_track", 6.0, 1.0, count=8, extra_items=[("simulatedcoasters:coaster_cart", 1), ("simulatedcoasters:coaster_anchorpoint", 2)], deps=["snr_conductor"], xp=90, mod_refs=["simulatedcoasters"], desc=["Construa loops, quedas livres e trilhos curvos de montanha-russa com fisica real!"]),
        ],
    )

    ch05 = ChapterSpec(
        filename="05_create_energia_e_industria",
        group_key="grp_create",
        title="Indústria Pesada: Diesel, Eletricidade, Aço & XP",
        subtitle="Petroleo, motores a diesel, geradores eletricos do Power Grid, ligas de Aco e industria de encantamentos.",
        icon="powergrid:electric_motor",
        quests=[
            item_q(
                'cdg_asphalt', 'Comece com combustíveis', 'Tampa de bacia e barril', 'createdieselgenerators:basin_lid', 0.0, 0.0,
                extra_items=[('createdieselgenerators:oil_barrel', 2)],
                deps=['cr_copper_fluids'],
                optional=True,
                shape='hexagon',
                size=1.4,
                xp=50,
                mod_refs=['createdieselgenerators'],
                desc=[
                    'Prepare a bacia e o armazenamento de líquidos. Nas etapas seguintes você vai extrair petróleo e processá-lo na coluna de destilação.',
                ],
            ),
            item_q(
                'cdg_pumpjack', 'Extracao de Petroleo Bruto (Pumpjack)', 'Perfure o subsolo por jazidas de petroleo', 'createdieselgenerators:pumpjack_head', 2.0, 0.0,
                extra_items=[('createdieselgenerators:pumpjack_crank', 1), ('createdieselgenerators:pumpjack_bearing', 1)],
                deps=['cdg_asphalt'],
                optional=True,
                xp=100,
                mod_refs=['createdieselgenerators'],
                desc=[
                    'Use o Detector de Petroleo ou localize chunks ricos em petroleo bruto e monte sua torre Pumpjack ligada a canos!',
                ],
            ),
            item_q(
                'cdg_distillation', 'Torre de Destilacao', 'Refine Petroleo em Gasolina e Diesel', 'createdieselgenerators:distillation_controller', 4.0, 0.0,
                deps=['cdg_pumpjack'],
                optional=True,
                xp=110,
                mod_refs=['createdieselgenerators'],
                desc=[
                    'Aplique sobre Fluid Tanks aquecidos por Blaze Burners para separar Petroleo em Fracoes Leves (Gasolina) e Pesadas (Diesel).',
                ],
            ),
            item_q(
                'cdg_engines', 'Motor a diesel', 'Converta combustível em rotação', 'createdieselgenerators:diesel_engine', 6.0, 0.0,
                deps=['cdg_distillation', 'br_brass'],
                optional=True,
                shape='octagon',
                size=1.3,
                xp=150,
                mod_refs=['createdieselgenerators'],
                desc=[
                    'Abasteça um Diesel Engine com combustível da destilação. Suba para versões maiores quando sua rede precisar de mais potência.',
                ],
            ),
            item_q(
                'pg_basics', 'Eletricidade: Power Grid', 'Alicate, Fios de Cobre e Bobinas', 'powergrid:wire_cutter', 0.0, 3.0,
                extra_items=[('powergrid:insulated_copper_wire', 4), ('powergrid:copper_coil', 2), ('powergrid:cord_junction', 2)],
                deps=['cr_press'],
                optional=True,
                shape='hexagon',
                size=1.4,
                xp=60,
                mod_refs=['powergrid'],
                desc=[
                    'Power Grid traz eletricidade realista (Volts, Amperes e Ohms) para dentro do Create! Cuidado com curtos e sobrecarga de fios.',
                ],
            ),
            item_q(
                'pg_generators', 'Geradores Eletricos & Motores', 'Converta rotacao (SU) em energia eletrica e vice-versa', 'powergrid:generator_housing', 2.0, 3.0,
                extra_items=[('powergrid:generator_induction_rotor', 1), ('powergrid:generator_commutator', 1), ('powergrid:electric_motor', 1)],
                deps=['pg_basics'],
                optional=True,
                xp=110,
                mod_refs=['powergrid'],
                desc=[
                    'Acione um Gerador multi-bloco com moinho/vapor/diesel e leve energia por cabos a distancia ate Motores Eletricos!',
                ],
            ),
            item_q(
                'pg_instruments', 'Meça e armazene eletricidade', 'Multímetro e bateria', 'powergrid:multimeter', 4.0, 3.0,
                extra_items=[('powergrid:battery', 1)],
                deps=['pg_generators'],
                optional=True,
                xp=100,
                mod_refs=['powergrid'],
                desc=[
                    'Confira tensão e conexões com o multímetro. A bateria armazena energia para quando o gerador parar.',
                ],
            ),
            item_q(
                'pg_tools', 'Ferramentas Eletricas & Placas PCB', 'Furadeira, Serra Portatil e Circuit Design Table', 'powergrid:portable_drill', 6.0, 3.0,
                extra_items=[('powergrid:portable_saw', 1), ('powergrid:circuit_design_table', 1)],
                deps=['pg_generators', 'br_mechanical_crafting', 'br_precision'],
                optional=True,
                xp=90,
                mod_refs=['powergrid'],
                desc=[
                    'Desenhe circuitos integrados personalizados na &bCircuit Design Table&r e use ferramentas portateis recarregaveis.',
                ],
            ),
            item_q(
                'alloyed_metals', 'Ligas de aço e bronze', 'Primeiras ligas do Alloyed', 'alloyed:steel_ingot', 0.0, 6.0,
                count=4,
                extra_items=[('alloyed:bronze_ingot', 4)],
                deps=['cr_blaze_burner'],
                optional=True,
                xp=80,
                mod_refs=['alloyed'],
                desc=[
                    'Confira o tipo de processamento de cada liga no EMI. Use as primeiras barras em chapas, peças e ferramentas quando precisar delas.',
                ],
            ),
            item_q(
                'cei_xp', 'Automatize experiência', 'Moagem e armazenamento de XP', 'create_enchantment_industry:mechanical_grindstone', 3.0, 6.0,
                extra_items=[('create_enchantment_industry:experience_hatch', 1)],
                deps=['cr_press'],
                optional=True,
                xp=140,
                mod_refs=['create_enchantment_industry'],
                desc=[
                    'Use a Mechanical Grindstone para recuperar experiência. A Experience Hatch conecta o XP armazenado ao sistema. Equipamentos de encantamento são a expansão dessa linha.',
                ],
            ),
            item_q(
                'cdp_dragons', 'Transfira fluidos especiais', 'Hatch e tanque do Dragons Plus', 'create_dragons_plus:fluid_hatch', 6.0, 6.0,
                extra_items=[('create_dragons_plus:fragile_fluid_tank', 1)],
                deps=['cr_copper_fluids'],
                optional=True,
                xp=100,
                mod_refs=['create_dragons_plus', 'create_factory'],
                desc=[
                    'Conecte a Fluid Hatch a um tanque. O Dragão do End é uma fonte de Bafo de Dragão, que você pode coletar em frascos durante a luta.',
                ],
            ),
        ],
    )

    # ------------------------------------------------------------------
    # GRUPO 3: Aeronautica, Fisica & Combate (06, 07)
    # ------------------------------------------------------------------
    ch06 = ChapterSpec(
        filename="06_aeronautica_propulsao_e_fisica",
        group_key="grp_aero",
        title="Aeronáutica, Veículos & Física Real",
        subtitle="Dirigiveis, avioes, carros off-road, mechas caminhantes, propulsores espaciais e o motor Sable.",
        icon="aeronautics:andesite_propeller",
        quests=[
            item_q(
                'aero_start', 'Sua primeira aeronave', 'Propulsão e sustentação', 'aeronautics:andesite_propeller', 0.0, 0.0,
                extra_items=[('aeronautics:adjustable_burner', 1), ('aeronautics:white_envelope', 4)],
                deps=['cr_contraptions', 'cr_press'],
                optional=True,
                shape='hexagon',
                size=1.5,
                xp=80,
                mod_refs=['aeronautics', 'aeronautics_bundled'],
                desc=[
                    'Teste o queimador ajustável e o envelope em uma montagem pequena. A hélice de andesito dá empuxo. Use o Ponder para montar e controlar a estrutura.',
                ],
            ),
            item_q(
                'aero_levitite', 'Voo estável', 'Rolamento giroscópico', 'aeronautics:gyroscopic_propeller_bearing', 2.0, 0.0,
                deps=['aero_start', 'br_brass'],
                optional=True,
                xp=110,
                mod_refs=['aeronautics', 'aeroencasedpipe', 'ballastmod', 'aeroclaims'],
                desc=[
                    'Oriente a hélice com o rolamento giroscópico. Use lastro e envelopes adicionais quando precisar equilibrar o veículo.',
                ],
            ),
            item_q(
                'aero_works', 'Controles de voo', 'Mesa de controle e joystick', 'aeroworks:control_desk', 4.0, 0.0,
                extra_items=[('aeroworks:joystick_module', 1)],
                deps=['aero_start', 'br_precision', 'cr6_network'],
                optional=True,
                xp=90,
                mod_refs=['aeroworks'],
                desc=[
                    'Ligue a mesa de controle a um joystick. Use servos e giroscópio conforme a aeronave precisar.',
                ],
            ),
            item_q(
                'aero_flight', 'Assistência de voo', 'Computador e controle de altitude', 'create_flight_control:flight_control_computer', 6.0, 0.0,
                extra_items=[('create_flight_control:altitude_hold', 1)],
                deps=['aero_works', 'br_deployer'],
                optional=True,
                shape='octagon',
                size=1.3,
                xp=130,
                mod_refs=['create_flight_control'],
                desc=[
                    'Faça o Flight Control Computer em uma linha de montagem e conecte o Altitude Hold. Teste em baixa altura antes de expandir o sistema.',
                ],
            ),
            item_q(
                'aero_toolgun', 'Montagem magnética', 'Magnetic Gun', 'create_aeronautics_toolgun:magnetic_gun', 2.0, 2.0,
                deps=['aero_start', 'br_precision'],
                optional=True,
                xp=90,
                mod_refs=['create_aeronautics_toolgun', 'aeronautics_utility_objects'],
                desc=[
                    'Use a ferramenta numa seção pequena da aeronave. Juntas, cilindros e amortecedores são peças para montagens mais complexas.',
                ],
            ),
            item_q(
                'prop_thrusters', 'Propulsão avançada', 'Propulsores iônico e vetorial', 'createpropulsion:ion_thruster', 0.0, 3.0,
                extra_items=[('createpropulsion:liquid_vector_thruster', 1)],
                deps=['sim_physics', 'br_precision'],
                optional=True,
                xp=120,
                mod_refs=['createpropulsion'],
                desc=[
                    'Use chapas resistentes, mecanismos de precisão e sensores nas receitas. Teste os propulsores em uma estrutura experimental.',
                ],
            ),
            item_q(
                'gg_gadgets', 'Controle avançado de veículos', 'Controlador e diagnóstico', 'createthrusters:advanced_contraption_controller', 2.0, 4.0,
                extra_items=[('createthrusters:diagnostic_tablet', 1)],
                deps=['prop_thrusters'],
                optional=True,
                xp=110,
                mod_refs=['createthrusters', 'createthrusters_bundled', 'gadgetsngizmos'],
                desc=[
                    'Inspecione o estado da contraption pelo Diagnostic Tablet antes de ajustar o controlador avançado.',
                ],
            ),
            item_q(
                'sim_physics', 'Monte uma estrutura física', 'Physics Assembler e sensor de altitude', 'simulated:physics_assembler', 4.0, 4.0,
                extra_items=[('simulated:altitude_sensor', 1)],
                deps=['cr_contraptions'],
                optional=True,
                xp=100,
                mod_refs=['simulated'],
                desc=[
                    'Monte uma estrutura de teste com o Physics Assembler. Use o sensor para observar a altura antes de adicionar controles.',
                ],
            ),
            item_q(
                'offroad_cars', 'Veículo terrestre', 'Pneu e suporte', 'offroad:tire', 6.0, 4.0,
                count=4,
                extra_items=[('offroad:wheel_mount', 4)],
                deps=['sim_physics'],
                optional=True,
                xp=120,
                mod_refs=['offroad'],
                desc=[
                    'Combine os pneus com suportes em uma estrutura física. Teste um chassi pequeno antes de usar as rodas maiores.',
                ],
            ),
            item_q(
                'liw_mechs', 'Estrutura que caminha', 'Articulação, junta e pé', 'let_it_walk:articulation_unit', 4.0, 6.0,
                count=2,
                extra_items=[('let_it_walk:leg_hinge', 2), ('let_it_walk:leg_foot', 2)],
                deps=['sim_physics'],
                optional=True,
                shape='octagon',
                size=1.3,
                xp=150,
                mod_refs=['let_it_walk'],
                desc=[
                    'Monte cada perna com uma articulação, uma junta e um pé. Faça um teste de movimento antes de adicionar acessórios.',
                ],
            ),
            check_q(
                'sable_aileron', 'Motor Sable & Voo de Aileron (Elytra)', 'Entendendo a base fisica e o voo planado', 'minecraft:elytra', 0.0, 6.0,
                deps=['aero_start'],
                optional=True,
                xp=40,
                mod_refs=['sable', 'sable_beyond', 'sablecompanion', 'sablephysicscompat', 'carryonaerocompat', 'veil', 'pehkui', 'aileron'],
                desc=[
                    '&bSable&r cria sub-niveis fisicos onde maquinas se movem suavemente sem travar blocos.',
                    '',
                    '&bAileron&r deixa a Elytra muito mais responsiva e permite ganhar altitude ao planar sobre fogueiras acesas!',
                ],
            ),
        ],
    )

    ch07 = ChapterSpec(
        filename="07_artilharia_e_radares",
        group_key="grp_aero",
        title="Artilharia Pesada & Radares",
        subtitle="Create Big Cannons & Create: Radars — Fundicao de canhoes, municoes balisticas e torres guiadas por radar.",
        icon="createbigcannons:cannon_mount",
        quests=[
            item_q(
                'cbc_foundry', 'Fundição de artilharia', 'Tampa de fundição e ferro fundido', 'createbigcannons:basin_foundry_lid', 0.0, 0.0,
                extra_items=[('createbigcannons:cast_iron_ingot', 4)],
                deps=['cr_mixer', 'cr_blaze_burner'],
                optional=True,
                shape='hexagon',
                size=1.4,
                xp=60,
                mod_refs=['createbigcannons', 'ritchiesprojectilelib'],
                desc=[
                    'Aqueça a fundição e comece por peças de ferro fundido. Reserve Nethersteel para as peças avançadas.',
                ],
            ),
            item_q(
                'cbc_drill', 'Construtor & Furadeira de Canhoes', 'Perfure e usine canos sob pressao', 'createbigcannons:cannon_builder', 2.0, 0.0,
                extra_items=[('createbigcannons:cannon_drill', 1)],
                deps=['cbc_foundry'],
                optional=True,
                xp=80,
                mod_refs=['createbigcannons'],
                desc=[
                    'Canos fundidos saem solidos — use a &bCannon Drill&r com agua para furar o calibre interno!',
                ],
            ),
            item_q(
                'cbc_mount', 'Monte seu canhão', 'Base e cano perfurado', 'createbigcannons:cannon_mount', 4.0, 0.0,
                extra_items=[('createbigcannons:cast_iron_cannon_barrel', 1)],
                deps=['cbc_drill'],
                optional=True,
                shape='diamond',
                size=1.3,
                xp=110,
                mod_refs=['createbigcannons'],
                desc=[
                    'Funda o cano e usine-o com a Cannon Drill. Prenda-o à base e use o Ponder para montar, apontar e carregar o canhão.',
                ],
            ),
            item_q(
                'cbc_ammo', 'Projétil e carga', 'Munição dos canhões grandes', 'createbigcannons:he_shell', 6.0, 0.0,
                extra_items=[('createbigcannons:powder_charge', 4)],
                deps=['cbc_mount', 'br_mechanical_crafting'],
                optional=True,
                shape='octagon',
                size=1.4,
                xp=140,
                mod_refs=['createbigcannons'],
                desc=[
                    'Use os Mechanical Crafters na montagem do projétil. Consulte o Ponder para carregar a munição e a carga na sequência certa.',
                ],
            ),
            item_q(
                'rad_dish', 'Vigilancia a Longa Distancia (Create: Radars)', 'Antena de Radar, Rolamento e Receptor', 'create_radar:radar_bearing', 2.0, 2.5,
                extra_items=[('create_radar:radar_dish_block', 4), ('create_radar:radar_receiver_block', 1)],
                deps=['cr_contraptions', 'br_rose_quartz'],
                optional=True,
                xp=90,
                mod_refs=['create_radar'],
                desc=[
                    'Gire a antena com energia cinetica sobre um &bRadar Bearing&r para detectar aeronaves, trens, jogadores e entidades a centenas de blocos!',
                ],
            ),
            item_q(
                'rad_monitor', 'Monitor de radar', 'Envie as leituras da antena', 'create_radar:monitor', 4.0, 2.5,
                extra_items=[('create_radar:data_link', 1)],
                deps=['rad_dish'],
                optional=True,
                xp=100,
                mod_refs=['create_radar'],
                desc=[
                    'Conecte o monitor ao radar por Data Link. Confira se a antena gira e se o receptor está configurado.',
                ],
            ),
            item_q(
                'rad_autofire', 'Sistema de Mira Automatica para Canhoes', 'Controladores Auto-Pitch, Auto-Yaw & Fire Controller', 'create_radar:fire_controller', 6.0, 2.5,
                extra_items=[('create_radar:auto_pitch_controller', 1), ('create_radar:auto_yaw_controller', 1)],
                deps=['rad_monitor', 'cbc_mount'],
                optional=True,
                shape='octagon',
                size=1.4,
                xp=160,
                mod_refs=['create_radar'],
                desc=[
                    'Conecte seu radar ao canhao usando &bAuto-Pitch&r, &bAuto-Yaw&r e &bFire Controller&r para calcular balistica e travar mira automaticamente!',
                ],
            ),
        ],
    )

    # ------------------------------------------------------------------
    # GRUPO 4: Gastronomia, Construcao & Mundo (08, 09, 10)
    # ------------------------------------------------------------------
    ch08 = ChapterSpec(
        filename="08_gastronomia_delight",
        group_key="grp_mundo",
        title="Gastronomia & Fazendas (Farmer's Delight)",
        subtitle="Fogoes, panelas, cutelaria, solo rico, pratos do Nether/Cavernas/End e automacao com Create.",
        icon="farmersdelight:cooking_pot",
        quests=[
            item_q(
                'fd_start', 'Primeira cozinha', 'Fogão e panela', 'farmersdelight:cooking_pot', 0.0, 0.0,
                extra_items=[('farmersdelight:stove', 1)],
                deps=['starter_iron'],
                optional=True,
                shape='hexagon',
                size=1.5,
                xp=50,
                mod_refs=['farmersdelight', 'eatinganimation', 'appleskin', 'smarterfarmers'],
                desc=[
                    'Coloque a panela sobre uma fonte de calor. Use tábua e faca para preparar ingredientes quando a receita pedir.',
                ],
            ),
            item_q(
                'fd_farming', 'Solo rico', 'Melhore a horta', 'farmersdelight:rich_soil', 2.0, 0.0,
                count=4,
                deps=['fd_start'],
                optional=True,
                xp=50,
                mod_refs=['farmersdelight'],
                desc=[
                    'Deixe o Organic Compost maturar para virar Rich Soil. Guarde uma parte do composto para ampliar a horta depois.',
                ],
            ),
            item_q(
                'fd_feast', 'Banquete Completo!', 'Frango Assado Servivel na Mesa', 'farmersdelight:roast_chicken_block', 4.0, 0.0,
                deps=['fd_farming'],
                optional=True,
                shape='octagon',
                size=1.2,
                xp=80,
                reward_item=('minecraft:golden_carrot', 4),
                mod_refs=['farmersdelight'],
                desc=[
                    'Coloque o banquete sobre a mesa e convide os amigos do servidor para pegar porcoes com tigelas!',
                ],
            ),
            item_q(
                'fd_slice', 'Cozinha automatizada', 'Fatiador e irrigação', 'sliceanddice:slicer', 2.0, 2.0,
                extra_items=[('sliceanddice:sprinkler', 1)],
                deps=['fd_start', 'cr_water_wheel'],
                optional=True,
                xp=85,
                mod_refs=['sliceanddice'],
                desc=[
                    'O Slicer automatiza receitas da tábua. O Sprinkler usa fertilizante líquido para acelerar o cultivo.',
                ],
            ),
            item_q(
                'mnd_nether', 'Cozinha do Nether', 'Fogão de tijolos e prato do Nether', 'mynethersdelight:nether_bricks_stove', 0.0, 3.0,
                extra_items=[('mynethersdelight:breakfast_sampler', 1)],
                deps=['fd_start', 'exp_dim_nether'],
                optional=True,
                xp=80,
                mod_refs=['mynethersdelight'],
                desc=[
                    'Prepare uma refeição com ingredientes do Nether. Procure a pimenta Bullet Pepper durante a exploração e confira as receitas no EMI.',
                ],
            ),
            item_q(
                'md_caves', 'Cozinha subterrânea', 'Panela e caneca de cobre', 'minersdelight:copper_pot', 2.0, 4.0,
                extra_items=[('minersdelight:copper_cup', 2)],
                deps=['fd_start'],
                optional=True,
                xp=70,
                mod_refs=['minersdelight'],
                desc=[
                    'Use a panela para refeições subterrâneas e leve a sopa em uma caneca de cobre.',
                ],
            ),
            item_q(
                'ed_end', 'Cozinha do End', 'Fogão do End e torta de chorus', 'ends_delight:end_stove', 4.0, 3.0,
                extra_items=[('ends_delight:chorus_fruit_pie', 1)],
                deps=['fd_start', 'exp_dim_end'],
                optional=True,
                xp=110,
                mod_refs=['ends_delight'],
                desc=[
                    'Colha a fruta e a flor de chorus nas ilhas externas. Com elas você pode montar a cozinha do End sem gastar o ovo do dragão.',
                ],
            ),
        ],
    )

    ch09 = ChapterSpec(
        filename="09_arquitetura_decoracao_e_economia",
        group_key="grp_mundo",
        title="Arquitetura, Decoração & Economia",
        subtitle="Copycats+, Quark, Supplementaries, Moveis, Iluminacao, Bricks e o sistema monetario Numismatics.",
        icon="supplementaries:globe",
        quests=[
            item_q(
                'dec_copycats', 'Construção com Copycats', 'Blocos que reaproveitam texturas', 'copycats:copycat_block', 0.0, 0.0,
                count=4,
                extra_items=[('copycats:copycat_slab', 2)],
                deps=['cr_andesite_alloy'],
                optional=True,
                shape='hexagon',
                size=1.4,
                xp=60,
                mod_refs=['copycats', 'createframed'],
                desc=[
                    'Aplique com o botão direito um material a uma peça Copycat. Experimente placas, vigas e passarelas depois das primeiras peças.',
                ],
            ),
            item_q(
                'dec_bits_prism', 'Acabamento industrial', 'Azulejos e vidro emoldurado', 'bits_n_bobs:andesite_tiles', 2.0, 0.0,
                count=4,
                extra_items=[('createprism:andesite_clear_glass_casing', 2)],
                deps=['dec_copycats'],
                optional=True,
                xp=65,
                mod_refs=['bits_n_bobs', 'createprism', 'createlanterns'],
                desc=[
                    'Combine azulejos de andesito com as molduras de vidro. Consulte o EMI para ver as opções de lâmpadas e vitrines.',
                ],
            ),
            item_q(
                'dec_bricks_furn', 'Alvenaria e móveis', 'Tijolos Black Ash e cadeira', 'brick_and_mortar:black_ash_bricks', 4.0, 0.0,
                count=4,
                extra_items=[('another_furniture:oak_chair', 1)],
                deps=['welcome'],
                optional=True,
                xp=55,
                mod_refs=['brick_and_mortar', 'another_furniture'],
                desc=[
                    'Use Black Ash Bricks para um novo acabamento. Another Furniture traz cadeiras, mesas, prateleiras e gavetas funcionais.',
                ],
            ),
            item_q(
                'dec_supp', 'Decoração funcional', 'Ampulheta e jarra', 'supplementaries:hourglass', 0.0, 2.5,
                extra_items=[('supplementaries:jar', 1)],
                deps=['welcome'],
                optional=True,
                xp=80,
                mod_refs=['supplementaries', 'amendments', 'moonlight', 'fastpaintings'],
                desc=[
                    'Teste o sinal de redstone da ampulheta e use a jarra para guardar itens ou líquidos, conforme o conteúdo.',
                ],
            ),
            item_q(
                'dec_quark', 'Construa com o Quark', 'Paleta de blocos com Trowel', 'quark:trowel', 2.0, 2.5,
                deps=['starter_iron'],
                optional=True,
                xp=85,
                mod_refs=['quark', 'zeta', 'connectiblechains', 'scholar'],
                desc=[
                    'A Trowel coloca um bloco aleatório da barra rápida. Experimente misturar materiais para texturizar uma parede.',
                ],
            ),
            item_q(
                'eco_numismatics', 'Monte uma loja', 'Vendor e terminal bancário', 'numismatics:vendor', 4.0, 3.0,
                extra_items=[('numismatics:bank_terminal', 1)],
                deps=['br_precision'],
                optional=True,
                shape='octagon',
                size=1.4,
                xp=100,
                mod_refs=['numismatics'],
                desc=[
                    'Configure um Vendor com preço e estoque. O Bank Terminal permite sacar ou depositar com um cartão autorizado.',
                ],
            ),
            item_q(
                'misc_az_horse', 'Proteja seu cavalo', 'Armadura de Netherite', 'netherite_horse_armor:netherite_horse_armor', 2.0, 4.5,
                deps=['exp_dim_nether'],
                optional=True,
                xp=80,
                mod_refs=['netherite_horse_armor', 'azimuth'],
                desc=[
                    'Procure a armadura nos baús de tesouro de bastiões. Leve um barco, blocos e rota de saída para a expedição.',
                ],
            ),
        ],
    )

    ch10 = ChapterSpec(
        filename="10_exploracao_biomas_e_estruturas",
        group_key="grp_mundo",
        title="Exploração, Dimensões & Estruturas",
        subtitle="Terralith, Tectonic, Ecologics, Friends&Foes e todas as 10 super-estruturas do YUNG!",
        icon="minecraft:ender_eye",
        quests=[
            check_q(
                'exp_terralith_tectonic', 'Um Novo Mundo: Terralith + Tectonic', 'Quase 100 biomas e relevo monumental sem lag', 'minecraft:oak_sapling', 0.0, 0.0,
                deps=['welcome'],
                shape='hexagon',
                size=1.5,
                xp=40,
                mod_refs=['terralith', 'tectonic', 'biolith', 'lithostitched'],
                desc=[
                    'O Overworld foi completamente transformado por &bTerralith&r + &bTectonic&r: cordilheiras altissimas, canions, rios profundos e dezenas de biomas novos feitos apenas com blocos vanilla!',
                ],
            ),
            item_q(
                'exp_ecologics', 'Vida na costa', 'Coco e conchas', 'ecologics:coconut', 2.0, 0.0,
                count=2,
                extra_items=[('ecologics:seashell', 2)],
                deps=['exp_terralith_tectonic'],
                xp=70,
                mod_refs=['ecologics'],
                desc=[
                    'Explore praias para encontrar coqueiros e conchas. Outros biomas têm espécies próprias do Ecologics.',
                ],
            ),
            item_q(
                'exp_friendsandfoes', 'Encontre uma buttercup', 'As primeiras criaturas de Friends & Foes', 'friendsandfoes:buttercup', 4.0, 0.0,
                deps=['exp_terralith_tectonic'],
                xp=100,
                mod_refs=['friendsandfoes', 'flowerymooblooms', 'beekeeperhut'],
                desc=[
                    'Procure a flor perto dos Mooblooms. Wildfire e seus fragmentos de coroa têm uma quest própria na trilha de chefes.',
                ],
            ),
            dim_q(
                'exp_dim_nether', 'Cruzando para o Nether', 'Fogo, Bazaltos, Fortalezas & Blazes', 'minecraft:the_nether', 'minecraft:netherrack', 0.0, 2.5,
                deps=['starter_iron'],
                xp=80,
                desc=[
                    'Acenda o portal de Obsidiana. Voce precisara de &bBlazes&r (para Blaze Burners!) e &bQuartzo&r para evoluir na Era do Latao no Create.',
                ],
            ),
            dim_q(
                'exp_dim_end', 'Despertando o The End', 'Atravesse o portal da fortaleza', 'minecraft:the_end', 'minecraft:end_stone', 0.0, 4.5,
                deps=['exp_dim_nether'],
                size=1.6,
                xp=200,
                mod_refs=['betterendisland'],
                desc=[
                    'Encontre a fortaleza com Olhos do Ender e atravesse o portal. A luta do dragão tem uma quest própria na trilha de chefes.',
                ],
            ),
            check_q("yung_overworld", "Estruturas YUNG: Superficie & Subsolo", "Dungeons, Mineshafts, Witch Huts & Extras", "minecraft:rail", 2.5, 2.5, deps=["exp_terralith_tectonic"], xp=60, mod_refs=["betterdungeons", "bettermineshafts", "betterwitchhuts", "yungsapi", "yungsextras"], desc=["Marque ao explorar qualquer versao redesenhada pelo YUNG de &bDungeons&r, &bMineshafts&r ou &bWitch Huts&r."]),
            check_q("yung_temples", "Estruturas YUNG: Templos & Oceanos", "Templos do Deserto, Selva & Monumentos Oceanicos", "minecraft:sea_lantern", 4.5, 2.5, deps=["yung_overworld"], xp=80, mod_refs=["betterdeserttemples", "betterjungletemples", "betteroceanmonuments"], desc=["Os antigos templos simples agora escondem quebra-cabecas de redstone, armadilhas mortais e salas de tesouro do &bLootr&r!"]),
            check_q("yung_endgame", "Estruturas YUNG: Fortalezas & Strongholds", "Nether Fortresses gigantes e Strongholds epicas", "minecraft:nether_bricks", 2.5, 4.5, deps=["exp_dim_nether"], xp=100, mod_refs=["betterfortresses", "betterstrongholds"], desc=["As Fortalezas do Nether e Strongholds estao monumentais — leve comida farta, mochilas e waypoints!"]),
        ],
    )

    # ------------------------------------------------------------------
    # GRUPO 5: Catalogo Completo de TODOS os mods (11 e 12)
    # ------------------------------------------------------------------
    core = [ch01, ch02, ch03, ch04, ch05, ch06, ch07, ch08, ch09, ch10]
    if "simulatedcoasters" not in mods_info:
        ch04.quests = [quest for quest in ch04.quests if quest.key != "sim_coasters"]
    bosses = build_boss_chapter()

    # Mapeia em qual capitulo cada mod aparece principalmente para informar no catalogo
    mod_to_chapter: dict[str, str] = {}
    for ch in [*core, bosses]:
        for q in ch.quests:
            for mr in q.mod_refs:
                mod_to_chapter.setdefault(mr, ch.title)
            for t in q.tasks:
                if t.kind == "item":
                    mod_to_chapter.setdefault(t.value.split(":")[0], ch.title)

    legacy_catalog = [key.removeprefix("cat_") for key in LEGACY_IDS if key.startswith("cat_")]
    part1_ids = legacy_catalog[:155]
    part2_ids = legacy_catalog[155:]
    for mid, info in sorted(mods_info.items()):
        if info["top_level"] and mid not in legacy_catalog and mid in mod_to_chapter:
            (part1_ids if mid[0] < "m" else part2_ids).append(mid)

    def make_catalog_chapter(filename: str, title: str, subtitle: str, icon: str, mod_ids: list[str]) -> ChapterSpec:
        quests: list[QuestSpec] = []
        cols = 10
        spacing = 1.6
        for idx, mid in enumerate(sorted(mod_ids)):
            info = mods_info.get(mid)
            curated = CURATED_MOD_DESCRIPTIONS.get(mid)
            dname = info["displayName"].replace("&", r"\&") if info else mid.replace("_", " ").title()
            icon_id = pick_best_icon_for_mod(mid, mods_info, all_items) if info else "minecraft:book"
            col = idx % cols
            row = idx // cols
            x = col * spacing
            y = row * spacing

            if curated:
                cat, _, pt_desc = curated
            else:
                cat = "Mod / Componente do Pack"
                raw_desc = info.get("description", "") if info else ""
                raw_desc = raw_desc or "Componente do Create+."
                pt_desc = raw_desc.replace("&", r"\&")

            lines = [
                f"&bMod ID:&r &e{mid}&r  |  &bCategoria:&r {cat}",
                "",
                pt_desc,
            ]
            if not info:
                lines.append("Este componente não está instalado nesta versão do perfil.")
            if mid in mod_to_chapter:
                lines.extend(["", f"&aGuia / Missoes detalhadas:&r Veja o capitulo &e{mod_to_chapter[mid]}&r."])

            guide = check_q(
                f"cat_{mid}", dname, f"Referência · {mid}", icon_id, x, y,
                desc=lines, task_title="Marcar como lido", shape="rsquare",
            )
            guide.optional = True
            guide.rewards.clear()
            quests.append(guide)
        return ChapterSpec(
            filename=filename,
            group_key="grp_catalogo",
            title=title,
            subtitle=subtitle,
            icon=icon,
            default_shape="rsquare",
            quests=quests,
        )

    ch11 = make_catalog_chapter(
        "11_catalogo_mods_parte_1",
        "Catálogo de Mods · Parte 1",
        "Referência opcional sobre os mods e componentes do Create+.",
        "minecraft:bookshelf",
        part1_ids,
    )
    ch12 = make_catalog_chapter(
        "12_catalogo_mods_parte_2",
        "Catálogo de Mods · Parte 2",
        "Referência opcional sobre os mods e componentes do Create+.",
        "minecraft:chiseled_bookshelf",
        part2_ids,
    )

    return [*core, ch11, ch12, bosses]


def build_boss_chapter() -> ChapterSpec:
    chapter = ChapterSpec(
        "13_chefes_e_expedicoes", "grp_mundo", "Chefes & Expedições",
        "Acompanhe os encontros de cada região e descubra suas recompensas.",
        "minecraft:diamond_sword",
    )

    def boss(key, title, icon, x, y, event, deps, desc):
        kind, value = event.split(" ", 1)
        chapter.quests.append(QuestSpec(
            key, title, "Encontro opcional", [desc], x, y,
            icon=icon, shape="hexagon", size=1.25, deps=deps,
            tasks=[TaskSpec(kind, value)], rewards=[RewardSpec("xp", 90)],
            mod_refs=[] if value.startswith("minecraft:") else [value.split(":")[0]],
            optional=True,
        ))

    boss("boss_frostmaw", "Frostmaw", "mowziesmobs:ice_crystal", 0, 0,
         "advancement mowziesmobs:kill_frostmaw", ["starter_iron"],
         "Procure o Frostmaw em regiões nevadas e evite o sopro de gelo. Derrotá-lo enquanto ainda carrega o cristal rende Ice Crystal.")
    boss("boss_wroughtnaut", "Ferrous Wroughtnaut", "mowziesmobs:wrought_axe", 2, 0,
         "advancement mowziesmobs:kill_ferrous_wroughtnaut", ["starter_iron"],
         "Explore câmaras subterrâneas. Acerte o ponto exposto nas costas após a investida. A vitória rende Axe of a Thousand Metals e Wrought Helm.")
    boss("boss_umvuthi", "Umvuthi", "mowziesmobs:sol_visage", 4, 0,
         "advancement mowziesmobs:kill_umvuthi", ["starter_iron"],
         "Procure o trono na savana. Em combate, lide com os seguidores que curam o chefe. A vitória rende Sol Visage.")
    boss("boss_sculptor_trial", "O desafio de Tongbi", "mowziesmobs:earthrend_gauntlet", 6, 0,
         "advancement mowziesmobs:sculptor_challenge", ["starter_iron"],
         "Encontre o mosteiro e entregue um Bluff Rod ao Sculptor. Complete o parkour para receber a Earthrend Gauntlet.")
    boss("boss_sir_pumpkinhead", "Sir Pumpkinhead", "born_in_chaos_v1:pumpkinstaffa", 8, 0,
         "kill born_in_chaos_v1:pumpkinhead", ["starter_iron"],
         "Encontre a abóbora ritual com velas. Derrote a cabeça na fase final para obter Pumpkin Staff e Soul Cutlass.")
    boss("boss_lord_pumpkinhead", "Lord Pumpkinhead", "born_in_chaos_v1:soulbane", 10, 0,
         "kill born_in_chaos_v1:lord_pumpkinhead_head", ["exp_dim_nether"],
         "Use Transmuting Elixir na Infernal Evil Pumpkin. A cabeça da fase final solta Soulbane, Pumpkin Pistol e outros itens.")
    boss("boss_nightmare_stalker", "Nightmare Stalker", "born_in_chaos_v1:nightmare_claw", 8, 2,
         "kill born_in_chaos_v1:nightmare_stalker", ["starter_iron"],
         "Prepare-se para um encontro à noite. Sua recompensa principal é a Nightmare Claw.")
    boss("boss_lifestealer", "Lifestealer", "born_in_chaos_v1:lifestealer_bone", 10, 2,
         "kill born_in_chaos_v1:lifestealer_true_form", ["starter_iron"],
         "Derrote a forma verdadeira para obter Lifestealer Bones e Dark Metal.")
    boss("boss_elder_guardian", "Elder Guardian", "minecraft:wet_sponge", 0, 2,
         "kill minecraft:elder_guardian", ["starter_iron"],
         "Prepare respiração aquática e entre no monumento oceânico. A vitória rende esponja molhada e pode render o molde Tide.")
    boss("boss_karkinos", "Karkinos", "hybrid_aquatic:karkinos_claw", 2, 2,
         "kill hybrid_aquatic:karkinos", ["starter_iron"],
         "Explore o oceano preparado para lutar debaixo d’água. A vitória rende Karkinos Claw e Raw Crab.")
    boss("boss_shell_beast", "Shell Beast", "hybrid_aquatic:comically_large_nautilus_shell", 4, 2,
         "kill hybrid_aquatic:shell_beast", ["starter_iron"],
         "A luta na água rende Raw Tentacle e Comically Large Nautilus Shell.")
    boss("boss_leviathan", "The Leviathan", "cataclysm:tidal_claws", 6, 2,
         "advancement cataclysm:kill_leviathan", ["starter_iron"],
         "Explore estruturas abissais com equipamento de mergulho. Derrote o Leviathan para obter Tidal Claws e Abyssal Egg.")
    boss("boss_warden", "Warden", "deeperdarker:heart_of_the_deep", 8, 4,
         "kill minecraft:warden", ["starter_iron"],
         "O Warden é um desafio opcional do Deep Dark. Sua derrota rende Heart of the Deep e carapaças.")
    chapter.quests.append(dim_q(
        "exp_otherside", "The Otherside", "Além da Cidade Ancestral",
        "deeperdarker:otherside", "deeperdarker:heart_of_the_deep", 10, 4,
        deps=["starter_iron"], xp=70, mod_refs=["deeperdarker"],
        desc=["Use Heart of the Deep para ativar o portal da Cidade Ancestral. Leve comida, luz e uma rota de volta."],
    ))
    chapter.quests[-1].optional = True
    boss("boss_stalker", "Stalker", "deeperdarker:soul_crystal", 12, 4,
         "kill deeperdarker:stalker", ["exp_otherside"],
         "Encontre o Stalker no Otherside. Sua derrota rende Soul Crystal.")
    boss("boss_sculksaur", "Sculksaur", "ec_ad:sculk_toxin", 8, 6,
         "kill ec_ad:sculksaur", ["starter_iron"],
         "Prepare-se para um combate subterrâneo. O Sculksaur deixa Sculk Toxin e Echo Shards. O arco é um drop raro.")

    boss("boss_wildfire", "Wildfire", "friendsandfoes:wildfire_crown_fragment", 0, 4,
         "kill friendsandfoes:wildfire", ["exp_dim_nether"],
         "Procure Wildfire no Nether. Reúna os fragmentos do drop para fabricar uma Wildfire Crown.")
    boss("boss_warped_mosco", "Warped Mosco", "alexsmobs:hemolymph_sac", 2, 4,
         "kill alexsmobs:warped_mosco", ["exp_dim_nether"],
         "A luta rende Hemolymph Sacs e pode render Warped Muscle.")
    boss("boss_monstrosity", "Netherite Monstrosity", "cataclysm:infernal_forge", 4, 4,
         "advancement cataclysm:kill_monstrosity", ["exp_dim_nether"],
         "Encontre a forja no Nether. A luta rende Infernal Forge, Monstrous Horn e Lava Power Cells.")
    boss("boss_wither", "Wither", "minecraft:nether_star", 0, 6,
         "kill minecraft:wither", ["exp_dim_nether"],
         "Obtenha três crânios de esqueleto Wither e quatro blocos de areia ou terra das almas. A vitória rende uma Nether Star.")
    boss("boss_harbinger", "The Harbinger", "cataclysm:witherite_block", 2, 6,
         "advancement cataclysm:kill_harbinger", ["boss_wither"],
         "Use uma Nether Star para ativar The Harbinger na estrutura subterrânea. A vitória rende um bloco de Witherite.")
    boss("boss_revenant", "Ignited Revenant", "cataclysm:burning_ashes", 4, 6,
         "advancement cataclysm:kill_revenant", ["exp_dim_nether"],
         "Encontre o Revenant no Nether e guarde Burning Ashes para invocar Ignis.")
    boss("boss_ignis", "Ignis", "cataclysm:ignitium_ingot", 6, 6,
         "advancement cataclysm:kill_ignis", ["boss_revenant"],
         "Use Burning Ashes no Altar of Fire. A vitória rende três Ignitium Ingots.")

    boss("boss_ender_dragon", "Dragão do End", "minecraft:dragon_breath", 0, 8,
         "advancement minecraft:end/kill_dragon", ["exp_dim_end"],
         "Destrua os cristais e derrote o dragão para abrir a passagem às ilhas externas. O ovo aparece na primeira vitória do mundo.")
    boss("boss_ender_golem", "Ender Golem", "cataclysm:void_core", 2, 8,
         "advancement cataclysm:kill_ender_golem", ["exp_dim_end"],
         "Explore a Ruined Citadel. A derrota do golem rende Void Core.")
    boss("boss_ender_guardian", "Ender Guardian", "cataclysm:gauntlet_of_guard", 4, 8,
         "advancement cataclysm:kill_ender_guardian", ["boss_ender_golem"],
         "Avance pela Ruined Citadel até a arena. A vitória rende Gauntlet of Guard.")
    boss("boss_void_worm", "Void Worm", "alexsmobs:void_worm_eye", 6, 8,
         "kill alexsmobs:void_worm", ["exp_dim_end"],
         "Invoque o Void Worm no End com Mysterious Worm. A vitória rende Void Worm Eye e duas mandíbulas.")
    boss("boss_remnant", "Ancient Remnant", "cataclysm:sandstorm_in_a_bottle", 0, 10,
         "advancement cataclysm:kill_remnant", ["starter_iron"],
         "Explore a estrutura do deserto e encontre o item de invocação. A vitória rende Sandstorm in a Bottle, Remnant’s Skull e um bloco de Ancient Metal.")
    boss("boss_aptrgangr", "Aptrgangr", "cataclysm:strange_key", 2, 10,
         "kill cataclysm:aptrgangr", ["starter_iron"],
         "Explore a Frozen Prison e obtenha a Strange Key para chegar ao Maledictus.")
    boss("boss_maledictus", "Maledictus", "cataclysm:cursium_ingot", 4, 10,
         "advancement cataclysm:kill_maledictus", ["boss_aptrgangr"],
         "Use a Strange Key na Frozen Prison. A vitória rende três a quatro Cursium Ingots.")
    boss("boss_scylla", "Scylla", "cataclysm:essence_of_the_storm", 6, 10,
         "advancement cataclysm:kill_scylla", ["starter_iron"],
         "Explore a Acropolis. A vitória rende Essence of the Storm e Lacrima.")
    chapter.quests.append(dim_q(
        "exp_outer_lands", "Outer Lands", "O labirinto do Thaumaturge",
        "thaumaturge:outer_lands", "thaumaturge:salis_mundus", 8, 10,
        deps=["starter_iron"], xp=70, mod_refs=["thaumaturge"],
        desc=["Siga a pesquisa do Thaumonomicon até abrir o portal para Outer Lands."],
    ))
    chapter.quests[-1].optional = True
    boss("boss_eldritch_warden", "Eldritch Warden", "thaumaturge:primordial_pearl", 10, 9,
         "kill thaumaturge:eldritch_warden", ["exp_outer_lands"],
         "Complete o encontro do labirinto e pegue o tesouro no baú da sala.")
    boss("boss_eldritch_hierophant", "Eldritch Hierophant", "thaumaturge:loot_bag_rare", 10, 11,
         "kill thaumaturge:eldritch_hierophant", ["exp_outer_lands"],
         "A recompensa fica no baú da sala do chefe, que contém Rare Treasures e itens adicionais.")
    return chapter


def validate_and_emit(chapters: list[ChapterSpec], all_items: set[str], output_dir: Path) -> None:
    all_items = all_items - {"ftbquests:missing_item"}
    missing_items: list[str] = []
    for ch in chapters:
        if ch.icon not in all_items:
            missing_items.append(f"chapter {ch.filename} icon={ch.icon}")
        for q in ch.quests:
            if q.icon and q.icon not in all_items:
                missing_items.append(f"{ch.filename}/{q.key} icon={q.icon}")
            for t in q.tasks:
                if t.kind == "item" and t.value not in all_items:
                    missing_items.append(f"{ch.filename}/{q.key} task={t.value}")
            for r in q.rewards:
                if r.kind == "item" and r.item not in all_items:
                    missing_items.append(f"{ch.filename}/{q.key} reward={r.item}")

    if missing_items:
        raise ValueError("Itens invalidos encontrados nos mod jars:\n  - " + "\n  - ".join(missing_items))

    quests = {q.key: q for ch in chapters for q in ch.quests}
    if len(quests) != sum(len(ch.quests) for ch in chapters):
        raise ValueError("Chave de quest duplicada no livro")
    missing_deps = {dep for q in quests.values() for dep in q.deps if dep not in quests}
    if missing_deps:
        raise ValueError(f"Dependências sem quest: {sorted(missing_deps)}")
    tuple(TopologicalSorter({key: q.deps for key, q in quests.items()}).static_order())

    quest_ids = {key: stable_id(3, key, LEGACY_IDS.get(key, (0,))[0]) for key in quests}
    if len(set(quest_ids.values())) != len(quests):
        raise ValueError("IDs de quest duplicados")

    # Alocadores de IDs hex 16 chars
    grp_alloc = IdAlloc(1)
    ch_alloc = IdAlloc(2)

    groups_meta = [
        ("grp_inicio", "Início & Sobrevivência"),
        ("grp_create", "Engenharia Create"),
        ("grp_aero", "Aeronáutica, Física & Combate"),
        ("grp_mundo", "Gastronomia, Construção & Mundo"),
        ("grp_catalogo", "Referência do Modpack"),
    ]
    group_ids: dict[str, str] = {}
    lang_entries: list[tuple[str, str | list[str]]] = [
        ("file.0000000000000001.title", "Create+ | Livro de Quests"),
    ]

    for gkey, gtitle in groups_meta:
        gid = grp_alloc.next()
        group_ids[gkey] = gid
        lang_entries.append((f"chapter_group.{gid}.title", gtitle))

    # data.snbt
    write_snbt_file(
        output_dir / "data.snbt",
        [
            "{",
            '\tdefault_autoclaim_rewards: "disabled"',
            "\tdefault_consume_items: false",
            "\tdefault_quest_disable_jei: false",
            '\tdefault_quest_shape: "circle"',
            "\tdefault_reward_team: false",
            "\tdetection_delay: 20",
            "\tdisable_gui: false",
            "\tdrop_book_on_death: false",
            "\tdrop_loot_crates: false",
            "\temergency_items_cooldown: 0",
            '\tfallback_locale: "pt_br"',
            "\tgrid_scale: 0.5d",
            "\thide_excluded_quests: false",
            "\ticon: {",
            '\t\tid: "create:brass_hand"',
            "\t}",
            '\tlock_message: ""',
            "\tloot_crate_no_drop: { boss: 0, monster: 600, passive: 4000 }",
            "\tpause_game: false",
            "\tpresets: {",
            '\t\tgoal: { shape: "hexagon", size: 2.0d }',
            '\t\tinfo: { shape: "gear", size: 1.0d }',
            '\t\tnormal: { shape: "square", size: 1.0d }',
            "\t}",
            '\tprogression_mode: "flexible"',
            "\tshow_lock_icons: true",
            "\tverify_on_load: false",
            "\tversion: 13",
            "}",
        ],
    )

    # chapter_groups.snbt
    cg_lines = ["{", "\tchapter_groups: ["]
    for gkey, _ in groups_meta:
        cg_lines.append(f'\t\t{{ id: "{group_ids[gkey]}" }}')
    cg_lines.extend(["\t]", "}"])
    write_snbt_file(output_dir / "chapter_groups.snbt", cg_lines)

    total_quests = 0
    for order_idx, ch in enumerate(chapters):
        cid = ch_alloc.next()
        gid = group_ids[ch.group_key]
        lang_entries.append((f"chapter.{cid}.title", ch.title))
        lang_entries.append((f"chapter.{cid}.chapter_subtitle", [ch.subtitle]))

        ch_lines = [
            "{",
            "\tdefault_hide_dependency_lines: false",
            f"\tdefault_quest_shape: {snbt_str(ch.default_shape)}",
            f"\tfilename: {snbt_str(ch.filename)}",
            f"\tgroup: {snbt_str(gid)}",
            "\ticon: {",
            f"\t\tid: {snbt_str(ch.icon)}",
            "\t}",
            f"\tid: {snbt_str(cid)}",
            "\timages: [ ]",
            f"\torder_index: {order_idx}",
            "\tquest_links: [ ]",
            "\tquests: [",
        ]

        for q in ch.quests:
            total_quests += 1
            qid = quest_ids[q.key]
            lang_entries.append((f"quest.{qid}.title", q.title))
            if q.subtitle:
                lang_entries.append((f"quest.{qid}.quest_subtitle", q.subtitle))
            if q.desc:
                lang_entries.append((f"quest.{qid}.quest_desc", q.desc))

            ch_lines.append("\t\t{")
            if q.deps:
                dep_ids = [snbt_str(quest_ids[d]) for d in q.deps]
                ch_lines.append(f"\t\t\tdependencies: [ {', '.join(dep_ids)} ]")
            if q.icon:
                ch_lines.append("\t\t\ticon: {")
                ch_lines.append(f"\t\t\t\tid: {snbt_str(q.icon)}")
                ch_lines.append("\t\t\t}")
            ch_lines.append(f"\t\t\tid: {snbt_str(qid)}")
            if q.optional:
                ch_lines.append("\t\t\toptional: true")

            # Rewards
            if q.rewards:
                ch_lines.append("\t\t\trewards: [")
                legacy_rewards = LEGACY_IDS.get(q.key, (0, [], []))[2]
                for index, rw in enumerate(q.rewards):
                    legacy = legacy_rewards[index] if index < len(legacy_rewards) else 0
                    rid = stable_id(5, f"{q.key}:{rw.kind}:{rw.item}:{index}", legacy)
                    ch_lines.append("\t\t\t\t{")
                    if rw.kind == "xp":
                        ch_lines.append(f"\t\t\t\t\tid: {snbt_str(rid)}")
                        ch_lines.append('\t\t\t\t\ttype: "xp"')
                        ch_lines.append(f"\t\t\t\t\txp: {rw.xp}")
                    elif rw.kind == "item":
                        if rw.count > 1:
                            ch_lines.append(f"\t\t\t\t\tcount: {rw.count}")
                        ch_lines.append(f"\t\t\t\t\tid: {snbt_str(rid)}")
                        ch_lines.append("\t\t\t\t\titem: {")
                        ch_lines.append("\t\t\t\t\t\tcount: 1")
                        ch_lines.append(f"\t\t\t\t\t\tid: {snbt_str(rw.item)}")
                        ch_lines.append("\t\t\t\t\t}")
                        ch_lines.append('\t\t\t\t\ttype: "item"')
                    ch_lines.append("\t\t\t\t}")
                ch_lines.append("\t\t\t]")

            ch_lines.append(f"\t\t\tshape: {snbt_str(q.shape)}")
            ch_lines.append(f"\t\t\tsize: {snbt_float_d(q.size)}")

            # Tasks
            ch_lines.append("\t\t\ttasks: [")
            for tk in q.tasks:
                task_key = f"{tk.kind}:{tk.value}"
                legacy = LEGACY_IDS.get(q.key, (0, {}, []))[1].get(task_key, 0)
                tid = stable_id(4, f"{q.key}:{task_key}", legacy)
                ch_lines.append("\t\t\t\t{")
                if tk.kind == "item":
                    if tk.count != 1:
                        ch_lines.append(f"\t\t\t\t\tcount: {tk.count}L")
                    ch_lines.append(f"\t\t\t\t\tid: {snbt_str(tid)}")
                    ch_lines.append("\t\t\t\t\titem: {")
                    ch_lines.append("\t\t\t\t\t\tcount: 1")
                    ch_lines.append(f"\t\t\t\t\t\tid: {snbt_str(tk.value)}")
                    ch_lines.append("\t\t\t\t\t}")
                    ch_lines.append('\t\t\t\t\ttype: "item"')
                elif tk.kind == "checkmark":
                    ch_lines.append(f"\t\t\t\t\tid: {snbt_str(tid)}")
                    ch_lines.append('\t\t\t\t\ttype: "checkmark"')
                    if tk.title:
                        lang_entries.append((f"task.{tid}.title", tk.title))
                elif tk.kind == "dimension":
                    ch_lines.append(f"\t\t\t\t\tdimension: {snbt_str(tk.value)}")
                    ch_lines.append(f"\t\t\t\t\tid: {snbt_str(tid)}")
                    ch_lines.append('\t\t\t\t\ttype: "dimension"')
                    if tk.title:
                        lang_entries.append((f"task.{tid}.title", tk.title))
                elif tk.kind == "advancement":
                    ch_lines.append(f"\t\t\t\t\tadvancement: {snbt_str(tk.value)}")
                    ch_lines.append(f"\t\t\t\t\tid: {snbt_str(tid)}")
                    ch_lines.append('\t\t\t\t\ttype: "advancement"')
                elif tk.kind == "kill":
                    ch_lines.append(f"\t\t\t\t\tentity: {snbt_str(tk.value)}")
                    ch_lines.append(f"\t\t\t\t\tid: {snbt_str(tid)}")
                    ch_lines.append('\t\t\t\t\ttype: "kill"')
                    ch_lines.append(f"\t\t\t\t\tvalue: {tk.count}L")
                else:
                    raise ValueError(f"Tipo de tarefa desconhecido: {tk.kind}")
                ch_lines.append("\t\t\t\t}")
            ch_lines.append("\t\t\t]")

            ch_lines.append(f"\t\t\tx: {snbt_float_d(q.x)}")
            ch_lines.append(f"\t\t\ty: {snbt_float_d(q.y)}")
            ch_lines.append("\t\t}")

        ch_lines.extend(["\t]", "}"])
        write_snbt_file(output_dir / "chapters" / f"{ch.filename}.snbt", ch_lines)

    # Lang files (pt_br e en_us com os textos PT-BR para garantir leitura independente do idioma do cliente)
    lang_lines = ["{"]
    for k, val in sorted(lang_entries, key=lambda kv: kv[0]):
        if isinstance(val, list):
            if len(val) == 1:
                lang_lines.append(f"\t{k}: [ {snbt_str(quest_text(val[0]))} ]")
            else:
                lang_lines.append(f"\t{k}: [")
                for line in val:
                    lang_lines.append(f"\t\t{snbt_str(quest_text(line))}")
                lang_lines.append("\t]")
        else:
            lang_lines.append(f"\t{k}: {snbt_str(quest_text(val))}")
    lang_lines.append("}")

    write_snbt_file(output_dir / "lang" / "pt_br.snbt", lang_lines)
    write_snbt_file(output_dir / "lang" / "en_us.snbt", lang_lines)

    catalog_count = sum(len(ch.quests) for ch in chapters if ch.group_key == "grp_catalogo")
    print(
        f"Livro de Quests gerado com sucesso: "
        f"{len(groups_meta)} grupos, {len(chapters)} capitulos, {total_quests} quests, "
        f"{catalog_count} mods documentados no catalogo!"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Gera o livro de quests do Create+ preservando IDs existentes.")
    parser.add_argument("--output", type=Path, default=QUESTS_DIR)
    args = parser.parse_args()
    mods_info, all_items = scan_installed_mods()
    chapters = build_chapters(mods_info, all_items)
    validate_and_emit(chapters, all_items, args.output)


if __name__ == "__main__":
    main()
