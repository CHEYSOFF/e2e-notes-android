"""Evidence for issue #24 - "Use a Cyrillic-capable UI font".

Does two things:

1. Prints a coverage + metrics table read straight out of the font binaries, so
   the claims in the issue ("Urbanist lacks Cyrillic glyphs") are checked rather
   than assumed, and so the layout impact of a swap is a number and not a guess.

2. Renders font-comparison.png: the same English and Russian strings drawn in
   Urbanist and in each candidate, at the exact weights and sp sizes that
   core-ui/src/main/java/my/cheysoff/core_ui/theme/Type.kt uses.

The PNG is drawn from the real TTF outlines, not from a browser's webfont
substitute, so the glyphs it shows are the glyphs that would ship.

The "Urbanist (current)" column is an honest depiction of today's build.
Urbanist's cmap contains zero codepoints in U+0400-04FF, so Android's text
layout cannot use it for Cyrillic and silently re-shapes those runs in the next
family of the system fallback chain. This script reproduces that by drawing
Cyrillic characters in Roboto and Latin characters in Urbanist within the same
string - which is what the device does today.

Run:  pip install pillow fonttools && python render_font_comparison.py
"""

import os
import tempfile
import urllib.request

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
# docs/design/fonts -> repo root -> the Urbanist variable font the app ships.
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
URBANIST = os.path.join(REPO, "core-ui", "src", "main", "res", "font", "urbanist_variable.ttf")

# The candidates are deliberately not committed: they are several hundred KB
# each and none is a dependency of the app yet. Fetch them into a temp dir on
# first run. All are SIL Open Font License 1.1, the same licence as Urbanist.
FONT_DIR = os.path.join(tempfile.gettempdir(), "notes-font-comparison")
SOURCES = {
    "Onest.ttf":
        "https://raw.githubusercontent.com/google/fonts/main/ofl/onest/Onest%5Bwght%5D.ttf",
    "Manrope.ttf":
        "https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/Manrope%5Bwght%5D.ttf",
    "NunitoSans.ttf":
        "https://raw.githubusercontent.com/google/fonts/main/ofl/nunitosans/"
        "NunitoSans%5BYTLC,opsz,wdth,wght%5D.ttf",
    # Roboto is not a candidate. It stands in for the system family that Android
    # currently falls back to for Cyrillic, so the first column is truthful.
    "Roboto.ttf":
        "https://raw.githubusercontent.com/google/fonts/main/ofl/roboto/Roboto%5Bwdth,wght%5D.ttf",
}


def ensure_fonts():
    os.makedirs(FONT_DIR, exist_ok=True)
    for name, url in SOURCES.items():
        dest = os.path.join(FONT_DIR, name)
        if not os.path.exists(dest):
            print("downloading", name)
            urllib.request.urlretrieve(url, dest)


def font_path(name):
    return os.path.join(FONT_DIR, name)


# ---------------------------------------------------------------------------
# 1. Coverage and metrics, read from the binaries
# ---------------------------------------------------------------------------

RU_UPPER = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
RUSSIAN = RU_UPPER + RU_UPPER.lower()
LATIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"


def report_table(entries):
    """entries: list of (label, path). Prints coverage + the metrics that decide
    whether a swap reflows the UI."""
    from fontTools.ttLib import TTFont

    header = (f"{'font':22s} {'size':>8s} {'RU':>7s} {'LA':>7s} "
              f"{'x-height':>9s} {'cap':>6s} {'avg lc':>7s} {'wght axis':>14s}")
    print(header)
    print("-" * len(header))
    for label, path in entries:
        f = TTFont(path, lazy=True)
        upm = f["head"].unitsPerEm
        os2 = f["OS/2"]
        cmap = f.getBestCmap()
        hmtx = f["hmtx"]

        def covered(text):
            return sum(1 for c in text if ord(c) in cmap)

        widths = [hmtx[cmap[ord(c)]][0] / upm
                  for c in "abcdefghijklmnopqrstuvwxyz" if ord(c) in cmap]
        axis = ""
        if "fvar" in f:
            wght = next((a for a in f["fvar"].axes if a.axisTag == "wght"), None)
            if wght:
                axis = f"{int(wght.minValue)}-{int(wght.maxValue)}"
        size_kb = os.path.getsize(path) / 1024
        print(f"{label:22s} {size_kb:7.0f}K "
              f"{covered(RUSSIAN):3d}/{len(RUSSIAN):<3d} "
              f"{covered(LATIN):3d}/{len(LATIN):<3d} "
              f"{getattr(os2, 'sxHeight', 0) / upm:9.3f} "
              f"{getattr(os2, 'sCapHeight', 0) / upm:6.3f} "
              f"{sum(widths) / len(widths):7.3f} {axis:>14s}")
        f.close()


