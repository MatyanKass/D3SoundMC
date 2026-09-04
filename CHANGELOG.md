# Changelog

*[Русская версия](CHANGELOG.ru.md)*

Version format: `1.6.1` — the second digit changes on noticeable engine work,
the third on small fixes.

## V-1.9.0 — 4 September 2026

* **A stopped sound could start anyway.** Decoding happens on another thread,
  and `stop` only removed what was already playing — a sound still being
  decoded was not there yet, so the callback started it afterwards regardless.
  For a footstep that is a blip; for a record it meant minutes of music that
  breaking the jukebox would not silence. Sounds are now marked as pending, and
  a stop while pending cancels the start.
* Rays ask a cell how much it is covered **along the axis they entered by**,
  instead of by filled volume. A ray passes a slab from the side and is stopped
  from above, as it should be.
* **Music and ambience are now processed too.** They play in your head rather
  than from a point in the world, so distance and direction do not apply — but
  the medium around you does. Underwater their top end is gone and what remains
  is the low pressure rumble; lava is stronger still; indoors they pick up the
  same tail as everything else, and out in the open there is none. New setting,
  0 % hands them back to the game untouched.
* Music is no longer dropped when the world is noisy: the source limit counts
  only sounds that occupy geometry.

## V-1.8.0 — 4 September 2026

* **Streamed sound arrived in pieces, with a click every fraction of a second.**
  The pump asked the decoder for a chunk of a given size, but `read` returns
  whatever it got — it finishes the packet it started, usually more than asked.
  The scratch buffer was fixed-size, so the tail of every chunk was silently
  dropped, punching a regular hole in the audio. The buffer now grows to fit and
  nothing is discarded.
* **Blocks are read as real geometry instead of one number.** A cell used to be
  described by its filled volume alone, and blocking was a yes/no test at half a
  cell. That was wrong in both directions at once: a stone wall block fills only
  about a third of its cell, so it blocked *nothing* — a jukebox behind a wall
  of them sounded as if you were in the same room — while a slab, at half a
  cell, blocked sound travelling sideways over it, which it cannot.
* Each cell now carries how much the block covers it seen along each axis, taken
  by sampling the real collision shape (cached per block state, so it costs
  nothing per cell). A ray asks for the axis it entered along: a slab stops
  sound from above but not from the side, a wall stops it sideways but not from
  above.
* Occlusion is continuous rather than binary. Foliage costs about 1.5 dB per
  layer and stays audible, thickening as the canopy does; a stone wall block
  costs 17.5 dB across; sound along the face of a slab loses 0.6 dB. Only when
  the path is 75 % covered does it count as blocked and switch to diffraction
  and transmission.
* Blocks taller than their own cell — walls and fences are one and a half — now
  fill the cell above them. That half block used to vanish, leaving a gap over
  every fence that does not exist in the world.

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
