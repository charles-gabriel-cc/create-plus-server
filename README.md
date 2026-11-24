# ⚙️ Create+ — Modpack & Servidor Oficial

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=for-the-badge&logo=minecraft&logoColor=white" alt="Minecraft 1.21.1" />
  <img src="https://img.shields.io/badge/Loader-NeoForge%2021.1.265-E05A2B?style=for-the-badge" alt="NeoForge 21.1.265" />
  <img src="https://img.shields.io/badge/Mods-259%20Client%20%2B%205%20Server-3B82F6?style=for-the-badge" alt="Mods" />
  <img src="https://img.shields.io/badge/FTB%20Quests-458%20Quests%20%7C%2013%20Cap%C3%ADtulos-F59E0B?style=for-the-badge" alt="FTB Quests" />
  <img src="https://img.shields.io/badge/Deploy-EnxadaHost%20%2B%20AutoModpack-8B5CF6?style=for-the-badge" alt="EnxadaHost + AutoModpack" />
</p>

> Modpack **Create+** para **Minecraft 1.21.1 (NeoForge)** — engenharia pesada com Create, anéis de poder com **Create: Lanterns**, combate dinâmico, chefes épicos, exploração e deploy automático via **Packwiz híbrido + EnxadaHost + AutoModpack**.

---

## 📑 Índice

