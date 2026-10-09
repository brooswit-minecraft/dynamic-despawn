# dynamic-despawn

Immersive item despawn for Minecraft (NeoForge 1.21.1, Java 21). Mod ID:
`dynamicdespawn`. MIT licensed. See epic MINECRAFT-145, story MINECRAFT-146.

Items left on the ground age and vanish over time instead of sitting forever
or disappearing abruptly at vanilla's fixed timer.

**Current state: scaffold only.** The mod registers and loads; despawn,
placement, and burial behavior ship in a later story.

## Building

Requires a JDK 21 with `javac` on `JAVA_HOME`.

```sh
./gradlew build
```
