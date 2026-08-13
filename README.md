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
3. Pick `INPUT`, `OUTPUT`, `NAND`, `SPLITTER`, `MERGER`, or a saved chip from the left library.
4. Click an output port and then a compatible input port to create a wire/bus.
5. Structural changes compile into the real NAND event-driven simulator.

### Navigation and keyboard workflow

The editor now separates navigation from editing so panning cannot accidentally drag a chip.

- **Right-drag** — pan the circuit canvas.
- **Middle-drag** — alternate pan control.
- **Mouse wheel** — zoom around the cursor from 35% to 250%.
- **Left-click / left-drag** — select and move nodes only.
- A small movement threshold prevents hand jitter from moving a selected node.
- Node placement/movement snaps to a light grid for cleaner layouts.
- **Ctrl+S** — open the Save Chip modal.
- **Del / Backspace** — delete the selected node or wire.
- Deleting a node that has attached wires opens a confirmation modal showing how many connections will also be removed.
- **F2** — rename/recolor the selected saved chip or folder in the library.
- **E** — enter/leave route-edit mode for the selected wire.
- **Esc** — cancel placement/wiring/route-edit mode before closing the screen.

The top toolbar uses compact drawn icons for save, new, delete, width down/up, fit, and home. Hovering an icon shows its meaning. Action/error feedback lives in a dedicated **bottom status bar**, not crammed into the top toolbar.

### Direct circuit testing

`INPUT` nodes contain a visible rectangular switch.

- Click it to change `OFF 0` ↔ `ON 1`.
- Multi-bit inputs toggle between zero and all-one bits.
- Wire/node colors update from the live simulator state.
- While creating a wire, compatible target ports are highlighted green and width-mismatched ports red.
- A live orthogonal wire preview follows the cursor before the connection is committed.

### Organized wire routing

Wire geometry is now editable separately from wire logic.

1. Click a wire to select it.
2. Press **E** to enter wire-route edit mode.
3. Square route handles appear.
4. Drag a corner to reposition the route.
5. Drag an **interior horizontal/vertical segment** perpendicular to itself; both end corners move together.
6. Double-click a segment to add a pair of route corners, then drag the new interior segment to create a clean detour.
7. Press **E** again to finish.

Manual route points are saved with the circuit but are **presentation-only**. They do not alter which ports are electrically connected and do not add hidden logic. The self-test suite explicitly verifies that changing a wire route cannot change circuit behavior.

### Component library, folders, and colors

The sidebar is a compact mixed component library:

- **PRIMITIVES** — Input, Output, NAND.
- **ROUTING** — Splitter, Merger.
- User folders containing reusable chips.
- **OTHER** for unfiled chips.

Controls:

- Click the **+** icon at the bottom to open **Add Folder**.
- Choose folder name and color in the modal.
- Click a folder to expand/collapse it.
- Drag a chip row onto a folder to move it.
- Select a chip/folder and press **F2** to rename/recolor it.
- Renaming a chip also rewrites references inside already-saved parent chips so a renamed lower-level chip does not silently break higher-level designs.
- Deleting a folder moves its chips to OTHER; it does not delete their circuit files.
- Left-click a saved chip to place it; right-click it to open it for editing.

Folder/color organization is stored in `config/logic-simulation/library.json`. Circuit files remain under `config/logic-simulation/chips/` as `.logicchip.json` files.

### Save Chip modal and reusable body layout

**Ctrl+S** opens a proper save modal instead of relying on fields in the toolbar. It configures:

- chip name,
- chip color,
- reusable body width,
- reusable minimum body height,
- spacing between exposed input/output pins.

This means a chip with two inputs can intentionally be made taller so the two pins have more visual separation. Saved dimensions are used when that custom chip is placed inside another circuit.

Safe ranges are enforced so corrupted/accidental values cannot create unusable UI geometry:

- width: `72–260`,
- minimum height: `42–300`,
- pin spacing: `10–48`.

### Buses, Splitter, and Merger

Bus width is part of the port type. A multi-bit connection is rendered as one bus wire labeled with its width, for example `[16]`.

- `SPLITTER N`: one N-bit bus input → N individual 1-bit outputs.
- `MERGER N`: N individual 1-bit inputs → one N-bit bus output.
- Width mismatches are rejected; for example, `16-bit → 1-bit` cannot be wired directly.

Splitter and Merger are structural routing primitives. They do not add hidden logic gates.

### Reusable custom chips

Custom chips remain NAND-authentic. At runtime they are recursively flattened into the NANDs the player actually built; a saved `NOT`, `Adder`, or later `ALU` is not a magic prebuilt gate.

## Core principles

- NAND is the only primitive logic gate.
- Higher-level gates and chips are user-built and reusable.
- Buses, splitters, mergers, probes, clocks, and I/O are infrastructure rather than hidden logic shortcuts.
- The simulation engine is independent from Minecraft's 20 TPS.
- Accurate NAND-level simulation and optimized/turbo execution are separate modes.
- Circuit hierarchy remains inspectable even when compiled for speed.
- Tracing is event-based and bounded so MHz simulation does not generate unbounded logs.

## Core engine

The repository currently contains:

1. `0 / 1 / X` logic values.
2. NAND nodes.
3. Event-driven propagation.
4. Typed 1–64-bit buses with structural split/merge mapping.
5. Freeform circuit compiler with width validation.
6. Recursive custom-chip flattening to NAND.
7. Persistent presentation-only orthogonal wire routes.
8. Persistent custom-chip visual dimensions/pin spacing.
9. Ring-buffer trace recorder.
10. Self-tests for NAND logic, buses, Split/Merge, custom chips, width mismatches, route/logic separation, chip visual bounds, and world interconnect validation.

## World interconnect foundation

The physical-computer layer has a validated typed topology model and the first placeable cable prototypes.

- `SIGNAL` / **Signal Wire**: exactly 1 bit.
- `BUS` / **Bus Cable**: multi-bit, 2–64 bits.
- Output → input direction is validated.
- Widths must match exactly; there is no hidden conversion.
- An input cannot silently acquire multiple drivers.
- Shared tri-state buses/arbitration will be explicit later rather than faked.

`Signal Wire` and `Bus Cable` exist as separate placeable blocks/items with slim cubic models and collision shapes. They are still an **early physical prototype**: they have the correct type identity but are not yet bound to world device ports or propagating virtual signals through adjacent blocks.

## Next hardware milestone

Add world-space device ports and topology discovery, bind placed cable networks to the typed `InterconnectGraph`, then expose saved custom chips as world devices. CPU/RAM/GPU/storage can then connect through the same system without using Minecraft Redstone as the computer bus.

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
