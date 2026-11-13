Paper 1.21.11 (Java 21). Put the jar in `plugins/` (replace the old one); dynmap is recommended (without it captures work but nothing shows on the map). **Delete `plugins/BmcWar/config.yml`** if you still have one from before 0.8.0: the world, site and dynmap settings moved into a `maps` section (the old file still loads, as a single map). The keys new since 0.9.0 have defaults, so a 0.8.0 file works as it is.

**Not yet tested on a real server.** Try it with a test war first, with `bmcwar.bypass` for the staff, and check `/bmcwar where` and `/bmcwar status`.

### New in 0.21.0
- **News in the BMC Atlas mod, with pictures.** The plugin sends the BMC Atlas client mod (1.8.0 and newer) the latest news when the player joins (`mod-sync.news`, 20) and each new one as it comes out. The mod pops new ones up in the top corner of the screen and lists them in its map's News panel, where a click opens the whole news with its picture.
- **Pictures in the Staff's posts.** The site's Discord bot takes a PNG with `/news post image:`; the plugin turns it into a full address for the mod (the chat and the `/news` menu stay text only).
- **Breaking News and Staff notice.** A post is Breaking News (news of the world) unless the bot marked it as a Staff notice (`/news post category:`). A new kind `breaking` for `news.announce`; a config that announces `post` announces Breaking News too.

### New in 0.20.1
- **The news resource pack has a public address.** The pack is in the repository: set `resource-pack=https://raw.githubusercontent.com/Normandioo/bmcmc-war-plugin/main/pack/bmcnews-pack.zip` and `resource-pack-sha1=acfc56d37d948c813432a28a806aba18068b78df` in `server.properties` (the sha1 changes whenever the pack is rebuilt with `python tools/build_pack.py`; the notes of the release that changes it say so).
- **A site that answers with something huge can no longer fill the server's memory:** downloads are read as a stream and refused above 16 MB. A zero-width text cut in `News.chunks` can no longer loop forever.

