# Logic Simulation

A NAND-first digital logic and computer simulation mod for **Minecraft Java 26.2 / Fabric**.

## Current platform

- Minecraft: `26.2`
- Fabric Loader: `0.19.3`
- Fabric API: `0.156.0+26.2`
- Fabric Loom: `1.17.17`
- Java: `25`
- Gradle Wrapper: `9.5.1`

## Current playable milestone — freeform circuit editor

1. Find **Circuit Block** in the Building Blocks creative tab.
2. Place it and right-click it with an empty hand.
3. The editor opens on an empty freeform canvas.
4. Place `INPUT`, `NAND`, `OUTPUT`, `SPLITTER`, `MERGER`, or a previously saved `CUSTOM CHIP`.
5. Click an output port and then a compatible input port to create a wire.
6. The circuit recompiles into the real NAND event-driven simulator after structural changes.

### Editor controls

- **Left click a tool, then the canvas** — place that node.
- **Left drag a node** — move it.
- **Click OUT port → IN port** — connect a wire/bus.
- **Right-click an INPUT** — toggle it between zero and all-one bits.
- **Left-click a node/wire + DELETE SELECTED** — delete it.
- **Right-click a wire** — delete that wire immediately.
- **Middle-drag** — pan the canvas.
- **Mouse wheel** — zoom from 35% to 250% around the cursor.
- **WIDTH - / WIDTH +** — change selected Input/Output/Splitter/Merger width through `1/2/4/8/16/32/64` bits. Attached wires are cleared when the width changes.
- **RESET VIEW** — reset pan/zoom.

### Buses, splitter, and merger

Bus width is part of the port type. A multi-bit connection is rendered as one bus wire and labeled with its width, for example `[16]`.

- `SPLITTER N`: one N-bit bus input → N individual 1-bit outputs.
- `MERGER N`: N individual 1-bit inputs → one N-bit bus output.
- Width mismatches are rejected, for example `16-bit → 1-bit` cannot be connected directly.

Splitter and Merger are structural wiring primitives; they do not add hidden logic gates.

### Reusable custom chips

Enter a name in **CIRCUIT NAME / SAVE** and press **SAVE CHIP**. Saved circuits are stored in the client config folder under `logic-simulation/chips/` as `.logicchip.json` files.

To reuse one:

1. Enter its name under **CHIP TO INSERT**.
2. Press **+ CUSTOM CHIP**.
3. Place it on the canvas and wire its exposed input/output ports.

At runtime custom chips are recursively flattened into the NANDs the player actually built; they are not magic prebuilt gates. **LOAD** opens a saved chip for editing.

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
4. Typed 1-64 bit buses with structural split/merge mapping.
5. Freeform circuit document compiler with width validation.
6. Recursive custom-chip flattening to NAND.
7. Ring-buffer trace recorder.
8. Self-tests covering NAND logic, buses, Split/Merge, custom chips, and width mismatch rejection.

## Next hardware milestone

The next major layer is **world-space interconnect**: place saved chips/computer components as blocks and connect their exposed typed ports using mod `Wire` and `Bus Cable` blocks. This will be separate from Minecraft redstone and will reuse the same width/type validation as the editor.

## Build / run

Minecraft 26.2 requires Java 25 for development.

Windows:

```powershell
.\gradlew.bat runClient
```

Build + core/editor self-test:

```powershell
.\gradlew.bat build selfTest
```

The produced mod JAR is placed in `build/libs/`.
