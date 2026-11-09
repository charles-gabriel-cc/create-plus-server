# Create+ Modpack & Server Auto-Deploy

Repositório sincronizado diretamente com o perfil **Create+** (`D:\Modrinth\profiles\Create+`) e com deploy automático para o servidor da **EnxadaHost** (`b31b9e05`) + **AutoModpack**.

## Como funciona

- **`mods/`**: Contém **todos os mods** (tanto mods de servidor quanto mods client-side).
  - Durante o deploy automático (`.github/workflows/deploy.yml`), o script `scripts/deploy_enxadahost.py` detecta automaticamente quais mods são de servidor (enviando para `/mods`) e quais são apenas de cliente (enviando para `/automodpack/host-modpack/main/mods`), mantendo todos visíveis na pasta `mods/` padrão dos jogadores via `forceCopyFilesToStandardLocation`.
- **`resourcepacks/`** e **`shaderpacks/`**: Sincronizados automaticamente com `/automodpack/host-modpack/main/`.
- **`config/`**: Arquivos alterados em `config/` no commit são enviados automaticamente para `/config/` no servidor.
- Após qualquer alteração, o GitHub Actions atualiza o manifesto do **AutoModpack** no servidor (e reinicia o servidor caso algum mod de servidor tenha sido adicionado, atualizado ou removido).
