# Nessy Brand Kit

Nessy is the friendly sea-creature mascot for the **Nessy** agentic harness project. The name plays on both **harness** and the legendary Loch Ness monster.

The visual identity is intentionally friendly and recognizable: Nessy faces the viewer, has a rounded sea-dragon silhouette, a center head fin, large expressive eyes, and a warm leather-and-brass harness with an **N** badge.

## Quick start

If this archive was downloaded as `nessy-brand-kit.zip`, unpack it directly into your repository's `brand/` directory:

```bash
mkdir -p brand
unzip nessy-brand-kit.zip -d brand
```

For a GitHub README, a good default is:

```markdown
<p align="center">
  <img src="brand/mascot/nessy-mascot-512.png" alt="Nessy mascot" width="320">
</p>
```

For smaller surfaces such as documentation headers, avatars, or application icons, use the simplified mark in `logo/nessy-mark.png` or the pre-sized assets under `icons/`.

## Asset map

| Need | Recommended asset |
| --- | --- |
| README hero / launch post | `mascot/nessy-mascot-full.png` |
| Standard project logo | `logo/nessy-logo-horizontal.png` |
| Narrow/stacked placement | `logo/nessy-logo-stacked.png` |
| Logo mark only | `logo/nessy-mark.png` |
| GitHub organization/repo avatar | `icons/github-avatar.png` |
| Browser favicon | `icons/favicon.ico` or `icons/nessy-icon-32.png` |
| Social preview | `social/github-social-preview.png` |
| One-color placement | `monochrome/nessy-mark-single-color.png` |
| Dark-background placement | `monochrome/nessy-mark-reversed.png` |

## Brand hierarchy

### 1. Full-fidelity mascot

Use the detailed Nessy illustration when personality matters more than compactness: README hero art, launch announcements, blog posts, decks, stickers, and project landing pages.

### 2. Logo-level mark

Use the simplified front-facing Nessy mark for routine branding. The face, center fin, and visible harness are the primary identifying features.

### 3. Micro mark

At favicon and small icon sizes, prioritize the eyes, silhouette, center fin, and smile. Fine harness details will naturally disappear.

## Usage principles

- Keep Nessy **front-facing**.
- Preserve the friendly, curious expression.
- Preserve the center head fin and broad rounded snout as identifying features.
- Keep the harness in the primary mark when the size permits; it is an important part of the Nessy/harness wordplay.
- Prefer the provided color variants rather than arbitrary recoloring.
- Do not stretch, skew, rotate, or add heavy effects to the logo.
- Give the mark clear space rather than placing detailed imagery directly behind it.

## Palette

| Token | Hex | Intended use |
| --- | --- | --- |
| Deep Teal | `#0A3644` | Typography, outlines, dark backgrounds |
| Nessy Teal | `#46A1A4` | Primary brand color |
| Nessy Aqua | `#8CD0CD` | Mascot body / secondary fills |
| Loch Mint | `#B9D5C4` | Soft secondary backgrounds |
| Loch Cream | `#EBE0C1` | Warm light backgrounds |
| Harness Leather | `#814F2F` | Harness accent |
| Harness Brass | `#C99B5A` | Buckles and highlights |

Machine-readable versions are in `palette/palette.json` and `palette/palette.css`.

## Source and production note

The files in `source/` are the approved generated concept artwork from which this v1 kit was derived. The PNG logo variants in this kit are clean raster crops/derivatives of that approved artwork; they are **not hand-authored vector masters**.

That is entirely suitable for a project README, GitHub avatar, social cards, documentation, and most digital use. If Nessy later needs print production, large-format merchandise, or strict brand reproduction, the next step should be to have the simplified mark redrawn as true vector paths and adopt that SVG as the canonical master.

*The `source/` originals are not vendored in this repository; they remain in the design archive.*

## Directory layout

```text
brand/
├── README.md
├── nessy-brand-sheet.png
├── source/
├── mascot/
├── logo/
├── icons/
├── monochrome/
├── social/
└── palette/
```

## Character note

Nessy is referred to as **she/her** in project branding copy.
