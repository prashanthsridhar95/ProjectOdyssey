#!/usr/bin/env python3
# Barlow has no Indian rupee sign, and the browser build of the customer app has no system font to fall back on,
# so every price showed a missing-glyph box. This draws one into each bundled font, matched to its stem and digit width.
# Run again after replacing the fonts:  pip install fonttools && python3 scripts/add-rupee-glyph.py mobile/core/ui/src/commonMain/composeResources/font/*.ttf

"""Adds a drawn Indian rupee sign (U+20B9) to TrueType fonts that lack it, matched to each font's stem width and digit width."""
import sys
from fontTools.ttLib import TTFont
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.boundsPen import BoundsPen

def stem(font):
    gs = font.getGlyphSet(); cm = font.getBestCmap()
    bp = BoundsPen(gs); gs[cm[ord('l')]].draw(bp)
    x0, _, x1, _ = bp.bounds
    return x1 - x0

def build(font):
    cm = font.getBestCmap()
    if 0x20B9 in cm: return False
    st = stem(font)
    adv, lsb = font['hmtx'][cm[ord('0')]]
    C = font['OS/2'].sCapHeight
    w = adv - 2 * lsb             # drawing width, same as a digit
    L = lsb; R = lsb + w
    def X(f): return round(L + f * w)
    pen = TTGlyphPen(None)
    def rect(x0, y0, x1, y1):      # clockwise in y-up
        pen.moveTo((x0, y1)); pen.lineTo((x1, y1)); pen.lineTo((x1, y0)); pen.lineTo((x0, y0)); pen.closePath()
    bowl_bottom = round(C * 0.50)
    mid = round((C + bowl_bottom) / 2)
    cx = X(0.42); ro = R - cx
    # top and middle bars across the full width
    rect(L, C - st, R, C)
    rect(L, mid - round(st * 0.45), R, mid + round(st * 0.45))
    # right-hand bowl from the top bar down to the bowl bottom
    pen.moveTo((cx, C)); pen.qCurveTo((R, C), (R, mid)); pen.qCurveTo((R, bowl_bottom), (cx, bowl_bottom))
    pen.lineTo((cx, bowl_bottom + st)); pen.qCurveTo((R - st, bowl_bottom + st), (R - st, mid)); pen.qCurveTo((R - st, C - st), (cx, C - st)); pen.closePath()
    # stroke from the bowl back to the left
    rect(L, bowl_bottom, cx + 1, bowl_bottom + st)
    # diagonal leg to the baseline
    dw = round(st * 1.35)
    pen.moveTo((L + round(st * 0.2), bowl_bottom + 1)); pen.lineTo((L + round(st * 0.2) + dw, bowl_bottom + 1)); pen.lineTo((R, 0)); pen.lineTo((R - dw, 0)); pen.closePath()
    glyph = pen.glyph()
    name = 'uni20B9'
    order = font.getGlyphOrder() + [name]
    font.setGlyphOrder(order)
    font['glyf'][name] = glyph
    font['hmtx'][name] = (adv, lsb)
    for table in font['cmap'].tables:
        if table.isUnicode(): table.cmap[0x20B9] = name
    if 'post' in font and hasattr(font['post'], 'extraNames'): pass
    return True

for path in sys.argv[1:]:
    f = TTFont(path)
    changed = build(f)
    if changed:
        f.save(path); print('added to', path)
    else: print('already has it', path)
