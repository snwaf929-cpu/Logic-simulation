# Logic Simulation

A NAND-first digital logic and computer simulation project intended to become a Fabric Minecraft mod.

## Core principles

- NAND is the only primitive logic gate.
- Higher-level gates and chips are user-built and reusable.
- Buses, splitters, mergers, probes, clocks, and I/O are infrastructure rather than hidden logic shortcuts.
- The simulation engine is independent from Minecraft's 20 TPS.
- Accurate NAND-level simulation and optimized/turbo execution are separate modes.
- Circuit hierarchy remains inspectable even when compiled for speed.
- Tracing is event-based and bounded so MHz simulation does not generate unbounded logs.

## First milestone

Build and benchmark a pure-Java logic core before adding Minecraft blocks or UI:

1. `0 / 1 / X` logic values.
2. Signals and NAND nodes.
3. Event-driven propagation.
4. Buses, splitter, and merger mapping.
5. Hierarchical chip definitions.
6. Ring-buffer trace recorder.
7. Benchmarks for NAND evaluations and virtual clock throughput.

Minecraft/Fabric integration comes after the core is proven correct and fast.
