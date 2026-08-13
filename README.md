# Logic Simulation

A NAND-first digital logic and computer simulation mod for **Minecraft Java 26.2 / Fabric**.

## Current platform

- Minecraft: `26.2`
- Fabric Loader: `0.19.3`
- Fabric API: `0.156.0+26.2`
- Fabric Loom: `1.17.17`
- Java: `25`
- Gradle Wrapper: `9.5.1`

## Current playable milestone

The first in-game circuit test is implemented:

1. Find **Circuit Block** in the Building Blocks creative tab.
2. Place it in the world.
3. Right-click it with an empty hand.
4. The Circuit Editor opens with two inputs driving one real NAND gate.
5. Toggle A and B and watch the output and wire states update through the event-driven simulation core.

This is intentionally a small vertical slice. The next editor milestone will add freely placeable nodes, wire creation, delete/move tools, and saving a circuit as a reusable custom chip.

## Core principles

- NAND is the only primitive logic gate.
- Higher-level gates and chips are user-built and reusable.
- Buses, splitters, mergers, probes, clocks, and I/O are infrastructure rather than hidden logic shortcuts.
- The simulation engine is independent from Minecraft's 20 TPS.
- Accurate NAND-level simulation and optimized/turbo execution are separate modes.
- Circuit hierarchy remains inspectable even when compiled for speed.
- Tracing is event-based and bounded so MHz simulation does not generate unbounded logs.

## Core engine

The repository contains:

1. `0 / 1 / X` logic values.
2. NAND nodes.
3. Event-driven propagation.
4. 1-64 bit buses with structural split/merge mapping.
5. Ring-buffer trace recorder.
6. Dependency-free self-tests and an early benchmark tool.

## Build / run

Minecraft 26.2 requires Java 25 for development.

Windows:

```powershell
.\gradlew.bat runClient
```

Build + core self-test:

```powershell
.\gradlew.bat build selfTest
```

The produced mod JAR is placed in `build/libs/`.
