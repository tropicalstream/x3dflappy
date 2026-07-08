# X3D Flappy 🕊️⚡

A **3D flappy-bird** for the **RayNeo X3 Pro**, rendered in **OpenGL ES 3.0** as
glowing neon **vector graphics** in the surreal, color-drenched spirit of Jeff
Minter (think *Tempest 2000* / *Space Giraffe*). A wireframe creature falls
through a color-cycling grid tunnel; **tap to flap** it up through the gaps in
oncoming vector-wall gates. Everything is additive glow on black — which on the
waveguide means the whole thing floats as light on the world.

Built with the X3 suite's proven practices: 640×480-logical HUD, **binocular
side-by-side** (stereo viewports, auto-on for the glasses), pure-black canvas,
KEY-event tap input, RayNeo hardware detection, high-score persistence, and a
generous bank of **runtime-synthesized** sound effects — zero vendor AARs, zero
permissions, zero binary assets.

## Controls

**Tap to flap. That's the whole game.**

- Temple **click** (KEY event) or a **touchscreen tap** — both flap.
- On the title screen, a tap begins. On game over, a tap (after a beat) retries.
- There is **no settings menu and no double-tap** — for obvious reasons, a
  double-tap would just be two flaps. Volume is the system volume (left temple
  pad on the glasses).

## What's on screen

- A **neon vector creature** with flapping wings, pitching with its velocity.
- **Vector-wall gates** — wireframe grid panels with a bright gap ring — scroll
  toward you and speed up as you score.
- A **color-cycling grid tunnel**, a scrolling **starfield**, and **particle
  bursts** on every flap, every gate cleared, and a big one when you crash.
- Score, best, and messages drawn in a **glowing stroke (vector) font**.

## Sound

A big synthesized set, generated at first launch and played via SoundPool:
flap / thrust, score, combo, near-miss, gate whoosh, crash, game-over,
new-high-score fanfare, start, zap, power, warn, chirp, blip — plus a looping
**Minter-style drone** under the action.

## Technical notes

- **OpenGL ES 3.0**: one additive line/point shader; the scene is drawn in
  perspective and the HUD in an orthographic overlay, both as `GL_LINES` /
  `GL_POINTS`. On the X3 the frame renders once per eye into left/right
  side-by-side viewports.
- Black clear color = transparency on the waveguide; additive blending makes
  overlapping neon lines bloom.
- No `ar_mode` meta-data (it would halve the display to one lens); Mercury +
  `AR_APP` launcher categories; SBS auto-enabled by RayNeo detection
  (manufacturer/brand/product, not `Build.MODEL` — the X3 reports `ARGF20`).

## Build & install

```bash
cd ~/Projects/x3dflappy
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A built `x3dflappy.apk` also ships in this repo's root for a quick sideload.

Toolchain: gradle 8.9 · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17 · compileSdk 35 /
minSdk 29 · OpenGL ES 3.0.
