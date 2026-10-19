Paper 1.21.11 (Java 21). Put the jar in `plugins/` (replace the old one); dynmap is recommended (without it captures work but nothing shows on the map). New config keys (`capture.underground`, `players`): the old `plugins/BmcWar/config.yml` keeps working with the defaults; delete it to get the new keys with their comments.

**Not yet tested on a real server.** Try it with a test war first, with `bmcwar.bypass` for the staff, and check `/bmcwar where` and `/bmcwar status`.

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
