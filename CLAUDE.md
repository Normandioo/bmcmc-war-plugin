# BmcWar — contexto para continuar o trabalho

(Arquivo de contexto: leia inteiro antes de mexer. O dono do projeto é brasileiro e escreve em português; responda em português, de forma direta.)

## O que é
Plugin **Paper 1.21.11 (Java 21)** que automatiza a captura de cidades nas guerras de um servidor Minecraft de países. Hoje, para tomar uma cidade, os jogadores de um lado ficam um tempo dentro dos limites dela e um moderador confere se há resistência e troca o ícone do marcador da cidade no **dynmap**. O plugin faz isso sozinho. Cidades, guerras, lados e jogadores vêm do site do servidor.

## O ecossistema (três peças, só esta está neste projeto)
1. **Site** `https://bmcmc.online/` (repo privado `WallepBrazilian/Cumania`). Publica `https://bmcmc.online/gaia/territories.json` (exemplo em `docs/territories-sample.json`, gerado por `tools/export_territories.py` a partir do `gaia/latest.svg`). O site faz proxy do dynmap em `/dynmap/*` e mostra a guerra ao vivo lendo os ícones dos marcadores (`js/warlive.js`).
2. **Mod Fabric "Cumania Territories"** (cliente, Xaero's World Map/Minimap; repo `Normandioo/bmcmc.online-to-xaero-s-world-minimap`). Lê o `territories.json` e os marcadores do dynmap, pinta territórios/guerra no mapa e avisa capturas no chat. **Não mexer nele a partir deste projeto.**
3. **Este plugin.** Escreve o ícone dos marcadores; site e mod só leem.

## Contrato com o dynmap (não quebrar)
- Cada cidade tem um marcador cujo **ícone termina na cor do lado que a segura**: `Red` = 1º lado da guerra, `Blue` = 2º, `Green` = 3º, `Contested`, `Uncontrolled`. O nome do ícone é o tipo da cidade + cor: `SmallRed`, `LocalityContested`, `FortUncontrolled`... Esses ícones já existem no dynmap do servidor. Se faltar algum, o plugin avisa uma vez no console e não troca.
- Achar o marcador de uma cidade (igual ao site): id `Nome(País)`, ou o id do mapa `marker__Nome-lp-País-rp-__Tipo`, ou pelo nome, ou o mais próximo num raio de 8 blocos.
- Numa guerra ao vivo todas as cidades dos participantes são pintadas pela cor do lado que as segura (as próprias também ficam Red/Blue). "Capturada" = lado que segura ≠ lado dono.
- Ícone trocado à mão por moderador **vale mais** que o plugin (e descarta a captura em andamento daquela cidade).

## Dados do site (`territories.json`)
Chaves: `format, world, dimension, generated, countries, provinces, localities, players, war`.
- `localities` (356): nome, país, `kind` (Locality, Colonial, Fort, Small, Medium, Large, e "...Capital"), x, z.
- `provinces` (356): polígonos em **coordenadas de bloco** (anéis, regra par-ímpar). Cada cidade é a província em que o marcador dela cai: 355/356 caem em exatamente uma; a que não tem recebe um círculo (`capture.fallback-radius`, 40).
- `zones` (opcional): **limites de captura** desenhados no `gaia/capture.svg` do site (`captureFile` no `timeline-config.json`), `{name, country, rings}`. Quando uma cidade tem zone, ela substitui a província (`SiteData.Limit` = ZONE / PROVINCE / CIRCLE; `City.limit()`). Feito pelo Enrico em outra conversa: no site, exportador + `js/capture.js` (contornos no mapa) na branch `capture-limits` do Cumania; aqui, `SiteData`, `TerritoryFile`, `Polygon` (comentário) e `tools/export_territories.py`. O exportador avisa e pula limite inválido (curva, sem cidade, sobreposição, viewBox diferente) em vez de parar. O Claude do mod confirmou (2026-10-18) que o mod ignora `zones` sem problema. Em 2026-10-19 as 356 cidades já tinham limite desenhado; 3 sobreposições pequenas (CentralAshdown×Hykethorpe 3 blocos, FolklandIslands×LassFolkvinas 2, Mu×MuFort 2) — **o dono decidiu ignorar**, não reportar de novo. 86 localidades vêm com cor no `kind` (`SmallRed`, `FortBlue`...) porque os ids dos marcadores no `latest.svg` estão pintados de propósito (o dono pinta SVG e dynmap para mostrar o lado); `SiteData.plainKind` tira a cor do tipo (v0.2.1). **Não pedir para tirar a cor do SVG.**
- `players`: `ign` (nick do jogo) → nome no site; `countries[].population` lista os jogadores de cada país. Lado do jogador = nick → nome no site → país → lado da guerra (`SiteData.sideOfPlayer`).
- `war.wars[]`: `id, name, live, ended, sides[] {name, members[] {name, countries[]}}, captured[]`. Um membro pode ter nome diferente do país (ex.: "Eleyetia" cobre USE e Mesea; "RedIce" cobre Iceland), então `sideOfCountry` casa também pelo nome do membro.
- Guerra ao vivo = `"live": true` no `diplomacy.json` do site. Com `wars: []` no config, o plugin roda toda guerra ao vivo que não terminou.

## Regras decididas pelo dono (estado atual do `config.yml`)
- Tempo para capturar **em segundos**, por tipo: cidade (Small/Medium/Large e capitais) **30 s**, Fort **15 s**, Locality/Colonial **0 s** (toma na hora, se há atacante dentro e nenhum oponente). `capital-multiplier: 1`.
- **Oponente dentro para o timer** (`on-resistance: pause`; a cidade vira Contested; ao sair o timer segue de onde parou. `reset` zera). Dois lados atacando ao mesmo tempo também é resistência.
- Sem tolerância ao sair (`grace-seconds: 0`: o atacante sai e a tentativa é descartada na hora) e sem cooldown (`cooldown-seconds: 0`).
- **Não contam**: espectadores, mortos, `bmcwar.bypass`, metadata `vanished`; quem **voa** (`isFlying()`/`isGliding()`, `capture.ignore-flying`); quem está **montado num veículo com só ar nos 3 blocos abaixo** (`capture.ignore-vehicle-above-blocks`, pensado nos aviões do plugin MTVehicles, sem usar a API dele; veículo no chão conta).
- Só o mundo `world`, no máximo 3 lados por guerra (3 cores).
- **Só cidades de países da guerra** entram no jogo: cidade de país neutro não é capturada nem contestada (antes era, e o ícone dela ficava vermelho).
- Cidade de 0 s gera só o evento `CAPTURED` (sem o `START` no mesmo segundo).
- Se o ícone não pôde virar Contested (sem marcador/ícone no dynmap), o engine guarda isso (`Progress.shown`) e não anuncia "contested" de novo a cada segundo.
- Ao desligar o servidor e no `/bmcwar reload`, `CaptureEngine.clear()` devolve o ícone original de toda cidade que o plugin deixou Contested.

## Arquitetura (`src/main/java/com/cumania/war/`)
- `site/TerritoryFile` (cópia dos tipos do JSON), `site/Polygon` (ponto-em-polígono par-ímpar, bbox, `circle` de fallback), `site/SiteData` (parse: `City` com `capital()`/`baseKind()`, `War`, `Side`; `sideOfCountry`, `sideOfPlayer`). **Sem classes do Bukkit.**
- `engine/CaptureEngine` — o coração: `tick(site, war, present, dt)` devolve eventos `START/CONTESTED/CLEARED/ABORTED/CAPTURED`; progresso por cidade, resistência, restauração do ícone, grace, cooldown, ícone manual vence; `statusOf`, `statuses`, `forget`, `clear`. `engine/Rules` (config → regras, `secondsFor(city)`), `engine/Markers` (interface: NONE=-1, CONTESTED=-2, UNCONTROLLED=-3, read/write), `engine/MemoryMarkers` (para testes). **Sem classes do Bukkit.**
- `dynmap/DynmapMarkers` — implementa `Markers` com a API do dynmap (`DynmapAPI`, `org.dynmap.markers.*`).
- `BmcWarPlugin` — config → `Rules`; baixa o site de forma assíncrona a cada N min com cópia em cache; tick síncrono de 1 s (filtros em `eligible`, anúncios no chat/action bar); `WarCommand` — `/bmcwar status|where|set|reload`.
- Resources: `plugin.yml` (permissões `bmcwar.use`, `bmcwar.admin`, `bmcwar.bypass`; `softdepend: dynmap`), `config.yml`.

## Build e CI
- `./gradlew build` (Java 21). Dependências `compileOnly`: `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`, `us.dynmap:dynmap-api:3.7-beta-6` **e** `us.dynmap:DynmapCoreAPI:3.7-beta-6` (o pacote `org.dynmap.markers` só existe neste último — sem ele o build falha). Repositórios: Maven Central, `repo.papermc.io`, `repo.mikeprimm.com`.
- `.github/workflows/build.yml` compila em todo push/PR e guarda o jar (Actions → execução → Artifacts → `BmcWar`). Se o build falhar, o passo `Build` coloca o trecho do erro do Gradle nas **anotações** da execução (`gh api repos/OWNER/REPO/check-runs/JOB_ID/annotations`), porque o log completo não é baixável em alguns ambientes.
- **Releases** (`.github/workflows/release.yml`): ao mudar `plugin_version` no `gradle.properties` na `main` (ou rodando o workflow "Release" à mão), o Actions compila e publica a release `v<versão>` com o jar, usando as notas de `.github/release-notes.md` (atualize-as antes). Se a release da versão já existe, não faz nada. A sessão do Claude não consegue criar tags nem releases direto (403); por isso é o Actions que cria. Repo: `Normandioo/bmcmc-war-plugin` (público). v0.1.0 e v0.2.0 (limites de captura) em 2026-10-18; v0.2.1 (cor no tipo) em 2026-10-19.
- Em ambientes sem acesso a Maven (como o sandbox em que isso foi escrito) o projeto não compila localmente; o CI do GitHub é a verificação real.

## Testes (sem servidor)
- `test/EngineCheck.java` roda a regra de captura contra o `territories.json` real (só precisa do Gson):
  ```
  javac -d out -cp gson.jar $(find src/main/java/com/cumania/war/site src/main/java/com/cumania/war/engine -name '*.java') test/EngineCheck.java
  java -cp out:gson.jar EngineCheck docs/territories-sample.json
  ```
  Os casos 1–6 usam os **valores antigos em minutos × 60** (600, 900...); os casos 7–10 (cidade neutra, cidade de 0 s, marcador ausente, `clear()`) usam a regra atual do `config.yml`. A checagem da LombriganWar só exige que ela exista com dois lados (no exemplo ela está `live=false`). Caso 11: limite de captura (`zones`); caso 12: cor no tipo. Estado atual: 31/31 ok. No sandbox, o Gson está em `/opt/apache-maven-*/lib/gson-*.jar`.
- `test/DynmapCheck.java` testa o adaptador com marcadores falsos (precisa do `dynmap-api` no classpath).

## O que NUNCA foi verificado (fazer primeiro num servidor de teste)
1. O plugin nunca rodou num servidor Paper de verdade. Só a lógica pura foi testada; o código de Bukkit/dynmap só compila.
2. A escrita do ícone no dynmap (nomes dos ícones, achar o marcador certo) e as mensagens de chat/action bar.
3. A suposição de que o piloto de um avião do MTVehicles é passageiro de uma entidade (`isInsideVehicle()`). Se não for, o filtro de veículo não pega — ajustar.
4. `capture.ignore-vehicle-above-blocks` olha só blocos de ar embaixo; avião baixo (< 3 blocos) conta como "no chão".
Dica de teste: guerra de teste, `bmcwar.bypass` na staff, e conferir com `/bmcwar status` e `/bmcwar where`.

## Ideias que o dono ainda não pediu
SVG próprio só com áreas de captura (hoje a área é a província do SVG do site); persistir capturas em andamento ao reiniciar; mais de 3 lados; mensagens traduzíveis; filtro de criativo; publicar o jar como Release.

## Como trabalhar com o dono
Respostas curtas, em português, dizendo o que mudou. Ele testa no servidor e cola resultados/prints. Depois de mexer: rodar o `EngineCheck`, fazer push e conferir o CI. Ao mudar chaves do `config.yml`, lembrar que o plugin só cria o arquivo na primeira vez: avise para apagar `plugins/BmcWar/config.yml` do servidor.
