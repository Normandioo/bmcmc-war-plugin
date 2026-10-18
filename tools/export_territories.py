#!/usr/bin/env python3
"""Exports a world's territories from its latest.svg to <world>/territories.json,
in Minecraft block coordinates, for the Xaero's addon mod. Besides the shapes it carries each
country's panel data (type, leader, government, ethnicity, population, diplomacy) and the
localities with their position, so the mod can show what the site shows.

    python tools/export_territories.py gaia
    python tools/export_territories.py gaia --dimension minecraft:overworld

Everything it needs comes from timeline-config.json: the SVG, the countries file
and the "dynmap" entry (center + size) that already maps the SVG onto blocks:

    block = center + (svgPoint - viewBoxOrigin) * size / viewBoxWidth - size / 2

Only straight-line paths (M L H V Z, absolute or relative) are supported, which
is all the territory paths use; anything else stops the export with an error
instead of producing wrong shapes.
"""
import argparse, base64, collections, datetime, json, math, re, sys, urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

SVG = '{http://www.w3.org/2000/svg}'
TOKEN = re.compile(r'([MmLlHhVvZzCcSsQqTtAa])|(-?\d*\.?\d+(?:[eE][-+]?\d+)?)')


def parse_path(d):
    """Path data -> list of rings [(x, y), ...] (each subpath is one ring)."""
    tokens = [(m.group(1), m.group(2)) for m in TOKEN.finditer(d)]
    rings, ring = [], []
    cmd, i = None, 0
    x = y = sx = sy = 0.0

    def nums(n):
        nonlocal i
        vals = []
        for _ in range(n):
            if i >= len(tokens) or tokens[i][1] is None:
                raise ValueError('malformed path data')
            vals.append(float(tokens[i][1])); i += 1
        return vals

    while i < len(tokens):
        if tokens[i][0]:
            cmd = tokens[i][0]; i += 1
            if cmd in 'Zz':
                if ring: rings.append(ring)
                ring = []; x, y = sx, sy
                continue
        elif cmd is None:
            raise ValueError('path data starts with a number')
        if cmd in 'MmLl':
            dx, dy = nums(2)
            if cmd in 'ml': dx += x; dy += y
            x, y = dx, dy
            if cmd in 'Mm':
                if ring: rings.append(ring)
                ring = [(x, y)]; sx, sy = x, y
                cmd = 'L' if cmd == 'M' else 'l'   # extra pairs after M are lineto
            else:
                ring.append((x, y))
        elif cmd in 'Hh':
            (v,) = nums(1); x = v + x if cmd == 'h' else v; ring.append((x, y))
        elif cmd in 'Vv':
            (v,) = nums(1); y = v + y if cmd == 'v' else v; ring.append((x, y))
        else:
            raise ValueError(f'unsupported path command {cmd!r}')
    if ring: rings.append(ring)
    return rings


def translation(attr):
    """transform="translate(a,b)" -> (a, b); None -> (0, 0); anything else is an error."""
    if not attr: return 0.0, 0.0
    m = re.fullmatch(r'\s*translate\(\s*(-?[\d.eE+-]+)[ ,]+(-?[\d.eE+-]+)\s*\)\s*', attr)
    if not m: raise ValueError(f'unsupported transform {attr!r}')
    return float(m.group(1)), float(m.group(2))


def style_fill(style):
    m = re.search(r'(?:^|;)\s*fill\s*:\s*(#[0-9a-fA-F]{6})', style or '')
    return m.group(1).lower() if m else None


def walk(el, tx, ty, tags=('path',)):
    for child in el:
        dx, dy = translation(child.get('transform'))
        if child.tag in [SVG + t for t in tags]:
            yield child, tx + dx, ty + dy
        elif child.tag == SVG + 'g':
            yield from walk(child, tx + dx, ty + dy, tags)


INKSCAPE_LABEL = '{http://www.inkscape.org/namespaces/inkscape}label'
ZONE_ID = re.compile(r'zone__(.+?)-lp-(.+?)-rp-.*')
ZONE_LABEL = re.compile(r'\s*(.+?)\s*\(\s*(.+?)\s*\)\s*')


