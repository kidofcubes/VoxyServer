# voxyserver

a fabric server side mod that voxelizes chunks into LODs using [voxy](https://github.com/MCRcortex/voxy) and streams them to connected clients. players with voxy installed will receive LOD data from the server automatically, no client side world scanning/loading needed.

**minecraft 1.21.11 | fabric**

## how it works

when chunks load on the server, they get voxelized into voxy's LOD format and stored in a per world database. when a player with voxy connects, the server streams LOD sections to them in a spiral outward from their position. as the player moves, new sections are sent automatically.

block changes (building, explosions, etc) are detected and the affected LOD sections are revoxelized and pushed to nearby players automatically.

players without voxy are unaffected.

## building

due to voxy's license, the source and binary can't be included in this repo. you'll need to clone and build it yourself.

1. clone voxy into the root of this project:
   ```
   git clone https://github.com/MCRcortex/voxy.git
   ```

2. build the voxy jar:
   ```
   cd voxy
   ./gradlew build
   ```

3. copy the built voxy jar to `libs/`:
   ```
   mkdir libs
   cp voxy/build/libs/voxy-*.jar libs/voxy.jar
   ```

4. build voxyserver:
   ```
   ./gradlew build
   ```

the output jar will be in `build/libs/`.

## installation

drop the voxyserver, voxy & sodium jar's into the server's `mods/` folder. no other server side dependencies are needed.

clients just need voxy installed as normal along with voxyserver.

## config

config file is generated at `config/voxyserver.json` on first run.

| option | default | description |
|--------|---------|-------------|
| `lodStreamRadius` | `256` | radius in chunks to stream LODs around each player |
| `maxSectionsPerTickPerPlayer` | `10` | max LOD sections sent per player per tick cycle |
| `tickInterval` | `5` | server ticks between each streaming cycle |
| `generateOnChunkLoad` | `true` | voxelize chunks as they load on the server |
| `dirtyTrackingEnabled` | `true` | revoxelize and push LODs when blocks change |
| `dirtyTrackingInterval` | `40` | ticks between dirty chunk flushes (40 = 2 seconds) |

### example config

```json
{
  "lodStreamRadius": 256,
  "maxSectionsPerTickPerPlayer": 10,
  "generateOnChunkLoad": true,
  "tickInterval": 5,
  "dirtyTrackingEnabled": true,
  "dirtyTrackingInterval": 40
}
```

higher `lodStreamRadius` means more LOD coverage but more storage and bandwidth. `maxSectionsPerTickPerPlayer` controls how fast LODs are sent to new players or when moving into unexplored areas. lower `tickInterval` = more frequent streaming checks.

## storage

LOD data is stored per world at `<world>/voxyserver/`. this can be safely deleted to regenerate all LOD data.

## license

This mod is licensed under GNU GPLv3.