# ---------------------------------------------------------------------------
# 2. The rendered comparison
# ---------------------------------------------------------------------------

S = 2  # image pixels per sp, so the PNG stays legible viewed inline on GitHub

# App palette, from core-ui/src/main/java/my/cheysoff/core_ui/theme/Color.kt
BLACK = (0, 0, 0)
SURFACE_DARK = (0x16, 0x16, 0x18)
OUTLINE_DARK = (0x2E, 0x2E, 0x34)
TITLE_GREY = (0xDC, 0xDC, 0xDC)
BODY_GREY = (0x7A, 0x7A, 0x7E)
WELCOME_GREY = (0xE2, 0xE2, 0xE2)
INDIGO_TINT = (0x6A, 0x5F, 0xD0)
ACCENT_INDIGO = (0x2C, 0x1A, 0xB0)
CAT_TEAL = (0x15, 0x69, 0x5E)
DIM = (0x5E, 0x5E, 0x62)
WARN = (0xC0, 0x5A, 0x5A)


class Face:
    """One variable font, instantiated on demand at a given (sp size, wght).

    set_variation_by_axes([weight]) sets the first axis and leaves any further
    axes at their defaults. Every font here declares Weight first (Nunito Sans
    then has Width / Optical size / YTLC, Roboto has Width) and their defaults
    are the values we want, so passing the weight alone is correct.
    """

    _cache = {}

    def __init__(self, path):
        self.path = path

    def at(self, size_sp, weight):
        key = (self.path, size_sp, weight)
        if key not in Face._cache:
            f = ImageFont.truetype(self.path, int(size_sp * S))
            f.set_variation_by_axes([weight])
            Face._cache[key] = f
        return Face._cache[key]


def is_cyrillic(ch):
    return 0x0400 <= ord(ch) <= 0x04FF


