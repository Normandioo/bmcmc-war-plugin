# Gera os PNGs do menu "Gazeta Diplomática" (pixel art 1x). Só biblioteca padrão.
# Uso: python art/gazeta/make_art.py  -> art/gazeta/png/...
import math, os, struct, zlib

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'png')


def hexc(h):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


class Img:
    def __init__(s, w, h, fill=None):
        s.w, s.h = w, h
        s.p = [[fill] * w for _ in range(h)]

    def set(s, x, y, c):
        if 0 <= x < s.w and 0 <= y < s.h:
            s.p[y][x] = c

    def get(s, x, y):
        return s.p[y][x] if 0 <= x < s.w and 0 <= y < s.h else None

    def rect(s, x0, y0, x1, y1, c):  # inclusivo
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                s.set(x, y, c)

    def paste(s, o, ox, oy):
        for y in range(o.h):
            for x in range(o.w):
                if o.p[y][x] is not None:
                    s.set(ox + x, oy + y, o.p[y][x])

    def copy(s):
        n = Img(s.w, s.h)
        n.p = [row[:] for row in s.p]
        return n

    def scaled(s, k):
        n = Img(s.w * k, s.h * k)
        n.p = [[c for c in row for _ in range(k)] for row in s.p for _ in range(k)]
        return n

    def save(s, path):
        path = os.path.join(OUT, path)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        raw = b''.join(b'\x00' + b''.join(bytes(c or (0, 0, 0, 0)) for c in row) for row in s.p)

        def chunk(t, d):
            return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
        with open(path, 'wb') as f:
            f.write(b'\x89PNG\r\n\x1a\n'
                    + chunk(b'IHDR', struct.pack('>IIBBBBB', s.w, s.h, 8, 6, 0, 0, 0))
                    + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def draw(img, rows, ox, oy, cmap):
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in cmap:
                img.set(ox + x, oy + y, cmap[ch])


# ---------- paleta ----------
OUTL = hexc('2A231C')
INK = hexc('33291F')
SUB = hexc('615446')
PAPER = hexc('D6CFBF')
HI = hexc('F2EEE4')
SH = hexc('9C9080')
EDGE = hexc('CBC3B1')
SPECK_D = hexc('CDC5B3')
SPECK_L = hexc('E0DACC')
SHADE = hexc('AFA48F')
CARD = hexc('EEEAE0')
SLOT = hexc('C4BCAA')
SLOT_D = hexc('857A68')
RED = hexc('B0302A')

FAM = {  # escuro, médio, claro
    'red': ('7A1C18', 'B0302A', 'D8574A'),
    'green': ('2F5A22', '4E8A3A', '7DB560'),
    'teal': ('1F5A55', '2E8A80', '5EB8AC'),
    'blue': ('23407A', '3A64A8', '6A93D0'),
    'amber': ('9A6810', 'D89A20', 'F2C350'),
    'purple': ('50306E', '7A4A9E', 'A47CC8'),
    'gold': ('9A7414', 'E0B030', 'F8DC70'),
}
FAM = {k: tuple(hexc(c) for c in v) for k, v in FAM.items()}

# ---------- fontes ----------
F5 = {
    'A': [".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    'C': [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
    'D': ["####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."],
    'E': ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
    'G': [".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"],
    'H': ["#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    'I': ["###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"],
    'L': ["#....", "#....", "#....", "#....", "#....", "#....", "#####"],
    'M': ["#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"],
    'N': ["#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"],
    'O': [".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    'P': ["####.", "#...#", "#...#", "####.", "#....", "#....", "#...."],
    'R': ["####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"],
    'S': [".####", "#....", "#....", ".###.", "....#", "....#", "####."],
    'T': ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
    'U': ["#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    'V': ["#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."],
    'Z': ["#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"],
    'B': ["####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."],
    'K': ["#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"],
    'W': ["#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"],
    'Y': ["#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."],
    'F': ["#####", "#....", "#....", "####.", "#....", "#....", "#...."],
    'J': ["..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."],
    'X': ["#...#", ".#.#.", "..#..", "..#..", "..#..", ".#.#.", "#...#"],
    '0': [".###.", "#..##", "#.#.#", "#.#.#", "#.#.#", "##..#", ".###."],
    '1': ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."],
    '2': [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
    '3': ["####.", "....#", "....#", ".###.", "....#", "....#", "####."],
    '4': ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
    '5': ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
    '/': ["....#", "....#", "...#.", "..#..", ".#...", "#....", "#...."],
    '.': [".", ".", ".", ".", ".", ".", "#"],
    ',': ["..", "..", "..", "..", "..", ".#", "#."],
    ':': [".", "#", ".", ".", ".", "#", "."],
    '-': ["...", "...", "...", "###", "...", "...", "..."],
}
F3 = {
    'A': [".#.", "#.#", "###", "#.#", "#.#"],
    'C': [".##", "#..", "#..", "#..", ".##"],
    'D': ["##.", "#.#", "#.#", "#.#", "##."],
    'E': ["###", "#..", "##.", "#..", "###"],
    'G': [".##", "#..", "#.#", "#.#", ".##"],
    'I': ["###", ".#.", ".#.", ".#.", "###"],
    'L': ["#..", "#..", "#..", "#..", "###"],
    'M': ["#...#", "##.##", "#.#.#", "#...#", "#...#"],
    'N': ["##.", "#.#", "#.#", "#.#", "#.#"],
    'O': [".#.", "#.#", "#.#", "#.#", ".#."],
    'P': ["##.", "#.#", "##.", "#..", "#.."],
    'R': ["##.", "#.#", "##.", "#.#", "#.#"],
    'S': [".##", "#..", ".#.", "..#", "##."],
    'T': ["###", ".#.", ".#.", ".#.", ".#."],
    'U': ["#.#", "#.#", "#.#", "#.#", "###"],
    'V': ["#.#", "#.#", "#.#", "#.#", ".#."],
}
ACCENT = {'Á': 'A', 'Í': 'I'}


def text_w(s, font, bold=False):
    w = 0
    for i, ch in enumerate(s):
        if ch == ' ':
            w += 3
            continue
        w += len(font[ACCENT.get(ch, ch)][0]) + (1 if bold else 0)
        if i + 1 < len(s) and s[i + 1] != ' ':
            w += 1
    return w


def text(img, s, x, y, color, font, bold=False, shadow=None):
    if shadow:
        text(img, s, x + 1, y + 1, shadow, font, bold)
    big = font is F5
    for i, ch in enumerate(s):
        if ch == ' ':
            x += 3
            continue
        g = font[ACCENT.get(ch, ch)]
        for dx in ((0, 1) if bold else (0,)):
            draw(img, g, x + dx, y, {'#': color})
        w = len(g[0])
        if ch in ACCENT:
            c = x + w // 2
            if big:
                img.set(c + 1, y - 2, color)
                img.set(c, y - 1, color)
                if bold:
                    img.set(c + 2, y - 2, color)
            else:
                img.set(c + 1, y - 2, color)
        x += w + (1 if bold else 0)
        if i + 1 < len(s) and s[i + 1] != ' ':
            x += 1


def text_c(img, s, cx, y, color, font, bold=False, shadow=None):
    text(img, s, cx - text_w(s, font, bold) // 2, y, color, font, bold, shadow)


# ---------- fundo ----------
def slot(img, x, y):  # moldura de slot 18x18, estilo vanilla em tons de papel
    img.rect(x, y, x + 16, y, SLOT_D)
    img.rect(x, y, x, y + 16, SLOT_D)
    img.rect(x + 1, y + 17, x + 17, y + 17, HI)
    img.rect(x + 17, y + 1, x + 17, y + 17, HI)
    img.set(x + 17, y, SLOT)
    img.set(x, y + 17, SLOT)
    img.rect(x + 1, y + 1, x + 16, y + 16, SLOT)


def card(img, x0, y0, x1, y1):
    img.rect(x0, y0, x1, y1, SHADE)
    img.rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, CARD)


def diamond(img, cx, cy, c):
    for dx, dy in ((0, -2), (-1, -1), (1, -1), (-2, 0), (2, 0), (-1, 1), (1, 1), (0, 2)):
        img.set(cx + dx, cy + dy, c)
    img.set(cx, cy, c)


def base(subtitle=True, nav_slots=(0, 2, 3, 4, 5, 7, 8)):
    W, H = 176, 222
    img = Img(W, H)
    seed = 12345
    for y in range(H):
        for x in range(W):
            seed = (seed * 1103515245 + 12345) & 0x7fffffff
            r = seed / 0x7fffffff
            c = SPECK_D if r < 0.07 else SPECK_L if r < 0.11 else PAPER
            d = min(x, y, W - 1 - x, H - 1 - y)
            if d == 0:
                c = OUTL
            elif d == 1:
                c = HI if (x == 1 or y == 1) else SH
            elif d in (2, 3) and r < 0.6:
                c = EDGE
            img.set(x, y, c)
    for cx, cy, sx, sy in ((0, 0, 1, 1), (W - 1, 0, -1, 1), (0, H - 1, 1, -1), (W - 1, H - 1, -1, -1)):
        img.set(cx, cy, None)
        img.set(cx + sx, cy, None)
        img.set(cx, cy + sy, None)
        img.set(cx + sx, cy + sy, OUTL)

    # cabeçalho (linha 1 + área do título): fixo, não clicável
    img.rect(6, 3, W - 7, 3, INK)
    img.rect(6, 5, W - 7, 5, SHADE)
    title = 'GAIA GUARDIAN'
    tw = text_w(title, F5, bold=True)
    tx = (W - tw) // 2
    text(img, title, tx, 9, INK, F5, bold=True, shadow=HI)
    for x in range(9, tx - 7):
        img.set(x, 12, SHADE)
    for x in range(tx + tw + 7, W - 9):
        img.set(x, 12, SHADE)
    diamond(img, tx - 5, 12, INK)
    diamond(img, tx + tw + 4, 12, INK)
    if subtitle:
        img.rect(6, 19, W - 7, 19, SHADE)
        text_c(img, 'DIPLOMACY AND WAR', W // 2, 22, SUB, F5)
        img.rect(6, 31, W - 7, 31, SHADE)
        img.rect(6, 33, W - 7, 33, INK)

    # navegação (linha 6): 0 prev, 2-5 filtros, 7 fechar, 8 próxima
    for c in nav_slots:
        slot(img, 7 + 18 * c, 107)
    diamond(img, 7 + 18 + 8, 115, SHADE)
    diamond(img, 7 + 18 * 6 + 8, 115, SHADE)

    # inventário do jogador (o jogo escreve "Inventário" em (8,128))
    for x in range(7, 169, 2):
        img.set(x, 137, SHADE)
    for r in range(3):
        for c in range(9):
            slot(img, 7 + 18 * c, 139 + 18 * r)
    for c in range(9):
        slot(img, 7 + 18 * c, 197)
    return img


def news_menu():
    img = base()
    for r in range(1, 5):  # linhas 2-5: ícone | faixa da manchete (slots 1-7) | botão
        y = 17 + 18 * r
        slot(img, 7, y)
        card(img, 25, y, 150, y + 17)
        slot(img, 151, y)
    return img


def news_read():
    img = base(subtitle=False, nav_slots=(0, 2, 7, 8))
    slot(img, 7, 17)                  # item: ícone do tipo
    card(img, 25, 17, 168, 34)        # faixa da manchete (slots 1-8), texto em x 28-164, topo y 22
    card(img, 7, 35, 168, 106)        # painel de leitura, liso
    card(img, 61, 108, 116, 124)      # rótulo da página (x 62-115, y 109-123)
    return img


INK_TXT = hexc('3B2F1E')


def wrap(words, width, font=None):
    font = font or F5
    lines, cur = [], ''
    for w in words.split():
        t = (cur + ' ' + w).strip()
        if text_w(t, font) <= width or not cur:
            cur = t
        else:
            lines.append(cur)
            cur = w
    return lines + [cur]


def news_read_mock(headline, body, page='1/3', icon='war'):
    img = news_read()
    img.paste(ICONS[icon], *at(0, 0))
    img.paste(BUTTONS['prev'], *at(0, 5))
    img.paste(BUTTONS['back_to_list'], *at(2, 5))
    img.paste(BUTTONS['close'], *at(7, 5))
    img.paste(BUTTONS['next'], *at(8, 5))
    text(img, headline, 28, 22, INK_TXT, F5)
    for i, ln in enumerate(wrap(body, 152)[:7]):
        text(img, ln, 12, 39 + 9 * i, INK_TXT, F5)
    text_c(img, page, 88, 112, INK_TXT, F5)
    return img


def paper(w, h, seed=777):
    img = Img(w, h)
    for y in range(h):
        for x in range(w):
            seed = (seed * 1103515245 + 12345) & 0x7fffffff
            r = seed / 0x7fffffff
            c = SPECK_D if r < 0.07 else SPECK_L if r < 0.11 else PAPER
            d = min(x, y, w - 1 - x, h - 1 - y)
            if d == 0:
                c = OUTL
            elif d == 1:
                c = HI if (x == 1 or y == 1) else SH
            elif d in (2, 3) and r < 0.6:
                c = EDGE
            img.set(x, y, c)
    for cx, cy, sx, sy in ((0, 0, 1, 1), (w - 1, 0, -1, 1), (0, h - 1, 1, -1), (w - 1, h - 1, -1, -1)):
        img.set(cx, cy, None)
        img.set(cx + sx, cy, None)
        img.set(cx, cy + sy, None)
        img.set(cx + sx, cy + sy, OUTL)
    return img


def book_button(forward, hover):
    img = Img(23, 13)
    img.rect(0, 0, 22, 12, OUTL)
    for p in ((0, 0), (22, 0), (0, 12), (22, 12)):
        img.set(*p, None)
    img.rect(1, 1, 21, 11, HI if hover else PAPER)
    img.rect(1, 11, 21, 11, SH)
    img.rect(21, 1, 21, 11, SH)
    c = FAM['blue'][1] if hover else INK
    rows = ["#...", "##..", "###.", "####", "###.", "##..", "#..."] if forward else ["...#", "..##", ".###", "####", ".###", "..##", "...#"]
    draw(img, rows, 9, 3, {'#': c})
    return img


def ribbon(img, x, y0, y1):
    """fita marcadora de 9 px de largura; a ponta em V (vértice para cima) devolve o papel nas últimas 4 linhas"""
    d, m, l = FAM['red']
    under = {(xx, yy): img.get(xx, yy) for yy in range(y0, y1 + 1) for xx in range(x, x + 9)}
    for yy in range(y0, y1 + 1):
        for xx in range(x, x + 9):
            img.set(xx, yy, OUTL if xx in (x, x + 8) else l if xx == x + 1 else d if xx == x + 7 else m)
    for k in range(4):
        yy = y1 - 3 + k
        for xx in range(x + 4 - k, x + 4 + k + 1):
            img.set(xx, yy, under[(xx, yy)])
        for xx in (x + 4 - k - 1, x + 4 + k + 1):
            if x < xx < x + 8:
                img.set(xx, yy, OUTL)


def flourish(img, x, y, sx=1, sy=1):
    """ornamento de canto 7x7, espelhável"""
    rows = ["#######", "#.....#", "#.###..", "#.#.#..", "#.##...", "#......", "#......"]
    for j, r in enumerate(rows):
        for i, ch in enumerate(r):
            if ch == '#':
                img.set(x + i * sx, y + j * sy, INK if (i, j) in ((0, 0), (2, 2), (3, 3)) else SHADE)


VIG = hexc('E2DDCE')


def book_panel(img, x0, y0, x1, y1):
    """painel do texto: miolo liso (o texto do jogo fica em x 36-150, y 32-158), acabamento só nas bordas"""
    card(img, x0, y0, x1, y1)
    for x in range(x0 + 1, x1):          # vinheta: 1 px sólido + 1 px pontilhado
        for y in range(y0 + 1, y1):
            d = min(x - x0, x1 - x, y - y0, y1 - y)
            if d == 1 or (d == 2 and (x + y) % 2 == 0):
                img.set(x, y, VIG)
    i = 4                                  # moldura interna fina, aberta nos cantos
    for x in range(x0 + i + 5, x1 - i - 4):
        img.set(x, y0 + i, EDGE)
        img.set(x, y1 - i, EDGE)
    for y in range(y0 + i + 5, y1 - i - 4):
        img.set(x0 + i, y, EDGE)
        img.set(x1 - i, y, EDGE)
    for cx, cy, sx, sy in ((x0 + i, y0 + i, 1, 1), (x1 - i, y0 + i, -1, 1), (x0 + i, y1 - i, 1, -1), (x1 - i, y1 - i, -1, -1)):
        for k in range(4):                 # colchete em L, curto: não chega à coluna do texto (x 36)
            img.set(cx + sx * k, cy, INK if k < 3 else SHADE)
            img.set(cx, cy + sy * k, INK if k < 3 else SHADE)
    mid = (x0 + x1) // 2                   # fecho no topo e embaixo, sobre a borda
    for cy in (y0, y1):
        diamond(img, mid, cy, INK)
        for dx in (-7, 7):
            img.set(mid + dx, cy, INK)
        for dx in (-5, -4, -3, 3, 4, 5):
            img.set(mid + dx, cy, SHADE)


def book():
    """textures/gui/book.png: folha 256x256, página 192x192 em (0,0); texto do jogo em x 36-150, y 32-158."""
    sheet = Img(256, 256)
    pg = paper(192, 192)
    # sombra de lombada à esquerda (pontilhado)
    for y in range(4, 188):
        for x in range(4, 14):
            if (x + y) % 2 == 0 and (x - 4) < 10 - 3 * ((x + y) // 2 % 3):
                pg.set(x, y, SPECK_D)
    for y in range(4, 188):
        pg.set(4, y, SH)
    title = 'GAIA GUARDIAN'
    tw = text_w(title, F5, bold=True)
    tx = (192 - tw) // 2
    text(pg, title, tx, 5, INK, F5, bold=True, shadow=HI)
    diamond(pg, tx - 6, 8, INK)
    diamond(pg, tx + tw + 5, 8, INK)
    for x in list(range(9, tx - 10)) + list(range(tx + tw + 9, 183)):
        pg.set(x, 8, SHADE)
    pg.rect(8, 15, 183, 15, SHADE)           # o jogo escreve "Page 1 of 3" em y 16, à direita
    # moldura lateral e inferior com ornamentos
    pg.rect(7, 26, 7, 182, SHADE)
    pg.rect(184, 26, 184, 182, SHADE)
    pg.rect(7, 183, 184, 183, SHADE)
    for cx, cy, sx, sy in ((8, 176, 1, 1), (183, 176, -1, 1)):
        flourish(pg, cx, cy, sx, sy)
    diamond(pg, 7, 26, INK)
    diamond(pg, 184, 26, INK)
    # painel liso: texto de x 36 a 150, y 32 a 158
    book_panel(pg, 28, 26, 158, 163)
    # divisória inferior
    for x in list(range(24, 86)) + list(range(106, 168)):
        pg.set(x, 178, SHADE)
    diamond(pg, 96, 178, INK)
    diamond(pg, 88, 178, SHADE)
    diamond(pg, 104, 178, SHADE)
    # fita marcadora
    ribbon(pg, 160, 0, 30)
    sheet.paste(pg, 0, 0)
    for i, (fw, hv) in enumerate(((True, False), (True, True), (False, False), (False, True))):
        sheet.paste(book_button(fw, hv), 23 * (i % 2), 192 if fw else 205)
    return sheet, {'page_forward': book_button(True, False), 'page_forward_highlighted': book_button(True, True),
                   'page_backward': book_button(False, False), 'page_backward_highlighted': book_button(False, True)}


def book_page(prev, nxt):
    """prévia: só a página 192x192 com os botões nos lugares do jogo"""
    sheet, sp = book()
    pg = Img(192, 192)
    for y in range(192):
        for x in range(192):
            pg.set(x, y, sheet.get(x, y))
    if prev:
        pg.paste(sp['page_backward'], 43, 159)
    if nxt:
        pg.paste(sp['page_forward'], 116, 159)
    return pg


def news_menu_empty():
    img = base()
    card(img, 7, 35, 168, 106)
    text_c(img, 'NO NEWS YET', 88, 61, INK, F5)
    for x in range(52, 125):
        if x % 2 == 0:
            img.set(x, 72, SHADE)
    diamond(img, 88, 72, SHADE)
    text_c(img, 'CHECK BACK LATER', 88, 78, SUB, F5)
    return img


# ---------- glifos 10x10 ----------
G = {
    'war': ["#........#", ".#......#.", "..#....#..", "...#..#...", "....##....",
            "..#.##.#..", "...#..#...", "..*.##.*..", ".*......*.", "#........#"],
    'peace': ['........#...', '.......##...', '..##..###...', '.#*#.####...', '##########..', '..##########', '...#########', '....#####...', '.....#......'],
    'truce': [".+........", ".######...", ".#######..", ".#######..", ".#####....",
              ".#........", ".#........", ".#........", ".#........", "###......."],
    'nap': [".########.", ".########.", ".######*#.", ".#*###*##.", ".##*#*###.",
            "..##*###..", "..######..", "...####...", "....##....", ".........."],
    'ultimatum': [".########.", "..#....#..", "..#++++#..", "...#++#...", "....##....",
                  "....##....", "...#..#...", "..#.++.#..", "..#++++#..", ".########."],
    'post': ["........##", "......##+#", ".....#++#.", "....#++#..", "...#++#...",
             "...#+#....", "..#.#.....", "..#.......", ".#........", "#........."],
    'alliance': ['...........', '##*##.*###*', '##*##.#*#*#', '*****.##*##', '##*##.#*#*#', '##*##.*###*', '.#*#...###.', '..#.....#..'],
    'alliance_end': ['##*##+.....', '##*##.*###*', '*****+#*#*#', '##*##.##*##', '##*##+#*#*#', '.#*#..*###*', '..#....###.', '........#..'],
    'vassal': ['..#####..', '...###...', '....#....', '.........', '#...#...#', '##.###.##', '#########', '#########'],
    'independence': ['....#....', '...###...', '..#####..', '.........', '#...#...#', '##.###.##', '#########', '#########'],
    'back_to_list': ['..........', '...#......', '..##......', '.#########', '..##.....#', '...#.....#', '.........#', '....######'],
    'prev': ["..........", "......#...", ".....##...", "....###...", "...####...",
             "...####...", "....###...", ".....##...", "......#...", ".........."],
    'close': ["..........", ".RR....RR.", ".RRR..RRR.", "..RRRRRR..", "...RRRR...",
              "...RRRR...", "..RRRRRR..", ".RRR..RRR.", ".RR....RR.", ".........."],
    'open': ["......RRR.", ".....RRRRR", ".....RRwRR", "......RRR.", "#######R##",
             "#ww#ww#R.#", "#ww#ww#ww#", "#ww#ww#ww#", "#ww#ww#ww#", "##########"],
}
G['next'] = [row[::-1] for row in G['prev']]
for k, v in G.items():
    assert all(len(r) == len(v[0]) for r in v), k


def medal(fam, glyph, badge=None):
    d, m, l = FAM[fam]
    dark = fam in ('amber', 'gold')  # símbolo escuro em selo claro
    img = Img(16, 16)

    def inside(x, y):
        return 0 <= x < 16 and 0 <= y < 16 and math.hypot(x - 7.5, y - 7.5) <= 8.0
    edge = set()
    for y in range(16):
        for x in range(16):
            if inside(x, y):
                img.set(x, y, m)
                if any(not inside(x + a, y + b) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    edge.add((x, y))
    for x, y in edge:
        img.set(x, y, OUTL)
    for y in range(16):
        for x in range(16):
            if inside(x, y) and (x, y) not in edge and any((x + a, y + b) in edge for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                img.set(x, y, l if x + y < 15 else d)
    prim = OUTL if dark else HI
    cmap = {'#': prim, '+': l if not dark else HI, '*': OUTL if not dark else d}
    rows = G[glyph]
    gw, gh = len(rows[0]), len(rows)
    ox, oy = (16 - gw) // 2, (16 - gh) // 2
    if badge and gw <= 10:  # abre espaço para o selinho no canto
        ox, oy = ox - 1, oy - 1
    shadow = l if dark else d
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != '.' and (y + 1 >= gh or x + 1 >= gw or rows[y + 1][x + 1] == '.'):
                img.set(ox + x + 1, oy + y + 1, shadow)
    draw(img, rows, ox, oy, cmap)
    if badge:
        bc = {'+': FAM['green'][1], '-': FAM['red'][1], 'x': INK}[badge]
        img.rect(9, 9, 15, 15, OUTL)
        for p in ((9, 9), (15, 9), (9, 15), (15, 15)):
            img.set(*p, None)
        img.rect(10, 10, 14, 14, bc)
        if badge == 'x':
            for p in ((11, 11), (13, 11), (12, 12), (11, 13), (13, 13)):
                img.set(*p, HI)
        else:
            img.rect(11, 12, 13, 12, HI)
        if badge == '+':
            img.rect(12, 11, 12, 13, HI)
    return img


def tile(glyph, fam=None, on=False):
    img = Img(16, 16)
    img.rect(0, 0, 15, 15, OUTL)
    for p in ((0, 0), (15, 0), (0, 15), (15, 15)):
        img.set(*p, None)
    if on:
        d, m, l = FAM[fam]
        img.rect(1, 1, 14, 14, l)
        img.rect(1, 1, 14, 1, d)
        img.rect(1, 1, 1, 14, d)
        img.rect(2, 2, 14, 14, m)
        cmap = {'#': HI, '+': l, '*': d, 'R': HI, 'w': m}
        oy = 1  # afundado
    else:
        img.rect(1, 1, 14, 14, SH)
        img.rect(1, 1, 13, 1, HI)
        img.rect(1, 1, 1, 13, HI)
        img.rect(2, 2, 13, 13, PAPER)
        fm = FAM[fam][1] if fam else INK
        cmap = {'#': INK, '+': fm, '*': fm, 'R': RED, 'w': HI}
        oy = 0
    rows = G[glyph]
    draw(img, rows, (16 - len(rows[0])) // 2, (16 - len(rows)) // 2 + oy, cmap)
    return img


def flag_frame():
    img = Img(12, 8)
    img.rect(0, 0, 11, 0, OUTL)
    img.rect(0, 7, 11, 7, OUTL)
    img.rect(0, 0, 0, 7, OUTL)
    img.rect(11, 0, 11, 7, OUTL)
    for p in ((0, 0), (11, 0), (0, 7), (11, 7)):
        img.set(*p, None)
    return img


ICONS_FAM = {'war': 'red', 'peace': 'green', 'truce': 'green', 'nap': 'teal', 'ultimatum': 'amber',
             'vassal': 'purple', 'independence': 'purple', 'post': 'gold'}
ICONS = {
    'war': medal('red', 'war'),
    'peace': medal('green', 'peace'),
    'post': medal('gold', 'post'),
    'alliance': medal('blue', 'alliance'),
    'alliance_join': medal('blue', 'alliance', '+'),
    'alliance_leave': medal('blue', 'alliance', '-'),
    'alliance_end': medal('blue', 'alliance', 'x'),
    'truce': medal('green', 'truce'),
    'nap': medal('teal', 'nap'),
    'ultimatum': medal('amber', 'ultimatum'),
    'vassal': medal('purple', 'vassal'),
    'independence': medal('purple', 'independence'),
}
for _t in ('war', 'nap', 'ultimatum'):
    ICONS[_t + '_plus'] = medal(ICONS_FAM[_t], _t, '+')
    ICONS[_t + '_minus'] = medal(ICONS_FAM[_t], _t, '-')
BUTTONS = {'prev': tile('prev'), 'next': tile('next'), 'close': tile('close'), 'open': tile('open'),
           'back_to_list': tile('back_to_list')}
for name, glyph, fam in (('war', 'war', 'red'), ('alliance', 'alliance', 'blue'),
                         ('ultimatum', 'ultimatum', 'amber'), ('truce', 'truce', 'green')):
    BUTTONS['filter_' + name] = tile(glyph, fam)
    BUTTONS['filter_' + name + '_on'] = tile(glyph, fam, on=True)


def at(c, r):  # canto do item 16x16 do slot (coluna, linha) do baú
    return 8 + 18 * c, 18 + 18 * r


def nav(img, active='war'):
    img.paste(BUTTONS['prev'], *at(0, 5))
    for i, f in enumerate(('war', 'alliance', 'ultimatum', 'truce')):
        img.paste(BUTTONS['filter_' + f + ('_on' if f == active else '')], *at(2 + i, 5))
    img.paste(BUTTONS['close'], *at(7, 5))
    img.paste(BUTTONS['next'], *at(8, 5))


if __name__ == '__main__':
    menu, empty = news_menu(), news_menu_empty()
    menu.save('menu/news_menu.png')
    empty.save('menu/news_menu_empty.png')
    for k, v in ICONS.items():
        v.save('icons/%s.png' % k)
    for k, v in BUTTONS.items():
        v.save('buttons/%s.png' % k)
    flag_frame().save('flags/flag_frame.png')
    news_read().save('menu/news_read.png')
    sheet, sprites = book()
    sheet.save('book/book.png')
    for n_, (pv, nx_) in {'book_page1': (False, True), 'book_page2': (True, True)}.items():
        book_page(pv, nx_).scaled(2).save('mockup/%s_x2.png' % n_)
    for k, v in sprites.items():
        v.save('book/sprites/%s.png' % k)
    news_read_mock('AGARTHA DECLARES WAR',
                   'AGARTHA HAS DECLARED WAR ON RECALLIA AFTER THE BORDER INCIDENT AT PESHBRON. '
                   'ALL ALLIED NATIONS MAY JOIN WITHIN THE NEXT 24 HOURS. THE STAFF WILL HOLD AN '
                   'ENGAGEMENT WHEN BOTH SIDES ARE READY.').scaled(3).save('mockup/news_read_x3.png')

    # mockups em escala 3 (sem as manchetes: o canvas escreve o texto por cima)
    m = menu.copy()
    for r, icon in zip(range(1, 5), ('war', 'vassal', 'ultimatum', 'post')):
        m.paste(ICONS[icon], *at(0, r))
        m.paste(BUTTONS['open'], *at(8, r))
    nav(m)
    m.scaled(3).save('mockup/mockup_x3.png')
    e = empty.copy()
    nav(e, active=None)
    e.scaled(3).save('mockup/mockup_empty_x3.png')
    print('ok')
