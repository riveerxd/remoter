#!/usr/bin/env python3
"""Writes core/design ColorTokens.kt from the OKLCH table below.

The conversion runs once here, never while drawing. A value outside sRGB keeps
its lightness and hue and loses chroma until it fits, because clipping each
channel on its own shifts the hue, and status colors must look like themselves.
Run: python3 tools/gen_tokens.py
"""
import math
import pathlib

# name: (dark, light). None for light means the same value as dark.
TOKENS = {
    "bg":            ((0.14, 0.005, 260), (1.00, 0.000, 0)),
    "surface":       ((0.19, 0.006, 260), (0.97, 0.003, 260)),
    "surfaceRaised": ((0.23, 0.007, 260), (1.00, 0.000, 0)),
    "line":          ((0.30, 0.008, 260), (0.90, 0.004, 260)),
    "text":          ((0.97, 0.000, 0),   (0.16, 0.005, 260)),
    "textMuted":     ((0.72, 0.010, 260), (0.45, 0.010, 260)),
    # cta is white in dark and near-black in light: the light text value.
    "cta":           ((1.00, 0.000, 0),   (0.16, 0.005, 260)),
    "onCta":         ((0.14, 0.005, 260), (1.00, 0.000, 0)),
    "volt":          ((0.89, 0.190, 125), None),
    "onVolt":        ((0.20, 0.030, 125), None),
    "ok":            ((0.80, 0.170, 150), (0.50, 0.130, 150)),
    "warn":          ((0.82, 0.150, 80),  (0.50, 0.110, 65)),
    "danger":        ((0.68, 0.200, 25),  (0.55, 0.210, 25)),
    "terminal":      ((0.10, 0.005, 260), None),
    # Text inside the always-dark terminal card.
    "onTerminal":    ((0.97, 0.000, 0),   None),
}


def oklch_to_linear_srgb(l, c, h):
    a = c * math.cos(math.radians(h))
    b = c * math.sin(math.radians(h))
    l_ = l + 0.3963377774 * a + 0.2158037573 * b
    m_ = l - 0.1055613458 * a - 0.0638541728 * b
    s_ = l - 0.0894841775 * a - 1.2914855480 * b
    l3, m3, s3 = l_ ** 3, m_ ** 3, s_ ** 3
    return (
        4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
        -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
        -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
    )


def in_gamut(rgb, eps=1e-6):
    return all(-eps <= x <= 1 + eps for x in rgb)


def encode(x):
    x = min(1.0, max(0.0, x))
    return 12.92 * x if x <= 0.0031308 else 1.055 * x ** (1 / 2.4) - 0.055


def to_hex(l, c, h):
    rgb = oklch_to_linear_srgb(l, c, h)
    mapped = False
    if not in_gamut(rgb):
        mapped = True
        lo, hi = 0.0, c
        for _ in range(40):
            mid = (lo + hi) / 2
            if in_gamut(oklch_to_linear_srgb(l, mid, h)):
                lo = mid
            else:
                hi = mid
        rgb = oklch_to_linear_srgb(l, lo, h)
    r, g, b = (round(encode(x) * 255) for x in rgb)
    return f"{r:02X}{g:02X}{b:02X}", mapped


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    out = root / "core/design/src/main/kotlin/me/river/remoter/core/design/ColorTokens.kt"
    lines = [
        "// Written by tools/gen_tokens.py from the OKLCH table in there. Don't edit by hand.",
        "package me.river.remoter.core.design",
        "",
        "import androidx.compose.ui.graphics.Color",
        "",
    ]
    notes = []
    for theme, idx in (("Dark", 0), ("Light", 1)):
        lines.append(f"internal object {theme}Tokens {{")
        for name, (dark, light) in TOKENS.items():
            value = dark if idx == 0 or light is None else light
            hx, mapped = to_hex(*value)
            src = f"oklch({value[0]:g} {value[1]:g} {value[2]:g})"
            lines.append(f"    val {name} = Color(0xFF{hx}) // {src}{' gamut mapped' if mapped else ''}")
            if mapped:
                notes.append(f"{theme}.{name} {src} was outside sRGB, chroma reduced")
        lines.append("}")
        lines.append("")
    out.write_text("\n".join(lines))
    print(f"wrote {out.relative_to(root)}")
    for n in notes:
        print(n)


if __name__ == "__main__":
    main()
