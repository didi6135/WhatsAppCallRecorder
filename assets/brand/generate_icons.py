"""Rebuild recorder identity from the same simple vector geometry as the SVG sources.

Pillow is used only to rasterize code-native primitives, with 6x supersampling.
Run from any directory: python assets/brand/generate_icons.py
"""

from pathlib import Path
import json

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
BRAND = ROOT / "assets" / "brand"
RES = ROOT / "android" / "app" / "src" / "main" / "res"
PREVIEW = ROOT / "preview" / "icons"
NAVY = "#102A2E"
MINT = "#C2EDDE"
CORAL = "#F08D70"
PAPER = "#F6F8F6"
BARS = [(27, 47, 33, 61), (37, 39, 43, 69), (65, 39, 71, 69), (75, 47, 81, 61)]
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
SUPER = 6


def draw_mark(draw, scale, offset=(0, 0), monochrome=False):
    def rect(bounds):
        return tuple(round((v + offset[i % 2]) * scale) for i, v in enumerate(bounds))

    color = NAVY if monochrome else MINT
    for bounds in BARS:
        draw.rounded_rectangle(rect(bounds), radius=round(3 * scale), fill=color)
    draw.ellipse(rect((43.5, 43.5, 64.5, 64.5)), fill=color)
    draw.ellipse(rect((48.75, 48.75, 59.25, 59.25)), fill=(0, 0, 0, 0) if monochrome else CORAL)


def render(size, background=None, mask=None, monochrome=False, splash=False):
    high = size * SUPER
    image = Image.new("RGBA", (high, high), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    if background:
        if mask == "circle":
            draw.ellipse((0, 0, high - 1, high - 1), fill=background)
        elif mask == "rounded":
            draw.rounded_rectangle((0, 0, high - 1, high - 1), radius=round(high * 0.23), fill=background)
        else:
            draw.rectangle((0, 0, high - 1, high - 1), fill=background)
    if splash:
        # Keep the logo compact on the launch screen and inside Android's safe mask.
        margin = high * 0.2
        draw.ellipse((margin, margin, high - margin, high - margin), fill=NAVY)
        draw_mark(draw, high * 0.6 / 108, (36, 36))
    else:
        draw_mark(draw, high / 108, monochrome=monochrome)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def svg(mark_only=False, monochrome=False, splash=False):
    color = NAVY if monochrome else MINT
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">']
    if splash:
        parts.append(f'<circle cx="54" cy="54" r="32.4" fill="{NAVY}"/>')
        parts.append('<g transform="translate(21.6 21.6) scale(.6)">')
    elif not mark_only:
        parts.append(f'<path fill="{NAVY}" d="M0 0h108v108H0z"/>')
    for left, top, right, bottom in BARS:
        parts.append(f'<rect x="{left}" y="{top}" width="{right-left}" height="{bottom-top}" rx="3" fill="{color}"/>')
    if monochrome:
        parts.append(f'<path fill="{color}" fill-rule="evenodd" d="M54 43.5a10.5 10.5 0 1 0 0 21a10.5 10.5 0 1 0 0-21M54 48.75a5.25 5.25 0 1 1 0 10.5a5.25 5.25 0 1 1 0-10.5"/>')
    else:
        parts.append(f'<circle cx="54" cy="54" r="10.5" fill="{MINT}"/>')
        parts.append(f'<circle cx="54" cy="54" r="5.25" fill="{CORAL}"/>')
    if splash:
        parts.append('</g>')
    parts.append('</svg>')
    return "\n".join(parts) + "\n"


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.suffix == ".webp":
        image.save(path, lossless=True, method=6)
    else:
        image.save(path)


def font(size, bold=False):
    path = Path("C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf")
    return ImageFont.truetype(str(path), size) if path.exists() else ImageFont.load_default()


def preview():
    canvas = Image.new("RGB", (1120, 700), PAPER)
    draw = ImageDraw.Draw(canvas)
    draw.text((56, 36), "Recorder / app identity", fill=NAVY, font=font(34, True))
    draw.text((56, 85), "An original voice mark. Ready for the Android launcher.", fill="#52676A", font=font(19))
    for x, shape, title in [(56, "rounded", "Classic"), (378, "circle", "Adaptive mask"), (700, None, "Store / Expo")]:
        image = render(248, NAVY, shape)
        canvas.paste(image, (x, 138), image)
        draw.text((x, 402), title, fill=NAVY, font=font(19, True))
    draw.rounded_rectangle((56, 460, 1064, 646), radius=24, fill="white")
    draw.text((80, 479), "Launcher sizes", fill=NAVY, font=font(19, True))
    xpos = 80
    for size in [72, 48, 32, 24]:
        image = render(size, NAVY, "rounded")
        canvas.paste(image, (xpos, 530), image)
        draw.text((xpos, 607), f"{size}px", fill="#52676A", font=font(15))
        xpos += 110
    draw.text((660, 479), "Themed Android icon", fill=NAVY, font=font(19, True))
    mono = render(112, monochrome=True)
    canvas.paste(mono, (660, 515), mono)
    draw.text((56, 666), "Navy #102A2E   /   Mint #C2EDDE   /   Record #F08D70", fill="#52676A", font=font(16))
    save(canvas, PREVIEW / "recorder-icon-preview.png")


def main():
    BRAND.mkdir(parents=True, exist_ok=True)
    (BRAND / "recorder-icon.svg").write_text(svg(), encoding="utf-8")
    (BRAND / "recorder-mark.svg").write_text(svg(mark_only=True), encoding="utf-8")
    (BRAND / "recorder-monochrome.svg").write_text(svg(mark_only=True, monochrome=True), encoding="utf-8")
    (BRAND / "recorder-splash.svg").write_text(svg(mark_only=True, splash=True), encoding="utf-8")
    save(render(1024, NAVY), ROOT / "assets" / "icon.png")
    save(render(1024), ROOT / "assets" / "adaptive-icon.png")
    save(render(1024, monochrome=True), ROOT / "assets" / "monochrome-icon.png")
    save(render(1024, splash=True), ROOT / "assets" / "splash-icon.png")
    save(render(48, NAVY, "rounded"), ROOT / "assets" / "favicon.png")
    manifest = []
    for density, factor in DENSITIES.items():
        launcher_size = round(48 * factor)
        foreground_size = round(108 * factor)
        splash_size = round(288 * factor)
        path = RES / f"mipmap-{density}"
        save(render(launcher_size, NAVY, "rounded"), path / "ic_launcher.webp")
        save(render(launcher_size, NAVY, "circle"), path / "ic_launcher_round.webp")
        save(render(foreground_size), path / "ic_launcher_foreground.webp")
        save(render(splash_size, splash=True), RES / f"drawable-{density}" / "splashscreen_logo.png")
        manifest.append({"density": density, "launcher": launcher_size, "adaptive_foreground": foreground_size, "splash": splash_size})
    PREVIEW.mkdir(parents=True, exist_ok=True)
    (PREVIEW / "icon-assets.json").write_text(json.dumps({"vector_viewbox": 108, "safe_bounds": [27, 39, 81, 69], "densities": manifest}, indent=2) + "\n", encoding="utf-8")
    preview()


if __name__ == "__main__":
    main()
