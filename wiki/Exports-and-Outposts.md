# Exports and outposts

Selling what a colony makes, and putting a small work site somewhere else.

## The export buffer

Jobs flagged `export` — and any station whose own export switch is on — put their output into the
colony's **export buffer** instead of its working stock. The buffer is `exportBufferSlots` (default
18) slots of the colony's store, exposed on the colony beacon.

### It is a capability, not an API

The buffer is an ordinary vanilla container, and every loader already wraps the beacon's container
as the standard item capability. **Hoppers, pipes, AE2 and Create drain it with zero coupling** —
there is no export API to depend on, because there is nothing an external mod needs beyond "this
block has an inventory". That is the whole design: interoperability by using the vanilla shape
rather than by publishing a bridge.

### Insertion from outside is refused

Nothing outside the colony can put goods *into* the buffer. What is in it is exactly what the colony
produced for sale, which is what makes selling a meaningful operation rather than a laundering
machine.

### Overflow blocks, it never voids

A full buffer **stops export production**. It does not spill into storage and it certainly does not
delete anything. A colony whose trade route has stalled should visibly stop making trade goods —
that is a problem the player can see and fix — rather than quietly converting inputs into nothing.

Nothing NeroColonies produces is ever destroyed by a race, either: if a room check passes and the
insert then does not fit because something else wrote to the store in between, the remainder goes
into the working stock rather than being voided.

## Selling

Selling pays the colony's **owner** in Neroland Core's `CREDITS` currency, through Core's currency
API. Everything sellable in the buffer is valued, removed and paid for in one operation.

Order matters: the market and the owner are checked **before** anything leaves the buffer, so a
refused sale is a no-op rather than a partial one. A sale can end four ways:

| Outcome | Meaning |
| --- | --- |
| **Sold** | goods were removed and credits paid |
| **Nothing to sell** | the buffer held nothing the loaded export tables recognise |
| **No market** | no real currency provider is installed |
| **No owner** | the colony has no player owner to pay |

### The no-market guard

Core ships an in-memory fallback currency provider whose balances do not persist — a development
stand-in. Paying into it would *look* like a sale and lose the player's goods, so NeroColonies asks
Core whether a **real** provider is installed and **refuses the sale if not**. The goods stay in the
buffer and the player is told why. When an economy mod exists it registers itself as Core's currency
provider and this path picks it up with no change here.

### Valuation

```text
credits = base value x item count x exportValueMultiplier   (floored to a whole number)
```

Where a stack matches more than one export entry, the **most valuable** entry the colony has
unlocked wins. A pack that lists an item twice meant the better price to apply, not whichever file
happened to load first.

The colony's GUI also shows a read-only preview of what the buffer would fetch. A number on a screen
is not a transaction, so the preview makes no market or ownership check.

## Export tables are pure datapack

An export entry is a target (an item or a tag), a base value, a stack size and an optional research
prerequisite. See [Content format](Content-Format.md).

The shipped tables:

| Entry | Target | Base value | Needs research |
| --- | --- | --- | --- |
| `nerocolonies:surplus_food` | `#nerocolonies:colony_food/staple` | 1.0 | — |
| `nerocolonies:refined_metals` | `minecraft:iron_ingot` | 4.0 | `nerocolonies:trade/manifest` |
| `nerocolonies:fabricated_goods` | `minecraft:repeater` | 12.0 | `nerocolonies:trade/manifest` |

**The only code-level hook in the entire export system is the base value feeding Core's currency
API.** It is a value lookup, not a pricing engine. NeroColonies has no market model and will not
grow one: pricing belongs to a dedicated economy mod, and this path is already shaped to hand over
to it.

## Outposts

An **outpost beacon** (`nerocolonies:outpost_beacon`) is a small remote work site belonging to a
parent colony.

### What an outpost has

- a **parent colony id**, whose claim and permission context it borrows wholesale — no separate
  owner and no separate access list, so there is no second place for player-shaped data to
  accumulate;
- its own small claim, `outpostClaimRadius` (default 16) blocks square from its beacon, widened by
  `RANGE` modules;
- reduced caps: `outpostColonistCap` (default 2) and `outpostJobSlots` (default 1);
- **no research, no morale, no housing tiers and no food store of its own.** It runs on the
  parent's, and its production feeds the parent's storage on the parent's colony tick.

### Placing one

The parent is chosen automatically: the **nearest colony you may act on**, within
`outpostMaxDistance` (default 512 blocks), **in the same dimension**. Choosing by proximity rather
than asking is the one rule that makes outposts placeable without a UI, and "nearest of yours" is
what a player means every time.

A placement is refused when:

| Check | Governed by |
| --- | --- |
| Outposts are switched off entirely | `outpostsPerColony` set to `0` |
| The spot is inside a colony's claim | — (it would be doing nothing the colony was not) |
| The spot is inside another outpost's claim | — |
| No colony you may act on is within range | `outpostMaxDistance` |
| The nearest such colony is already at its allowance | `outpostsPerColony` (default 4) |

Removing an outpost follows the same rule as a colony beacon: **sneak and break it**, as the parent
colony's owner or an operator.

### An outpost cannot become a colony

There is no graduation path. Break it and place a colony beacon instead. Graduation would mean
deciding what happens to the parent's claim, the shared research and the split of the goods — three
design questions with no obviously right answer, none of which need answering to make outposts
useful.

### Orphaned outposts

An outpost whose parent has been dissolved goes **inert immediately** — the parent lookup returns
nothing, so nothing ticks — and the retention sweep removes the record. An outpost is **never
silently re-parented**: one that quietly attached itself to whichever colony happened to be nearest
would be a claim exploit. The same rule applies at world load, where an outpost whose parent did not
survive is dropped rather than adopted.

## See also

- [Jobs & research](Jobs-and-Research.md) — the export flag and the per-station export switch
- [Content format](Content-Format.md) — the export entry schema
- [Colony basics](Colony-Basics.md) — claims, dissolving, and the colony tick
- [Config](Config.md) — `exportBufferSlots`, `exportValueMultiplier`, the outpost keys