def zone_shapes(el, tx, ty, warn):
    """The capture-limit SVG: every path, polygon, rect, circle or ellipse with its translation. Unlike the
    territory SVG, a shape that cannot be read is skipped with a warning instead of stopping the export."""
    for child in el:
        try:
            dx, dy = translation(child.get('transform'))
        except ValueError as e:
            warn(f'{child.get("id") or child.tag}: {e} (skipped)'); continue
        tag = child.tag[len(SVG):] if child.tag.startswith(SVG) else child.tag
        if tag in ('path', 'polygon', 'rect', 'circle', 'ellipse'):
            yield child, tag, tx + dx, ty + dy
        elif tag == 'g':
            yield from zone_shapes(child, tx + dx, ty + dy, warn)


def zone_rings(el, tag):
    """One capture-limit shape -> rings in SVG units. Circles and ellipses become 32-sided polygons."""
    if tag == 'path':
        return parse_path(el.get('d', ''))
    if tag == 'polygon':
        v = [float(n) for n in re.findall(r'-?\d*\.?\d+(?:[eE][-+]?\d+)?', el.get('points', ''))]
        return [list(zip(v[0::2], v[1::2]))]
    if tag == 'rect':
        x, y, w, h = (float(el.get(k, 0)) for k in ('x', 'y', 'width', 'height'))
        return [[(x, y), (x + w, y), (x + w, y + h), (x, y + h)]]
    cx, cy = float(el.get('cx', 0)), float(el.get('cy', 0))
    rx = float(el.get('r') if tag == 'circle' else el.get('rx', 0))
    ry = float(el.get('r') if tag == 'circle' else el.get('ry', 0))
    return [[(cx + rx * math.cos(2 * math.pi * i / 32), cy + ry * math.sin(2 * math.pi * i / 32)) for i in range(32)]]


def inside(rings, x, z):
    """Even-odd point-in-polygon over flat rings [x0, z0, x1, z1, ...], like the plugin's Polygon."""
    hit = False
    for r in rings:
        n = len(r) // 2
        for i in range(n):
            j = (i - 1) % n
            xi, zi, xj, zj = r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1]
            if (zi > z) != (zj > z) and x < (xj - xi) * (z - zi) / (zj - zi) + xi: hit = not hit
    return hit


