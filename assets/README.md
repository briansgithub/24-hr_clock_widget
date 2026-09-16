# Android app icon

`app_icon.svg` is the approved five-wave circadian helix design used by the Android launcher. Keep it as the editable source of truth.

To regenerate platform assets:

```sh
python -m pip install Pillow resvg-py
python scripts/generate_app_icons.py
```

These are asset-generation dependencies only. The Android app does not render SVG at runtime.

Android uses a vector foreground, slate background, and separate monochrome vector for themed launchers. Foreground artwork is inset for adaptive-icon cropping. Density-specific square and round PNGs are also generated.

Android framing centers the gray disc at the exact center of the icon and enlarges the design by 19%. The sun and moon remain inside the adaptive safe circle, with about 4.5% of the visible icon width as edge padding. This framing is applied by the generator; the editable source artwork is unchanged.

The Python widget retains its original dynamically drawn tray and window icon.
