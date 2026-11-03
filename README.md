# Equinox

The whole world follows the year: **Spring**, **Summer**, **Autumn** and **Winter**.

Equinox is a seasons mod for **NeoForge 1.21.1**. Every biome has its own temperatures, snow falls in winter
and melts in the rain, crops grow by temperature, and grass, leaves and flowers change through the year.
No library mods are needed.

## Features

### Seasons
- Four seasons, each split into early, mid and late. 42 days per season by default (a 168-day year).
- Every year is a bit different: random warmer or colder seasons and days.
- Long summer days, short winter days.

### Climate
- Each biome has its own temperatures: mild coasts, a cold north, deserts, rainy and dry tropics.
- Neighbouring biomes blend, and winter fades out near jungles and deserts.
- Other mods can read the air and water temperature through the API.

### Snow
- Snow appears during snowfall and melts in the rain.
- It settles in grass, flowers and plants and on fences, with an animation as it appears and melts.
- Lakes and rivers freeze while it snows.

### Crops
- Crops grow by temperature: fast when it's ideal, slowly when it's cool or warm, dormant in the cold, killed by
  frost or heat.
- Campfires, torches and other heat sources protect them; ice cools them.
- On hot days farmland needs water closer by.

### Colors
- Grass and leaves change color through the year; birches turn yellow and orange in autumn.
- Flowers bud, bloom and go to seed.

### More
- Distant Horizons: far terrain follows the colors and the snow.
- A config screen with tabs, descriptions and the performance cost of each setting.

## Installation

1. Install [NeoForge](https://neoforged.net/) for Minecraft 1.21.1.
2. Put the Equinox jar in your `mods` folder, on both the server and the clients.

Equinox replaces other seasons mods; don't run it next to one.

## Commands

| Command | Who | What it does |
|---|---|---|
| `/season` or `/season get` | everyone | Shows the season, the climate and the temperature where you stand |
| `/season set <season> [early\|mid\|late]` | operators | Jumps to a season |
| `/season add <days>` | operators | Moves the year forward (or back) by some days |

## Documentation

See the [wiki](https://github.com/VenatorVictor/Equinox/wiki) for the climate, config options, datapacks and the API.

## Building

```sh
./gradlew build
```

The jar ends up in `build/libs/`. Needs Java 21.

## License

[MIT](LICENSE)
