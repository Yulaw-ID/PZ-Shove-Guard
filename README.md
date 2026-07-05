# PZ Shove Guard

Shove Guard is an archived Java Agent that patched the Bluetooth Shove exploit in Project Zomboid Build 42.15.

**This repository is provided for educational purposes only.**

The exploit was officially fixed by The Indie Stone in Build 42.16.0 under the changelog entry:

> "Fixed Bluetooth-shoves while holding firearms"

As a result, this project is no longer intended for use on current versions of the game. It is preserved as a reverse-engineering and educational reference.

## Features

- Detects the Bluetooth Shove exploit pattern.
- Prevents invalid ranged shove collisions.
- Handles both player and window collision paths.
- Uses Java Instrumentation and Javassist to patch `CombatManager` at runtime.

## Project Structure

```text
src/
└── shoveguard/
    ├── Agent.java
    └── Hooks.java

lib/
└── javassist-3.30.2-GA.jar

MANIFEST.MF
Friendly Notes.txt
```

## Notes

For a more detailed explanation of how the exploit worked and how the fix was implemented, see **Friendly Notes.txt**.

## License

This project is licensed under the MIT License. See the LICENSE file for details.