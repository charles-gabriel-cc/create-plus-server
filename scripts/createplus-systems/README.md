# Create+ Server Systems (`createplus-systems`)

Mod **100% server-side** (empacotado em `server-mods/createplus-systems-1.0.0.jar`, zero mod extra no cliente) para o servidor Create+ (NeoForge 1.21.1).

## Módulos Implementados
1. **Core (`core`):** MariaDB assíncrono (`createplus-db.json` ou fallback `boss-arena-db.json`), ponte direta com `Create: Numismatics`, codec SNBT 1.21.1 e Chest GUI server-side.
2. **Economia (`economy`):** `/saldo`, `/loja`, `/vender`, `/vender contratos` (Black Market), `/ah`, `/ah vender <spurs>`, `/ah caixa`, `/ah meus`, `/eco admin ...`.
3. **Dungeons Instanciadas (`dungeons`):** dimensão vazia `createplussystems:dungeons` embutida no JAR, mapas `.nbt` (single ou multi-tile) + arena fallback para teste imediato, ondas + Boss Final, proteção de blocos/limites, pressão ambiental T2/T3 (protegida por banquetes/poções do Chef), retorno seguro pós-crash no MariaDB. Comandos: `/dungeon`, `/dungeon entrar <id>`, `/dungeon sair`, `/dungeon admin reload | reset_cd`.
4. **Eventos (`events`):** agenda em horário fixo (`America/Sao_Paulo`), avisos 5m/1m antes, BossBar global, 4 motores (`WORLD_BOSS`, `SUPPLY_DROP`, `DUNGEON_HOUR`, `COMPETITION_FISHING/MINING/KILLS`). Comandos: `/eventos`, `/evento iniciar <tipo> [min]`, `/evento parar`, `/evento reload`.
5. **Profissões por Maestria (`professions`):** 5 profissões abertas (**Engenheiro**, **Ferreiro**, **Taumaturgo**, **Chef & Alquimista**, **Naturalista**), 40 níveis / 4 Tiers, XP por prática/variedade + anti-AFK, trava de ferramentas por Tier em `config/createplus/profession_rules.json`. Comandos: `/profissoes`, `/profissoes top <id>`, `/profissoes admin dar_xp | set_tier | gating | reload`.

## Compilar & Sincronizar com a instância de teste
```powershell
python scripts/sync_dev_instance.py
```
