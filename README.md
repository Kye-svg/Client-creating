# Click's Client

A Meteor Client addon for Minecraft 1.21.11. It adds **one module**, "Clicks Client", in its own "Click's Client" category. Everything is in that module's single settings window: a section per feature, each with its own on/off switch, settings and colours, followed by shared Markers, Tracers, Alerts and Diagnostics sections. Stock Meteor GUI, no logo, no automation. It only reads data the server sends you.

Every feature only looks at Y -1 and below, and reports what it finds as **flagged chunks**: a plate and a beam floating above the ground over the chunk, optional tracers, and a box on the exact spot.

## Features (sections of the one settings window)

| Section | What it does | What it reads |
|---|---|---|
| Light ESP Bypass | Boxes every light source (torch, lantern, lamp, lava...) | The raw block-light arrays in the server's light packets, so it does not depend on the blocks being visible |
| Anti-ESP Bypass | Flags chunks where the server shows a perfectly uniform solid section (filler where terrain should be mixed). Remembers spawners and storage the moment the server reveals them, even after it hides them again | Chunk blocks, block entities, light |
| Redstone Inspector | Flags redstone that keeps flickering on and off (clocks, lamp flashers, farms) | Block state flips, and repeated light updates for the same section |
| Huge Area Detector | Groups neighbouring chunks that look excavated or built over into one huge area | Touched light sections, fully cleared layers, filler sections |
| Monster Spawner Detector | Finds spawners, tells you which mob, remembers them | Block entities and block states |
| Motion Scanner | Movement while you stand on the surface. Flags fade after the movement stops | Light bursts, sounds with coordinates, tracked entities, block changes |

Shared settings: marker style (plate / pillar / both / outline), height (surface / fixed / player), fill and outline colours, a low and a high colour per feature blended by detection strength, tracer colour and count, render distance, far-plane extension so distant markers are not clipped, chat alerts, expiry, and a clear button.

## Getting the jar

I could not build the jar for you: building downloads Minecraft, its mappings and Meteor, which needs internet access. Pick one:

**A. Build in the cloud (nothing to install).**
1. Make a free GitHub repository and upload everything in this folder, including the hidden `.github` folder.
2. Open the repository's **Actions** tab. The "Build jar" workflow runs on upload; you can also start it with "Run workflow".
3. When it finishes, open the run and download the **clicks-client-jar** artifact. Inside is the one jar, `clicks-client-1.0.0.jar`.

**B. Build on your PC.** Install JDK 21 and Gradle 9.x, then in this folder run `gradle build`. The jar is `build/libs/clicks-client-1.0.0.jar`.

**C. Use the official template.** Copy `src/` into a fresh clone of Meteor's `meteor-addon-template` for 1.21.11 and keep its build files.

Put the jar in your `mods` folder next to Meteor Client for 1.21.11 (build 86 is the last for this version). Meteor loads it as an addon by itself.

`gradle.properties` holds the versions: Minecraft 1.21.11, Yarn `1.21.11+build.4`, Loader 0.19.3, Meteor `1.21.11-SNAPSHOT`. If your template uses different numbers, use the template's.

## Checking it in game (2 minutes)

1. Stand on the **surface**. Light-based signals pause while you are below Y 0, because your own torches and digging would show up. Each feature says so once in chat if that is why it is quiet. Set its Y limit to -64 to never pause.
2. Turn on the module and run `.clicks test`. A test flag appears on your chunk for 15 seconds, so you can check the plate, beam, colours and tracers.
3. Move to an area you have not loaded (for example `/rtp`), then run `.clicks diag`. It prints what the server is really sending:
   * `chunk packets first=... resent=...`: `resent` above 0 means the server re-sends chunks you already have, which is when hidden terrain gets revealed.
   * `below Y 0 sky: ... zeroed=...`: zeroed light sections are the "something happened here" signal.
   * `below Y 0 block light: ... lit=... brightest=...`: if this stays at `lit=0 brightest=0` after loading lots of chunks, the server is not sending block light below Y 0 and Light ESP has nothing to read.
4. `.clicks clear` clears every flag.

The Diagnostics section can print the same lines to chat every 15 seconds.

## Honest limits

* Tested here: the whole addon compiles against stubs of the Minecraft / Meteor / Orbit APIs (warnings as errors), and 141 checks pass on the detection logic: light decoding, light-source finding including chunk and section borders, flicker windows, clustering, filler-section detection, cleared layers, the flag store, and packet-to-flag pipelines.
* **Not** tested: a build against the real Minecraft and Meteor jars, running in the game, or running on any live server. If the build stops with a compile error, send it to me and it is a one-line fix.
* Whether a signal exists depends on what the server sends. `.clicks diag` shows that.
* The light signals only cover chunks that load or update **after** the module is on, so turn it on before you travel.
* Reading hidden data does not make you invisible: servers can and do ban for this kind of client. DonutSMP's published rules list ESP, radar, tracers and cave-finder style mods as bannable, with enforcement that can extend to linked accounts. This addon adds nothing to hide itself and nothing that automates play.

## Running the logic tests (no Minecraft needed)

```
javac -d out src/main/java/dev/clicksclient/core/*.java src/test/java/SelfTest.java
java -cp out SelfTest
```
