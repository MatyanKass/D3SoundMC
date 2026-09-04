# Changelog

*[Русская версия](CHANGELOG.ru.md)*

Version format: `1.6.1` — the second digit changes on noticeable engine work,
the third on small fixes.

## V-1.7.0 — 4 September 2026

* **Streamed sounds went through vanilla, bypassing all physics.** Anything the
  game streams — music discs in a jukebox, long ambient loops — was handed back
  to the vanilla engine untouched, and vanilla has no occlusion at all. A
  jukebox behind a stone wall sounded as if the wall were not there. This is why
  testing with a record kept showing sound coming through blocks: those sounds
  never reached the engine (the on-screen counter showed a 0.0 ms solve, meaning
  no sources at all).
* Streamed sounds are now captured too, and fed to the mixer in chunks through a
  ring buffer: playback starts immediately and memory stays flat no matter how
  long the track is — a six-minute record costs the same as a footstep. If the
  stream stalls or dies, the source is dropped instead of hanging forever.

## V-1.6.1 — 4 September 2026

* The active preset button is no longer greyed out. It read as a fault: the
  preset you already had was literally unclickable. It is now marked with a tick
  and any button can be pressed — which also puts a preset back after manual
  tweaks.
* The conflict detector stopped crying wolf. It rated a mod by any *mention* of
  a sound class, and anything that merely wants to play a sound has to call the
  sound engine — so FancyMenu, Melody, Dynamic FPS and CreativeCore were all
  labelled "better disable this". Mixing into a class is now told apart from
  calling it, and only mods that change those classes' behaviour raise a real
  warning.

## V-1.6.0 — 4 September 2026

* When there were too many sources the list was truncated arbitrarily, so the
  nearest and most important sound could be the one dropped. The most distant
  ones go first now.
* New setting: how many sources get the full physics (auto 6–24 by quality).
  Cost grows in direct proportion to them, so this is a direct optimisation
  lever.
* New setting: diffraction strength — a multiplier on the edge loss. Costs no
  CPU, only changes the sound.

## V-1.5.0 — 4 September 2026

* **Walls leaked.** The source-visibility check started exactly on a block face,
  `floor()` landed inside the wall itself, and the first grid step skipped that
  cell. A one-block barrier became transparent, and reflections came through it
  full-band and louder than honest diffraction — this is what was heard as
  "sound coming through the wall".
* **New: transmission through barriers.** The material sound-insulation table
  had never been used anywhere. A wall now honestly passes part of the sound
  under the mass law (+6 dB per doubling of thickness): stone costs 54 dB and
  leaves a dull rumble, wool costs 9.7 dB and lets nearly everything through.
* Lava wired up as a medium: sound in it is slow and dead.
* New settings: transmission through walls and its level, simulation range,
  update interval. The on-screen counter shows radius and interval.
* Documentation rewritten to match reality; English version and MIT licence
  added.

## V-1.4.0 — 1 September 2026

* Presets: Realistic (High), Realistic (Low), Balanced, Performance, High
  quality.

## V-1.3.4 — 31 August 2026

* **No more cathedral under an open sky.** A ray that escapes upward now counts
  as total absorption — it is never coming back. On a stone shore the engine
  used to compute a 12-second decay and drown everything in tail.
* The limiter stopped bending quiet sounds: below the knee the signal passes
  through untouched, and only peaks are squeezed.

## V-1.3.3 — 31 August 2026

* Rays are traced on several threads; volume and settings apply on the fly.

## V-1.3.2 — 31 August 2026

* The engine measures its own CPU cost and stays inside its allotted share.

## V-1.3.1 — 31 August 2026

* Numeric bench: the maths is checked against references and formulas, and never
  plays a sound. Conflicting-mod scanning moved to the background.

## V-1.3.0 — 31 August 2026

* **New: structure-borne sound** — the reason you hear the neighbours through a
  wall. It arrives before the airborne path and is heard from the wall itself.
* A screen listing mods that compete for sound.

## V-1.2.3 — 31 August 2026

* Engine volume goes up to 500 %.

## V-1.2.2 — 31 August 2026

* Fixed volume, head shadow and echo off foliage.

## V-1.2.1 — 31 August 2026

* In-game settings, on-screen load counter, the medium around the listener, and
  partial blocks (slabs, stairs, fences) in the acoustics.

## Before V-1.2.1

Mod scaffold, interception of the game's sound engine, and the first voxel ray
tracing: diffraction, scattered reflections, CPU-adaptive quality.
