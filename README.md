# Create+ Modpack & Server Auto-Deploy

Repositório sincronizado diretamente com o perfil **Create+** (`D:\Modrinth\profiles\Create+`), integrado ao **Packwiz** (modo híbrido) e com deploy automático para o servidor da **EnxadaHost** (`b31b9e05`) + **AutoModpack**.

## Como funciona

- **Packwiz híbrido ([`pack.toml`](pack.toml), [`index.toml`](index.toml), [`.packwizignore`](.packwizignore), `*.pw.toml`)**:
  - Os arquivos `.jar` e `.zip` continuam sendo versionados normalmente no Git junto com os metadados do Packwiz (`.pw.toml`, `pack.toml`, `index.toml`).
  - Você pode adicionar, atualizar ou remover mods/resourcepacks/shaderpacks tanto pela interface do **Modrinth App** quanto pelo terminal com **`packwiz`** (`packwiz modrinth add <slug>`, `packwiz update --all`, `packwiz remove <slug>`).
  - Ao fazer qualquer `git commit` (pelo VS Code, GitHub Desktop ou terminal — via hook `pre-commit` em [`scripts/pre-commit`](scripts/pre-commit)) ou ao rodar [`sincronizar-github.bat`](sincronizar-github.bat) / `python scripts/sync_packwiz.py`, a sincronização nos dois sentidos acontece automaticamente:
    - Mudanças feitas no **Modrinth App** geram, atualizam ou removem os `.pw.toml` correspondentes via API do Modrinth.
    - Mudanças feitas via **`packwiz` CLI** baixam, atualizam ou removem os `.jar`/`.zip` locais automaticamente.
    - Arquivos que não existem no Modrinth (como `mods/let_it_walk-0.1.5.jar`, `mods/toomanyrecipeviewers-0.9.0+mc.21.1.jar` e `resourcepacks/Visual Effects+.zip`) são mantidos fora da lista de ignore do `.packwizignore` para entrarem na pasta `overrides/` do `.mrpack`.
- **`mods/`** e **`server-mods/`**:
  - Durante o deploy automático ([`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)), o script [`scripts/deploy_enxadahost.py`](scripts/deploy_enxadahost.py) usa os metadados `side` dos `.pw.toml` (além da inspeção dos `.jar` e API do Modrinth) para enviar mods de servidor/ambos para `/mods` e mods client-only para `/automodpack/host-modpack/main/mods`.
  - Mods exclusivos do servidor (como DirectAuth) ficam em `server-mods/` e não entram no cliente nem no `.mrpack`.
- **`resourcepacks/`** e **`shaderpacks/`**: Sincronizados automaticamente com `/automodpack/host-modpack/main/`.
- **`config/`**: Arquivos alterados em `config/` no commit são enviados automaticamente para `/config/` no servidor.
- **AutoModpack + `.mrpack` automático + Pré-inicialização (`packwiz-installer`)**:
  - Após qualquer alteração na branch `main`, o GitHub Actions atualiza o servidor na EnxadaHost, dispara `automodpack generate` e gera/publica o arquivo **`Create+.mrpack`** tanto como artefato da execução quanto na release **`latest`** do GitHub.
  - O arquivo `.mrpack` e o repositório já incluem [`packwiz-installer-bootstrap.jar`](packwiz-installer-bootstrap.jar) e [`atualizar-mods.bat`](atualizar-mods.bat). Se o repositório estiver público no GitHub, os jogadores podem configurar o **Pre-launch command** (comando pré-inicialização) nas opções da instância no Modrinth App / Prism Launcher para atualizar tudo antes mesmo do jogo abrir (sem precisar reiniciar depois pelo AutoModpack):
    - **Modrinth App** (*Settings > Hooks > Pre-launch*): `atualizar-mods.bat` (ou caminho completo `"D:\Modrinth\profiles\Create+\atualizar-mods.bat"`)
    - **Prism Launcher**: `java -jar packwiz-installer-bootstrap.jar https://raw.githubusercontent.com/charles-gabriel-cc/create-plus-server/main/pack.toml`
