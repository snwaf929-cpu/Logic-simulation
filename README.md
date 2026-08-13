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
4. Pick `INPUT`, `OUTPUT`, `NAND`, `SPLITTER`, `MERGER`, or a saved custom chip from the left component library.
5. Click an output port and then a compatible input port to create a wire/bus.
6. The document recompiles into the real NAND event-driven simulator after structural changes.

### Editor navigation and testing

- **Left-drag empty canvas** — pan around the circuit naturally.
- **Middle-drag** — alternate pan control.
- **Mouse wheel** — zoom around the cursor from 35% to 250%.
- **FIT** — frame all nodes automatically.
- **HOME** — reset pan/zoom.
- **Left-drag a node** — move it.
- **Click the visible switch inside an INPUT** — toggle it between `OFF 0` and `ON 1`; multi-bit inputs toggle between zero and all-one bits.
- **Click OUT port → IN port** — connect a wire/bus.
- **Right-click a wire** — delete that wire immediately.
- **Select node/wire + DELETE** — delete the selection.
- **W- / W+** — change selected Input/Output/Splitter/Merger width through `1/2/4/8/16/32/64` bits. Attached wires are cleared when width changes.

Input, Output, and NAND nodes use a compact cubic layout instead of the original oversized cards. Ports stay square rather than circular.

### Component library, folders, and colors

The old vertical wall of vanilla buttons has been replaced by a compact mixed component library:

- Built-in **PRIMITIVES**: Input, Output, NAND.
- Built-in **ROUTING**: Splitter, Merger.
- User-created folders with saved chips nested beneath them.
- An **OTHER** section for unfiled chips.

Library organization is persisted in `config/logic-simulation/library.json` separately from the circuit files, so old `.logicchip.json` chips remain compatible.

- Enter a folder name and press **+ FOLDER** to create a collection.
- Click a folder to expand/collapse it.
- Drag a chip row onto a folder to move it there.
- Select a folder and use **RENAME** or **DELETE**. Deleting a folder moves its chips to OTHER; it does not delete the chip circuits.
- Select a folder or chip and click a color swatch to assign a color.
- Custom chip colors are also shown on instances placed on the circuit canvas.
- Click a saved chip to place an instance. Right-click a saved chip to open it for editing.

### Buses, splitter, and merger

Bus width is part of the port type. A multi-bit connection is rendered as one bus wire and labeled with its width, for example `[16]`.

- `SPLITTER N`: one N-bit bus input → N individual 1-bit outputs.
- `MERGER N`: N individual 1-bit inputs → one N-bit bus output.
- Width mismatches are rejected, for example `16-bit → 1-bit` cannot be connected directly.

Splitter and Merger are structural wiring primitives; they do not add hidden logic gates.

### Reusable custom chips

Enter a name in the top **CHIP** field and press **SAVE**. Saved circuits are stored under `config/logic-simulation/chips/` as `.logicchip.json` files and immediately appear in the component library.

At runtime custom chips are recursively flattened into the NANDs the player actually built; they are not magic prebuilt gates.

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

## World interconnect foundation

The physical-computer layer now has both a validated typed topology model and the first placeable cable prototypes.

- `SIGNAL` / **Signal Wire**: exactly 1 bit.
- `BUS` / **Bus Cable**: multi-bit, 2-64 bits.
- Output → input direction is validated by the interconnect graph.
- Widths must match exactly; there is no hidden conversion.
- An input cannot silently acquire multiple drivers.
- Shared tri-state buses/arbitration are intentionally not faked; they will be implemented as a later explicit layer.

`Signal Wire` and `Bus Cable` now exist as separate placeable blocks/items with slim cubic models and collision shapes. They are an **early physical prototype**: they carry the correct cable identity in code, but they are not yet bound to world device ports or propagating virtual signals through adjacent blocks.

Self-tests verify 1-bit signal connections, 16-bit bus connections, wrong-cable rejection, and width-mismatch rejection.

## Next hardware milestone

Add world-space device ports and topology discovery, bind placed cable networks to the typed `InterconnectGraph`, then expose saved custom chips as world devices. After that, CPU/RAM/GPU/storage blocks can connect through the same system. These cables remain separate from Minecraft redstone.

## Build / run

Minecraft 26.2 requires Java 25 for development.

Windows:

```powershell
.\gradlew.bat runClient
```

Build + all current self-tests:

```powershell
.\gradlew.bat build selfTest
```

The produced mod JAR is placed in `build/libs/`.
