#!/usr/bin/env python3
"""Generates 60 distinct JPEG posters 342x513 for the poster spike."""
import os, sys, random, subprocess
from PIL import Image, ImageDraw, ImageFont

POSTERS_DIR = os.path.abspath(os.environ.get("POSTERS_DIR", "F:/Dev/tvdemo-sdk/web2/posters"))
WWW_POSTERS_DIR = os.path.abspath(os.environ.get("WWW_POSTERS_DIR", "F:/Dev/tvdemo-sdk/web2/www/posters"))

TITLES = [
    "COSMIC DAWN", "NEON SHADOWS", "SILENT ORBIT", "CYBER RUNNER", "THE LAST VOYAGE",
    "DEEP ECHOES", "MIDNIGHT DRIVE", "QUANTUM REALM", "STELLAR DUST", "AURORA SKY",
    "GHOST SIGNAL", "VELOCITY ZERO", "EMERALD PEAK", "IRON HORIZON", "DARK MATTER",
    "SOLAR FLARE", "NEBULA DRIFT", "LOST PROTOCOL", "VAPOR TRAIL", "CHRONO SHIFT",
    "FROZEN STATIC", "ECLIPSE RISING", "TITAN ENGINE", "OBSIDIAN BAY", "PULSAR FLASH",
    "RADIO SILENCE", "ALPHA STATION", "CIRCUIT BREAKER", "VOID RUNNER", "DESERT MIRAGE",
    "HYPER LIGHT", "NIGHT CALLER", "STEEL OCEAN", "RED SECTOR", "FRACTURE LINE",
    "ORBITAL DECAY", "SHADOW FALL", "TWILIGHT ZONE", "INFINITY CORE", "ROGUE PLANET",
    "ZERO HOUR", "PRISM BREAK", "TERMINAL NODE", "VECTOR FIELDS", "STATIC DREAMS",
    "BEYOND HORIZONS", "PHANTOM GRID", "COBALT SKY", "SUB ZERO", "WARP MATRIX",
    "METEOR RAIN", "GRAVITY WELL", "CARBON DRIFT", "ASTRAL PLANE", "NEON HIGHWAY",
    "THUNDER RUN", "DIGITAL RAIN", "OUTER LIMITS", "OMEGA POINT", "FINAL FRONTIER"
]

def ensure_dirs():
    os.makedirs(POSTERS_DIR, exist_ok=True)
    # Link or junction in web2/www/posters so serve.py (which serves web2/www) reaches them
    if not os.path.exists(WWW_POSTERS_DIR):
        try:
            # Try Windows directory junction first
            subprocess.run(["cmd.exe", "/c", "mklink", "/J", WWW_POSTERS_DIR, POSTERS_DIR], check=True, stdout=subprocess.DEVNULL)
        except Exception:
            try:
                os.symlink(POSTERS_DIR, WWW_POSTERS_DIR)
            except Exception:
                pass

def get_font(size):
    font_paths = [
        "C:/Windows/Fonts/arialbd.ttf",
        "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/segoeui.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    ]
    for p in font_paths:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()

def main():
    ensure_dirs()
    font_big = get_font(28)
    font_sub = get_font(18)
    font_num = get_font(52)

    sizes = []
    for i in range(60):
        random.seed(4200 + i)
        im = Image.new("RGB", (342, 513))
        draw = ImageDraw.Draw(im)

        # Gradient background
        c1 = (random.randint(15, 230), random.randint(15, 230), random.randint(15, 230))
        c2 = (random.randint(15, 230), random.randint(15, 230), random.randint(15, 230))
        for y in range(513):
            t = y / 512.0
            r = int(c1[0] * (1 - t) + c2[0] * t)
            g = int(c1[1] * (1 - t) + c2[1] * t)
            b = int(c1[2] * (1 - t) + c2[2] * t)
            draw.line([(0, y), (341, y)], fill=(r, g, b))

        # Varied shapes and details so each decodes uniquely and reaches ~30-60 KB
        for _ in range(40):
            x = random.randint(0, 342)
            y = random.randint(0, 513)
            rad = random.randint(15, 75)
            col = (random.randint(0, 255), random.randint(0, 255), random.randint(0, 255))
            draw.ellipse([x - rad, y - rad, x + rad, y + rad], outline=col, width=random.randint(2, 5))

        # Prominent number and title
        num_str = f"{i + 1:02d}"
        draw.text((25, 40), num_str, fill=(255, 255, 255), font=font_num)

        # Darkened title banner at bottom
        draw.rectangle([0, 390, 342, 513], fill=(12, 16, 26))
        title = TITLES[i % len(TITLES)]
        draw.text((20, 410), title, fill=(245, 247, 255), font=font_big)
        draw.text((20, 455), f"POSTER {num_str} • 2026", fill=(154, 164, 191), font=font_sub)

        # 2px border
        draw.rectangle([0, 0, 341, 512], outline=(255, 255, 255), width=2)

        out_path = os.path.join(POSTERS_DIR, f"poster_{i}.jpg")
        im.save(out_path, "JPEG", quality=90)
        sz = os.path.getsize(out_path)
        sizes.append(sz)

    # Ensure www/posters has the files if junction wasn't possible
    if os.path.exists(WWW_POSTERS_DIR) and not os.path.samefile(POSTERS_DIR, WWW_POSTERS_DIR):
        for f in os.listdir(POSTERS_DIR):
            src_f = os.path.join(POSTERS_DIR, f)
            dst_f = os.path.join(WWW_POSTERS_DIR, f)
            if not os.path.exists(dst_f):
                import shutil
                shutil.copy2(src_f, dst_f)

    min_kb = min(sizes) / 1024.0
    max_kb = max(sizes) / 1024.0
    avg_kb = (sum(sizes) / len(sizes)) / 1024.0
    print(f"Generated 60 posters in {POSTERS_DIR}")
    print(f"Sizes: min={min_kb:.1f} KB, max={max_kb:.1f} KB, avg={avg_kb:.1f} KB")

if __name__ == "__main__":
    main()
