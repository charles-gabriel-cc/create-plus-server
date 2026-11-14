#!/usr/bin/env python3
"""
Gera o livro completo de quests do FTB Quests (MC 1.21.1 NeoForge / version 13)
em config/ftbquests/quests/, cobrindo a progressao detalhada dos mods principais
e um catalogo de 100% dos mods instalados no modpack Create+.
"""

from __future__ import annotations

import glob
import io
import json
import os
import tomllib
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
MODS_DIR = ROOT / "mods"
QUESTS_DIR = ROOT / "config" / "ftbquests" / "quests"

# Submodulos internos puramente tecnicos (Maven shaded libs / sub-apis) que nao
# precisam poluir o catalogo porque ja pertencem ao jar do mod pai documentado.
IGNORED_SUBMODULE_PREFIXES = (
    "com_fasterxml_",
    "org_apache_groovy_",
    "dev_lukebemish_opensesame_",
    "fabric_api_base",
    "fabric_block_view_api_",
    "fabric_renderer_api_",
    "fabric_rendering_data_attachment_",
    "connectorextras_",
    "forgified_fabric_api",
    "ixeris_dummy",
    "groovybundler",
    "mixinsquared",
    "conditional_mixin",
    "voicechat_api",
    "xaerolib",
    "kuma_api",
    "sable_rapier",
    "pym",
)

