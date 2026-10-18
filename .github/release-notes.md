Paper 1.21.11 (Java 21). Put the jar in `plugins/`; dynmap is recommended (without it captures work but nothing shows on the map).

**First release — not yet tested on a real server.** Try it with a test war first, with `bmcwar.bypass` for the staff, and check `/bmcwar where` and `/bmcwar status`.

- Cities, wars, sides and players come from `https://bmcmc.online/gaia/territories.json`.
- A side that stays inside a city's land for its time (City 30 s, Fort 15 s, Locality/Colonial at once), with no opponent inside, takes it; the dynmap icon changes colour (Red / Blue / Green / Contested).
- Only cities of countries in the war can be taken; flying, spectating, vanished and airborne-vehicle players do not count.
- Cities left contested are put back on shutdown and `/bmcwar reload`.
- Commands: `/bmcwar status | where | set <city> <state> | reload`.
