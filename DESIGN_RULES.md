# DESIGN_RULES.md — Gozar Android

Scope: the Android app (`app/`), View system + Material 3. The desktop UI
(`desktop_flutter/`) is Flutter and is not governed by this file.

This file exists so a later change does not have to re-derive the design from
the code. It records the tokens that are allowed, the anchors they came from,
and the rules that produced them. Every value in `res/values/` must trace back
to a line here.

## Anchors

1. **Material You (primary)** — the app is a stock Android application: M3
   colour roles, 48dp touch targets, edge-to-edge insets, the platform bottom
   navigation. Nothing here fights a platform convention.
2. **iOS 26 Liquid Glass (surface treatment only)** — contributes the glass
   material and its motion, not the layout, not the type, not the navigation.
   Implemented by `QWEA0/liquidglass` behind the `FoxGlass` facade.

Mixing rule: Material provides colour roles, type scale, spacing and shape
family; the glass anchor may only change *how a surface is drawn*, never where
it sits or how large it is.

## Tokens

### Shape (`res/values/shapes.xml`, `dimens.xml`)

| Token | Value | Used for |
|---|---|---|
| `radius_s` | 16dp | tiles, chips, icon frames |
| `radius_m` | 24dp | rows, cards, list items |
| `radius_l` | 32dp | hero card, sheets, nav bar |
| `card_radius` | 32dp | glass panels |
| `tile_radius` | 16dp | leading icon tiles |

Rules:
- A stack of related rows shares one outline: only the first row rounds its top
  corners and only the last rounds its bottom. This is why a list is a panel,
  not a column of boxes.
- No radius below 16dp on a surface that contains text.
- Radii above 32dp are reserved for pill-shaped controls.

### Surface

| Token | Light | Dark | Purpose |
|---|---|---|---|
| `frost_row` | `#B8FFFFFF` | `#B81A1D24` | rows, cards, nav bar: translucent, not opaque |
| `frost_edge` | `#33FFFFFF` | `#26FFFFFF` | 1dp inner edge, the "glass thickness" line |
| `shell_backdrop_start/end` | `#FDFDFF` → `#E9EEF7` | `#14161C` → `#0A0B0F` | window backdrop gradient |
| `shell_glow`, `shell_glow_alt` | `#33E4002B`, `#2E5B8DEF` | same | two very low-contrast radial washes |

The washes exist because a lens cannot refract a flat colour: with a solid
background the glass reads as a plain translucent rectangle. They are below the
contrast threshold that would tint text, and their alpha is fixed at `0x33` and
`0x2E` in both themes.

### Glass (`FoxGlass`)

| Surface | Radius | Bevel | Refraction | Dispersion |
|---|---|---|---|---|
| hero card | 28dp | 24dp | 14dp | 0.12 + sensor highlight |
| traffic chart card | 28dp | default | default | default |
| server row | 20dp | 16dp | 8dp | default |
| bottom navigation | 32dp | 22dp | 10dp | 0.10 |
| bottom sheet | 28dp | 28dp | 16dp | 0.12 |

Tint: `GlassViewConfig.REGULAR_TINT` (`#2EFFFFFF`) on light,
`GlassViewConfig.DARK_TINT` (`#33101418`) on dark. Both stay under 20% alpha so
text on top keeps its contrast.

## Hard rules

1. **Content never moves for a visual effect.** Glass is inserted *behind*
   existing views (`FoxGlass.wrap` / `rehost`); ids, adapters and click
   listeners are untouched. If a change would require finding a view by id
   afterwards, the change is wrong.
2. **Every glass call is optional.** `FoxGlass.available()` is false on any ABI
   without the native blur kernels and every entry point then returns null and
   leaves the hierarchy alone. Never call the library's classes directly from a
   screen.
3. **No decorative gradients.** Gradients are allowed only where they are the
   backdrop a lens refracts (`fox_hero`, `shell_backdrop`), never as a fill or
   as a text shader. No purple→pink, no rainbow dispersion above 0.25.
4. **A small control never spends its area on its rim.** Below 110dp the
   library scales the lens down; pass `bevelWidthPx` / `refractionHeightPx`
   explicitly for anything under that size.
5. **Touch targets stay 48dp** even when the visual radius shrinks.
6. **Dark theme parity.** Every colour above has a `values-night` counterpart;
   the app must never hardcode a light-only surface.
7. **Copy is real.** Strings come from `res/values/strings.xml` (or
   `strings-fa`). No placeholder text, no "beautiful", no "seamless".
8. **One glass pane per visible region.** Refraction costs a backdrop capture
   per frame; a pane per list row is forbidden, which is why rows use a frosted
   surface and only the sheet *panel* is glass.

## Verification

- Unit: `./gradlew :app:testDebugUnitTest --tests 'com.gozar.FoxGlassTest'`
  (asserts the no-op path, the double-wrap guard and density conversion).
- Layout: `./gradlew :app:assembleRelease` and open the hero, the server sheet
  and the nav bar in both themes.
- Native engine: `tools/hev_config_probe.py` must print `PASS` (it drives the
  real engine against a real tun device).