def build_image(columns, urb, roboto):
    col_w, gutter, pad = 430 * S, 22 * S, 30 * S
    width = pad * 2 + col_w * len(columns) + gutter * (len(columns) - 1)
    img = Image.new("RGB", (width, 1010 * S), BLACK)
    d = ImageDraw.Draw(img)

    def mixed(x, y, text, latin, cyr, size, weight, fill):
        """Draw text character by character, switching face on Cyrillic when
        `cyr` is set. When `cyr` is None one face covers both scripts."""
        face_lat = latin.at(size, weight)
        face_cyr = cyr.at(size, weight) if cyr else face_lat
        for ch in text:
            f = face_cyr if (cyr and is_cyrillic(ch)) else face_lat
            d.text((x, y), ch, font=f, fill=fill)
            x += d.textlength(ch, font=f)
        return x

    def caption(x, y, text, size=9):
        d.text((x, y), text, font=urb.at(size, 400), fill=DIM)

    # Banner: one note title, as the app draws it today vs. in a single family.
    y = pad
    d.text((pad, y), "ISSUE #24 - CYRILLIC IN THE UI FONT",
           font=urb.at(13, 700), fill=INDIGO_TINT)
    y += 26 * S
    caption(pad, y, "TODAY - one note title, two typefaces (Urbanist + Roboto fallback):", 10)
    y += 20 * S
    end = mixed(pad, y, "Заметки Notes", urb, roboto, 37, 500, TITLE_GREY)
    d.text((end + 24 * S, y + 14 * S), "two typefaces in one line",
           font=urb.at(12, 400), fill=WARN)
    y += 56 * S
    caption(pad, y, "PROPOSED - one note title, one typeface (Onest shown):", 10)
    y += 20 * S
    mixed(pad, y, "Заметки Notes", columns[1][1], None, 37, 500, TITLE_GREY)
    y += 62 * S
    d.line([pad, y, width - pad, y], fill=OUTLINE_DARK, width=1 * S)
    y += 26 * S

    top = y
    for i, (name, latin, cyr, note) in enumerate(columns):
        x0 = pad + i * (col_w + gutter)
        y = top

        d.text((x0, y), name.upper(), font=urb.at(12, 700), fill=INDIGO_TINT)
        y += 20 * S
        caption(x0, y, note, 10)
        y += 30 * S

        caption(x0, y, "HERO HEADER - titleLarge 37sp, Light 300 / Medium 500")
        y += 18 * S
        d.text((x0, y), "Good", font=latin.at(37, 300), fill=WELCOME_GREY)
        y += 40 * S
        d.text((x0, y), "morning.", font=latin.at(37, 500), fill=INDIGO_TINT)
        y += 62 * S

        def card(y, title, body, meta, accent):
            """A note card: titleSmall 20sp Medium, bodySmall 17sp, labelSmall 13sp."""
            h = 118 * S
            d.rounded_rectangle([x0, y, x0 + col_w, y + h], radius=18 * S,
                                fill=SURFACE_DARK, outline=OUTLINE_DARK, width=1 * S)
            d.rounded_rectangle([x0, y, x0 + 5 * S, y + h], radius=2 * S, fill=accent)
            px = x0 + 18 * S
            mixed(px, y + 14 * S, title, latin, cyr, 20, 500, TITLE_GREY)
            mixed(px, y + 45 * S, body, latin, cyr, 17, 400, BODY_GREY)
            mixed(px, y + 88 * S, meta, latin, cyr, 13, 400, DIM)
            return y + h + 16 * S

        caption(x0, y, "NOTE CARD - English")
        y += 18 * S
        y = card(y, "Grocery list", "Milk, oat flour, coffee beans",
                 "2 hours ago", ACCENT_INDIGO)

        caption(x0, y + 4 * S, "NOTE CARD - Russian (user-typed content)")
        y += 22 * S
        y = card(y, "Список покупок", "Молоко, овсяная мука, кофе",
                 "2 часа назад", CAT_TEAL)

        caption(x0, y + 4 * S, "BOTH SCRIPTS IN ONE LINE")
        y += 22 * S
        mixed(x0, y, "Note: перезвонить в 14:30", latin, cyr, 20, 500, TITLE_GREY)
        y += 34 * S
        mixed(x0, y, "Проект Mañana — заметки", latin, cyr, 17, 400, BODY_GREY)
        y += 42 * S

        caption(x0, y, "ALPHABET")
        y += 18 * S
        for line in ("АБВГДЕЖЗИЙКЛМНОП", "абвгдежзийклмноп", "рстуфхцчшщъыьэюя"):
            mixed(x0, y, line, latin, cyr, 17, 400, TITLE_GREY)
            y += 26 * S
        for line in ("ABCDEFGHIJKLMNOP", "abcdefghijklmnop"):
            d.text((x0, y), line, font=latin.at(17, 400), fill=TITLE_GREY)
            y += 26 * S

    return img


def main():
    ensure_fonts()

    report_table([
        ("Urbanist (current)", URBANIST),
        ("Onest", font_path("Onest.ttf")),
        ("Manrope", font_path("Manrope.ttf")),
        ("Nunito Sans", font_path("NunitoSans.ttf")),
        ("Roboto (fallback)", font_path("Roboto.ttf")),
    ])

    urb = Face(URBANIST)
    roboto = Face(font_path("Roboto.ttf"))
    # (label, Latin face, Cyrillic face or None when the Latin face covers it, caption)
    columns = [
        ("Urbanist  (current)", urb, roboto, "no Cyrillic - falls back to Roboto"),
        ("Onest", Face(font_path("Onest.ttf")), None, "full Cyrillic, 189 KB"),
        ("Manrope", Face(font_path("Manrope.ttf")), None, "full Cyrillic, 162 KB"),
        ("Nunito Sans", Face(font_path("NunitoSans.ttf")), None, "full Cyrillic, 558 KB"),
    ]
    out = os.path.join(HERE, "font-comparison.png")
    build_image(columns, urb, roboto).save(out)
    print("\nwrote", out)


if __name__ == "__main__":
    main()
