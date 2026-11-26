#!/usr/bin/env python3
"""Swap Tubify launcher-icon PNG entries inside a built APK with the new
Routify-style icon. Only pixel content of known icon entries changes;
everything else (code, versions, other resources) stays byte-identical.

Usage: swap_icons.py IN.apk NEWDIR OUT.apk
NEWDIR holds bg432.png, mono432.png, legacy-48.png ... legacy-192.png.
"""
import struct
import sys
import zipfile

from PIL import Image

DENSITY = {48, 72, 96, 144, 192}


def classify(name, data, zf):
    if not name.endswith(".png") or name.endswith(".9.png"):
        return None
    w, h = struct.unpack(">II", data[16:24])
    if (w, h) == (432, 432):
        im = Image.open(zf.open(name)).convert("RGBA")
        alpha = im.getchannel("A")
        hist = alpha.histogram()
        opaque_frac = sum(hist[128:]) / float(w * h)
        if opaque_frac > 0.5:
            # Full-bleed background (v0.1.2+).
            return "bg432"
        # Glyph-on-transparent layers (v0.1.0/v0.1.1 era): the background is
        # the colored gradient T, the monochrome layer is flat white.
        rs = [p[0] for p in im.getdata() if p[3] > 128]
        mean = sum(rs) / len(rs)
        var = sum((v - mean) ** 2 for v in rs) / len(rs)
        return "mono432" if var < 25.0 else "bg432"
    if w == h and w in DENSITY and len(data) >= 2500:
        return f"legacy-{w}"
    return None


def main():
    src, newdir, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    zin = zipfile.ZipFile(src)
    replacements = {}
    for name in zin.namelist():
        data = zin.read(name)
        kind = classify(name, data, zin)
        if kind:
            replacements[name] = kind

    if not replacements:
        print(f"NO ICON ENTRIES FOUND in {src}", file=sys.stderr)
        sys.exit(1)

    with zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename in replacements:
                kind = replacements[info.filename]
                data = open(f"{newdir}/{kind}.png", "rb").read()
                print(f"swap {info.filename} <- {kind}.png")
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zi.extra = info.extra
            zout.writestr(zi, data)
    print(f"wrote {dst} ({len(replacements)} icon entries swapped)")


if __name__ == "__main__":
    main()
