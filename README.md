# BmcWar (plugin de servidor)

Guerras pelo relógio. Hoje, para tomar uma cidade, os jogadores de um lado ficam um tempo dentro dos limites dela e um moderador troca o ícone no dynmap se não houve resistência. Este plugin faz essa parte sozinho, do mesmo jeito, e o site e o mod do Xaero's continuam lendo o dynmap como já fazem.

## O que ele faz

- **Cidades e lados vêm do site** (`gaia/territories.json`). **O país do jogador vem da tag dele** (o time do scoreboard), comparada com os nomes dos países no site. Um vassalo luta pelo lado do suserano: com a tag de Santana, que o site diz ser vassalo de Greneshire, o jogador é do lado de Greneshire. `players.side-from: site` volta a usar as listas de população do site, e `team-then-site` usa o site para quem não tem tag. Times com nome diferente do país vão em `players.team-aliases`.
- **Subsolo:** quem está mais de `capture.underground.max-depth` (10) blocos abaixo do chão natural (pedra, terra, areia; construções não contam como chão) não conta, nem como atacante nem como resistência. Cidades com túneis ou bunkers que fazem parte delas ganham outra profundidade em `capture.underground.cities` (`Nome(País): 40`, ou `-1` para qualquer profundidade).
- **Os limites de cada cidade vêm dos SVGs do site.** Se a cidade tem um limite de captura desenhado no `gaia/capture.svg` (id `zone__Nome-lp-País-rp-` ou rótulo `Nome(País)`), vale ele. Senão, vale a província do `latest.svg` em que o marcador dela está. O exportador converte os dois em polígonos em blocos (`zones` e `provinces` no `territories.json`). Nos dados de hoje, 355 das 356 cidades têm província própria, sem sobreposição. A que não tem nenhum dos dois recebe uma área redonda (`capture.fallback-radius`). `/bmcwar where` diz quando você está dentro de um limite desenhado.
- **Tempo por tipo** (`capture.seconds`, em segundos): cidade (Small, Medium, Large e capitais) 30 s, Fort 15 s, o resto (Locality, Colonial) 0 s, ou seja, toma assim que um atacante está dentro sem oponente. Capitais multiplicam por `capital-multiplier` (1).
- **Regra:** um lado que fica dentro da cidade pelo tempo dela, sem ninguém do lado que a segura lá dentro, toma a cidade. O ícone no dynmap muda para a cor do lado (Red = 1º lado, Blue = 2º, Green = 3º), como o site e o mod esperam.
- **Resistência:** alguém do lado que segura a cidade dentro dela, ou dois lados atacando ao mesmo tempo, deixa a cidade **Contested** e o tempo recomeça (`on-resistance: pause` mantém o que já tinha). Quando a resistência sai, o ícone volta ao que era.
- **Só as cidades dos países da guerra** podem ser tomadas ou contestadas; passar por um país neutro não muda nada.
- **Ao desligar ou recarregar**, as cidades que o plugin deixou Contested voltam ao ícone que tinham.
- **Quem segura a cidade é o que o ícone diz.** Se um moderador trocar o ícone à mão, vale o que ele pôs (e uma captura em andamento nessa cidade é descartada). Cidade sem cor de guerra é de quem é dona dela.
- **Sem tolerância:** `grace-seconds` (0) é quanto os atacantes podem sair antes de a tentativa ser descartada (0 = descarta na hora); `cooldown-seconds` (0 = sem espera) é o tempo em que uma cidade recém-tomada não pode ser tomada de novo.
- **Avisos:** no chat quando um lado começa a capturar, quando a cidade fica contestada e quando é tomada; na barra acima da hotbar, o tempo que falta para quem está dentro. Cada aviso liga e desliga em `announce`.
- **Vários mapas ao mesmo tempo** (`maps` no `config.yml`): cada mapa é um mundo do Minecraft com o próprio `territories.json` do site (cidades, limites, guerras, países) e os próprios marcadores no dynmap, e tem o próprio front, Engagements e capturas. Gaia é um; um mapa de evento (guerra simulada noutro mundo) é outro, e os dois rodam juntos. Só conta quem está no mundo do mapa; o ready check de uma guerra não pergunta quem está no mundo de outro mapa. Se dois mapas têm guerra com o mesmo id, os comandos aceitam `mapa:id`.
- **Não contam** jogadores em espectador, mortos, em vanish (`vanished` metadata) ou com a permissão `bmcwar.bypass`.

