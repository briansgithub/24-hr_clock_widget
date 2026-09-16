#!/usr/bin/env python3
"""Generate Android launcher icons from assets/app_icon.svg.

Developer-only dependencies: python -m pip install Pillow resvg-py
Run from any directory: python scripts/generate_app_icons.py
The app does not need an SVG renderer at runtime.
"""

from __future__ import annotations

import io
from pathlib import Path
import xml.etree.ElementTree as ET

from PIL import Image
import resvg_py

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"
ANDROID = "http://schemas.android.com/apk/res/android"
BACKGROUND = "#46555E"
# Center the gray disc, rather than the asymmetric artwork's bounding box.
ART_CENTER = (246, 276)
ICON_SCALE = 1.19
ADAPTIVE_SCALE = ICON_SCALE * 72 / 108
ET.register_namespace("android", ANDROID)


def attr(name: str) -> str:
    return f"{{{ANDROID}}}{name}"


def vector(source: ET.Element, monochrome: bool = False) -> bytes:
    """Translate the source's flat paths/circles to Android vector geometry."""
    result = ET.Element("vector", {
        attr("width"): "108dp", attr("height"): "108dp",
        attr("viewportWidth"): "512", attr("viewportHeight"): "512",
    })
    # Adaptive layers include 18dp of overscan around the 72dp visible tile.
    # Match the visible tile's 19% zoom while keeping every marker within the
    # central 66dp safe circle (the sun is the furthest point, at radius 196).
    group = ET.SubElement(result, "group", {
        attr("pivotX"): str(ART_CENTER[0]), attr("pivotY"): str(ART_CENTER[1]),
        attr("translateX"): str(256 - ART_CENTER[0]),
        attr("translateY"): str(256 - ART_CENTER[1]),
        attr("scaleX"): str(ADAPTIVE_SCALE), attr("scaleY"): str(ADAPTIVE_SCALE),
    })

    def visit(node: ET.Element, inherited: dict[str, str]) -> None:
        tag = node.tag.rsplit("}", 1)[-1]
        values = {**inherited, **node.attrib}
        if tag in ("svg", "g"):
            for child in node:
                visit(child, values)
            return
        if tag in ("title", "desc", "rect"):
            return  # Adaptive background is its own full-bleed layer.
        if tag == "circle":
            cx, cy, radius = (float(values[key]) for key in ("cx", "cy", "r"))
            # Keep a half-lit moon in themed icons, not an opaque full disc.
            if monochrome and values.get("fill") == "#000000":
                return
            data = (f"M{cx-radius:g},{cy:g}a{radius:g},{radius:g} 0,1 0 "
                    f"{2*radius:g},0a{radius:g},{radius:g} 0,1 0 {-2*radius:g},0Z")
        elif tag == "path":
            data = values["d"]
        else:
            raise ValueError(f"Unsupported SVG element: {tag}")
        # In a single-color glyph the strands simply intersect. Omit the
        # slate under-strokes that separate crossings in the color artwork.
        if monochrome and values.get("stroke") == BACKGROUND:
            return
        props = {attr("pathData"): data}
        for src, dest in (("fill", "fillColor"), ("stroke", "strokeColor")):
            color = values.get(src, "#000000" if src == "fill" else "none")
            props[attr(dest)] = ("#00000000" if color == "none" else
                                "#FFFFFFFF" if monochrome else color)
        for src, dest in (("stroke-width", "strokeWidth"),
                          ("stroke-linecap", "strokeLineCap"),
                          ("stroke-linejoin", "strokeLineJoin")):
            if src in values:
                props[attr(dest)] = values[src]
        ET.SubElement(group, "path", props)

    visit(source, {})
    ET.indent(result)
    return ET.tostring(result, encoding="utf-8", xml_declaration=True) + b"\n"


def rendered(source: str, size: int) -> Image.Image:
    return Image.open(io.BytesIO(resvg_py.svg_to_bytes(
        svg_string=source, width=size, height=size,
    ))).convert("RGBA")


def masked(source: str, shape: str) -> str:
    start = source.index('<rect width="512"')
    background_end = source.index('/>', start) + 2
    clip = ('<circle cx="256" cy="256" r="256"/>' if shape == "round" else
            '<rect width="512" height="512" rx="112"/>')
    return (source[:start] + '<defs><clipPath id="icon-mask">' + clip +
            '</clipPath></defs><g clip-path="url(#icon-mask)">' +
            source[start:background_end] +
            f'<g transform="translate(256 256) scale({ICON_SCALE}) '
            f'translate(-{ART_CENTER[0]} -{ART_CENTER[1]})">' +
            source[background_end:]).replace('</svg>', '</g></g></svg>')


def main() -> None:
    source = (ROOT / "assets/app_icon.svg").read_text(encoding="utf-8")
    parsed = ET.fromstring(source)
    for name, mono in (("ic_launcher_foreground", False),
                       ("ic_launcher_monochrome", True)):
        (RES / "drawable" / f"{name}.xml").write_bytes(vector(parsed, mono))

    # Render once at high resolution, then downsample for clean small icons.
    square = rendered(masked(source, "square"), 1024)
    round_icon = rendered(masked(source, "round"), 1024)
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                          ("xxhdpi", 144), ("xxxhdpi", 192)):
        for name, image in (("ic_launcher", square), ("ic_launcher_round", round_icon)):
            image.resize((size, size), Image.Resampling.LANCZOS).save(
                RES / f"mipmap-{density}" / f"{name}.png")

    print("Generated Android vector/themed/density icons.")


if __name__ == "__main__":
    main()