def capture_zones(svg_path, to_block, localities, expected_vb):
    """Each city's capture limit from the capture SVG, in blocks: [{name, country, rings}]. A shape is tied to a
    city by its id "zone__Name-lp-Country-rp-" (like the markers) or its Inkscape label "Name(Country)".
    Several shapes for the same city are combined (even-odd, so a shape inside another is a hole).
    The SVG must have the territory SVG's viewBox, or every limit would land in the wrong place."""
    try:
        tree = ET.parse(svg_path).getroot()
        vb = [float(v) for v in (tree.get('viewBox') or '').replace(',', ' ').split()]
    except (ET.ParseError, ValueError) as e:
        print(f'  capture limits: {svg_path} cannot be read ({e}); no capture limits exported')
        return []
    if vb != expected_vb:
        print(f'  capture limits: {svg_path} has viewBox {vb}, not {expected_vb} like the territory SVG; no capture limits exported')
        return []
    by_key = {(plain(l['name']), l['country']): l for l in localities}
    zones, warnings = {}, []
    warn = warnings.append
    for el, tag, tx, ty in zone_shapes(tree, 0.0, 0.0, warn):
        ident = el.get('id') or ''
        m = ZONE_ID.fullmatch(ident) or ZONE_LABEL.fullmatch(el.get(INKSCAPE_LABEL) or '')
        if not m:
            warn(f'{ident or tag}: not tied to a city (id "zone__Name-lp-Country-rp-" or label "Name(Country)"), skipped'); continue
        loc = by_key.get((plain(m.group(1)), m.group(2)))
        if not loc:
            warn(f'{ident}: no locality {m.group(1)}({m.group(2)}) on the map, skipped'); continue
        try:
            rings = zone_rings(el, tag)
        except (ValueError, TypeError) as e:
            warn(f'{ident}: {e} (draw it with straight lines only), skipped'); continue
        flat = []
        for ring in rings:
            pts = [to_block(px + tx, py + ty) for px, py in ring]
            if len(pts) > 1 and pts[0] == pts[-1]: pts.pop()
            if len(pts) >= 3: flat.append([v for p in pts for v in p])
        if not flat:
            warn(f'{ident}: has no area, skipped'); continue
        key = (loc['name'], loc['country'])
        zones.setdefault(key, {'name': loc['name'], 'country': loc['country'], 'rings': []})['rings'].extend(flat)

    out = list(zones.values())
    for z in out:
        loc = by_key[(plain(z['name']), z['country'])]
        if not inside(z['rings'], loc['x'] + 0.5, loc['z'] + 0.5):
            warn(f'{z["name"]}({z["country"]}): its marker is outside its own capture limit')
    # two limits that share ground: someone standing there counts for both cities
    def box(z):
        xs = [v for r in z['rings'] for v in r[0::2]]; zs = [v for r in z['rings'] for v in r[1::2]]
        return min(xs), min(zs), max(xs), max(zs)
    boxes = [box(z) for z in out]
    for i in range(len(out)):
        for j in range(i + 1, len(out)):
            a, b = boxes[i], boxes[j]
            if a[2] < b[0] or b[2] < a[0] or a[3] < b[1] or b[3] < a[1]: continue
            pa = [(r[k], r[k + 1]) for r in out[i]['rings'] for k in range(0, len(r), 2)]
            pb = [(r[k], r[k + 1]) for r in out[j]['rings'] for k in range(0, len(r), 2)]
            if any(inside(out[j]['rings'], x, z) for x, z in pa) or any(inside(out[i]['rings'], x, z) for x, z in pb):
                warn(f'{out[i]["name"]}({out[i]["country"]}) and {out[j]["name"]}({out[j]["country"]}): their capture limits overlap')
    for w in warnings: print(f'  capture limits: {w}')
    return out


def as_list(value):
    if value is None: return []
    return [str(v) for v in value] if isinstance(value, list) else [str(value)]


def load_json(path):
    return json.loads(path.read_text(encoding='utf8')) if path.exists() else {}


def plain(name):
    return re.sub(r'[^A-Za-z0-9]', '', name)


def _get(url, timeout=20):
    req = urllib.request.Request(url, headers={'User-Agent': 'cumania-territories-export'})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def skin_target(record, name):
    """What to look the skin up by: the player's "skin" (a NameMC link, a UUID or a name), else their "ign", else their site name."""
    ref = str(record.get('skin') or '').strip()
    m = re.search(r'namemc\.com/(?:[a-z]{2}/)?profile/([^/?#\s]+)', ref)
    if m: ref = m.group(1)
    return ref or str(record.get('ign') or '').strip() or name


def download_skin(target, dest):
    """Mojang's own skin texture for a name or UUID, saved as a 64x64/64x32 PNG. False when it cannot be had."""
    try:
        uid = target.replace('-', '')
        if not re.fullmatch(r'[0-9a-fA-F]{32}', uid):
            uid = json.loads(_get('https://api.mojang.com/users/profiles/minecraft/' + target))['id']
        profile = json.loads(_get('https://sessionserver.mojang.com/session/minecraft/profile/' + uid))
        prop = next(p for p in profile['properties'] if p['name'] == 'textures')
        url = json.loads(base64.b64decode(prop['value']))['textures']['SKIN']['url']
        data = _get(url)
        if not data.startswith(b'\x89PNG'): return False
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        return True
    except Exception as e:
        print(f'  no skin for {target}: {e}')
        return False

PURGE_TIMERS = [(5, 30), (10, 25), (15, 20), (20, 15), (25, 10), (float('inf'), 5)]   # [up to this many Members, days], as js/purge.js


