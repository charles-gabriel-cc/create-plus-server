# Boss Arena Guard

Compile o mod com JDK 21 no `PATH` e as bibliotecas do perfil Modrinth instaladas.

```powershell
python scripts/boss-arena-guard/build.py
```

Em uma worktree fora da instalação do Modrinth, indique o diretório das bibliotecas.

```powershell
python scripts/boss-arena-guard/build.py --libraries 'D:/Modrinth/meta/libraries'
```

O mod cria arenas para entidades que aparecem no livro do Boss Checklist nas categorias **Boss** e **Miniboss** e estão presentes no catálogo do servidor. O servidor lê as definições do Boss Checklist instalado e de `config/boss_checklist/override/bosses.json` durante a inicialização ou um `/reload`. Os perfis controlam a geometria das arenas das entidades elegíveis.

Bosses e minibosses elegíveis usam o balanceamento de atributos da arena. Dungeon Difficulty aplica seus bônus de atributos aos demais mobs. O ajuste da arena fica registrado no console do servidor.

Os perfis padrão estão em [boss-arena-profiles.csv](src/main/resources/boss-arena-profiles.csv). Ender Dragon, Void Worm e Wither usam a altura da dimensão até o limiar do vazio. Ferrous Wroughtnaut usa um teto adicional em Y=0 quando a arena e o boss já estão abaixo desse limite. Os demais perfis especificam uma faixa vertical relativa ao centro da arena.

## MariaDB ou MySQL

1. Gere o SQL inicial com `python scripts/boss-arena-guard/schema.py` e execute a saída no banco. O script cria `boss_arena_profiles` e preenche os perfis padrão. Linhas existentes conservam os valores atuais.
2. Na raiz do servidor dedicado, junto a `server.properties`, crie `boss-arena-db.json` com `url`, `user` e `password`. Use as credenciais do banco do servidor. A conta usada pelo mod só precisa de `SELECT` nessa tabela.

```json
{
  "url": "jdbc:mariadb://database.example:3306/arena_db?sslMode=verify-full",
  "user": "arena_reader",
  "password": "senha-do-banco"
}
```

Use os parâmetros TLS fornecidos pela hospedagem. O servidor consulta o banco a cada 30 segundos. Uma alteração válida atualiza novas arenas e arenas em espera. Combates em andamento conservam a geometria que tinham ao começar. O mod usa os valores embutidos antes da primeira carga do banco e mantém a última configuração válida se uma consulta falhar ou uma linha estiver inválida.

```sql
UPDATE boss_arena_profiles
SET upper_offset = 24
WHERE boss_id = 'mowziesmobs:ferrous_wroughtnaut';
```

- `boss_id` é o ID da entidade no Minecraft, incluindo o namespace.
- `radius` é o raio horizontal em blocos, de 32 a 512.
- `bounded` usa `lower_offset` de −512 a −4 e `upper_offset` de 4 a 512. `ceiling_y` é opcional e representa um limite absoluto aplicado a arenas que começam abaixo dele.
- `dimension` usa os limites da dimensão. Configure ambos os offsets como `0` e `ceiling_y` como `NULL`.
- Excluir a linha restaura o perfil padrão do mod para aquele boss.

Operadores podem consultar a geometria da próxima arena pelo comando `/bossarena inspect @e[type=mowziesmobs:ferrous_wroughtnaut,limit=1]`. O log do servidor registra quando uma configuração nova é carregada.
