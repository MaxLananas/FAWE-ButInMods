# Brand assets

Everything in this folder is part of the repository, not a hotlink: the README renders offline, the
assets ship with the source, and every one of them can be edited here.

| File | What it is | How it was made |
|---|---|---|
| `logo.svg` | Square icon: a wireframe selection cube cut by a builder's axe | Hand-written SVG (gradients, no raster) |
| `hero.svg` | 1200×360 banner: wordmark, version pills, isometric voxel cluster with a selection box | Hand-written SVG |
| `section.svg` | The data layout of one 16³ section: palette, state index, packed longs, written bitset, one-int history rows | Hand-written SVG |
| `bench.svg` | Throughput chart for the rows `./gradlew :core:bench` prints | Hand-written SVG, numbers from the benchmark harness |

## Rules of the house

- **Text lives in the SVG as text.** No baked-in raster labels, so translations and copy edits are a
  find-and-replace away, and the files stay a few kilobytes.
- **Vector only.** No raster images ship here: text stays sharp at any zoom, the whole folder is a
  handful of kilobytes, and nothing has to be regenerated when a screenshot goes stale.
- **No embedded fonts, no external images, no scripts.** GitHub serves these through its image
  proxy, which sanitises SVG; anything that needs a script or a foreign object would not render.
- **Palette** — background `#0a1120`→`#0d1530`, accent cyan `#3fd8ff`→`#26c6ff`, violet `#8b5cf6`,
  text `#c7d5e6`, muted `#7d90a8`. Reuse these if you add a diagram.
- **The chart is data, not decoration.** If a benchmark row changes, `bench.svg` changes with it in
  the same commit; a chart that disagrees with the table is worse than no chart.

## Licence

The SVG assets are distributed under the same **GPL-3.0** licence as the mod
(see `../../LICENSE.txt`). The project icon artwork credited in the README is by
[Elfi (@elfi_draw)](https://www.instagram.com/elfi_draw) and is used with permission.