def realm_info(meta, diplomacy):
    """For every nation: its realm's root (the independent nation above it) and that realm's population, like the site's purge timer."""
    confed = diplomacy.get('confederations') or {}

    def parent(n):
        d = diplomacy.get(n)
        if isinstance(d, dict) and d.get('overlord'): return d['overlord'][0].lstrip('@')
        return next((c for c, members in confed.items() if n in members), None)

    def own(n):
        info = meta.get(n, {})
        return set(as_list(info.get('population'))) | set(as_list(info.get('leader')))

    children = collections.defaultdict(list)
    for n in set(meta) | {k for k, v in diplomacy.items() if isinstance(v, dict) and k not in confed}:
        if parent(n): children[parent(n)].append(n)

    def total(n, seen=()):
        if n in seen: return set()
        out = set(own(n))
        for c in children.get(n, []): out |= total(c, seen + (n,))
        return out

    def root(n):
        seen = set()
        while parent(n) and n not in seen: seen.add(n); n = parent(n)
        return n

    return root, total, confed


def current_affiliations(name, meta, cliques, shown):
    key = name.lower()
    out = []
    for nation, info in meta.items():
        if any(p.lower() == key for p in as_list(info.get('leader'))): out.append(f'{shown(nation)} - Leader')
        elif any(p.lower() == key for p in as_list(info.get('population'))): out.append(f'{shown(nation)} - Member')
    for cid, c in cliques.items():
        if cid.startswith('_') or not isinstance(c, dict): continue
        label = c.get('displayName') or cid
        if str(c.get('warlord', '')).lower() == key: out.append(f'{label} - Warlord')
        elif any(str(a).lower() == key for a in c.get('affiliates', [])): out.append(f'{label} - Affiliate')
    return out


def player_extras(names, players_file, folder, meta, diplomacy, cliques, refresh=False):
    """The site's player records (players.json) for the given people, plus their skins saved under <folder>/skins."""
    table = {k.lower(): v for k, v in (players_file.get('players') or {}).items() if not k.startswith('_')}
    out = {}
    root_of, total_of, confed = realm_info(meta, diplomacy)
    shown = lambda n: meta.get(n, {}).get('displayName') or n
    for name in names:
        record = table.get(name.lower())
        if not record: continue
        entry = {}
        current = current_affiliations(name, meta, cliques, shown)
        if current: entry['current'] = current
        home = next((n for n, info in meta.items() if n not in confed and name.lower() in
                     {p.lower() for p in as_list(info.get('population')) + as_list(info.get('leader'))}), None)
        if home:
            root = root_of(home); population = len(total_of(root))
            entry['purge'] = {'realm': shown(root), 'population': population, 'days': next(d for m, d in PURGE_TIMERS if population <= m)}
        if record.get('immune'): entry['immune'] = str(record['immune']).lower() if record['immune'] is not True else 'true'
        for key in ('ign', 'country', 'lastJoined', 'notes'):
            if record.get(key): entry[key] = str(record[key])
        affs = []
        for a in record.get('affiliations') or []:
            if isinstance(a, str): affs.append(a); continue
            text = a.get('nation', '')
            if a.get('role'): text += f" - {a['role']}"
            if a.get('period'): text += f" ({a['period']})"
            if text: affs.append(text)
        if affs: entry['affiliations'] = affs
        dest = folder / 'skins' / (plain(name) + '.png')
        if refresh or not dest.exists():
            print(f'skin: {name}')
            download_skin(skin_target(record, name), dest)
        if dest.exists(): entry['skin'] = 'skins/' + dest.name
        out[name] = entry
    return out