### New in 0.20.0
- **Diplomatic news.** The plugin compares the site's diplomacy each time it downloads it and turns every change into a news item: war declared or ended, alliances (formed, joined, left, dissolved), truces and pacts, ultimatums, subjects and independence, written like a war newspaper, with dates in the world's calendar (AES). The Staff's posts (the site's Discord bot, `/news post`) show up too. A framed block in the chat announces each one and opens the menu when clicked; players are told what they missed when they join. `/news` (or `/bmcwar news`) opens the menu: a chest with filters and pages, and a click on a news opens it in a window with the country flags. Settings: the `news` section of `config.yml` (new keys, added by themselves).
- **The menu needs the resource pack.** Its title is drawn by a resource pack: build it with `python tools/build_pack.py` (needs the site's repo for the flags), host the zip and set `resource-pack` and `resource-pack-sha1` in `server.properties`. Without the pack the menu title shows broken characters.
- **Much lighter on the game thread.** The front of each war is worked out again only when something changed, the message to the BMC Atlas mod is built only on change, site downloads use ETag and gzip and skip identical data, a slow site can no longer pile up downloads, and the capitulation table sends its sidebar only when it changes. Measured on a test server with a live war: 15.7 ms -> 4.1 ms per second with 30 players, 49 ms -> 1.6 ms with 10 maps. `/bmcwar maps` now shows what the plugin costs the game thread.

### New in 0.19.0
- **New versions are announced.** The plugin reads the latest releases from GitHub when it starts and every 6 hours. When a newer BmcWar is out, the console says so and players with `bmcwar.admin` are told when they join, with a link to download it.
- **Players with an old BMC Atlas mod are told to update**, with a link. The mod (1.2.6 and newer) says its version on the `bmcatlas:sync` channel; a mod that opens the channel but says nothing is 1.2.4 or 1.2.5. A mod that already tells the player about updates itself is left alone, and players without the mod (or with one older than 1.2.4) get nothing. `updates.check: false` turns all of it off, `updates.warn-old-mod: false` only the players' warning (new keys, added to the config by themselves).

### New in 0.18.0
- **The BMC Atlas mod is told what changed, instead of asking.** Through the game connection (channel `bmcatlas:sync`), the plugin sends the BMC Atlas client mod who holds each city of the live wars and the bridges the staff opened or closed, only when something changes, and which site data the server uses (so the mod downloads `territories.json` only when the site changed). Players with the mod (1.2.4 or newer) then stop asking dynmap for the war markers every few seconds and the site every half hour. Players without the mod get nothing; older mods keep working as before. `mod-sync.enabled: false` turns it off (new key, added to the config by itself).

### New in 0.17.1
- The war maps now come from the site's `maps.json`, the one list the Xaero's mod reads too (0.17.0 read a separate `worlds.json`; a config with that address is switched over by itself).

### New in 0.17.0
- **Plug and play: the war maps come from the site by themselves.** The plugin reads the site's list of maps (`site.worlds`, https://bmcmc.online/maps.json, the same list the Xaero's mod reads) and starts a war map for every map on it whose Minecraft world this server has (its `world`, else its `dynmapWorld`, else the server's main world). A new world on the site needs nothing done on the server; a world this server does not have downloads nothing; a world loaded later (Multiverse) starts at once. The `maps` section of `config.yml` is now only for changes: another Minecraft or dynmap world name, which wars to run, `enabled: false`, or a map the site does not list. Old `maps` entries keep working.
- **No more deleting `config.yml`.** Settings added by a new version are written into the existing `config.yml` with their default values; everything already set stays.
- **`/bmcwar maps`** (staff): every war map, where it comes from, whether its world is on this server, how many cities and live wars its data has, when it was last updated, and how many of its cities have a dynmap marker.

### New in 0.16.0
- **A bridge opened or closed with `/bmcwar bridge` now shows on dynmap and on the site.** The plugin swaps the bridge's dynmap icon (`BridgeOpenRed` <-> `BridgeClosedRed`, the colour stays), and the site's war map reads it from there within a few seconds. The bridge needs a marker on dynmap with a `Bridge...` or `Strait...` icon, and dynmap needs the `BridgeClosed...` icons; if one is missing, the console says so once. No need to edit `diplomacy.json` any more.

### New in 0.15.0
- **`/bmcwar bridge`: open and close Bridges and Straits (7.7.10-14).** `/bmcwar bridge` lists the war's bridges, open or closed, and who holds each end. `/bmcwar bridge <bridge> close` closes one: it no longer Connects its ends (7.7.11), so the cities across it may stop being In Play or become Encircled. `open` opens it again; `site` gives it back to what the site's map says. By the Laws only the side holding both ends may do it, between engagements (7.7.13): the plugin checks that, and `force` skips the check. The setting is kept in `plugins/BmcWar/bridges.yml` across restarts; set it in the site's `diplomacy.json` too if the site's map should show it.

### New in 0.14.0
- **No more Warzones.** Cities joined by a yellow line on the site's map are captured one by one, each in its own time; the line is just a Connection between them. Before, they were captured together as one unit.

### New in 0.13.1
- **Fix: standing inside a city's capture limit, the plugin said you were in another place.** A locality with no capture limit of its own reaches over its whole province, and the plugin took the first one it found. Now a city's own drawn limit always wins over a province or a round area. Seen in Cumania IV: inside Peshbron the plugin said "Unnamed3".
- Bridges and Straits in the site's data (`BridgeOpenRed`, `StraitClosed`...) are never cities. (The site also stopped exporting them as localities.)

### New in 0.13.0
- **Each city has a range of heights that count (7.8.4), instead of the underground depth.** A player below it (hiding underground) or above it (a skybridge) counts neither as attacker nor as resistance. It is worked out from each city's terrain: from the lowest ground on its land minus `capture.height.below-ground` (10) up to its highest building plus `capture.height.above-buildings` (30). Set a city by hand in `capture.height.cities` (`Name(Country): {min: 40, max: 160}`) for tunnels, bunkers or tall towers. `/bmcwar where` shows the city's range and your Y; `/bmcwar debug` says when you are outside it.
- **Delete `plugins/BmcWar/config.yml`** (or replace its `capture.underground` section by the new `capture.height`): `capture.underground` is no longer used.

### New in 0.12.1
- **Fix: being inside a building no longer counts as underground.** Depth now counts the solid blocks over the player's head only in layers at least 3 blocks thick: a roof and the floors of a building are thin, whatever they are made of (stone, terracotta, sandstone...), so inside a house, a hall or a tower is depth 0. The rock over a cave, a mine or a tunnel still counts. Before, a stone or terracotta roof more than 10 blocks up made the player "underground" and reset the capture.
- (0.12.0 was withdrawn: its dynmap drawing is not part of the plugin. Use this version.)

### New in 0.11.0 - the Referee's tools (7.10.4-7.10.6)
- **`/bmcwar engagement pause` / `resume`**: the Referee stops the engagement for a moment (a crash, a dispute): nothing can be captured and its time stands still; resumed, it goes on with the time it had left. Captures under way when it was paused start over.
- **`/bmcwar undo [war]`**: undoes the latest capture; its localities get back the icons they had just before. Again goes one further back (up to 50, since the server started). An engagement the capture ended (objective taken, capitulation) is not started again: start a new one.
- **War log**: every engagement called, begun, paused, resumed, stopped or over; every capture started, contested, cleared, abandoned or made, with who was standing inside; capitulations, objectives, undo, set and reset - with the time. `/bmcwar log [war] [lines]` shows the latest lines; the whole of it is in `plugins/BmcWar/logs/<war>.log`.

### New in 0.10.2
- **Fix: a capital drawn as a Locality fell at once.** A capital that is not a Settlement is a Fort (15 s, 5.6.7), but with the config's times its kind's name ("Locality") was matched first and gave it 0 s. Siesta (the Diarchy's capital) and the like now take 15 s.

### New in 0.10.1
- **Resistance resets the clock, as the Laws say.** A Fort is only taken after 15 s, a Settlement after 30 s, of no enemy presence (7.8.5-6): when the defenders leave a contested city, the attackers' time starts over. Before, it went on from where it stopped, so a city nearly taken before the defenders came fell the second they left. A Locality is still taken the moment the resistance is gone (7.8.3). If your config says `on-resistance: pause`, change it to `reset` (or delete the config).

### New in 0.10.0
- **Tactical Campaigns, Assaults and Raids end when their objective is taken.** As soon as one side holds every target, having taken at least one of them since the engagement began, it is over: the chat says which side took it, and everyone in the war or on its map sees it on screen. In a Raid, that is the attackers capturing the Fort (7.17.10); the Fort then goes back as always (7.17.13). A Raid's defender standing in its own Fort ends nothing. `engagement.end-on-objective: false` keeps them going until their time runs out.

### New in 0.9.0
- **Capitulation is announced.** When a nation of the war Capitulates (its capital taken and its score over 100, 7.9.7), the chat says so and everyone fighting in the war or standing on its map sees it on screen. `announce.capitulated: false` turns it off.
- **A capitulation ends the engagement.** The engagement going on is over the moment a nation Capitulates (a Raid's fort goes back as usual); the staff starts the next one. `engagement.end-on-capitulation: false` keeps it going.
- A nation that had already capitulated when the server starts is not announced again.

### New in 0.8.0
- **Several maps at once.** The new `maps` section lists every world wars are fought in, each with its own `territories.json` from the site (cities, limits, wars, countries) and its own dynmap world; each map has its own front, engagements and captures, and they all run at the same time (Gaia and an event map, say). Only players standing in a map's world count there. A war's ready check does not ask players who are in another map's world. `/bmcwar where` and `/bmcwar status` say which map; when two maps have wars with the same id, commands take `map:id`.

### New in 0.7.0
- **An engagement only begins when everyone is ready.** `/bmcwar engagement start` now calls it and waits: nothing can be captured and its time does not run until everyone asked has typed `ready`. Players who said `not ready` keep being asked and can say `ready` later; a player who leaves the server no longer holds it up. When the last one is ready, everyone sees "<kind> begins!" and its time starts. The staff can begin it anyway with `/bmcwar engagement go`. `engagement.ready-check.wait-for-all: false` goes back to the check only informing (new key; the default applies without it).

### New in 0.6.0
- **Capitulation table (`/bmcwar table`)**: a sidebar each player turns on and off for themselves, showing every nation of the war with an engagement going on and how close it is to Capitulating (its score out of 100, 7.9), marked when its capital is taken or it has capitulated, plus the time left in the engagement. Tags and nametag colours keep showing while it is on; if another plugin changes your scoreboard, the table turns itself off.

### New in 0.5.1
- **The ready check is only for who is online:** a player who leaves the server is no longer asked and drops out of the count (the staff is told); a player with a side who comes online while the check is still open is asked a few seconds after joining.

### New in 0.5.0
- **Ready check:** when an engagement starts, everyone with a side in the war gets a title on screen asking whether they are ready; they answer by typing `ready` or `not ready` in the chat. The answer stays out of the public chat, is confirmed on their screen and reported to the staff (`bmcwar.admin`) and the console. Those who do not answer are asked again every 20 s for 2 minutes. The summary (who is ready per side, who is not, who did not answer) is in `/bmcwar status` and `/bmcwar engagement`. Settings: `engagement.ready-check` (new keys; the defaults apply without them).

### New in 0.4.1
- **Jumping, falling, wind charges and maces count as normal.** Flying (fly) or gliding (elytra) only takes a player out of a capture after `capture.air-grace-seconds` (3) in a row, so an elytra opened by mistake when jumping off a tower no longer stops the count.
- **`/bmcwar debug`**: shows above your hotbar, every second, whether you count for captures and why not. `/bmcwar where` says it too.

### New in 0.4.0 - the Laws of the Game, Law 7
- **Times by category (7.8.5-6):** a Settlement (Small, Medium, Large, Colonial) 30 s, a Fort that is not a Settlement 15 s (drawn as a Fort, designated "Fort" on the site, or a non-Settlement capital), anything else at once.
- **In Play (7.8.1):** only a locality Connected to one held by another side may be captured. The Connections come from the site's map (its exporter now writes them into `territories.json`), through open Bridges and Straits and across neutral land (short circuits), exactly as the site's War mapmode works them out.
- **Encirclement (7.8.9-10):** pockets cut off from every capital of their side put nothing In Play, except a locality whose capture breaks the encirclement. Sea routes are not worked out yet.
- **Warzones (7.8.7):** Forts joined by a yellow line are captured as one.
- **Theater (7.1.2-7.1.4):** a Restricted War is only fought over its Theater.
- **Engagements (7.8.8):** captures only happen during an engagement: `/bmcwar engagement start <strategic|tactical|assault|raid|free> [cities...]`, `stop`, `status`. Each ends by itself after the Laws' time; a Raid's Fort goes back to its holder at the end (7.17.13). `rules.require-engagement: false` turns this off.
- **Capitulation (7.9)** scores in `/bmcwar status`; `/bmcwar where` says whether a locality is In Play, encircled, in a warzone, and whether it can be captured now.

### New in 0.3.0
- **A player's country comes from their tag** (scoreboard team), matched against the countries on the site. A vassal fights for its overlord: a Santana tag is on Greneshire's side. `players.side-from: site` goes back to the site's population lists; `players.team-aliases` maps team names that are not a country's name.
- **Underground players do not count**, neither as attackers nor as resistance, when more than `capture.underground.max-depth` (10) blocks below the natural ground. Buildings are not ground (inside a house is depth 0). Cities with tunnels or bunkers get their own depth in `capture.underground.cities`.
- **`/bmcwar reset [war] confirm`**: the fronts back to the start of the war, every city with its own side, no capture under way.
- `/bmcwar where` shows your country (and whether it comes from your tag) and how deep underground you are.

### New in 0.2.1
- **A war colour stuck on a city's type is ignored.** Some cities on the site carry the dynmap colour in their type (`SmallRed`, `FortBlue`, `LargeCapitalRed`); they were taken at once instead of in their time (Small 30 s, Fort 15 s) and capitals were not seen as capitals. Who holds a city still comes from its dynmap icon, as before.

### New in 0.2.0
- **Capture limits drawn on the site.** A city with a limit drawn in the site's `gaia/capture.svg` (the `zones` in `territories.json`) is captured inside that limit instead of its province. Cities without one keep their province, as before.
- `/bmcwar where` says when you are inside a drawn capture limit.

### Since 0.1.0
- Cities, wars, sides and players come from `https://bmcmc.online/gaia/territories.json`.
- A side that stays inside a city's land for its time (City 30 s, Fort 15 s, Locality/Colonial at once), with no opponent inside, takes it; the dynmap icon changes colour (Red / Blue / Green / Contested).
- Only cities of countries in the war can be taken; flying, spectating, vanished and airborne-vehicle players do not count.
- Cities left contested are put back on shutdown and `/bmcwar reload`.
- Commands: `/bmcwar status | where | set <city> <state> | reload`.
