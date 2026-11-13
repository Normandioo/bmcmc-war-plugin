#!/usr/bin/env python3
"""Builds the resource pack of the news menu (NewsMenu.java) and of the news window (NewsDialog.java) from the art in art/gazeta/png
and the flags of the site.

    python tools/build_pack.py [out_dir] [--site PATH]       (default out_dir: build/pack; default site: ../Cumania, the site's repository)

Writes <out_dir>/src (the pack as folders), <out_dir>/bmcnews-pack.zip and prints its sha1 (for resource-pack-sha1 in server.properties).
It also writes src/main/resources/flags.json (country or alliance name -> the flag's character): the plugin reads it, so build the pack and
the plugin together. The zip is reproducible: the same art and flags always give the same sha1. Only the standard library is used.

What goes in the pack:
- font bmcnews:menu: the 176x222 background (U+E000) and a "space" character for every advance from -256 to 255 (U+F000 + advance + 256)
- fonts bmcnews:l<top>: Minecraft's own ascii font (not copied: the game has it), shifted so a line of text starts at y = <top> of the chest,
  plus the space (advance 4), which the game defines apart from ascii.png
- item models bmcnews:<name> for every icon and button (items are drawn over the background), and bmcnews:hidden (draws nothing: tooltips only)
- font bmcnews:dialog: the banner (U+E100, the masthead cut from the menu's background) and a divider (U+E101) for the news window
- font bmcnews:flags: one 12x8 character per flag (U+E300 and up), the site's flag inside the art's flag frame
"""
import argparse, hashlib, json, shutil, struct, sys, zipfile, zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / 'art' / 'gazeta' / 'png'
BASELINE = 13   # a chest's title is drawn at y = 6, its baseline 7 below
PACK_FORMAT = 75   # Minecraft 1.21.11
# Two 8 px lines in each of the 4 news cards (the slot of card r has its top at 18 + 18 r). Keep NewsMenu.LINE_TOPS the same.
LINE_TOPS = [36, 44, 54, 62, 72, 80, 90, 98]
BANNER = (8, 7, 168, 20)   # x0, y0, x1, y1 of the masthead inside news_menu.png
FLAG_FIRST = 0xE300        # the first flag's character; the plugin's Flags class reads src/main/resources/flags.json

parser = argparse.ArgumentParser()
parser.add_argument('out', nargs='?', default=str(ROOT / 'build' / 'pack'))
parser.add_argument('--site', default=str(ROOT.parent / 'Cumania'))
OPTS = parser.parse_args()
OUT = Path(OPTS.out)
SRC = OUT / 'src'


# ---------------------------------------------------------------- PNG (standard library only)

def png_write(path, w, h, rows):
    """rows: one bytes of w * 4 RGBA values per row."""
    raw = b''.join(b'\x00' + bytes(r) for r in rows)

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def png_read(path):
    """A PNG as (w, h, one bytearray of w * 4 RGBA values per row)."""
    w, h, pixels = png_rows(Path(path).read_bytes())
    return w, h, [bytearray(b''.join(bytes(p) for p in pixels(y))) for y in range(h)]


def png_rows(data):
    """Any non-interlaced PNG of 8 bits (or a palette): (w, h, pixels) where pixels(y) is the row's list of (r, g, b, a). Ask for the rows in order."""
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a PNG')
    pos, idat, plte, trns = 8, [], None, None
    while pos < len(data):
        n = int.from_bytes(data[pos:pos + 4], 'big'); tag = data[pos + 4:pos + 8]; body = data[pos + 8:pos + 8 + n]; pos += 12 + n
        if tag == b'IHDR': w, h, depth, ctype, _, _, interlace = struct.unpack('>IIBBBBB', body)
        elif tag == b'PLTE': plte = body
        elif tag == b'tRNS': trns = body
        elif tag == b'IDAT': idat.append(body)
    if interlace or depth == 16 or (depth != 8 and ctype != 3):
        raise ValueError('interlaced, 16-bit or unusual PNG')
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bits = depth * channels; bpp = max(1, bits // 8); stride = (w * bits + 7) // 8
    raw = zlib.decompress(b''.join(idat))
    state = {'i': 0, 'prev': bytearray(stride), 'y': -1, 'line': None}

    def unfilter():
        i = state['i']; kind = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); state['i'] = i + 1 + stride; prev = state['prev']
        if kind == 1:
            for x in range(bpp, stride): line[x] = (line[x] + line[x - bpp]) & 255
        elif kind == 2:
            line = bytearray((a + b) & 255 for a, b in zip(line, prev))
        elif kind == 3:
            for x in range(stride): line[x] = (line[x] + (((line[x - bpp] if x >= bpp else 0) + prev[x]) >> 1)) & 255
        elif kind == 4:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0; b = prev[x]; c = prev[x - bpp] if x >= bpp else 0
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        state['prev'] = line
        return line

    def pixels(y):
        while state['y'] < y:
            state['line'] = unfilter(); state['y'] += 1
        line = state['line']
        if ctype == 6: return [(line[x * 4], line[x * 4 + 1], line[x * 4 + 2], line[x * 4 + 3]) for x in range(w)]
        if ctype == 2: return [(line[x * 3], line[x * 3 + 1], line[x * 3 + 2], 255) for x in range(w)]
        if ctype == 0: return [(line[x], line[x], line[x], 255) for x in range(w)]
        if ctype == 4: return [(line[x * 2], line[x * 2], line[x * 2], line[x * 2 + 1]) for x in range(w)]
        out, mask = [], (1 << depth) - 1
        for x in range(w):
            k = (line[(x * depth) >> 3] >> (8 - depth - ((x * depth) & 7))) & mask
            out.append((plte[3 * k], plte[3 * k + 1], plte[3 * k + 2], trns[k] if trns and k < len(trns) else 255))
        return out
    return w, h, pixels