def country_extras(name, meta, diplomacy, world=''):
    """Panel data for one country, as the site stores it (the site's derived relations are not reproduced)."""
    info = meta.get(name, {})
    out = {}
    for key in ('displayName', 'type', 'officialName', 'formed', 'government', 'demonym', 'ethnicity', 'capital'):
        if info.get(key): out[key] = str(info[key])
    flag = str(info.get('flag') or '')
    if flag:   # relative to the folder territories.json lives in ("gaia/flags/X.png" -> "flags/X.png")
        out['flag'] = flag[len(world) + 1:] if world and flag.startswith(world + '/') else flag
    if as_list(info.get('leader')): out['leader'] = ', '.join(as_list(info['leader']))

    people = list(dict.fromkeys(as_list(info.get('population')) + as_list(info.get('leader'))))
    dip = diplomacy.get(name, {}) if isinstance(diplomacy.get(name), dict) else {}
    for sub in dip.get('subject', []):
        sub = sub.lstrip('@')
        subinfo = meta.get(sub, {})
        for person in as_list(subinfo.get('population')) + as_list(subinfo.get('leader')):
            if person not in people: people.append(person)
    result = {'info': out}
    if people: result['population'] = people

    def shown(n):
        n = n.lstrip('@')
        return meta.get(n, {}).get('displayName') or n

    relations = {}
    for key in ('overlord', 'subject', 'allies', 'nap'):
        if dip.get(key): relations[key] = [shown(n) for n in dip[key]]
    alliances = [a.get('displayName') or key for key, a in (diplomacy.get('alliances') or {}).items()
                 if any(m.lstrip('@') == name for m in a.get('members', []))]
    if alliances: relations['alliances'] = alliances
    wars = []
    for key, war in (diplomacy.get('wars') or {}).items():
        if war.get('ended'): continue
        for side, label in (('attackers', 'attacker'), ('defenders', 'defender')):
            if any(m.lstrip('@') == name for m in war.get(side, [])):
                wars.append({'name': war.get('displayName') or key, 'side': label})
    if wars: relations['wars'] = wars
    if relations: result['diplomacy'] = relations
    return result


# ---------------------------------------------------------------------------------------------
# Wars, alliances and diplomatic timers (what js/wars.js shows), for the mod's war menu
# ---------------------------------------------------------------------------------------------
WAR_TYPES = {
    'total': ('Total War', 'Unrestricted: all territory of every belligerent (7.2)'),
    'colonial': ('Colonial War', 'Restricted to one Hinterland or a group of Colonies (7.3)'),
    'civil': ('Civil War', 'Between cliques of the same nation, on its territory (7.4)'),
    'independence': ('Independence War', 'Over the territory being Seceded (7.5)'),
    'succession': ('Succession War', 'Over the territory of a Collapsed nation (7.6)'),
}
MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december']
WORLD_DATE = re.compile(r'^(?:([A-Za-z]+)\s+(\d+)(?:st|nd|rd|th)?,?\s+)?(\d+)\s*([A-Za-z]+)(?:\s+(\d{1,2}):(\d{2}))?$')


