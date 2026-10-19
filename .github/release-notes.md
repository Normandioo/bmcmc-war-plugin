Paper 1.21.11 (Java 21). Put the jar in `plugins/` (replace the old one); dynmap is recommended (without it captures work but nothing shows on the map). **Delete `plugins/BmcWar/config.yml`** before starting this version: `capture.seconds` now goes by Settlement / Fort / Locality, and there are new `rules` and `engagement` sections (the old file still loads, with the Laws' defaults and a warning).

**Not yet tested on a real server.** Try it with a test war first, with `bmcwar.bypass` for the staff, and check `/bmcwar where` and `/bmcwar status`.

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