def downscale(path, tw, th):
    """The picture as tw x th RGBA pixels (bytes of 4), each the average of its part of the picture, colours weighted by their alpha."""
    w, h, pixels = png_rows(Path(path).read_bytes())
    sx, sy = max(1, w // (tw * 6)), max(1, h // (th * 6))   # a part of the picture is sampled at ~36 points: enough for a flag this small
    sums = [[0, 0, 0, 0, 0] for _ in range(tw * th)]
    for y in range(h):
        row = pixels(y)   # every row is read, in order: the PNG's filters depend on the row before
        if y % sy:
            continue
        cell = (y * th // h) * tw
        for x in range(0, w, sx):
            r, g, b, a = row[x]; s = sums[cell + x * tw // w]
            s[0] += r * a; s[1] += g * a; s[2] += b * a; s[3] += a; s[4] += 1
    return [bytes((s[0] // s[3], s[1] // s[3], s[2] // s[3], s[3] // s[4]) if s[3] else (0, 0, 0, 0)) for s in sums]


# ---------------------------------------------------------------- the pack

def dump(path, data):
    p = SRC / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, indent=1), encoding='utf-8')


def copy(source, target):
    (SRC / target).parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(source, SRC / target)


def flag_sources(site):
    """{name: flag file} of the countries and alliances of the site's worlds (the names the news use: a display name, or the key)."""
    site = Path(site)
    names = {}
    worlds = json.loads((site / 'timeline-config.json').read_text(encoding='utf-8')).get('worlds', {})
    for world in worlds.values():
        for file, alliances in ((world.get('countriesFile'), False), (world.get('diplomacyFile'), True)):
            if not file or not (site / file).exists():
                continue
            data = json.loads((site / file).read_text(encoding='utf-8'))
            for key, entry in (data.get('alliances', {}) if alliances else data).items():
                if not isinstance(entry, dict) or not entry.get('flag'):
                    continue
                flag = site / entry['flag']
                if not flag.exists():
                    flag = (site / file).parent / entry['flag']
                if not flag.exists():
                    continue
                for name in (key, entry.get('displayName')):
                    if name and name not in names:
                        names[name] = flag
    return names


def build_flags():
    """One 12x8 glyph per flag file (the flag inside the art's flag frame); returns {name: character code}."""
    frame_w, frame_h, frame = png_read(ART / 'flags' / 'flag_frame.png')
    sources = flag_sources(OPTS.site)
    cache = OUT / 'flag-cache'
    cache.mkdir(parents=True, exist_ok=True)
    glyphs, providers = {}, []
    for flag in sorted(set(sources.values()), key=lambda p: p.name.lower()):
        key = f'{flag.stem}-{flag.stat().st_size}-{int(flag.stat().st_mtime)}.bin'
        try:
            if (cache / key).exists():
                data = (cache / key).read_bytes()
                cells = [data[i:i + 4] for i in range(0, len(data), 4)]
            else:
                cells = downscale(flag, frame_w - 2, frame_h - 2)
                (cache / key).write_bytes(b''.join(cells))
        except Exception as e:   # a JPG or an unusual PNG: that flag is left out
            print(f'  no glyph for {flag.name}: {e}')
            continue
        rows = []
        for y in range(frame_h):
            row = bytearray(frame[y])
            for x in range(frame_w):
                if row[x * 4 + 3] == 0 and 1 <= x <= frame_w - 2 and 1 <= y <= frame_h - 2:   # the frame's hollow: the flag goes there
                    row[x * 4:x * 4 + 4] = cells[(y - 1) * (frame_w - 2) + (x - 1)]
            rows.append(bytes(row))
        code = FLAG_FIRST + len(glyphs)
        glyphs[flag] = code
        png_write(SRC / 'assets/bmcnews/textures/font/flags' / f'{flag.stem}.png', frame_w, frame_h, rows)
        providers.append({'type': 'bitmap', 'file': f'bmcnews:font/flags/{flag.stem}.png', 'ascent': 7, 'height': 8, 'chars': [chr(code)]})
    dump('assets/bmcnews/font/flags.json', {'providers': providers})
    return {name: glyphs[flag] for name, flag in sources.items() if flag in glyphs}


def build_dialog_font():
    """The banner (cut from the menu's masthead) and a divider, as characters of the news window's text."""
    w, h, rows = png_read(ART / 'menu' / 'news_menu.png')
    x0, y0, x1, y1 = BANNER
    png_write(SRC / 'assets/bmcnews/textures/font/banner.png', x1 - x0, y1 - y0, [bytes(rows[y][x0 * 4:x1 * 4]) for y in range(y0, y1)])
    line, jewel, clear = bytes((0xAF, 0xA4, 0x8F, 255)), bytes((0xEE, 0xEA, 0xE0, 255)), bytes((0, 0, 0, 0))
    divider = []
    for y in range(7):
        row = b''
        for x in range(160):
            d = abs(x - 79) + abs(y - 3)   # a small diamond in the middle, a line either side of it
            row += jewel if d <= 3 else clear if (y != 3 or d <= 5) else line
        divider.append(row)
    png_write(SRC / 'assets/bmcnews/textures/font/divider.png', 160, 7, divider)
    dump('assets/bmcnews/font/dialog.json', {'providers': [
        {'type': 'bitmap', 'file': 'bmcnews:font/banner.png', 'ascent': 7, 'height': y1 - y0, 'chars': ['']},
        {'type': 'bitmap', 'file': 'bmcnews:font/divider.png', 'ascent': 7, 'height': 7, 'chars': ['']}]})


def main():
    if not (ART / 'menu' / 'news_menu.png').exists():
        sys.exit(f'The art is not at {ART} (menu/news_menu.png).')
    shutil.rmtree(SRC, ignore_errors=True)

    # the menu font: the background and the spaces
    copy(ART / 'menu' / 'news_menu.png', 'assets/bmcnews/textures/font/news_menu.png')
    dump('assets/bmcnews/font/menu.json', {'providers': [
        {'type': 'space', 'advances': {chr(0xF000 + n + 256): n for n in range(-256, 256)}},
        {'type': 'bitmap', 'file': 'bmcnews:font/news_menu.png', 'ascent': BASELINE, 'height': 222, 'chars': ['']}]})

    # Minecraft's ascii font as 16 rows of 16 characters (ascii.png comes from the game, it is not part of the pack)
    nul = '\0' * 16
    rows = [nul, nul, ' !"#$%&\'()*+,-./', '0123456789:;<=>?', '@ABCDEFGHIJKLMNO', 'PQRSTUVWXYZ[\\]^_', '`abcdefghijklmno', 'pqrstuvwxyz{|}~\0'] + [nul] * 8
    assert len(rows) == 16 and all(len(r) == 16 for r in rows)
    for top in LINE_TOPS:
        # the game defines the space apart from ascii.png (a blank cell has no glyph), so without this provider a space has no width at all
        dump(f'assets/bmcnews/font/l{top}.json', {'providers': [{'type': 'space', 'advances': {' ': 4}},
                                                                   {'type': 'bitmap', 'file': 'minecraft:font/ascii.png', 'ascent': BASELINE - top, 'chars': rows}]})

    # every icon and button is an item model; "hidden" draws nothing
    for sub in ('icons', 'buttons'):
        for png in sorted((ART / sub).glob('*.png')):
            name = png.stem
            copy(png, f'assets/bmcnews/textures/item/{name}.png')
            dump(f'assets/bmcnews/items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'bmcnews:item/{name}'}})
            dump(f'assets/bmcnews/models/item/{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'bmcnews:item/{name}'}})
    dump('assets/bmcnews/items/hidden.json', {'model': {'type': 'minecraft:empty'}})

    build_dialog_font()
    codes = build_flags() if (Path(OPTS.site) / 'timeline-config.json').exists() else {}
    if not codes:
        print(f'No flags: the site is not at {OPTS.site} (use --site PATH).')
    flags_json = ROOT / 'src' / 'main' / 'resources' / 'flags.json'
    flags_json.parent.mkdir(parents=True, exist_ok=True)
    flags_json.write_text(json.dumps(dict(sorted(codes.items())), indent=1), encoding='utf-8')
    print(f'{len(set(codes.values()))} flag glyphs for {len(codes)} names -> {flags_json}')

    dump('pack.mcmeta', {'pack': {'pack_format': PACK_FORMAT, 'min_format': PACK_FORMAT, 'max_format': PACK_FORMAT, 'description': 'BMC news menu'}})
    zpath = OUT / 'bmcnews-pack.zip'
    with zipfile.ZipFile(zpath, 'w') as z:
        for f in sorted(p for p in SRC.rglob('*') if p.is_file()):
            info = zipfile.ZipInfo(f.relative_to(SRC).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))   # a fixed date: the same files, the same zip
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, f.read_bytes())
    print(f'{zpath}  {zpath.stat().st_size} bytes\nsha1 {hashlib.sha1(zpath.read_bytes()).hexdigest()}')


if __name__ == '__main__':
    main()
