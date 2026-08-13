# Bus quick guide

A bus is one typed connection that carries several bits in parallel. It is not created by chaining BUS nodes together.

## Pack individual bits into a bus

For a 16-bit design:

```text
BIT0  ─┐
BIT1  ─┤
BIT2  ─┤
...    ├──> BITS -> BUS / MERGER [16] ───── [16-bit bus] ─────>
BIT15 ─┘
```

Set the Merger width to 16. Its sixteen 1-bit inputs are bit 0 through bit 15. Its single output is a 16-bit bus.

## Break a bus back into individual bits

```text
[16-bit bus] ───> BUS -> BITS / SPLITTER [16]
                                      ├── BIT0
                                      ├── BIT1
                                      ├── ...
                                      └── BIT15
```

## BUS LINE

`BUS LINE [N]` is only a structural pass-through/organization node for an already-packed N-bit bus. It does not combine separate 1-bit wires. Use a Merger for that.

## Width rule

Widths must match exactly. A `[16]` bus connects directly only to another `[16]` port. Use Splitter/Merger when converting between one N-bit bus and N separate one-bit wires.

## Feedback rule

Pure routing loops such as `BUS A -> BUS B -> BUS A` are invalid and are rejected with a compiler error. Sequential feedback through NAND gates remains legal so NAND-built latches/registers can work.
