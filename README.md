[![Join the Discord](https://img.shields.io/discord/1487928162636533871?label=Join%20the%20Discord&logo=discord&color=5865F2&logoColor=white)](https://discord.gg/R5NdTVRDpb)

# **Skylist**

Skylist is a Fabric mod for Hypixel SkyBlock focused on scam prevention and player safety.

It scans players in real time during trades, party joins, and other interactions, warning you if someone is linked to known scammer databases. The goal is simple. Stop scams before they happen.

## **Supported Versions**

* Minecraft `26.1.2`
* Minecraft `26.2`

Built with [Stonecutter](https://stonecutter.kikugie.dev/) for multi-version Fabric support. See
[Building](#building) below to compile a specific version.

## **Core Features**

* Real-time scammer detection using external databases
* Trade protection with instant warnings before accepting
* Party join scanning for known scammers
* Fast, clear alerts with actionable responses

## **Trade Protection**

* Scans players when a trade is sent or received
* Warns if the player is flagged in scammer databases
* Helps prevent bad trades before confirmation

## **Party Safety**

* Detects players joining your party in real time
* Alerts you if a flagged player is found
* Designed to give early warning, not force actions

## **Commands**

* `/skylist` â€” Open main menu

## **Dependencies**

* Fabric API
* Fabric Language Kotlin

## **Building**

This project uses [Stonecutter](https://stonecutter.kikugie.dev/) to build against multiple
Minecraft versions from one shared codebase (`src/`). Version-specific settings live in
[stonecutter.properties.toml](stonecutter.properties.toml).

```bash
# Build every supported version, jars land in build/
./gradlew build

# Build just one version
./gradlew ":26.2:build"

# Switch the IDE's active version (affects code completion / the src/ view only)
./gradlew stonecutterSwitchTo26.2
```

Add a new Minecraft version by adding it to `versions(...)` in
[settings.gradle.kts](settings.gradle.kts) and a matching `[x.y.z]` table in
[stonecutter.properties.toml](stonecutter.properties.toml) - no other files need to change unless
that version actually renames an API Skylist uses.

## **Credits**

* Scammer detection powered by SkyBlockZ
