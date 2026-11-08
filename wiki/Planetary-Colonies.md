# Planetary colonies

What changes when a colony is founded on another world.

> **The short version:** with Nerospace 2.0 or later installed, a colony beacon placed on one of six
> planets founds a colony of that planet's **type**. A typed colony keeps everything an ordinary colony
> has and adds two buildings, two jobs and one research node of its own. Everywhere else, and in any
> game without Nerospace, every colony is an ordinary (generic) colony and nothing changes.

## The six types

| Planet | Colony type | Its buildings | Its jobs | Its research |
| --- | --- | --- | --- | --- |
| Luna | Lunar Outpost | Regolith Dome, Sinter Kiln | Regolith sintering; Helium-3 extraction | Helium-3 Extraction |
| Mars | Martian Settlement | Storm Shelter, Water Works | Water extraction; hematite smelting | Hematite Smelting |
| Cindara | Ember Forge | Sulfur Vats, Forge Hall | Sulfur refining; ember smelting | Ember Smelting |
| Glacira | Cryo Station | Methane Plant, Cryo Habitat | Methane cracking; methane power | Methane Power |
| Greenxertz | Canopy Commune | Sap Platform, Amber Biolab | Sap tapping; amber biotech | Amber Biotech |
| Nyxara | Twilight Enclave | Observatory, Lens Workshop | Voidglass grinding; lens crafting | Voidglass Optics |

The orbital station, Earth and every dimension from another mod found **generic** colonies.

A typed colony still builds every ordinary building, works every ordinary job and researches every
ordinary node. The planetary content is added on top, and only that colony type sees it: a Lunar
Outpost never builds a Storm Shelter, and an ordinary colony on Earth never sees any of it.

## How a colony gets its type

**Once, at founding.** When the beacon founds the colony, NeroColonies asks Nerospace which colony
profile the planet has and stores the matching type on the colony. It never asks again.

- **Colonies founded before this update stay generic**, even on a planet. Nothing re-types an existing
  colony. To get a planetary colony on a planet where you already have one, found a new colony.
- **Outposts follow their parent.** An outpost has no type of its own; its stations run the jobs its
  parent colony may run, wherever the outpost stands.
- A Nerospace older than 2.0 has no colony profiles, so every colony there is generic. Life support and
  hazards still work as before.

The founding message tells you the type when it is not generic.

## The jobs and what they need

Every planetary job runs on an ordinary job station (refinery, fabricator, farm or hydroponics
station). The first job of each type is available as soon as the colony has the station; the second is
unlocked by that type's research node, which sits in the **Planetary** branch of the research screen and
needs **Shelter** first.

Inputs are item tags, so the planet's own material works and so does a plain Minecraft stand-in:

| Tag | Planet material | Stand-in |
| --- | --- | --- |
| `nerocolonies:planetary/regolith` | regolith, red regolith | gravel |
| `nerocolonies:planetary/martian_ice` | dry ice | ice |
| `nerocolonies:planetary/hematite` | raw hematite | raw iron |
| `nerocolonies:planetary/sulfur` | sulfur | netherrack |
| `nerocolonies:planetary/methane` | methane crystal, methane ice | packed ice |
| `nerocolonies:planetary/amber` | amber sap | honeycomb |
| `nerocolonies:planetary/voidglass` | voidglass | obsidian |

Two jobs make a Nerospace item: **Helium-3 extraction** (Helium-3 canisters) and **lens crafting**
(voidglass lenses). Both send their output to the export buffer, and the matching export entries sell
it once the type's research is done. Without Nerospace those two jobs and their exports simply do not
load; nothing else is affected, and they are not reported as broken content.

## Terraforming inside a claim

Nerospace's reversible terraforming overlays need a claim system to say who may change what. With
NeroColonies installed, an overlay may be created, advanced or rolled back only when the **whole**
overlay disc lies inside **one** colony's claim, by a player who may build there (the owner, a Chief,
an Ally or an operator, never an Enemy). Anywhere else the change is refused, and that includes an
**outpost's** claim: outposts do not own terraforming, only the colony claim around a colony beacon does.

Two limits worth knowing:

- This covers Nerospace's **overlay** terraforming only. The Terraformer block and landing pods are
  Nerospace's own business and do not ask NeroColonies.
- Nerospace has room for one claim system. If another mod installs its own rule after NeroColonies, the
  later one is the one used.

## For pack makers

Any blueprint, job, research node or export entry can be limited to colony types with an optional
`colony_types` list:

```json
{
  "station": "nerocolonies:refinery_station",
  "inputs": [ { "tag": "nerocolonies:planetary/regolith", "count": 4 } ],
  "outputs": [ { "item": "minecraft:smooth_stone", "count": 2 } ],
  "colony_types": [ "lunar_outpost" ]
}
```

- No list, or an empty one, means every colony, which is how all content before 0.7 behaves.
- The names are `generic`, `lunar_outpost`, `martian_settlement`, `ember_forge`, `cryo_station`,
  `canopy_commune` and `twilight_enclave`.
- An unknown name is reported in the datapack validation report (`/nerocolonies reload-check`) and
  ignored. A list that names no known type at all drops the definition, so content meant for one kind
  of colony never turns into content for every colony.
- Content with a `colony_types` list that names an item from a mod that is not installed is skipped
  quietly, because that is what planetary content is expected to do.

See [Content format](Content-Format.md) for the full schemas.

## Multiplayer

The colony screen now carries the colony type, so **a 0.7 server needs 0.7 clients**. A companion app
sees the type as `colony_type` in the `colonies` section (link schema 3); see
[Link module](Link-Module.md).

## Privacy

A colony type describes a place, never a person. It is not personal data, it is not part of a data
export, and an erasure request does not touch it. The terraform rule above is asked about one player at
a time and never stores or logs who asked. See [Data storage](Data-Storage.md).

## See also

- [Colony basics](Colony-Basics.md) — founding and claims
- [Jobs & research](Jobs-and-Research.md) — job stations and the research tree
- [Exports & outposts](Exports-and-Outposts.md) — selling what the colony makes
- [Life support](Life-Support.md) — airless worlds
