Paper 1.21.11 (Java 21). Put the jar in `plugins/` (replace the old one); dynmap is recommended (without it captures work but nothing shows on the map). No config key changed: the existing `plugins/BmcWar/config.yml` keeps working.

**Not yet tested on a real server.** Try it with a test war first, with `bmcwar.bypass` for the staff, and check `/bmcwar where` and `/bmcwar status`.

### New in 0.2.0
- **Capture limits drawn on the site.** A city with a limit drawn in the site's `gaia/capture.svg` (the `zones` in `territories.json`) is captured inside that limit instead of its province. Cities without one keep their province, as before.
- `/bmcwar where` says when you are inside a drawn capture limit.

### Since 0.1.0
- Cities, wars, sides and players come from `https://bmcmc.online/gaia/territories.json`.
- A side that stays inside a city's land for its time (City 30 s, Fort 15 s, Locality/Colonial at once), with no opponent inside, takes it; the dynmap icon changes colour (Red / Blue / Green / Contested).
- Only cities of countries in the war can be taken; flying, spectating, vanished and airborne-vehicle players do not count.
- Cities left contested are put back on shutdown and `/bmcwar reload`.
- Commands: `/bmcwar status | where | set <city> <state> | reload`.
