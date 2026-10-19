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
- **Não contam** jogadores em espectador, mortos, em vanish (`vanished` metadata) ou com a permissão `bmcwar.bypass`.

## Comandos

| Comando | O que faz | Permissão |
|---|---|---|
| `/bmcwar status` | capturas em andamento e tempo que falta | `bmcwar.use` (todos) |
| `/bmcwar where` | seu país (pela tag), em que cidade você está, quem a segura, por quem você luta e se você está fundo demais no subsolo | `bmcwar.use` |
| `/bmcwar set <cidade> <red\|blue\|green\|contested\|uncontrolled\|clear>` | troca a cor da cidade à mão (some qualquer captura em andamento nela) | `bmcwar.admin` (op) |
| `/bmcwar reset [guerra] confirm` | volta o front ao início da guerra: toda cidade dos países da guerra fica com a cor do lado do próprio país, e as capturas em andamento somem (sem `confirm` só mostra o que vai fazer) | `bmcwar.admin` |
| `/bmcwar reload` | recarrega a config e baixa os dados do site de novo | `bmcwar.admin` |

## Instalar

1. Compile (`./gradlew build` nesta pasta, Java 21) e ponha `build/libs/BmcWar-<versão>.jar` em `plugins/`. Precisa do **dynmap** (sem ele funciona, mas nada aparece no mapa e tudo é esquecido ao reiniciar).
2. Inicie o servidor uma vez para criar `plugins/BmcWar/config.yml`; ajuste os tempos (os que vêm são **valores de exemplo**) e o nome do mundo.
3. Marque a guerra com `"live": true` no `diplomacy.json` do site e rode o workflow, para ela sair no `territories.json` como ao vivo. Com `wars: []` o plugin roda todas as guerras assim; para rodar só algumas, liste os ids em `wars`.
4. Os ícones do dynmap precisam existir com o nome do tipo da cidade mais a cor (`SmallRed`, `SmallBlue`, `LocalityContested`, `FortUncontrolled`...), que são os que os moderadores já usam. Se faltar algum, o plugin avisa uma vez no console e não troca o ícone.

As versões do Paper API e do dynmap API em `build.gradle` são as que eu esperava existir; se o Gradle não as achar, troque pelas atuais (os endereços estão no comentário do arquivo).

## Como testar sem servidor

`test/EngineCheck.java` roda a regra de captura contra o `territories.json` real do site e só precisa do Gson (nada de Paper):

```
javac -d out -cp gson.jar $(find src/main/java/com/cumania/war/site src/main/java/com/cumania/war/engine -name '*.java') test/EngineCheck.java
java -cp out:gson.jar EngineCheck caminho/para/territories.json
```

`test/DynmapCheck.java` testa o adaptador do dynmap com marcadores falsos e precisa do `dynmap-api` no classpath, além de `src/main/java/com/cumania/war/dynmap`.

## Limites

- Só o mundo configurado em `world` (o principal), só até três lados por guerra (as cores do dynmap são três).
- Um jogador que está na população de mais de um país conta pelo primeiro que o site lista.
- Ainda não foi rodado num servidor de verdade: a lógica foi testada com os dados reais, e o código de Paper e dynmap só foi checado contra a API que ele usa. Teste primeiro com uma guerra de teste e a permissão `bmcwar.bypass` na staff.

- **Quem voa não conta:** com `capture.ignore-flying: true` (padrão), jogadores voando (fly) ou planando (elytra) são ignorados, tanto como atacantes quanto como resistência. Também não contam espectadores, mortos, quem tem a permissão `bmcwar.bypass` e quem está com a metadata `vanished`.
- **Aviões (MTVehicles e outros veículos):** quem está sentado num veículo com só ar nos `capture.ignore-vehicle-above-blocks` (3) blocos abaixo também é ignorado. O plugin não depende do MTVehicles: olha se o jogador está montado em algo e longe do chão. Veículo parado ou rodando no chão continua contando. `0` desliga isso.