- [✨ Destaques do Modpack](#-destaques-do-modpack)
- [🏰 Mods Próprios & Server-Side](#-mods-próprios--server-side)
- [📖 Livro de Quests (FTB Quests)](#-livro-de-quests-ftb-quests)
- [⌨️ Teclas Padrão do Servidor (Create: Lanterns)](#-teclas-padrão-do-servidor-create-lanterns)
- [🎨 Resource Packs & Shaders Inclusos](#-resource-packs--shaders-inclusos)
- [🚀 Como Instalar e Jogar](#-como-instalar-e-jogar)
- [🛠️ Manutenção, Packwiz Híbrido & Deploy Automático](#-manutenção-packwiz-híbrido--deploy-automático)

---

## ✨ Destaques do Modpack

| Pilar | Conteúdo Principal |
| :--- | :--- |
| ⚙️ **Engenharia & Indústria** | **Create** e ecossistema completo: ferrovias e rotas (*Create Railways Navigator*), energia, automação logística, aeronáutica/física (*Valkyrien Skies / Clockwork*), artilharia e radares (*Create Big Cannons*, *Create Radar*). |
| 💚 **Create: Lanterns** | Anéis energéticos, roda de construções, traje, agarrar/lançar entidades, voo com giro no ar e habilidades especiais — já configurado com teclas padrão livres de conflito. |
| ⚔️ **Combate & Chefes** | **Better Combat**, **Better Mob Combat: Reimagined**, **Punchy Moves**, **Enhanced AI**, **L_Ender's Cataclysm**, **Mowzie's Mobs**, **Bosses of Mass Destruction** e **Deeper and Darker**. |
| 🌍 **Exploração & Dimensões** | Novos biomas, estruturas, oceanos vivos (*Hybrid Aquatic*), *The Otherside* (*Deeper and Darker*) e exploração espacial (*Cosmonautics*). |
| 🍲 **Gastronomia & Economia** | **Farmer's Delight** e expansões culinárias + moeda oficial com **Create: Numismatics**. |
| 🎙️ **Social & Qualidade de Vida** | **Simple Voice Chat**, **Xaero's Minimap/World Map**, **Map Atlases**, **Sophisticated Backpacks**, **Quark**, **Supplementaries**, **Zume** (zoom) e **Sodium + Iris + Voxy** para alto desempenho visual. |

---

## 🏰 Mods Próprios & Server-Side

O servidor roda mods próprios desenvolvidos especificamente para o **Create+**, sem exigir instalações extras fora do pack:

| Módulo | Onde fica | Função |
| :--- | :--- | :--- |
| **`createplus-keybind-defaults`** | `mods/` | Aplica automaticamente as teclas padrão do Create+ (inclusive em instalações existentes, respeitando teclas já personalizadas pelo jogador) e ajusta o botão *Reset* dos Controles. |
| **`boss-arena-guard`** | `scripts/boss-arena-guard/` | Proteção e paletas visuais para arenas de chefes. |
| **`sculksaur-loot`** | `scripts/sculksaur-loot/` | Faz o Sculksaur dropar também o loot do Warden. |
| **`sky-throttle`** | `scripts/sky-throttle/` | Otimização de desempenho do céu do Cosmonautics. |
| **`createplus-required-packs`** | `server-mods/` | Gerenciamento de packs obrigatórios no servidor. |
| **Segurança & Infra** | `server-mods/` | `DirectAuth` (autenticação), `AntiXray`, `MinerTrack` e `friendsandfoes-statue-fix`. |

---

## 📖 Livro de Quests (FTB Quests)

O modpack conta com um guia completo em português com **5 grupos**, **13 capítulos** e **458 quests** (gerado por [`scripts/generate_ftb_quests.py`](scripts/generate_ftb_quests.py)):

| # | Capítulo | Foco |
| :---: | :--- | :--- |
| 01 | **Primeiros Passos** | Início no servidor, ferramentas essenciais e progressão básica |
| 02 | **Create: Era do Andesito** | Energia rotacional, prensas, misturadores e automação inicial |
| 03 | **Create: Era do Latão e Logística** | Máquinas avançadas, filtros, pacotes e logística de fábrica |
| 04 | **Create: Ferrovias e Circuitos** | Trens, sinais, rotas e automação ferroviária |
| 05 | **Create: Energia e Indústria** | Geração elétrica, processos industriais pesados |
| 06 | **Aeronáutica, Propulsão e Física** | Dirigíveis, física de veículos e propulsão |
| 07 | **Artilharia e Radares** | Canhões do Create Big Cannons e sistemas de radar |
| 08 | **Gastronomia Delight** | Culinária avançada, banquetes e buffs de combate |
| 09 | **Arquitetura, Decoração e Economia** | Construção, decoração e uso de Numismatics |
| 10 | **Exploração, Biomas e Estruturas** | Biomas, dimensões, oceanos e expedições |
| 11–12 | **Catálogo de Mods (Partes 1 e 2)** | Referência rápida de todos os mods do pack |
| 13 | **Chefes e Expedições** | Trilha de bosses (*Mowzie's Mobs*, *Cataclysm*, *Hybrid Aquatic*, *Deeper and Darker*, etc.) |

---

## ⌨️ Teclas Padrão do Servidor (Create: Lanterns)

Para evitar conflitos com os atalhos do **Create: Lanterns**, o modpack já aplica estas teclas por padrão tanto em instalações novas ([`configureddefaults/options.txt`](configureddefaults/options.txt)) quanto em instalações existentes ([`scripts/keybind-defaults`](scripts/keybind-defaults/src/main/java/com/createplus/keybinddefaults/KeybindDefaults.java)):

### 💚 Create: Lanterns (originais mantidas)

| Ação | Tecla Padrão |
| :--- | :---: |
| Poder do Anel (`ring_power`) | `G` |
| Roda de Construções (`construct_wheel`) | `R` |
| Traje (`suit`) | `H` |
| Agarrar / Montar (`grip`) | `V` |
| Girar Construção (`grip_turn`) | `Tab` |
| Giro no Ar — Esquerda / Direita (`roll_left` / `roll_right`) | `Z` / `C` |
| Usar Poder (`power`) | `B` |
| Modificadora (`modifier`) | `Alt Esquerdo` |

### 🔀 Teclas Reorganizadas (livres de conflito)

| Mod / Ação | Tecla Padrão |
| :--- | :---: |
| **Simple Voice Chat** — Falar / Grupos / Esconder ícones | `Mouse 5` / `Page Up` / `End` |
| **Zume** — Zoom | `Mouse 4` |
| **Sophisticated Backpacks** — Abrir Mochila | `O` |
| **Supplementaries** — Aljava (Quiver) | `;` (`Ç` no teclado ABNT2) |
| **Quark** — Variantes / Trocar Hotbar / Voltar | `.` / `,` / *(sem tecla)* |
| **Xaero's Map** — Novo Waypoint / Ampliar Minimapa | `Page Down` / `Ctrl Direito` |
| **Map Atlases** — Colocar Pin | `Insert` |
| **Create Railways Navigator** — Opções de Overlay | `Home` |
| **Iris Shaders** — Recarregar / Ligar-Desligar / Escolher | `F8` / `F9` / `F10` |
| **Punchy Moves** — Câmera / Emotes / Olá / Menu Principal | `Num 0` / `Num 1` / `Num 2` / `Num 9` |
| **Punchy Moves** — Câmera Livre / Emote Static | *(sem tecla)* |
| **Essential** — Roda de Emotes / Outros atalhos | `Alt Direito` / *(sem tecla)* |

> **Dica:** *Voz (`Mouse 5`)* e *Zoom (`Mouse 4`)* usam botões laterais do mouse. Se o seu mouse não tiver botões laterais, basta trocar livremente em **Opções → Controles** — o modpack preserva suas alterações pessoais.

---

## 🎨 Resource Packs & Shaders Inclusos

<details>
<summary><b>📦 Resource Packs (9)</b></summary>

- `FreshAnimations_v1.10.4.zip` + `FA+Quivers-v2.2.zip`
- `HyperPunchy-v2.8.zip`
- `Modded Swords x Punchy! 1.21.1 v1.3.2.zip`
- `Drigo 3D Lantern's x Punchy.zip`
- `Fluid & Foolish 1.0.1.zip`
- `Visual Effects+.zip`
- `Better-Leaves-9.6.zip`
- `Just 3D Potions 1.2.zip`

</details>

<details>
<summary><b>🌅 Shader Packs (5)</b></summary>

- `Bliss_v2.1.2_(Chocapic13_Shaders_edit) + Clrwl_1.0.5.zip` *(compatível com Colorwheel)*
- `Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip`
- `ComplementaryReimagined_r5.9.3.zip`
- `photon_v1.4.zip`
- `Solas Shader V3.9.zip`

</details>

---

## 🚀 Como Instalar e Jogar

1. **Baixe a versão mais recente do `Create+.mrpack`** na página de [Releases (`latest`)](https://github.com/charles-gabriel-cc/create-plus-server/releases/latest) do repositório.
2. **Importe no Modrinth App** (recomendado) ou **Prism Launcher**.
3. **Aloque entre 6 GB e 8 GB de RAM** nas configurações da instância (Java 21).
4. *(Opcional, mas recomendado)* **Atualização automática antes de abrir o jogo (Pre-launch):**
   - O `.mrpack` já inclui [`packwiz-installer-bootstrap.jar`](packwiz-installer-bootstrap.jar) e [`atualizar-mods.bat`](atualizar-mods.bat).
   - **Modrinth App** (*Settings → Hooks → Pre-launch*):
     ```text
     atualizar-mods.bat
     ```
     *(ou o caminho completo, ex.: `"D:\Modrinth\profiles\Create+\atualizar-mods.bat"`)*
   - **Prism Launcher** (*Settings → Custom Commands → Pre-launch command*):
     ```text
     java -jar packwiz-installer-bootstrap.jar https://raw.githubusercontent.com/charles-gabriel-cc/create-plus-server/main/pack.toml
     ```
   - Sem o *Pre-launch*, o **AutoModpack** também sincroniza tudo automaticamente ao conectar no servidor (basta reiniciar o jogo quando ele pedir).

---

## 🛠️ Manutenção, Packwiz Híbrido & Deploy Automático

Repositório sincronizado diretamente com o perfil **Create+** (`D:\Modrinth\profiles\Create+`), integrado ao **Packwiz** (modo híbrido) e com deploy automático para o servidor da **EnxadaHost** (`b31b9e05`) + **AutoModpack**.

### Como funciona

- **Packwiz híbrido ([`pack.toml`](pack.toml), [`index.toml`](index.toml), [`.packwizignore`](.packwizignore), `*.pw.toml`)**:
  - Os arquivos `.jar` e `.zip` continuam sendo versionados normalmente no Git junto com os metadados do Packwiz (`.pw.toml`, `pack.toml`, `index.toml`).
  - Você pode adicionar, atualizar ou remover mods/resourcepacks/shaderpacks tanto pela interface do **Modrinth App** quanto pelo terminal com **`packwiz`** (`packwiz modrinth add <slug>`, `packwiz update --all`, `packwiz remove <slug>`).
  - Ao fazer qualquer `git commit` (pelo VS Code, GitHub Desktop ou terminal — via hook `pre-commit` em [`scripts/pre-commit`](scripts/pre-commit)) ou ao rodar [`sincronizar-github.bat`](sincronizar-github.bat) / `python scripts/sync_packwiz.py`, a sincronização nos dois sentidos acontece automaticamente:
    - Mudanças feitas no **Modrinth App** geram, atualizam ou removem os `.pw.toml` correspondentes via API do Modrinth.
    - Mudanças feitas via **`packwiz` CLI** baixam, atualizam ou removem os `.jar`/`.zip` locais automaticamente.
    - Arquivos que não existem no Modrinth (como `mods/let_it_walk-0.1.5.jar`, `mods/toomanyrecipeviewers-0.9.0+mc.21.1.jar` e `resourcepacks/Visual Effects+.zip`) são mantidos fora da lista de ignore do `.packwizignore` para entrarem na pasta `overrides/` do `.mrpack`.
- **`mods/`** e **`server-mods/`**:
  - Durante o deploy automático ([`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)), o script [`scripts/deploy_enxadahost.py`](scripts/deploy_enxadahost.py) usa os metadados `side` dos `.pw.toml` (além da inspeção dos `.jar` e API do Modrinth) para enviar mods de servidor/ambos para `/mods` e mods client-only para `/automodpack/host-modpack/main/mods`.
  - Mods exclusivos do servidor (como `DirectAuth`, `AntiXray` e `MinerTrack`) ficam em `server-mods/` e não entram no cliente nem no `.mrpack`.
- **`resourcepacks/`** e **`shaderpacks/`**: sincronizados automaticamente com `/automodpack/host-modpack/main/`.
- **`config/`**: arquivos alterados em `config/` no commit são enviados automaticamente para `/config/` no servidor.
- **AutoModpack + `.mrpack` automático**:
  - Após qualquer push na branch `main`, o GitHub Actions atualiza o servidor na EnxadaHost, dispara `automodpack generate` e gera/publica o arquivo **`Create+.mrpack`** tanto como artefato da execução quanto na release **`latest`** do GitHub.

### Scripts Úteis de Desenvolvimento

| Script | Uso |
| :--- | :--- |
| [`sincronizar-github.bat`](sincronizar-github.bat) | Sincroniza o Packwiz, cria commit e faz `git push origin main` (dispara deploy na EnxadaHost). |
| `python scripts/sync_packwiz.py` | Sincroniza manualmente os metadados do Packwiz (`*.pw.toml`, `index.toml`, `pack.toml`). |
| `python scripts/generate_ftb_quests.py` | Regera o livro de FTB Quests a partir dos mods instalados. |
| `python scripts/keybind-defaults/build.py` | Recompila `mods/createplus-keybind-defaults-1.0.0.jar`. |