## As Leis da Guerra (Law 7 do LOTG)

O plugin segue as regras de captura do livro *The Laws of the Game* e lê o front do mesmo jeito que o modo War do site (`js/warmap.js`):

- **Tempo por categoria** (`capture.seconds`): Settlement (Small, Medium, Large, Colonial) 30 s, Fort que não é Settlement (desenhado como Fort, com a designação "Fort" no site, ou capital que não é Settlement) 15 s, o resto na hora (7.8.5-6, 5.6.7).
- **Theater** (7.1.2-7.1.4): numa guerra restrita (`theater` no `diplomacy.json`), só as localidades do Theater; senão, todas as dos países da guerra.
- **In Play** (7.8.1, `rules.in-play`): só pode ser tomada uma localidade Conectada a outra de um lado diferente. As Conexões vêm das linhas do `latest.svg` (o exportador do site as grava em `network` no `territories.json`), passando por Bridges e Straits abertas e por terra neutra (Short Circuits, com as escolhas da Staff em `shortCircuits`).
- **Encirclement** (7.8.9-10, `rules.encirclement`): um bolsão sem ligação com nenhuma capital (ou Supply Capital) do seu lado não põe nada In Play, a não ser a localidade cuja captura o reconectaria. Rotas pelo mar ainda não entram.
- **Warzone** (7.8.7): Forts ligados por linha amarela no mapa são capturados como um só, no tempo da localidade mais demorada.
- **Engagement** (7.8.8, `rules.require-engagement`): só se captura durante um Engagement aberto com `/bmcwar engagement start`. Strategic e free valem para o Theater inteiro; Tactical e Assault só para as cidades nomeadas; um Raid toma um Fort (que não precisa estar In Play) e o devolve ao fim (7.17.13). Cada um acaba sozinho no tempo do livro (`engagement.minutes`).
- **Ready check:** quando um Engagement começa, todo jogador com lado na guerra vê um título na tela perguntando se está pronto e responde digitando `ready` ou `not ready` no chat (a resposta não aparece no chat público; a staff é avisada e vê o resumo em `/bmcwar status`). Por padrão o Engagement **só começa quando todos disseram ready** (até lá ninguém captura e o tempo dele não corre); quem sai do servidor deixa de segurar, e a staff pode começar mesmo assim com `/bmcwar engagement go`. `engagement.ready-check` liga/desliga, ajusta os lembretes e `wait-for-all: false` faz o check ser só informativo.
- **Capitulação** (7.9): `/bmcwar status` mostra o score de cada país (cada país como um único Mainland; Colonial não vale). Quando um país capitula (capital tomada e score acima de 100), todo mundo é avisado no chat e com um título na tela (quem luta na guerra ou está no mapa dela), e o Engagement em andamento acaba (`engagement.end-on-capitulation`, `announce.capitulated`).

## Comandos

| Comando | O que faz | Permissão |
|---|---|---|
| `/bmcwar status` | Engagement, quantas localidades estão In Play, capturas em andamento e Capitulação | `bmcwar.use` (todos) |
| `/bmcwar where` | seu país (pela tag), em que cidade você está, se ela está In Play, cercada ou numa Warzone, se pode ser tomada agora e por quê, quem a segura, por quem você luta e se você está fundo demais no subsolo | `bmcwar.use` |
| `/bmcwar table` | liga/desliga, só para você, uma tabela na lateral da tela com a % de capitulação de cada país da guerra com Engagement ativo (as tags continuam aparecendo) | `bmcwar.use` |
| `/bmcwar debug` | liga/desliga: mostra acima da hotbar, a cada segundo, se você está contando para as capturas e por que não | `bmcwar.use` |
| `/bmcwar engagement` | o Engagement em andamento e quanto falta | `bmcwar.use` |
| `/bmcwar engagement start <strategic\|tactical\|assault\|raid\|free> [war=<id>] [cidades...]` | abre um Engagement (a cidade de uma Warzone traz a Warzone inteira); avisa quando um alvo vai contra o livro (capital num Assault, alvo fora de In Play...) | `bmcwar.admin` |
| `/bmcwar engagement go [guerra]` | começa agora o Engagement que está esperando todos ficarem ready | `bmcwar.admin` |
| `/bmcwar engagement stop [guerra]` | encerra o Engagement (num Raid, o Fort volta a quem o tinha) | `bmcwar.admin` |
| `/bmcwar set <cidade> <red\|blue\|green\|contested\|uncontrolled\|clear>` | troca a cor da cidade à mão (some qualquer captura em andamento nela) | `bmcwar.admin` (op) |
| `/bmcwar reset [guerra] confirm` | volta o front ao início da guerra: toda cidade dos países da guerra fica com a cor do lado do próprio país, e as capturas em andamento somem (sem `confirm` só mostra o que vai fazer) | `bmcwar.admin` |
| `/bmcwar reload` | recarrega a config e baixa os dados do site de novo | `bmcwar.admin` |

