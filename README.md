# Logic Simulation

A NAND-first digital logic and computer simulation mod for **Minecraft Java 26.2 / Fabric**.

## Current platform

- Minecraft: `26.2`
- Fabric Loader: `0.19.3`
- Fabric API: `0.156.0+26.2`
- Fabric Loom: `1.17.17`
- Java: `25`
- Gradle Wrapper: `9.5.1`

## Core principles

- NAND is the only primitive logic gate.
- Higher-level gates and chips are user-built and reusable.
- Buses, splitters, mergers, probes, clocks, and I/O are infrastructure rather than hidden logic shortcuts.
- The simulation engine is independent from Minecraft's 20 TPS.
- Accurate NAND-level simulation and optimized/turbo execution are separate modes.
- Circuit hierarchy remains inspectable even when compiled for speed.
- Tracing is event-based and bounded so MHz simulation does not generate unbounded logs.

## Current core

The repository already contains the first pure-Java simulation core:

1. `0 / 1 / X` logic values.
2. NAND nodes.
3. Event-driven propagation.
4. 1-64 bit buses with structural split/merge mapping.
5. Ring-buffer trace recorder.
6. Dependency-free self-tests and an early benchmark tool.

The Fabric 26.2 entrypoint is wired around that core. Minecraft blocks, the circuit editor, custom-chip hierarchy, compiled Turbo simulation, displays, buses in-world, UIB, networking, and computer hardware are upcoming milestones.

## Build

Minecraft 26.2 requires Java 25 for development. GitHub Actions builds the project on Java 25 and Gradle 9.5.1 on every push to `main`.

Windows:

```powershell
.\gradlew.bat build selfTest
```

Linux/macOS:

```bash
./gradlew build selfTest
```

The produced mod JAR is placed in `build/libs/`.