# Itens vanilla 1.21.1 verificados usados como icones/tarefas/recompensas.
VANILLA_ITEMS = {
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


def snbt_str(s: str) -> str:
    escaped = (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "")
    )
    return f'"{escaped}"'


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

            # Itens considerados 100% confiaveis: intersecao com lang quando ha lang,
            # alem de itens referenciados como resultado de receitas.
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
    kind: str  # "item", "checkmark", "dimension"
    value: str = ""  # item id or dimension id
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
    xp: int = 15,
    reward_item: tuple[str, int] | None = None,
    shape: str = "circle",
    size: float = 1.0,
    mod_refs: list[str] | None = None,
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
        tasks=[TaskSpec(kind="checkmark", title=task_title)],
        rewards=rewards,
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
    "brick_and_mortar": ("Decoracao & Vanilla+", "brick_and_mortar:algal_tiles", "Tijolos, azulejos e fornada especializada com dezenas de variantes de construcao."),
    "c2me": ("Performance & Server", "minecraft:compass", "Concurrent Chunk Management Engine: gera e carrega chunks usando varios nucleos da CPU."),
    "cardboarded_conveynience": ("Engenharia Create", "cardboarded_conveynience:invisible_package", "Permite se disfarcar de encomenda de papelao e viajar pelas esteiras aereas (Chain Conveyors)!"),
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
                "welcome",
                "Bem-vindo ao Create+!",
                "Seu guia completo do modpack",
                "ftbquests:book",
                0.0,
                0.0,
                shape="hexagon",
                size=1.8,
                xp=50,
                reward_item=("minecraft:bread", 8),
                mod_refs=["ftbquests", "ftblibrary", "ftbteams", "ftbxmodcompat", "ftbfiltersystem"],
                desc=[
                    "Bem-vindo ao &bCreate+&r (Minecraft 1.21.1 NeoForge)!",
                    "",
                    "Este livro foi montado para guiar desde os primeiros passos ate maquinas voadoras, trens, petroleo, eletricidade, canhoes e receitas — cobrindo &atodos os mods do pack&r.",
                    "",
                    "Dicas iniciais:",
                    "- Use &e/ftbteams&r para formar um time e compartilhar progresso de quests com os amigos.",
                    "- Segure &e[W]&r sobre pecas do Create no inventario para abrir tutoriais animados em 3D (&bPonder&r)!",
                    "- No EMI (lado direito do inventario), aperte &e[R]&r para ver a receita ou &e[U]&r para ver onde o item e usado.",
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
                "bp_gold",
                "Mochila de Ouro",
                "Upgrade de capacidade Tier 3",
                "sophisticatedbackpacks:gold_backpack",
                -2.5,
                5.0,
                deps=["bp_iron"],
                xp=60,
                desc=["Mais espaco ainda para longas expedicoes e obras com o Create."],
            ),
            item_q(
                "bp_diamond",
                "Mochila de Diamante",
                "Upgrade de capacidade Tier 4",
                "sophisticatedbackpacks:diamond_backpack",
                -2.5,
                6.5,
                deps=["bp_gold"],
                xp=100,
                desc=["Quase um bau duplo inteiro nas costas, com multiplos slots de upgrades."],
            ),
            item_q(
                "bp_netherite",
                "Mochila de Netherite",
                "A prova de lava — Capacidade maxima",
                "sophisticatedbackpacks:netherite_backpack",
                -2.5,
                8.0,
                deps=["bp_diamond"],
                shape="octagon",
                size=1.3,
                xp=200,
                reward_item=("minecraft:netherite_scrap", 1),
                desc=["Nao queima na lava e oferece armazenamento portatil absurdo!"],
            ),
            item_q(
                "bp_upgrades_util",
                "Upgrades Essenciais da Mochila",
                "Ima + Coleta + Alimentacao + Compactacao",
                "sophisticatedbackpacks:magnet_upgrade",
                -4.5,
                3.5,
                deps=["bp_basic"],
                extra_items=[
                    ("sophisticatedbackpacks:pickup_upgrade", 1),
                    ("sophisticatedbackpacks:feeding_upgrade", 1),
                    ("sophisticatedbackpacks:compacting_upgrade", 1),
                ],
                xp=75,
                mod_refs=["sophisticatedbackpacks", "sophisticatedcore"],
                desc=[
                    "Instale esses upgrades nos slots laterais da mochila:",
                    "- &eMagnet Upgrade&r: puxa itens do chao direto para a mochila.",
                    "- &ePickup Upgrade&r: coleta automaticamente blocos minerados.",
                    "- &eFeeding Upgrade&r: alimenta voce automaticamente quando sentir fome.",
                    "- &eCompacting Upgrade&r: compacta minerios e pedregulhos em blocos 2x2 / 3x3.",
                ],
            ),
            item_q(
                "bp_deposit",
                "Deposito Rapido em Baus",
                "Sophisticated Backpacks — Deposit Upgrade",
                "sophisticatedbackpacks:deposit_upgrade",
                -4.5,
                5.0,
                deps=["bp_upgrades_util"],
                xp=50,
                desc=["Com um clique (Shift + Botao Direito no bau), descarrega da mochila tudo que combina com o bau!"],
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
            item_q(
                "lootr_chest_guide",
                "Tesouros Individuais (Lootr)",
                "Todo bau de estrutura tem loot proprio para cada jogador!",
                "lootr:trophy",
                2.5,
                2.0,
                deps=["welcome"],
                xp=50,
                mod_refs=["lootr"],
                desc=[
                    "No servidor multiplayer, &bLootr&r garante que voce NUNCA encontre uma dungeon ja saqueada!",
                    "Baus dourados/azuis brilhantes do Lootr geram um inventario separado e exclusivo para cada jogador que o abrir pela primeira vez.",
                    "A Taca (&eTrophy&r) ou baus antigos convertidos mostram quantos baus voce ja saqueou!",
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
                "maps_xaero",
                "Minimapa & Mapa Mundi",
                "Xaero's Minimap e World Map",
                "minecraft:compass",
                0.0,
                3.5,
                deps=["map_atlas"],
                xp=25,
                mod_refs=["xaerominimap", "xaeroworldmap"],
                desc=[
                    "Controles padrao do mapa:",
                    "- &e[M]&r abre o Mapa Mundi completo com zoom e marcações.",
                    "- &e[B]&r cria um Waypoint rapido na sua posicao atual.",
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
        subtitle="Primeiros passos da engenharia cinetica: engrenagens, agua, vento, prensas e fluidos.",
        icon="create:andesite_alloy",
        quests=[
            item_q("cr_andesite_alloy", "Liga de Andesito", "Base de toda a era inicial do Create", "create:andesite_alloy", 0.0, 0.0, count=8, shape="hexagon", size=1.5, xp=30, reward_item=("create:andesite_alloy", 8), desc=["Combine &bAndesito&r com pepitas de Ferro ou Zinco na bancada — ou melhor ainda, no Misturador depois para render o dobro!"]),
            item_q("cr_shaft", "Eixos (Shafts)", "Transmitindo rotacao em linha reta", "create:shaft", 2.0, 0.0, count=8, deps=["cr_andesite_alloy"], xp=20, desc=["Shafts transmitem rotacao sem alterar velocidade nem direcao."]),
            item_q("cr_cogs", "Engrenagens Pequenas & Grandes", "Alterando velocidade (RPM) e direcao", "create:cogwheel", 4.0, 0.0, count=4, extra_items=[("create:large_cogwheel", 2)], deps=["cr_shaft"], xp=30, desc=["Conecte uma Engrenagem Grande em uma Pequena para DOBRAR o RPM (reduzindo torque), ou o inverso para aumentar a forca!"]),
            item_q("cr_wrench", "Chave Inglesa do Create", "A ferramenta mais importante do engenheiro", "create:wrench", 2.0, -2.0, deps=["cr_andesite_alloy"], xp=30, desc=["Rotaciona blocos do Create, configura pistas/filtros e desmonta maquinas instantaneamente com &eShift + Botao Direito&r."]),
            item_q("cr_goggles", "Oculos de Engenheiro", "Veja SU, RPM e fluidos nos blocos!", "create:goggles", 4.0, -2.0, deps=["cr_wrench"], xp=50, desc=["Equipe no capacete para enxergar o estresse (SU), RPM, direcao de eixos e conteudo de tanques/tubos ao olhar para qualquer maquina."]),
            item_q("cr_hand_crank", "Manivela Manual", "Energia bruta com a forca do braco", "create:hand_crank", 0.0, 2.0, deps=["cr_andesite_alloy"], xp=20, desc=["Gire manualmente para testar maquinas antes de montar geracao automatica."]),
            item_q("cr_water_wheel", "Roda d'Agua & Roda Grande", "Energia hidraulica 24h", "create:water_wheel", 2.0, 2.0, extra_items=[("create:large_water_wheel", 1)], deps=["cr_shaft"], xp=50, desc=["Posicione agua correndo pelas pas da roda. A &bLarge Water Wheel&r gira mais devagar porem gera &a512 SU&r constante!"]),
            item_q("cr_windmill", "Moinho de Vento", "Engenharia eolica de alta capacidade", "create:windmill_bearing", 2.0, 4.0, extra_items=[("create:white_sail", 16)], deps=["cr_water_wheel"], xp=75, desc=["Quanto mais &bSails&r (velas) acopladas ao Windmill Bearing (ate 128 blocos), maior a energia (ate &a8192 SU&r)! Nao precisa nem usar Super Cola nas velas."]),
            item_q("cr_millstone", "Moinho de Pedra (Millstone)", "Moagem inicial de graos e minerios", "create:millstone", 6.0, 0.0, deps=["cr_cogs"], xp=40, desc=["Transforma trigo em farinha, ossos em farinha de osso com bonus e flores em mais corantes."]),
            item_q("cr_press", "Prensa Mecanica + Deposito", "Forje chapas de Ferro, Cobre e Ouro", "create:mechanical_press", 6.0, 2.0, extra_items=[("create:depot", 1)], deps=["cr_cogs"], xp=50, reward_item=("create:iron_sheet", 4), desc=["Coloque a Prensa 2 blocos acima de um Depot ou Esteira para amassar lingotes em &bChapas (Sheets)&r."]),
            item_q("cr_mixer", "Misturador Mecanico + Bacia", "Receitas automatizadas e ligas", "create:mechanical_mixer", 8.0, 2.0, extra_items=[("create:basin", 1)], deps=["cr_press"], xp=60, desc=["Junto a uma Basin abaixo, mistura ingredientes e dobra o rendimento da Liga de Andesito."]),
            item_q("cr_blaze_burner", "Queimador de Blaze", "Calor intenso para forjar Latao e aco", "create:blaze_burner", 10.0, 2.0, deps=["cr_mixer"], shape="octagon", size=1.3, xp=100, reward_item=("create:blaze_cake", 1), desc=["Construa um &bEmpty Blaze Burner&r e clique com ele direito em um Blaze (ou Spawner de Blaze no Nether) para captura-lo! Essencial para aquecer receitas na Basin."]),
            item_q("cr_encased_fan", "Ventilador Encaixotado", "Lavar, defumar, fundir e assombrar em massa", "create:encased_fan", 6.0, -2.0, deps=["cr_cogs"], xp=45, desc=["Assopre atraves de: &9Agua&r (lavar cascalho -> pepitas de ferro!), &6Fogo&r (defumar), &cLava&r (fundir stacks sem carvao!) ou &5Fogo das Almas&r (assombrar)."]),
            item_q("cr_belt", "Esteiras & Caixas de Andesito", "Transporte de itens em linha de producao", "create:belt_connector", 8.0, -2.0, count=4, extra_items=[("create:andesite_casing", 4)], deps=["cr_press"], xp=45, desc=["Clique entre dois Shafts alinhados para estender uma Esteira Mecanica. Clique com Casings na esteira para encaixota-la."]),
            item_q("cr_funnels_chutes", "Funis, Tuneis e Calhas", "Entrada, saida e separacao de itens", "create:andesite_funnel", 10.0, -2.0, extra_items=[("create:andesite_tunnel", 1), ("create:chute", 2)], deps=["cr_belt"], xp=40, desc=["Use Funnels para inserir/extrair de baus diretamente sobre esteiras e Chutes para transporte vertical."]),
            item_q(
                "cr_farm_machines",
                "Maquinas de Colheita, Madeira e Mineracao",
                "Serra, Furadeira, Colheitadeira e Arado",
                "create:mechanical_saw",
                8.0,
                4.0,
                extra_items=[
                    ("create:mechanical_drill", 1),
                    ("create:mechanical_harvester", 1),
                    ("create:mechanical_plough", 1),
                ],
                deps=["cr_belt"],
                xp=75,
                desc=[
                    "Quando acopladas em contraptions moveis (veja rolamentos abaixo), essas ferramentas fazem milagres:",
                    "- &bMechanical Saw&r: derruba e replanta arvores inteiras em segundos.",
                    "- &bMechanical Drill&r: escava tuneis gigantes em carrinhos ou plataformas.",
                    "- &bHarvester&r: colhe plantacoes maduras e ja replanta.",
                ],
            ),
            item_q(
                "cr_copper_fluids",
                "Era do Cobre: Fluidos e Tubulacoes",
                "Casing de Cobre, Tubos, Bomba, Tanque, Bico e Dreno",
                "create:copper_casing",
                6.0,
                6.0,
                extra_items=[
                    ("create:fluid_pipe", 8),
                    ("create:mechanical_pump", 1),
                    ("create:fluid_tank", 2),
                    ("create:hose_pulley", 1),
                    ("create:spout", 1),
                    ("create:item_drain", 1),
                ],
                deps=["cr_press"],
                xp=80,
                desc=[
                    "Domine liquidos, lava e pocoes:",
                    "- &bHose Pulley&r: bombeia lagos de lava/agua (com mais de 10.000 blocos vira fonte infinita!).",
                    "- &bSpout&r: despeja liquidos sobre itens em Depots/Esteiras.",
                    "- &bItem Drain&r: esvazia baldes e frascos direto para a rede de canos.",
                ],
            ),
            item_q(
                "cr_diving_gear",
                "Equipamento de Mergulho de Cobre",
                "Respire indefinidamente debaixo d'agua ou na lava",
                "create:copper_backtank",
                8.0,
                6.0,
                extra_items=[("create:copper_diving_helmet", 1), ("create:copper_diving_boots", 1)],
                deps=["cr_copper_fluids"],
                xp=75,
                desc=["Encha o Backtank conectado a um eixo rotativo e use com o Capacete de Mergulho!"],
            ),
            item_q(
                "cr_contraptions",
                "Contraptions Moveis & Colagem",
                "Super Glue, Chassis, Rolamentos, Pistoes e Montador",
                "create:super_glue",
                10.0,
                4.0,
                extra_items=[
                    ("create:mechanical_bearing", 1),
                    ("create:mechanical_piston", 1),
                    ("create:cart_assembler", 1),
                    ("create:linear_chassis", 2),
                    ("create:radial_chassis", 1),
                    ("create:portable_storage_interface", 2),
                ],
                deps=["cr_farm_machines"],
                xp=90,
                desc=[
                    "Use a &bSuper Glue&r para unir blocos selecionando os dois cantos de uma estrutura!",
                    "Depois acople em um &bMechanical Bearing&r (fazendas circulares), &bCart Assembler&r (tuneleiras), &bRope/Elevator Pulley&r ou &bPortable Storage Interface&r para descarregar itens automaticamente.",
                ],
            ),
            item_q(
                "cr_elevators_schematics",
                "Elevadores, Polias & Canhao de Schematics",
                "Construa estruturas salvas automaticamente",
                "create:elevator_pulley",
                12.0,
                4.0,
                extra_items=[
                    ("create:rope_pulley", 1),
                    ("create:schematic_table", 1),
                    ("create:schematicannon", 1),
                ],
                deps=["cr_contraptions"],
                xp=90,
                desc=["Construa elevadores suaves multi-andares com &bElevator Pulley&r ou use &bSchematic Table + Schematicannon&r (alimentado com polvora) para imprimir projetos salvos bloco por bloco!"],
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
            item_q("br_rose_quartz", "Quartzo Rosa Polido & Tubo de Eletrons", "Circuitos analogicos do Create", "create:polished_rose_quartz", 0.0, 0.0, extra_items=[("create:electron_tube", 2)], shape="hexagon", size=1.4, xp=45, desc=["Combine Quartzo do Nether com Redstone para criar Rose Quartz, depois lixe com Lixa (Sand Paper)."]),
            item_q("br_brass", "Lingote & Casing de Latao", "Desbloqueando maquinas inteligentes", "create:brass_ingot", 2.0, 0.0, count=8, extra_items=[("create:brass_casing", 4)], deps=["br_rose_quartz"], xp=50, desc=["Aqueça &bCobre + Zinco&r na Basin usando um Blaze Burner!"]),
            item_q("br_deployer", "Mao de Latao & Deployer", "O braco robotico que clica, monta e usa itens", "create:deployer", 4.0, 0.0, extra_items=[("create:brass_hand", 1)], deps=["br_brass"], xp=65, desc=["Essencial para linhas de montagem sequenciais, automacao de fazendas e uso automatico de ferramentas."]),
            item_q("br_precision", "Mecanismo de Precisao", "O coracao das maquinas avancadas", "create:precision_mechanism", 6.0, 0.0, deps=["br_deployer"], shape="octagon", size=1.3, xp=120, reward_item=("create:precision_mechanism", 1), desc=["Montagem sequencial (5 ciclos): Prensa + Engrenagem Pequena + Engrenagem Grande + Pepita de Ferro sobre uma Chapa de Ouro!"]),
            item_q("br_crafter_crusher", "Crafters Mecanicos & Rodas Trituradoras", "Receitas 3x3+ e trituracao com bonus", "create:crushing_wheel", 8.0, 0.0, count=2, extra_items=[("create:mechanical_crafter", 9)], deps=["br_precision"], xp=120, desc=["Use 21+ &bMechanical Crafters&r em cadeia para forjar um par de &bCrushing Wheels&r! Elas multiplicam minerios e geram XP em pepitas."]),
            item_q("br_rsc_arm", "Controlador de Velocidade & Braco Mecanico", "Ajuste fino de RPM (1 a 256) e bracos roboticos", "create:rotation_speed_controller", 6.0, 2.0, extra_items=[("create:mechanical_arm", 1)], deps=["br_precision"], xp=90, desc=["Com uma Large Cogwheel encaixada acima, defina exatamente de -256 a +256 RPM com um clique!"]),
            item_q("br_smart_logistics", "Filtros Inteligentes, Observadores & Links", "Sensores de estoque, Redstone Link, Nixie & Telao", "create:brass_funnel", 4.0, 2.0, extra_items=[("create:brass_tunnel", 1), ("create:smart_chute", 1), ("create:smart_fluid_pipe", 1), ("create:content_observer", 1), ("create:stockpile_switch", 1), ("create:redstone_contact", 1), ("create:redstone_link", 2), ("create:nixie_tube", 2), ("create:display_link", 1), ("create:display_board", 2)], deps=["br_brass"], xp=100, desc=["Monitore estoques, transmita redstone sem fio com &bRedstone Link&r e projete informacoes em tempo real nos &bDisplay Boards&r."]),
            item_q("br_steam_engine", "Motor a Vapor Industrial", "Energia cinetica colossal (ate 294.912 SU!)", "create:steam_engine", 2.0, 2.5, deps=["br_brass"], shape="diamond", size=1.4, xp=120, reward_item=("create:steam_whistle", 1), desc=["Empilhe Fluid Tanks, bombeie agua constante, coloque Blaze Burners embaixo e conecte &bSteam Engines&r aos tanques!"]),
            item_q(
                "br_trains",
                "Ferrovias do Create: Era dos Trens",
                "Casings Ferroviarios, Trilhos, Estacoes, Sinais e Rotas",
                "create:track",
                2.0,
                4.5,
                count=16,
                extra_items=[
                    ("create:railway_casing", 4),
                    ("create:track_station", 2),
                    ("create:controls", 1),
                    ("create:track_signal", 2),
                    ("create:track_observer", 1),
                    ("create:schedule", 1),
                ],
                deps=["br_brass"],
                shape="hexagon",
                size=1.4,
                xp=130,
                desc=[
                    "Trens do Create funcionam atraves de chunks descarregados e até cruzam Portais do Nether!",
                    "Use &bTrain Schedule&r para automatizar maquinistas (Blazes ou mobs sentados) viajando de estacao em estacao.",
                ],
            ),
            item_q(
                "cr6_cardboard",
                "Logistica Create 6.0: Papelao & Empacotamento",
                "Cardboard, Packager, Repackager e Frogport",
                "create:packager",
                8.0,
                2.5,
                extra_items=[
                    ("create:cardboard", 4),
                    ("create:cardboard_block", 1),
                    ("create:bound_cardboard_block", 1),
                    ("create:repackager", 1),
                    ("create:package_frogport", 2),
                ],
                deps=["br_precision"],
                shape="rsquare",
                size=1.3,
                xp=120,
                desc=[
                    "Novidade revolucionaria do &bCreate 6.0 (Post Production)&r!",
                    "- &bPackager&r: transforma itens de um bau/vault em caixas lacradas com etiqueta de destino.",
                    "- &bPackage Frogport&r: sapos mecanicos que pegam e arremessam pacotes entre redes de correntes!",
                ],
            ),
            item_q(
                "cr6_network",
                "Rede Central de Estoque & Correias Aereas",
                "Stock Link, Stock Ticker, Factory Gauge, Chain Conveyor & Conveynience",
                "create:stock_ticker",
                10.0,
                2.5,
                extra_items=[
                    ("create:stock_link", 2),
                    ("create:redstone_requester", 1),
                    ("create:factory_gauge", 2),
                    ("create:brass_table_cloth", 1),
                    ("create:chain_conveyor", 4),
                    ("create:transmitter", 1),
                    ("cardboarded_conveynience:invisible_package", 1),
                ],
                deps=["cr6_cardboard"],
                shape="octagon",
                size=1.4,
                xp=150,
                mod_refs=["create", "cardboarded_conveynience", "create_access_denied"],
                desc=[
                    "Monte seu proprio sistema estilo 'AE2/Armazem Mecanico' usando apenas engrenagens e caixas!",
                    "- Conecte &bStock Links&r em seus baus e controle tudo pelo &bStock Ticker&r (com um Blaze sentado).",
                    "- &bChain Conveyors&r ligam predios inteiros transportando pacotes pelo ar (e voce mesmo pode viajar pendurado usando a Wrench!).",
                    "- &bCardboarded Conveynience&r permite viajar disfarçado de pacote nas correntes!",
                    "- &bCreate: Access Denied&r protege redes privadas contra pedidos nao autorizados.",
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
            item_q("snr_conductor", "Steam 'n' Rails: Condutores e Handcar", "Bone de condutor, apito e vagoneta manual", "railways:conductor_whistle", 0.0, 0.0, extra_items=[("railways:black_conductor_cap", 1), ("railways:handcar", 1)], shape="hexagon", size=1.4, xp=60, mod_refs=["railways", "createthreadedtrains"], desc=["Construa um &bConductor&r com o bone, ou atravesse os trilhos pedalando um &bHandcar&r! O addon &eThreaded Trains&r acelera todo o servidor."]),
            item_q("snr_tracks", "Trilhos Especiais, Acopladores & Locometal", "Monotrilho, bitola larga/estreita, semaforos e caldeiras", "railways:track_monorail", 2.0, 0.0, extra_items=[("railways:track_oak_narrow", 4), ("railways:track_oak_wide", 4), ("railways:track_coupler", 1), ("railways:semaphore", 1), ("railways:smokestack_oilburner", 1), ("railways:brass_wrapped_locometal", 2)], deps=["snr_conductor"], xp=90, desc=["Construa monotrilhos futuristicos, trens estreitos de mina ou locomotivas largas com acopladores automaticos e chaminés fumegantes!"]),
            item_q("snr_carts", "Vagoes Especiais & Tanques", "Bancada movel, Jukebox sobre trilhos e reservatorios", "railways:benchcart", 4.0, 0.0, extra_items=[("railways:jukeboxcart", 1), ("railways:fuel_tank", 1)], deps=["snr_tracks"], xp=60, desc=["Combine vagoes utilitarios para viagens longas."]),
            item_q("crn_nav", "Create Railways Navigator", "GPS ferroviario e paineis de horarios em tempo real", "createrailwaysnavigator:navigator", 2.0, 2.0, extra_items=[("createrailwaysnavigator:advanced_display_panel", 2), ("createrailwaysnavigator:train_station_clock", 1)], deps=["snr_conductor"], xp=80, mod_refs=["createrailwaysnavigator"], desc=["Consulte em qual plataforma e daqui quantos minutos seu trem chegara!"]),
            item_q("baw_deco", "Bells & Whistles", "Farois, degraus e frentes estilizadas de locomotivas", "bellsandwhistles:headlight", 4.0, 2.0, extra_items=[("bellsandwhistles:andesite_pilot", 1), ("bellsandwhistles:brass_door_step", 2), ("bellsandwhistles:metro_casing", 2)], deps=["snr_tracks"], xp=60, mod_refs=["bellsandwhistles"], desc=["De acabamento profissional aos seus trens e metros."]),
            item_q("sim_coasters", "Create: Coasters Simulated", "Montanhas-russas reais no Sable!", "simulatedcoasters:coaster_track", 6.0, 1.0, count=8, extra_items=[("simulatedcoasters:coaster_cart", 1), ("simulatedcoasters:coaster_anchorpoint", 2)], deps=["snr_conductor"], xp=90, mod_refs=["simulatedcoasters"], desc=["Construa loops, quedas livres e trilhos curvos de montanha-russa com fisica real!"]),
            item_q("cr_power_loader", "Carregadores de Chunks Mecanicos", "Create Power Loader — Fabrica funcionando 24h", "create_power_loader:andesite_chunk_loader", 0.0, 2.5, extra_items=[("create_power_loader:brass_chunk_loader", 1)], xp=90, mod_refs=["create_power_loader"], desc=["Alimente com rotacao constante (SU) para manter chunks carregados para suas fazendas e fabricas!"]),
            item_q("cr_gauges_all", "Medidores Extras & Simulados", "Extra Gauges + Simulated Gauges", "extra_gauges:logic_gauge", 2.0, 4.0, extra_items=[("extra_gauges:counter_gauge", 1), ("extra_gauges:filter_gauge", 1), ("extra_gauges:expression_gauge", 1), ("simulated_gauges:altitude_gauge", 1), ("simulated_gauges:velocity_gauge", 1), ("simulated_gauges:gimbal_gauge", 1)], xp=80, mod_refs=["extra_gauges", "simulated_gauges"], desc=["Leve a logica industrial do Create 6.0 ao extremo com portas logicas, contadores, expressoes e telemetria de altitude/velocidade!"]),
            item_q("cr_circuits", "Placas de Circuito & Microchips", "Create Circuit Board + Power Chip", "createcircuitboard:circuit_board", 4.0, 4.0, extra_items=[("createcircuitboard:circuit_monitor", 1), ("powerchip:chip_casing", 1), ("powerchip:chip", 1)], xp=80, mod_refs=["createcircuitboard", "powerchip"], desc=["Compacte circuitos inteiros de portas logicas em um unico bloco."]),
            item_q("cr_ca_addon", "Components & Additions + Escalated", "Gearboxes de Latao e Escadas Rolantes", "create_ca:brass_gearbox", 6.0, 3.0, extra_items=[("create_ca:brass_chain_drive", 2), ("create_ca:inverted_clutch", 1), ("escalated:metal_walkway_steps", 2), ("escalated:wooden_walkway_steps", 2)], xp=70, mod_refs=["create_ca", "escalated"], desc=["Novas transmissoes de latao criadas especialmente para o Create+ mais escadas e esteiras rolantes para jogadores."]),
            item_q("cr_tools_addon", "Chaves Estendidas, Filtros, Schematics & Deployer", "Extended Wrenches, Shuffle Filter, Pattern Schematics", "extendedwrenches:extended_wrench", 6.0, 4.5, extra_items=[("extendedwrenches:wrench_head_augment", 1), ("createshufflefilter:shuffle_filter", 1), ("create_pattern_schematics:pattern_schematic", 1), ("deployer:rare_liukrast_package", 1)], deps=["cr_ca_addon"], xp=65, mod_refs=["extendedwrenches", "createshufflefilter", "create_pattern_schematics", "deployer"], desc=["Personalize sua Wrench com modulos visuais e automatize padroes aleatorios com Shuffle Filter e Pattern Schematics."]),
            item_q("cr_pocket", "Create: Pocket Sized", "Engenharia em miniatura e compressores", "pocket:compression_gun", 8.0, 3.0, extra_items=[("pocket:glue_gun", 1), ("pocket:magnifying_glass", 1), ("pocket:helm_bearing", 1)], xp=80, mod_refs=["pocket"], desc=["Addon experimental: comprima, inspecione com a lupa e monte miniaturas mecanicas com a &bCompression Gun&r!"]),
        ],
    )

    ch05 = ChapterSpec(
        filename="05_create_energia_e_industria",
        group_key="grp_create",
        title="Indústria Pesada: Diesel, Eletricidade, Aço & XP",
        subtitle="Petroleo, motores a diesel, geradores eletricos do Power Grid, ligas de Aco e industria de encantamentos.",
        icon="powergrid:electric_motor",
        quests=[
            item_q("cdg_asphalt", "Asfalto & Tampa de Bacia Industrial", "Create Diesel Generators — Primeiros passos", "createdieselgenerators:asphalt_block", 0.0, 0.0, count=4, extra_items=[("createdieselgenerators:basin_lid", 1), ("createdieselgenerators:oil_barrel", 2), ("createdieselgenerators:canister", 1)], shape="hexagon", size=1.4, xp=50, mod_refs=["createdieselgenerators"], desc=["Construa tambores para estocar combustiveis e a Basin Lid para fermentar biomassa e produzir etanol!"]),
            item_q("cdg_pumpjack", "Extracao de Petroleo Bruto (Pumpjack)", "Perfure o subsolo por jazidas de petroleo", "createdieselgenerators:pumpjack_head", 2.0, 0.0, extra_items=[("createdieselgenerators:pumpjack_crank", 1), ("createdieselgenerators:pumpjack_bearing", 1)], deps=["cdg_asphalt"], xp=100, desc=["Use o Detector de Petroleo ou localize chunks ricos em petroleo bruto e monte sua torre Pumpjack ligada a canos!"]),
            item_q("cdg_distillation", "Torre de Destilacao", "Refine Petroleo em Gasolina e Diesel", "createdieselgenerators:distillation_controller", 4.0, 0.0, deps=["cdg_pumpjack"], xp=110, desc=["Aplique sobre Fluid Tanks aquecidos por Blaze Burners para separar Petroleo em Fracoes Leves (Gasolina) e Pesadas (Diesel)."]),
            item_q("cdg_engines", "Motores a Diesel (Medio, Grande e Gigante)", "Potencia brutal por combustivel", "createdieselgenerators:diesel_engine", 6.0, 0.0, extra_items=[("createdieselgenerators:large_diesel_engine", 1), ("createdieselgenerators:huge_diesel_engine", 1), ("createdieselgenerators:chemical_sprayer", 1)], deps=["cdg_distillation"], shape="octagon", size=1.3, xp=150, desc=["Motores a combustao entregam toneladas de SU em espaco compacto! Experimente tambem o &bChemical Sprayer&r."]),
            item_q("pg_basics", "Eletricidade: Power Grid", "Alicate, Fios de Cobre e Bobinas", "powergrid:wire_cutter", 0.0, 3.0, extra_items=[("powergrid:insulated_copper_wire", 4), ("powergrid:copper_coil", 2), ("powergrid:cord_junction", 2)], shape="hexagon", size=1.4, xp=60, mod_refs=["powergrid"], desc=["Power Grid traz eletricidade realista (Volts, Amperes e Ohms) para dentro do Create! Cuidado com curtos e sobrecarga de fios."]),
            item_q("pg_generators", "Geradores Eletricos & Motores", "Converta rotacao (SU) em energia eletrica e vice-versa", "powergrid:generator_housing", 2.0, 3.0, extra_items=[("powergrid:generator_induction_rotor", 1), ("powergrid:generator_commutator", 1), ("powergrid:electric_motor", 1)], deps=["pg_basics"], xp=110, desc=["Acione um Gerador multi-bloco com moinho/vapor/diesel e leve energia por cabos a distancia ate Motores Eletricos!"]),
            item_q("pg_instruments", "Baterias, Solar, Painel & Multimetro", "Controle sua rede eletrica com seguranca", "powergrid:multimeter", 4.0, 3.0, extra_items=[("powergrid:battery", 1), ("powergrid:solar_panel", 1), ("powergrid:transformer_core", 1), ("powergrid:hv_switch", 1), ("powergrid:voltage_gauge", 1), ("powergrid:light_fixture", 1)], deps=["pg_generators"], xp=100, desc=["Meça tensao e corrente em qualquer fio usando o &bMultimeter&r, armazene em Baterias e use Transformadores para linhas de alta tensao!"]),
            item_q("pg_tools", "Ferramentas Eletricas & Placas PCB", "Furadeira, Serra Portatil e Circuit Design Table", "powergrid:portable_drill", 6.0, 3.0, extra_items=[("powergrid:portable_saw", 1), ("powergrid:circuit_design_table", 1)], deps=["pg_instruments"], xp=90, desc=["Desenhe circuitos integrados personalizados na &bCircuit Design Table&r e use ferramentas portateis recarregaveis."]),
            item_q("alloyed_metals", "Metalurgia Alloyed: Bronze & Aco", "Ligas resistentes, Sino de Bronze e Casings de Aco", "alloyed:steel_ingot", 0.0, 6.0, count=4, extra_items=[("alloyed:bronze_ingot", 4), ("alloyed:steel_sheet", 2), ("alloyed:steel_casing", 2), ("alloyed:steel_pickaxe", 1), ("alloyed:bronze_bell", 1)], xp=80, mod_refs=["alloyed"], desc=["Misture cobre + zinco para &bBronze&r e ferro + carvao sob calor intenso para &bAco (Steel)&r — obrigatorio tambem para canhoes e aeronaves!"]),
            item_q("cei_xp", "Industria de Encantamentos", "Rebolo Mecanico, Blaze Enchanter, Impressora e Bolo de XP", "create_enchantment_industry:blaze_enchanter", 3.0, 6.0, extra_items=[("create_enchantment_industry:mechanical_grindstone", 1), ("create_enchantment_industry:grindstone_drain", 1), ("create_enchantment_industry:blaze_forger", 1), ("create_enchantment_industry:printer", 1), ("create_enchantment_industry:experience_hatch", 1), ("create_enchantment_industry:super_experience_block", 1), ("create_enchantment_industry:experience_cake", 1)], xp=140, mod_refs=["create_enchantment_industry"], desc=["Transforme itens encantados em XP Liquido, imprima copias exatas de livros raros com a &bPrinter&r e ultrapasse niveis vanilla no &bBlaze Enchanter&r!"]),
            item_q("cdp_dragons", "Create: Dragons Plus & Factory Food", "Automacao de Bafo do Dragao e confeitaria industrial", "create_dragons_plus:dragon_breath_bucket", 6.0, 6.0, extra_items=[("create_dragons_plus:fluid_hatch", 1), ("create_dragons_plus:fragile_fluid_tank", 1), ("create_dragons_plus:blaze_upgrade_smithing_template", 1), ("create_factory:cake_paste", 1), ("create_factory:chocolate_waffle", 2), ("create_factory:caramel_apple", 1)], xp=100, mod_refs=["create_dragons_plus", "create_factory"], desc=["Engarrafe Bafo do Dragao automaticamente e monte linhas industriais de doces, geleias e waffles do &bCreate: Factory&r!"]),
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
            item_q("aero_start", "Create Aeronautics: Primeiros Voos", "Oculos de Aviador, Queimador, Helice e Baloes", "aeronautics:aviators_goggles", 0.0, 0.0, extra_items=[("aeronautics:adjustable_burner", 1), ("aeronautics:andesite_propeller", 1), ("aeronautics:white_envelope", 4)], shape="hexagon", size=1.5, xp=80, mod_refs=["aeronautics", "aeronautics_bundled"], desc=["Construa dirigiveis de ar quente reais! Encha envelopes com calor do &bAdjustable Burner&r e acelere com helices giratorias."]),
            item_q("aero_levitite", "Levitite & Rolamento Giroscopico", "Antigravidade e estabilidade em voo", "aeronautics:levitite", 2.0, 0.0, extra_items=[("aeronautics:gyroscopic_propeller_bearing", 1), ("aeroencasedpipe:white_envelope_encased_pipe", 2), ("ballastmod:ballast_stones", 2), ("aeroclaims:claim_block", 1)], deps=["aero_start"], xp=110, mod_refs=["aeronautics", "aeroencasedpipe", "ballastmod", "aeroclaims"], desc=["Use &bLevitite&r para naves pesadas, &bBallast Stones&r para equilibrar o centro de massa e o &bClaim Block&r para proteger seu veiculo contra roubo."]),
            item_q("aero_works", "Create: Aeroworks — Cabines & Controles", "Mesas de comando, Joysticks, Giroscopios e Servos", "aeroworks:control_desk", 4.0, 0.0, extra_items=[("aeroworks:joystick_module", 1), ("aeroworks:gyroscope", 1), ("aeroworks:mechanical_servo", 1)], deps=["aero_start"], xp=90, mod_refs=["aeroworks"], desc=["Monte uma cabine completa com volante, acelerador, pedais e modulos de painel de instrumento."]),
            item_q("aero_flight", "Computador de Voo & Piloto Automatico", "Create: Flight Control", "create_flight_control:flight_control_computer", 6.0, 0.0, extra_items=[("create_flight_control:altitude_hold", 1), ("create_flight_control:attitude_hold", 1), ("create_flight_control:smart_vector_thruster", 1), ("create_flight_control:arrester_hook", 1)], deps=["aero_works"], shape="octagon", size=1.3, xp=130, mod_refs=["create_flight_control"], desc=["Estabilize automaticamente a inclinacao (&bAttitude Hold&r) e altura (&bAltitude Hold&r) ou pouse em porta-avioes com o &bArrester Hook&r!"]),
            item_q("aero_toolgun", "Toolgun Magnetica & Transmissao Hidraulica", "Manipule naves inteiras e conecte bracos articulados", "create_aeronautics_toolgun:magnetic_gun", 2.0, 2.0, extra_items=[("aeronautics_utility_objects:universal_joint", 2), ("aeronautics_utility_objects:hydraulic_rod", 1), ("aeronautics_utility_objects:damping_stress_bearing", 1)], deps=["aero_start"], xp=90, mod_refs=["create_aeronautics_toolgun", "aeronautics_utility_objects"], desc=["Com a &bMagnetic Gun&r voce move, alinha e empacota contraptions fisicas com facilidade."]),
            item_q("prop_thrusters", "Create Propulsion: Simulated", "Propulsores de Ion, Vetor Liquido, Platina & Lentes", "createpropulsion:ion_thruster", 0.0, 3.0, extra_items=[("createpropulsion:liquid_vector_thruster", 1), ("createpropulsion:coral_generator", 1), ("createpropulsion:assembler_stick", 1), ("createpropulsion:platinum_ingot", 2), ("createpropulsion:optical_lens", 1)], xp=120, mod_refs=["createpropulsion"], desc=["Explore minerio de Platina, produza combustivel de plasma e construa naves ultrarrapidas com propulsores direcionaveis."]),
            item_q("gg_gadgets", "Create Gadgets & Gizmos", "Controladores Avancados, Ailerons, Alternador & Tablet", "createthrusters:advanced_contraption_controller", 2.0, 4.0, extra_items=[("createthrusters:advanced_navigation_table", 1), ("createthrusters:aileron_bearing", 2), ("createthrusters:alternator", 1), ("createthrusters:diagnostic_tablet", 1), ("createthrusters:blackstone_alloy", 4)], deps=["prop_thrusters"], xp=110, mod_refs=["createthrusters", "createthrusters_bundled", "gadgetsngizmos"], desc=["Use o &bDiagnostic Tablet&r para inspecionar forças aerodinamicas, arrasto e energia em qualquer estrutura fisica!"]),
            item_q("sim_physics", "Create Simulated: Montagem Fisica", "Physics Assembler, Sensores de Altitude e Diagramas", "simulated:physics_assembler", 4.0, 4.0, extra_items=[("simulated:altitude_sensor", 1), ("simulated:analog_transmission", 1), ("simulated:contraption_diagram", 1), ("simulated:docking_connector", 2)], deps=["gg_gadgets"], xp=100, mod_refs=["simulated"], desc=["Transforme qualquer construcao conectada em um veiculo fisico interativo e acople naves com &bDocking Connectors&r."]),
            item_q("offroad_cars", "Create Offroad: Carros, Caminhoes & Escavadeiras", "Pneus pequenos/monstruosos, Eixos e Broca Gigante", "offroad:tire", 6.0, 4.0, count=4, extra_items=[("offroad:large_tire", 2), ("offroad:monstrous_tire", 1), ("offroad:wheel_mount", 4), ("offroad:borehead_bearing", 1), ("offroad:rockcutting_wheel", 1)], deps=["sim_physics"], xp=120, mod_refs=["offroad"], desc=["Construa jipes off-road, caminhoras 6x6 ou tuneleiras gigantes que mastigam montanhas inteiras com a &bRockcutting Wheel&r!"]),
            item_q("liw_mechs", "Let It Walk! — Robos & Mechas Caminhantes", "Cinematica inversa real para pernas mecanicas", "let_it_walk:articulation_unit", 4.0, 6.0, count=2, extra_items=[("let_it_walk:leg_hinge", 2), ("let_it_walk:leg_foot", 2), ("let_it_walk:jet_nozzle", 1), ("let_it_walk:pilot_controls", 1)], deps=["sim_physics"], shape="octagon", size=1.3, xp=150, mod_refs=["let_it_walk"], desc=["Construa aranhas mecanicas e mechas gigantes que realmente dao passos adaptados ao relevo de Terralith/Tectonic!"]),
            check_q("sable_aileron", "Motor Sable & Voo de Aileron (Elytra)", "Entendendo a base fisica e o voo planado", "minecraft:elytra", 0.0, 6.0, deps=["aero_start"], xp=40, mod_refs=["sable", "sable_beyond", "sablecompanion", "sablephysicscompat", "carryonaerocompat", "veil", "pehkui", "aileron"], desc=["&bSable&r cria sub-niveis fisicos onde maquinas se movem suavemente sem travar blocos.", "", "&bAileron&r deixa a Elytra muito mais responsiva e permite ganhar altitude ao planar sobre fogueiras acesas!"]),
        ],
    )

    ch07 = ChapterSpec(
        filename="07_artilharia_e_radares",
        group_key="grp_aero",
        title="Artilharia Pesada & Radares",
        subtitle="Create Big Cannons & Create: Radars — Fundicao de canhoes, municoes balisticas e torres guiadas por radar.",
        icon="createbigcannons:cannon_mount",
        quests=[
            item_q("cbc_foundry", "Fundicao de Artilharia (Basin Foundry)", "Ferro Fundido & Aco do Nether", "createbigcannons:basin_foundry_lid", 0.0, 0.0, extra_items=[("createbigcannons:cast_iron_ingot", 4), ("createbigcannons:nethersteel_ingot", 4)], shape="hexagon", size=1.4, xp=60, mod_refs=["createbigcannons", "ritchiesprojectilelib"], desc=["Aqueça ferro e ligas com a &bBasin Foundry Lid&r para fundir blocos de metal liquido em moldes de cano e culatra."]),
            item_q("cbc_drill", "Construtor & Furadeira de Canhoes", "Perfure e usine canos sob pressao", "createbigcannons:cannon_builder", 2.0, 0.0, extra_items=[("createbigcannons:cannon_drill", 1)], deps=["cbc_foundry"], xp=80, desc=["Canos fundidos saem solidos — use a &bCannon Drill&r com agua para furar o calibre interno!"]),
            item_q("cbc_mount", "Base de Canhao & Controlador de Rotacao", "Mirando em alvos a centenas de blocos", "createbigcannons:cannon_mount", 4.0, 0.0, extra_items=[("createbigcannons:yaw_controller", 1), ("createbigcannons:cast_iron_cannon_barrel", 1)], deps=["cbc_drill"], shape="diamond", size=1.3, xp=110, desc=["Acople rotacao no Cannon Mount (inclinacao) e no Yaw Controller abaixo dele (giro 360 graus)."]),
            item_q("cbc_ammo", "Projeteis Balisticos & Autocanhoes", "AP, HE, Cargas de Polvora e Autocanhao", "createbigcannons:he_shell", 6.0, 0.0, extra_items=[("createbigcannons:ap_shell", 1), ("createbigcannons:powder_charge", 4), ("createbigcannons:steel_autocannon_barrel", 1)], deps=["cbc_mount"], shape="octagon", size=1.4, xp=140, desc=["&bAP Shell&r perfura armaduras e paredes grossas; &bHE Shell&r explode em area! Carregue com Powder Charges e dispare com sinal de Redstone."]),
            item_q("rad_dish", "Vigilancia a Longa Distancia (Create: Radars)", "Antena de Radar, Rolamento e Receptor", "create_radar:radar_bearing", 2.0, 2.5, extra_items=[("create_radar:radar_dish_block", 4), ("create_radar:radar_receiver_block", 1)], xp=90, mod_refs=["create_radar"], desc=["Gire a antena com energia cinetica sobre um &bRadar Bearing&r para detectar aeronaves, trens, jogadores e entidades a centenas de blocos!"]),
            item_q("rad_monitor", "Monitor Tatico & Link de Dados", "Visualize todos os alvos num mapa ao vivo", "create_radar:monitor", 4.0, 2.5, extra_items=[("create_radar:data_link", 1), ("create_radar:binoculars", 1)], deps=["rad_dish"], xp=100, desc=["Ligue o radar ao Monitor usando Data Links para acompanhar vetores de velocidade e distancias."]),
            item_q("rad_autofire", "Sistema de Mira Automatica para Canhoes", "Controladores Auto-Pitch, Auto-Yaw & Fire Controller", "create_radar:fire_controller", 6.0, 2.5, extra_items=[("create_radar:auto_pitch_controller", 1), ("create_radar:auto_yaw_controller", 1)], deps=["rad_monitor", "cbc_mount"], shape="octagon", size=1.4, xp=160, desc=["Conecte seu radar ao canhao usando &bAuto-Pitch&r, &bAuto-Yaw&r e &bFire Controller&r para calcular balistica e travar mira automaticamente!"]),
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
            item_q("fd_start", "Cozinha do Fazendeiro", "Fogao, Panela de Cozimento, Frigideira e Tabua", "farmersdelight:cooking_pot", 0.0, 0.0, extra_items=[("farmersdelight:stove", 1), ("farmersdelight:skillet", 1), ("farmersdelight:cutting_board", 1), ("farmersdelight:iron_knife", 1)], shape="hexagon", size=1.5, xp=50, mod_refs=["farmersdelight", "eatinganimation", "appleskin", "smarterfarmers"], desc=["Coloque a &bCooking Pot&r sobre o &bStove&r aceso para cozinhar sopas, ensopados e banquetes nutritivos!", "Use facas em colheitas para conseguir palha (&bStraw&r), cordas e &bCanvas&r."]),
            item_q("fd_farming", "Agricultura Riquissima", "Composto Organico, Solo Rico, Corda & Cesto", "farmersdelight:rich_soil", 2.0, 0.0, count=4, extra_items=[("farmersdelight:organic_compost", 2), ("farmersdelight:bamboo_basket", 1), ("farmersdelight:rope", 4), ("farmersdelight:canvas", 2)], deps=["fd_start"], xp=50, desc=["Deixe &bOrganic Compost&r curtir perto de agua e cogumelos ate virar &bRich Soil&r: plantacoes nele crescem mais rapido e nunca viram terra ao pular!"]),
            item_q("fd_feast", "Banquete Completo!", "Frango Assado Servivel na Mesa", "farmersdelight:roast_chicken_block", 4.0, 0.0, deps=["fd_farming"], shape="octagon", size=1.2, xp=80, reward_item=("minecraft:golden_carrot", 4), desc=["Coloque o banquete sobre a mesa e convide os amigos do servidor para pegar porcoes com tigelas!"]),
            item_q("fd_slice", "Create Slice & Dice — Culinaria Automatizada", "Fatiador Mecanico & Aspersores Irrigadores", "sliceanddice:slicer", 2.0, 2.0, extra_items=[("sliceanddice:sprinkler", 1), ("sliceanddice:fertilizer_bucket", 1)], deps=["fd_start"], xp=85, mod_refs=["sliceanddice"], desc=["O &bSlicer&r automatiza qualquer receita da Cutting Board em linha de producao, e o &bSprinkler&r acelera plantacoes usando farinha de osso fluida!"]),
            item_q("mnd_nether", "My Nether's Delight — Chamas na Cozinha", "Fogao de Tijolo do Nether, Panela de Pedra Negra & Pimenta", "mynethersdelight:nether_bricks_stove", 0.0, 3.0, extra_items=[("mynethersdelight:blackstone_cooking_pot", 1), ("mynethersdelight:bullet_pepper", 2), ("mynethersdelight:strider_slice", 1), ("mynethersdelight:breakfast_sampler", 1)], deps=["fd_start"], xp=80, mod_refs=["mynethersdelight"], desc=["Sobreviva no Nether cozinhando bifes de Hoglin, fatias de Strider e pimentas explosivas!"]),
            item_q("md_caves", "Miner's Delight — Sabor Subterraneo", "Panela de Cobre & Canecas Portateis para Mineracao", "minersdelight:copper_pot", 2.0, 4.0, extra_items=[("minersdelight:copper_cup", 2), ("minersdelight:cave_carrot", 4), ("minersdelight:baked_tentacles", 1)], deps=["fd_start"], xp=70, mod_refs=["minersdelight"], desc=["A &bCopper Pot&r cozinha rapidamente nas cavernas, e a &bCopper Cup&r permite carregar sopas empilhaveis na mochila!"]),
            item_q("ed_end", "End's Delight — Iguarias do Fim", "Fogao do End, Faca de Casca de Ovo & Cha de Chorus", "ends_delight:end_stove", 4.0, 3.0, extra_items=[("ends_delight:dragon_egg_shell_knife", 1), ("ends_delight:chorus_fruit_pie", 1), ("ends_delight:dragon_breath_soda", 1)], deps=["fd_start"], xp=110, mod_refs=["ends_delight"], desc=["Transforme frutas de Chorus, cascas de Shulker e bafo do dragao em sobremesas e bebidas com buff!"]),
        ],
    )

    ch09 = ChapterSpec(
        filename="09_arquitetura_decoracao_e_economia",
        group_key="grp_mundo",
        title="Arquitetura, Decoração & Economia",
        subtitle="Copycats+, Quark, Supplementaries, Moveis, Iluminacao, Bricks e o sistema monetario Numismatics.",
        icon="supplementaries:globe",
        quests=[
            item_q("dec_copycats", "Create: Copycats+ & Vidros Emoldurados", "Qualquer textura em qualquer formato + Create Framed", "copycats:copycat_block", 0.0, 0.0, count=4, extra_items=[("copycats:copycat_slab", 2), ("copycats:copycat_beam", 2), ("copycats:copycat_catwalk", 2), ("createframed:andesite_alloy_window", 4)], shape="hexagon", size=1.4, xp=60, mod_refs=["copycats", "createframed"], desc=["Clique com qualquer bloco da mao em um bloco &bCopycat&r para aplicar a textura mantendo formatos especiais como vigas, paineis e passarelas!"]),
            item_q("dec_bits_prism", "Bits 'n' Bobs, Prismatic Shine & Lanterns", "Paineis iluminados, azulejos, baterias e vitrines", "bits_n_bobs:andesite_tiles", 2.0, 0.0, count=4, extra_items=[("bits_n_bobs:black_chair", 1), ("bits_n_bobs:brass_lamp", 1), ("createprism:andesite_clear_glass_casing", 2), ("createprism:andesite_illumination_casing", 2), ("createlanterns:gold_display_case", 1)], deps=["dec_copycats"], xp=65, mod_refs=["bits_n_bobs", "createprism", "createlanterns"], desc=["Estilize bases industriais com moveis rotativos, lampadas de latao e casings de vidro cristalino."]),
            item_q("dec_bricks_furn", "Brick & Mortar + Another Furniture", "Alvenaria variada e mobilia completa", "brick_and_mortar:algal_tiles", 4.0, 0.0, count=4, extra_items=[("brick_and_mortar:black_ash_bricks", 4), ("another_furniture:oak_chair", 1), ("another_furniture:oak_table", 1), ("another_furniture:oak_drawer", 1), ("another_furniture:oak_shelf", 1)], xp=55, mod_refs=["brick_and_mortar", "another_furniture"], desc=["Dezenas de novas texturas de tijolos mais cadeiras funcionais (da pra sentar!), gavetas, prateleiras e cortinas."]),
            item_q("dec_supp", "Supplementaries & Amendments", "Globo, Ampulheta, Cofre, Jarra, Saco & Alto-Falante", "supplementaries:globe", 0.0, 2.5, extra_items=[("supplementaries:hourglass", 1), ("supplementaries:sack", 1), ("supplementaries:safe", 1), ("supplementaries:jar", 1), ("supplementaries:faucet", 1), ("supplementaries:turn_table", 1), ("supplementaries:spring_launcher", 1), ("supplementaries:speaker_block", 1), ("supplementaries:blackboard", 1)], xp=80, mod_refs=["supplementaries", "amendments", "moonlight", "fastpaintings"], desc=["Uma caixa inteira de brinquedos funcionais vanilla+: grave mensagens no &bSpeaker Block&r, proteja itens no &bSafe&r trancado com chave e desenhe placas no &bBlackboard&r!"]),
            item_q("dec_quark", "Quark — Melhorias & Aventuras Vanilla+", "Trowel de construcao, Pickarang, Abaco e Nuvem Engarrafada", "quark:pickarang", 2.0, 2.5, extra_items=[("quark:trowel", 1), ("quark:abacus", 1), ("quark:bottled_cloud", 1), ("quark:backpack", 1), ("quark:seed_pouch", 1)], xp=85, mod_refs=["quark", "zeta", "connectiblechains", "scholar"], desc=["A &bTrowel&r coloca blocos aleatorios da hotbar (otima para texturizar construcoes!); o &bPickarang&r minera a distancia igual um bumerangue!"]),
            item_q("eco_numismatics", "Create: Numismatics — Economia & Lojas", "Guia Bancario, Moedas, Terminal, Blaze Banker & Vendedor", "numismatics:banking_guide", 4.0, 3.0, extra_items=[("numismatics:spur", 4), ("numismatics:bevel", 2), ("numismatics:cog", 1), ("numismatics:bank_terminal", 1), ("numismatics:vendor", 1), ("numismatics:blaze_banker", 1), ("numismatics:andesite_depositor", 1)], shape="octagon", size=1.4, xp=100, mod_refs=["numismatics"], desc=["Monte um mercado ou sistema bancario oficial entre os jogadores do servidor!", "- Use cartoes autorizados no &bBank Terminal&r para sacar/depositar.", "- Configure lojas automaticas com &bVendors&r ou cobre pedágios/entradas com o &bDepositor&r!"]),
            item_q("misc_az_horse", "Utilitarios Extras: Azimuth & Armadura de Cavalo", "Ferramenta Ponder e Armadura de Cavalo em Netherite", "netherite_horse_armor:netherite_horse_armor", 2.0, 4.5, extra_items=[("azimuth:ponder_coordinate", 1)], xp=80, mod_refs=["netherite_horse_armor", "azimuth"], desc=["Proteja seu cavalo com armadura de Netherite na Mesa de Ferraria."]),
        ],
    )

    ch10 = ChapterSpec(
        filename="10_exploracao_biomas_e_estruturas",
        group_key="grp_mundo",
        title="Exploração, Dimensões & Estruturas",
        subtitle="Terralith, Tectonic, Ecologics, Friends&Foes e todas as 10 super-estruturas do YUNG!",
        icon="minecraft:ender_eye",
        quests=[
            check_q("exp_terralith_tectonic", "Um Novo Mundo: Terralith + Tectonic", "Quase 100 biomas e relevo monumental sem lag", "minecraft:oak_sapling", 0.0, 0.0, shape="hexagon", size=1.5, xp=40, mod_refs=["terralith", "tectonic", "biolith", "lithostitched"], desc=["O Overworld foi completamente transformado por &bTerralith&r + &bTectonic&r: cordilheiras altissimas, canions, rios profundos e dezenas de biomas novos feitos apenas com blocos vanilla!"]),
            item_q("exp_ecologics", "Fauna & Flora: Ecologics", "Coqueiros, Nogueiras, Pinguins, Esquilinhos & Conchas", "ecologics:coconut", 2.0, 0.0, count=2, extra_items=[("ecologics:walnut", 2), ("ecologics:penguin_feather", 1), ("ecologics:seashell", 2), ("ecologics:sandcastle", 1)], deps=["exp_terralith_tectonic"], xp=70, mod_refs=["ecologics"], desc=["Explore praias para achar cocos e conchas, taigas congeladas para pinguins (que dão efeito de nado) e bosques de nogueira!"]),
            item_q("exp_friendsandfoes", "Amigos & Inimigos: Friends&Foes", "Totens do Iceologer/Ilusionista, Copper Golem, Crab & Mooblooms", "friendsandfoes:buttercup", 4.0, 0.0, extra_items=[("friendsandfoes:crab_claw", 1), ("friendsandfoes:totem_of_freezing", 1), ("friendsandfoes:totem_of_illusion", 1)], deps=["exp_terralith_tectonic"], xp=100, mod_refs=["friendsandfoes", "flowerymooblooms", "beekeeperhut"], desc=["Todos os mobs das votacoes da comunidade estao vivos aqui!", "- Construa &bCopper Golems&r para apertar botoes de cobre aleatoriamente.", "- Explore a &bCabana do Apicultor&r e encontre dezenas de &bFlowery Mooblooms&r."]),
            dim_q("exp_dim_nether", "Cruzando para o Nether", "Fogo, Bazaltos, Fortalezas & Blazes", "minecraft:the_nether", "minecraft:netherrack", 0.0, 2.5, deps=["exp_terralith_tectonic"], xp=80, desc=["Acenda o portal de Obsidiana. Voce precisara de &bBlazes&r (para Blaze Burners!) e &bQuartzo&r para evoluir na Era do Latao no Create."]),
            dim_q("exp_dim_end", "Despertando o The End", "Derrote o Dragao na ilha refeita pelo YUNG!", "minecraft:the_end", "minecraft:end_stone", 0.0, 4.5, deps=["exp_dim_nether"], shape="octagon", size=1.6, xp=200, reward_item=("minecraft:shulker_box", 1), mod_refs=["betterendisland"], desc=["Com o &bBetter End Island&r do YUNG, a batalha contra o Ender Dragon e uma experiencia cinematografica totalmente redesenhada!"]),
            check_q("yung_overworld", "Estruturas YUNG: Superficie & Subsolo", "Dungeons, Mineshafts, Witch Huts & Extras", "minecraft:rail", 2.5, 2.5, deps=["exp_terralith_tectonic"], xp=60, mod_refs=["betterdungeons", "bettermineshafts", "betterwitchhuts", "yungsapi", "yungsextras"], desc=["Marque ao explorar qualquer versao redesenhada pelo YUNG de &bDungeons&r, &bMineshafts&r ou &bWitch Huts&r."]),
            check_q("yung_temples", "Estruturas YUNG: Templos & Oceanos", "Templos do Deserto, Selva & Monumentos Oceanicos", "minecraft:sea_lantern", 4.5, 2.5, deps=["yung_overworld"], xp=80, mod_refs=["betterdeserttemples", "betterjungletemples", "betteroceanmonuments"], desc=["Os antigos templos simples agora escondem quebra-cabecas de redstone, armadilhas mortais e salas de tesouro do &bLootr&r!"]),
            check_q("yung_endgame", "Estruturas YUNG: Fortalezas & Strongholds", "Nether Fortresses gigantes e Strongholds epicas", "minecraft:nether_bricks", 2.5, 4.5, deps=["exp_dim_nether"], xp=100, mod_refs=["betterfortresses", "betterstrongholds"], desc=["As Fortalezas do Nether e Strongholds estao monumentais — leve comida farta, mochilas e waypoints!"]),
        ],
    )

    # ------------------------------------------------------------------
    # GRUPO 5: Catalogo Completo de TODOS os mods (11 e 12)
    # ------------------------------------------------------------------
    covered_mods_with_items: set[str] = set()
    for ch in (ch01, ch02, ch03, ch04, ch05, ch06, ch07, ch08, ch09, ch10):
        for q in ch.quests:
            for t in q.tasks:
                if t.kind == "item":
                    covered_mods_with_items.add(t.value.split(":")[0])
            for mr in q.mod_refs:
                if mods_info.get(mr, {}).get("verified_preferred"):
                    covered_mods_with_items.add(mr)

    # Mapeia em qual capitulo cada mod aparece principalmente para informar no catalogo
    mod_to_chapter: dict[str, str] = {}
    for ch in (ch01, ch02, ch03, ch04, ch05, ch06, ch07, ch08, ch09, ch10):
        for q in ch.quests:
            for mr in q.mod_refs:
                mod_to_chapter.setdefault(mr, ch.title)
            for t in q.tasks:
                if t.kind == "item":
                    mod_to_chapter.setdefault(t.value.split(":")[0], ch.title)

    catalog_mod_ids = [
        mid
        for mid in sorted(mods_info.keys())
        if not mid.startswith(IGNORED_SUBMODULE_PREFIXES)
    ]

    mid_point = (len(catalog_mod_ids) + 1) // 2
    part1_ids = catalog_mod_ids[:mid_point]
    part2_ids = catalog_mod_ids[mid_point:]

    def make_catalog_chapter(filename: str, title: str, subtitle: str, icon: str, mod_ids: list[str]) -> ChapterSpec:
        quests: list[QuestSpec] = []
        cols = 10
        spacing = 1.6
        for idx, mid in enumerate(mod_ids):
            info = mods_info[mid]
            dname = info["displayName"]
            icon_id = pick_best_icon_for_mod(mid, mods_info, all_items)
            col = idx % cols
            row = idx // cols
            x = col * spacing
            y = row * spacing

            curated = CURATED_MOD_DESCRIPTIONS.get(mid)
            if curated:
                cat, _, pt_desc = curated
            else:
                cat = "Mod / Componente do Pack"
                raw_desc = info.get("description") or "Componente integrado ao modpack Create+."
                pt_desc = raw_desc

            lines = [
                f"&bMod ID:&r &e{mid}&r  |  &bCategoria:&r {cat}",
                f"&bArquivo(s):&r {', '.join(info['jars'][:2])}",
                "",
                pt_desc,
            ]
            if mid in mod_to_chapter:
                lines.extend(["", f"&aGuia / Missoes detalhadas:&r Veja o capitulo &e{mod_to_chapter[mid]}&r."])

            # Se o mod tem itens registrados e NAO possui missao de item nos capitulos 1-10, pede 1 item dele!
            # Caso contrario (ou mods tecnicos/performance/libs/ja cobertos), usa checkmark interativo com o icone do mod.
            has_items = len(info.get("verified_preferred", [])) > 0
            if has_items and mid not in covered_mods_with_items and icon_id.startswith(f"{mid}:"):
                quests.append(
                    item_q(
                        f"cat_{mid}",
                        dname,
                        f"[{cat}] ID: {mid}",
                        icon_id,
                        x,
                        y,
                        desc=lines,
                        xp=15,
                        shape="rsquare",
                        mod_refs=[mid],
                    )
                )
            else:
                quests.append(
                    check_q(
                        f"cat_{mid}",
                        dname,
                        f"[{cat}] ID: {mid}",
                        icon_id,
                        x,
                        y,
                        desc=lines,
                        task_title=f"Conhecer {dname}",
                        xp=5,
                        shape="rsquare",
                        mod_refs=[mid],
                    )
                )
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
        "Catálogo de Mods (A – L)",
        f"Enciclopédia completa cobrindo os primeiros {len(part1_ids)} mods instalados no Create+.",
        "minecraft:bookshelf",
        part1_ids,
    )
    ch12 = make_catalog_chapter(
        "12_catalogo_mods_parte_2",
        "Catálogo de Mods (M – Z)",
        f"Enciclopédia completa cobrindo os demais {len(part2_ids)} mods instalados no Create+.",
        "minecraft:chiseled_bookshelf",
        part2_ids,
    )

    return [ch01, ch02, ch03, ch04, ch05, ch06, ch07, ch08, ch09, ch10, ch11, ch12]


def validate_and_emit(chapters: list[ChapterSpec], all_items: set[str], catalog_count: int) -> None:
    # 1. Verifica existencia de TODOS os itens referenciados
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

    # Limpa diretorio anterior para evitar arquivos stale
    if QUESTS_DIR.exists():
        import shutil
        shutil.rmtree(QUESTS_DIR)

    QUESTS_DIR.mkdir(parents=True, exist_ok=True)
    (QUESTS_DIR / "chapters").mkdir(parents=True, exist_ok=True)
    (QUESTS_DIR / "lang").mkdir(parents=True, exist_ok=True)

    # Alocadores de IDs hex 16 chars
    grp_alloc = IdAlloc(1)
    ch_alloc = IdAlloc(2)
    q_alloc = IdAlloc(3)
    t_alloc = IdAlloc(4)
    r_alloc = IdAlloc(5)

    groups_meta = [
        ("grp_inicio", "Início & Sobrevivência"),
        ("grp_create", "Engenharia Create"),
        ("grp_aero", "Aeronáutica, Física & Combate"),
        ("grp_mundo", "Gastronomia, Construção & Mundo"),
        ("grp_catalogo", "Catálogo Completo do Modpack"),
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
        QUESTS_DIR / "data.snbt",
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
            '\tfallback_locale: "pt_br"',
            "\tgrid_scale: 0.5d",
            "\thide_excluded_quests: false",
            "\ticon: {",
            '\t\tid: "create:brass_hand"',
            "\t}",
            '\tlock_message: ""',
            "\tpause_game: false",
            '\tprogression_mode: "flexible"',
            "\tshow_lock_icons: true",
            "\tversion: 13",
            "}",
        ],
    )

    # chapter_groups.snbt
    cg_lines = ["{", "\tchapter_groups: ["]
    for gkey, _ in groups_meta:
        cg_lines.append(f'\t\t{{ id: "{group_ids[gkey]}" }}')
    cg_lines.extend(["\t]", "}"])
    write_snbt_file(QUESTS_DIR / "chapter_groups.snbt", cg_lines)

    # Pre-alocar IDs de todas as quests de cada capitulo para resolver dependencies
    total_quests = 0
    for order_idx, ch in enumerate(chapters):
        cid = ch_alloc.next()
        gid = group_ids[ch.group_key]
        lang_entries.append((f"chapter.{cid}.title", ch.title))
        lang_entries.append((f"chapter.{cid}.chapter_subtitle", [ch.subtitle]))

        quest_key_to_id: dict[str, str] = {}
        for q in ch.quests:
            if q.key in quest_key_to_id:
                raise ValueError(f"Quest key duplicada no capitulo {ch.filename}: {q.key}")
            quest_key_to_id[q.key] = q_alloc.next()

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
            qid = quest_key_to_id[q.key]
            lang_entries.append((f"quest.{qid}.title", q.title))
            if q.subtitle:
                lang_entries.append((f"quest.{qid}.quest_subtitle", q.subtitle))
            if q.desc:
                lang_entries.append((f"quest.{qid}.quest_desc", q.desc))

            ch_lines.append("\t\t{")
            if q.deps:
                dep_ids = [snbt_str(quest_key_to_id[d]) for d in q.deps]
                ch_lines.append(f"\t\t\tdependencies: [ {', '.join(dep_ids)} ]")
            if q.icon:
                ch_lines.append("\t\t\ticon: {")
                ch_lines.append(f"\t\t\t\tid: {snbt_str(q.icon)}")
                ch_lines.append("\t\t\t}")
            ch_lines.append(f"\t\t\tid: {snbt_str(qid)}")

            # Rewards
            if q.rewards:
                ch_lines.append("\t\t\trewards: [")
                for rw in q.rewards:
                    rid = r_alloc.next()
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
                tid = t_alloc.next()
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
                ch_lines.append("\t\t\t\t}")
            ch_lines.append("\t\t\t]")

            ch_lines.append(f"\t\t\tx: {snbt_float_d(q.x)}")
            ch_lines.append(f"\t\t\ty: {snbt_float_d(q.y)}")
            ch_lines.append("\t\t}")

        ch_lines.extend(["\t]", "}"])
        write_snbt_file(QUESTS_DIR / "chapters" / f"{ch.filename}.snbt", ch_lines)

    # Lang files (pt_br e en_us com os textos PT-BR para garantir leitura independente do idioma do cliente)
    lang_lines = ["{"]
    for k, val in sorted(lang_entries, key=lambda kv: kv[0]):
        if isinstance(val, list):
            if len(val) == 1:
                lang_lines.append(f"\t{k}: [ {snbt_str(val[0])} ]")
            else:
                lang_lines.append(f"\t{k}: [")
                for line in val:
                    lang_lines.append(f"\t\t{snbt_str(line)}")
                lang_lines.append("\t]")
        else:
            lang_lines.append(f"\t{k}: {snbt_str(val)}")
    lang_lines.append("}")

    write_snbt_file(QUESTS_DIR / "lang" / "pt_br.snbt", lang_lines)
    write_snbt_file(QUESTS_DIR / "lang" / "en_us.snbt", lang_lines)

    print(
        f"Livro de Quests gerado com sucesso: "
        f"{len(groups_meta)} grupos, {len(chapters)} capitulos, {total_quests} quests, "
        f"{catalog_count} mods documentados no catalogo!"
    )


def main() -> None:
    mods_info, all_items = scan_installed_mods()
    chapters = build_chapters(mods_info, all_items)
    catalog_count = sum(len(ch.quests) for ch in chapters[-2:])
    validate_and_emit(chapters, all_items, catalog_count)


if __name__ == "__main__":
    main()
