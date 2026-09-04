# D3Sound for Minecraft

*[Русская версия](README.md)*

Minecraft's sound engine rewritten around real physics: instead of panning by
distance, the wave actually propagates through the geometry of the world.

A **Fabric** mod for **Minecraft 26.2**, Java 25. Client-side only — the server
does not need it.

The physics comes from [D3Sound](https://github.com/MatyanKass/D3Sound), a
desktop test bench where it is verified against numbers: arrival times match the
analytic result to a fraction of a sample, air absorption agrees with the
ISO 9613-1 tables, the diffuse field level matches the classic formula within
1 dB, and interaural delay and level differences land on measured HRTFs.

## What you hear

Not "effects" but consequences of the physics — each one can be switched off
separately.

* **Direction and distance.** A binaural model of its own: interaural time
  difference, frequency-dependent head shadow, inverse-square falloff.
* **Air.** Speed of sound and absorption from temperature, humidity and
  pressure. Underwater sound is four times faster and barely decays; the Nether
  is a third faster; the End is slow and sluggish; lava is dead and dull.
* **Diffraction.** A wavefront over free cells finds the shortest way around an
  obstacle, and the edge costs Maekawa attenuation — more at higher frequencies.
  That is why sound around a corner is muffled rather than merely quiet.
* **Transmission through walls.** The wave presses on the wall, the wall
  vibrates as a whole and radiates on the far side. The loss is set by the
  material's sound insulation, and thickness follows the mass law (+6 dB per
  doubling). Wool lets nearly everything through; stone costs 54 dB — a rumble
  and nothing more.
* **Structure-borne sound.** The reason you hear the neighbours: the wall is
  driven at the source and radiates at the listener. It arrives before the
  airborne path (3200 m/s) and is heard from the wall, not from the source.
* **Reflections.** Ray tracing from the listener with Lambert scattering. Early
  echoes arrive from the direction of the actual wall, not as anonymous wash.
* **Rooms.** Reverberation time via Eyring, measured from the rays themselves.
  A ray that escapes to the sky counts as total absorption — which is why there
  is no echo under an open sky even in solid stone.
* **Doppler.** From the real travel time of the wave, not a formula bolted on
  top.

## Architecture

```
game sound ──► [Mixin: intercept] ──► [voxel snapshot around the listener]
                                            │
                                            ▼
                          [solver on a background thread: bypass paths,
                           transmission through walls, structure-borne
                           sound, reflections, reverberation time]
                                            │
                                            ▼
                          [binaural mixer] ──► OpenAL
```

The game thread only samples the world and the source positions; all physics
runs on its own thread and is published as a whole, so the mixer always sees a
consistent solution. The audio thread performs no allocation and never touches
the world.

## CPU load

The engine measures what it costs and stays inside its allotted share: when the
machine is free it raises ray count, reflection depth and update rate; when the
system is loaded or a solve overruns, it backs off sharply. Quality climbs
slowly and drops immediately — better to lose a second of accuracy than to drop
frames.

If the automatic choice does not suit you, the manual levers are: CPU share,
total-load ceiling, fixed quality, simulation range and update interval.

## Settings

Opened from the game's own sound settings. Every entry has a tooltip explaining
what it does and what changing it costs. Zero on a numeric slider always means
"Auto".

Presets: Realistic (High), Realistic (Low), Balanced, Performance, High quality.
A preset simply sets the sliders at once; it never touches engine volume, the
on-screen counter, or the master on/off switch.

The settings screen also lists mods that compete for sound and how badly they
interfere.

## Building and testing

```bash
./gradlew bench          # numeric bench: 68 checks, never plays a sound
./gradlew build          # jar in build/libs
./gradlew runClient      # client with the mod
```

The bench computes exactly what the in-game engine does, but checks the numbers
against references and formulas: ISO 9613-1 for air, Eyring for rooms, measured
ITDs for the head, conservation laws for the ray tracing. No test ever plays
audio through the speakers.

Requires JDK 25 (Minecraft 26.x runs on it). The path is set in
`gradle.properties`.

## Licence

MIT — see [LICENSE](LICENSE).