def when(value, clock=None):
    """A date as the site writes it (ISO, or a world date like "March 3rd, 868 AES") -> UTC datetime, or None."""
    if value is None or value is False or str(value).strip() == '': return None
    text = str(value).strip()
    try:
        dt = datetime.datetime.fromisoformat(text.replace('Z', '+00:00'))
        return (dt if dt.tzinfo else dt.replace(tzinfo=datetime.timezone.utc)).astimezone(datetime.timezone.utc)
    except ValueError:
        pass
    m = WORLD_DATE.match(text)
    if not m or not clock: return None
    month = MONTHS.index(m.group(1).lower()) + 1 if m.group(1) else 1
    if m.group(1) and m.group(1).lower() not in MONTHS: return None
    day = int(m.group(2)) if m.group(2) else 1
    year, era = int(m.group(3)), m.group(4)
    hour, minute = int(m.group(5) or 0), int(m.group(6) or 0)
    zero = datetime.datetime.fromisoformat(clock['yearZero']).replace(tzinfo=datetime.timezone.utc)
    days = year if era.lower() == str(clock.get('era', 'AES')).lower() else -year
    return zero + datetime.timedelta(days=days, hours=(month - 1) * 2 + (day - 1) // 15,
                                     minutes=((day - 1) % 15) * 4 + hour // 6, seconds=(hour % 6) * 10 + minute // 6)


def iso_z(dt):
    return dt.strftime('%Y-%m-%dT%H:%M:%SZ') if dt else None


def war_block(meta, diplomacy, countries, localities, clock):
    """The "war" part of territories.json. Everything is stored with absolute times, so the mod works out what is still running."""
    confed = diplomacy.get('confederations') or {}
    keys = {plain(k).lower(): k for k in countries}
    strip = lambda n: str(n).lstrip('@')

    def resolve(name):
        return keys.get(plain(strip(name)).lower())

    def shown(name):
        key = resolve(name) or strip(name)
        return meta.get(key, {}).get('displayName') or key

    def expand_at(name, visited=None):
        visited = visited if visited is not None else set()
        if name in confed: return list(confed[name])
        if name in visited: return [name]
        visited.add(name)
        out = [name]
        d = diplomacy.get(name)
        for sub in (d.get('subject', []) if isinstance(d, dict) else []):
            for m in (expand_at(sub[1:], visited) if sub.startswith('@') else [sub]):
                if m not in out: out.append(m)
        return out

    def expand(entry):
        name = strip(entry)
        if name in confed: return list(confed[name])
        return expand_at(name) if str(entry).startswith('@') else [name]

    def member(entry):
        name = strip(entry)
        d = diplomacy.get(name)
        note = 'and subjects' if str(entry).startswith('@') and name not in confed and isinstance(d, dict) and d.get('subject') else ''
        if name in confed: note = 'confederation'
        out = {'name': shown(name)}
        if note: out['note'] = note
        cs = []
        for n in expand(entry):
            k = resolve(n)
            if k and k not in cs: cs.append(k)
        out['countries'] = cs
        return out

    loc_by_id = {f"{l['name']}({l['country']})": l for l in localities}
    wars = []
    for wid, war in (diplomacy.get('wars') or {}).items():
        t = re.sub(r'war$', '', re.sub(r'[^a-z]', '', str(war.get('type') or 'total').lower())) or 'total'
        label, rule = WAR_TYPES.get(t, WAR_TYPES['total'])
        raw_sides = war.get('sides') or [{'name': 'Attackers', 'members': war.get('attackers', [])},
                                         {'name': 'Defenders', 'members': war.get('defenders', [])}]
        sides = []
        for i, sd in enumerate(raw_sides):
            side = {'name': sd.get('name') or f'Side {i + 1}', 'members': [member(m) for m in as_list(sd.get('members'))]}
            if sd.get('color'): side['color'] = str(sd['color'])
            sides.append(side)
        captured = []
        for lid, by in (war.get('captured') or {}).items():
            loc = loc_by_id.get(lid)
            if not loc: continue
            word = re.sub(r'[^a-z]', '', str(by or '').lower())
            entry = {'name': loc['name'], 'country': loc['country'], 'x': loc['x'], 'z': loc['z']}
            if word in ('contested', 'uncontrolled'): entry['state'] = word
            else: entry['state'] = 'held'; entry['holder'] = shown(by); entry['holderKey'] = resolve(by) or strip(by)
            captured.append(entry)
        out = {'id': wid, 'name': war.get('displayName') or re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', wid), 'type': label, 'rule': rule,
               'started': iso_z(when(war.get('started'), clock)), 'ended': iso_z(when(war.get('ended'), clock)),
               'sides': sides, 'captured': captured}
        if war.get('noAllies'): out['noAllies'] = True
        if war.get('live') and not war.get('map'): out['live'] = True   # the site reads who holds its cities from dynmap (js/warlive.js); the mod does the same
        theater = [t for t in as_list(war.get('theater')) if t.strip()]
        if theater: out['theater'] = ', '.join(theater)
        if war.get('notes'): out['notes'] = str(war['notes'])
        wars.append(out)

    alliances = []
    for aid, a in (diplomacy.get('alliances') or {}).items():
        joined = a.get('joined') or {}
        members = []
        for entry in as_list(a.get('members')):
            m = member(entry)
            when_joined = joined.get(entry) or joined.get(strip(entry))
            m['note'] = ('joined ' + str(when_joined)) if when_joined else ('founding member' if a.get('formed') else m.get('note', ''))
            if not m['note']: del m['note']
            members.append(m)
        item = {'id': aid, 'name': a.get('displayName') or re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', aid), 'members': members}
        for key in ('formed', 'officialName', 'color', 'notes', 'flag'):
            if a.get(key): item[key] = str(a[key])
        alliances.append(item)
    for b in diplomacy.get('bilateralAlliances') or []:
        ms = as_list(b.get('members'))
        if len(ms) == 2:
            alliances.append({'id': 'bilateral:' + '+'.join(ms), 'name': ' & '.join(shown(m) for m in ms),
                              'members': [member(m) for m in ms], **({'formed': str(b['formed'])} if b.get('formed') else {})})

    def pairs(table, ender):
        out = []
        for x, inner in (table or {}).items():
            for y, v in (inner or {}).items():
                out.append(ender(shown(x), shown(y), v))
        return out

    def truce(a, b, v):
        if isinstance(v, dict):
            frm = when(v.get('from'), clock); end = when(v.get('until'), clock) or (frm + datetime.timedelta(days=3) if frm else None)
            return {'a': a, 'b': b, 'from': iso_z(frm), 'until': iso_z(end)}
        return {'a': a, 'b': b, 'until': iso_z(when(v, clock))}

    out = {'wars': wars, 'alliances': alliances,
           'truces': [t for t in pairs(diplomacy.get('truceExpiry'), truce) if t.get('until')],
           'ultimatums': [u for u in pairs(diplomacy.get('ultimatums'), lambda a, b, v: {'from': a, 'to': b, 'sent': iso_z(when(v, clock))}) if u['sent']],
           'naps': [n for n in pairs(diplomacy.get('napExpiry'), lambda a, b, v: {'a': a, 'b': b, 'until': iso_z(when(v, clock))}) if n['until']]}
    locks = []
    for name, info in meta.items():
        if resolve(name) is None: continue
        founded = when(info.get('foundedOn'), clock)
        if founded: locks.append({'nation': shown(name), 'kind': 'Foundation', 'until': iso_z(founded + datetime.timedelta(days=3))})
        if info.get('diplolocked'):
            end = when(info.get('diplolockedUntil'), clock)
            locks.append({'nation': shown(name), 'kind': 'Set by Staff', **({'until': iso_z(end)} if end else {})})
    out['locks'] = locks
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('world', help='world key in timeline-config.json (e.g. gaia)')
    ap.add_argument('--root', default='.', help='site folder (default: current)')
    ap.add_argument('--dimension', default='minecraft:overworld')
    ap.add_argument('--refresh-skins', action='store_true', help='download every skin again (default: only missing ones)')
    ap.add_argument('--out', help='output file (default: <world folder>/territories.json)')
    args = ap.parse_args()

    root = Path(args.root)
    cfg = json.loads((root / 'timeline-config.json').read_text(encoding='utf8'))['worlds'][args.world]
    dyn = cfg.get('dynmap')
    if not dyn: sys.exit(f'"{args.world}" has no "dynmap" entry (center/size) in timeline-config.json')
    cx, cz = dyn.get('center', [0, 0]); size = dyn.get('size', 5000)

    svg_path = root / cfg['latestFile']
    tree = ET.parse(svg_path).getroot()
    vb = [float(v) for v in tree.get('viewBox').replace(',', ' ').split()]
    scale = size / vb[2]

    def to_block(px, py):
        return (round(cx + (px - vb[0]) * scale - size / 2),
                round(cz + (py - vb[1]) * (size / vb[3]) - size / 2))

    countries_file = root / cfg['countriesFile']
    meta = json.loads(countries_file.read_text(encoding='utf8')) if countries_file.exists() else {}
    diplomacy = load_json(root / cfg['diplomacyFile']) if cfg.get('diplomacyFile') else {}

    provinces, fills, skipped = [], collections.defaultdict(collections.Counter), 0
    for path, tx, ty in walk(tree, 0.0, 0.0):
        country = next((c for c in (path.get('class') or '').split() if c != 'territory'), None)
        if not country or not path.get('id'): skipped += 1; continue
        try:
            rings = parse_path(path.get('d', ''))
        except ValueError as e:
            sys.exit(f'{path.get("id")}: {e}')
        flat = []
        for ring in rings:
            if len(ring) < 3: continue
            pts = [to_block(px + tx, py + ty) for px, py in ring]
            if pts[0] == pts[-1]: pts.pop()
            if len(pts) >= 3: flat.append([v for p in pts for v in p])
        if not flat: continue
        provinces.append({'id': path.get('id'), 'country': country, 'rings': flat})
        fill = style_fill(path.get('style')) or path.get('fill')
        if fill: fills[country][fill.lower()] += 1

    countries = {}
    for name in sorted({p['country'] for p in provinces}):
        info = meta.get(name, {})
        countries[name] = {
            'name': info.get('officialName') or name,
            'color': fills[name].most_common(1)[0][0] if fills[name] else '#888888',
            **country_extras(name, meta, diplomacy, args.world),
        }

    # Localities: positions come from the SVG's markers, details from localities.json
    loc_file = cfg.get('localitiesFile')
    details = load_json(root / loc_file) if loc_file else {}
    by_key = {}
    for key, value in details.items():
        m = re.fullmatch(r'(.+)\((.+)\)', key)
        if m: by_key[(plain(m.group(1)), m.group(2))] = (m.group(1), m.group(2), value)
    localities = []
    for el, tx, ty in walk(tree, 0.0, 0.0, ('ellipse',)):
        m = re.fullmatch(r'marker__(.+?)-lp-(.+?)-rp-__(.+)', el.get('id') or '')
        if not m or (plain(m.group(1)), m.group(2)) not in by_key: continue   # bridges, straits...
        name, country, value = by_key[(plain(m.group(1)), m.group(2))]
        x, z = to_block(float(el.get('cx')) + tx, float(el.get('cy')) + ty)
        entry = {'name': name, 'country': country, 'kind': m.group(3), 'x': x, 'z': z}
        for field in ('development', 'lord', 'ethnicity'):
            if value.get(field): entry[field] = ', '.join(map(str, value[field])) if isinstance(value[field], list) else str(value[field])
        if value.get('designations'): entry['designations'] = [str(d) for d in value['designations']]
        localities.append(entry)

    # Capture limits (optional "captureFile"): the land a side must hold to take a city in a live war (the BmcWar
    # server plugin). A city with no limit drawn there keeps the province its marker stands in.
    zones = []
    if cfg.get('captureFile'):
        capture_path = root / cfg['captureFile']
        if capture_path.exists(): zones = capture_zones(capture_path, to_block, localities, vb)
        else: print(f'  capture limits: {capture_path} not found; none exported')

    # Players behind the countries' populations: the site's own records and their skins
    people = list(dict.fromkeys(n for c in countries.values() for n in c.get('population', [])))
    players_file = load_json(root / cfg['playersFile']) if cfg.get('playersFile') else {}
    folder = root / Path(cfg['latestFile']).parent
    cliques = load_json(root / cfg['cliquesFile']) if cfg.get('cliquesFile') else {}
    players = player_extras(people, players_file, folder, meta, diplomacy, cliques, args.refresh_skins)

    clock = cfg.get('clock')
    war = war_block(meta, diplomacy, countries, localities, clock)

    out = {
        'format': 1, 'world': args.world, 'dimension': args.dimension,
        'generated': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        'countries': countries, 'provinces': provinces, 'localities': localities,
    }
    if zones: out['zones'] = zones
    if players: out['players'] = players
    if war['wars'] or war['alliances'] or war['truces'] or war['ultimatums'] or war['naps'] or war['locks']: out['war'] = war
    target = Path(args.out) if args.out else root / Path(cfg['latestFile']).parent / 'territories.json'
    if target.exists():
        try:
            before = json.loads(target.read_text(encoding='utf8')); before.pop('generated', None)
            now = dict(out); now.pop('generated')
            if before == now:
                print(f'{target}: unchanged ({len(provinces)} provinces, {len(countries)} countries, {len(localities)} localities)')
                return
        except ValueError:
            pass
    target.write_text(json.dumps(out, separators=(',', ':'), ensure_ascii=False), encoding='utf8')
    print(f'{target}: {len(provinces)} provinces, {len(countries)} countries, '
          f'{len(localities)} localities, {len(zones)} capture limits, {target.stat().st_size / 1024:.0f} KB ({skipped} non-territory paths ignored)')


if __name__ == '__main__':
    main()