## Instalar

1. Compile (`./gradlew build` nesta pasta, Java 21) e ponha `build/libs/BmcWar-<versão>.jar` em `plugins/`. Precisa do **dynmap** (sem ele funciona, mas nada aparece no mapa e tudo é esquecido ao reiniciar).
2. Inicie o servidor uma vez para criar `plugins/BmcWar/config.yml`; ajuste os tempos (os que vêm são **valores de exemplo**) e os mapas (`maps`: mundo, `territories.json` do site e mundo do dynmap de cada um).
3. Marque a guerra com `"live": true` no `diplomacy.json` do site e rode o workflow, para ela sair no `territories.json` como ao vivo. Com `wars: []` o plugin roda todas as guerras assim; para rodar só algumas, liste os ids em `wars`.
4. Os ícones do dynmap precisam existir com o nome do tipo da cidade mais a cor (`SmallRed`, `SmallBlue`, `LocalityContested`, `FortUncontrolled`...), que são os que os moderadores já usam. Se faltar algum, o plugin avisa uma vez no console e não troca o ícone.

As versões do Paper API e do dynmap API em `build.gradle` são as que eu esperava existir; se o Gradle não as achar, troque pelas atuais (os endereços estão no comentário do arquivo).

## Como testar sem servidor

`test/EngineCheck.java` roda a regra de captura contra o `territories.json` real do site e só precisa do Gson (nada de Paper):

```
javac -d out -cp gson.jar $(find src/main/java/com/cumania/war/site src/main/java/com/cumania/war/engine -name '*.java') test/EngineCheck.java
java -cp out:gson.jar EngineCheck caminho/para/territories.json
```

`test/WarCheck.java` testa a Law 7 (tempos, In Play, Encirclement, Warzone, Engagements, Theater, Capitulação) com os dados reais do site com a rede (`docs/territories-network-sample.json`); os números do front foram conferidos com o modo War do próprio site rodando num navegador:

```
java -cp out:gson.jar WarCheck docs/territories-network-sample.json
```

`test/DynmapCheck.java` testa o adaptador do dynmap com marcadores falsos e precisa do `dynmap-api` no classpath, além de `src/main/java/com/cumania/war/dynmap`.

## Limites

- Só os mundos listados em `maps`, só até três lados por guerra (as cores do dynmap são três).
- Um jogador que está na população de mais de um país conta pelo primeiro que o site lista.
- Ainda não foi rodado num servidor de verdade: a lógica foi testada com os dados reais, e o código de Paper e dynmap só foi checado contra a API que ele usa. Teste primeiro com uma guerra de teste e a permissão `bmcwar.bypass` na staff.

- **Quem voa não conta:** com `capture.ignore-flying: true` (padrão), jogadores voando (fly) ou planando (elytra) por mais de `capture.air-grace-seconds` (3) seguidos são ignorados, tanto como atacantes quanto como resistência. Pular de uma torre, cair, wind charge e mace contam normal. Também não contam espectadores, mortos, quem tem a permissão `bmcwar.bypass` e quem está com a metadata `vanished`.
- **Aviões (MTVehicles e outros veículos):** quem está sentado num veículo com só ar nos `capture.ignore-vehicle-above-blocks` (3) blocos abaixo também é ignorado. O plugin não depende do MTVehicles: olha se o jogador está montado em algo e longe do chão. Veículo parado ou rodando no chão continua contando. `0` desliga isso.
