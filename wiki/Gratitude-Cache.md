# The Gratitude Cache

A colony that is doing well leaves gifts for its owner. They collect in the **Gratitude Cache**, and
they get better as the colony grows.

## What it takes

Two things, both of which the colony provides for itself in time:

1. **A finished Gratitude Cache Pavilion.** It is a Settled-stage building, one per colony, and the
   colony builds it unprompted like any other. You can also place it with the
   [Colony Planner](Buildings.md#the-colony-planner).
2. **A Quartermaster.** The pavilion opens one place in the Quartermaster trade.

The second is the one that takes time. Trades are filled in a fixed order and the Quartermaster comes
after Farmer, Forester, Miner, Builder, Cook, Hauler, Toolsmith, Guard, Beastkeeper and Researcher.
A colony gets a Quartermaster once it has an adult left over after every open place in those trades
is taken — so a small colony with a pavilion and no gifts simply needs more people. See
[Nerans and professions](Nerans-and-Professions.md#trades).

One Quartermaster is enough. A second changes nothing.

## Stocking

Once both conditions hold, the cache is stocked at once, and then again every
`cacheStockIntervalCycles` colony cycles. With the defaults — 36 cycles of 100 ticks — that is every
three minutes.

Each stocking rolls one loot table, chosen by the colony's stage:

| Stage | Table | Gifts per stocking | What a gift can be (share of the draws) |
| --- | --- | --- | --- |
| Founding | `nerocolonies:gratitude/tier_1` | 2–3 | `minecraft:bread` × 1–3 (30%), `minecraft:apple` × 1–3 (25%), `minecraft:iron_nugget` × 2–6 (20%), `minecraft:wheat_seeds` × 2–5 (15%), `minecraft:carrot` × 1–3 (8%), `minecraft:emerald` (2%) |
| Settled | `nerocolonies:gratitude/tier_2` | 2–3 | `minecraft:baked_potato` × 2–4 (22%), `minecraft:iron_ingot` × 1–3 (20%), `minecraft:copper_ingot` × 2–5 (18%), `minecraft:cooked_cod` × 1–3 (15%), `minecraft:torch` × 4–8 (12%), `minecraft:emerald` × 1–2 (8%), `minecraft:honey_bottle` × 1–2 (4%), `minecraft:name_tag` (1%) |
| Growing | `nerocolonies:gratitude/tier_3` | 3–4 | `minecraft:iron_ingot` × 2–5 (20%), `minecraft:cooked_beef` × 2–4 (18%), `minecraft:gold_ingot` × 1–3 (14%), `minecraft:emerald` × 1–3 (14%), `minecraft:lapis_lazuli` × 3–8 (10%), `minecraft:book`, randomly enchanted (8%), `minecraft:golden_carrot` × 2–4 (8%), `minecraft:name_tag` (4%), `minecraft:diamond` (2%) |
| Thriving | `nerocolonies:gratitude/tier_4` | 3–4 | `minecraft:emerald` × 2–5 (18%), `minecraft:golden_carrot` × 3–6 (16%), `minecraft:gold_ingot` × 2–5 (14%), `minecraft:book`, randomly enchanted (14%), `minecraft:diamond` × 1–2 (10%), `minecraft:experience_bottle` × 2–5 (10%), `minecraft:golden_apple` (8%), `minecraft:name_tag` (6%), `minecraft:music_disc_cat` (2%), `minecraft:music_disc_mellohi` (2%) |
| Metropolis | `nerocolonies:gratitude/tier_5` | 4–5 | `minecraft:emerald` × 3–8 (20%), `minecraft:diamond` × 1–3 (18%), `minecraft:book`, randomly enchanted (16%), `minecraft:golden_apple` × 1–2 (14%), `minecraft:experience_bottle` × 4–8 (12%), `minecraft:name_tag` (8%), `minecraft:netherite_scrap` (3%), `minecraft:music_disc_otherside` (2%), `minecraft:music_disc_pigstep` (2%), `minecraft:music_disc_relic` (2%), `minecraft:enchanted_golden_apple` (1%) |

Percentages are each item's share of the draws from that table, rounded.

With the shipped buildings a colony is at least Settled before it can have a pavilion, so the
Founding table is there for datapacks and for the [gallery](Gallery.md), which shows one roll of
every table.

Stocking happens on the colony cycle, so only while the colony's beacon chunk is loaded. Offline
catch-up does not stock the cache.

### The Thank-you Note

Every stocking, at every stage, also has a 2% chance of adding a **Thank-you Note**. It carries one
of five messages from the colony as its lore, and it does nothing else. It is a keepsake.

## It never throws anything away

The cache has 27 slots. A stocking either fits entirely or does not happen: if any part of the roll
would not fit, nothing is added, and the colony tries again at the next interval. The colony's
members are told once, through the companion app, that the cache is full
([Link module](Link-Module.md)), and not again until a stocking has fitted.

Nothing is ever pushed out to make room. A full cache just waits for you.

## The block, and where the gifts really are

The **Gratitude Cache** block is a door, not a container. The gifts live in the colony's own record,
one cache per colony, so:

- every Gratitude Cache block inside the claim opens the same 27 slots;
- breaking the block loses nothing;
- two people looking at once see, and take from, the same stock.

The pavilion contains one. You can craft more — four copper ingots, four gold ingots and a chest —
and put them wherever is convenient inside the claim. Extra blocks do not make the cache stock
faster or hold more, and a block without a pavilion and a Quartermaster opens an empty cache that
stays empty. Outside any claim the block tells you so and does nothing.

It opens as an ordinary three-row chest, so you can put things in as well as take them out.

> **Dissolving the colony drops the cache too.** Whatever is still inside lands at the beacon along
> with the colony's storage and export buffer.

## Who may open it

| Viewer | By default | When the owner has shared it |
| --- | --- | --- |
| Owner | yes | yes |
| Chief | no | yes |
| Ally | no | yes |
| Anyone else | no | no |

Operators count as the owner. A member who may not open it is told their rank does not allow it.

A colony with no owner — one handed to the server after an erasure request — keeps stocking. Its
cache can be opened by operators, and by its members if it was shared.

### Sharing

Sharing is the owner's decision and is off to begin with. Three ways to flip it:

- the `Cache: private` / `Cache: shared` button on the beacon's **Roles** tab;
- `/nerocolonies colony cache share <colony> true` (or `false`);
- the `toggle_cache_sharing` action in a companion app ([Link module](Link-Module.md)).

The reply is `The Gratitude Cache is now shared with allies.` or `The Gratitude Cache is now for the
owner only.` Sharing lets every member open it; there is no way to share with Chiefs only.

Every member can see **how full** the cache is in a companion app, whether or not they may open it.
Nobody is told what is in it without opening it.

## For pack makers

The five tables are ordinary loot tables at
`data/nerocolonies/loot_table/gratitude/tier_1.json` to `tier_5.json`, and a datapack replaces one by
shipping the same file. They are rolled with an empty context — no position, no entity, no tool — so
conditions that need one of those will not pass.

Which building enables the cache is decided by the blueprint role `gratitude_cache`, and which trade
stocks it is `nerocolonies:quartermaster`. See [Content format](Content-Format.md#blueprints).

## Configuration

| Key | Default | Effect |
| --- | --- | --- |
| `cacheStockIntervalCycles` | 36 | Colony cycles between stockings |

## See also

- [Progression](Progression.md) — the stages that decide the table
- [Roles and defence](Roles-and-Defence.md) — who counts as owner, Chief and Ally
- [Nerans and professions](Nerans-and-Professions.md) — the Quartermaster trade
- [Commands](Commands.md) — `colony cache share`
