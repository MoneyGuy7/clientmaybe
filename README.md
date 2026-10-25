# Hacker Client

Base client for the Hacker SMP (Fabric, Minecraft 26.3).

## Commands (typed in chat, never sent to the server)
- `.help`, `.modules`, `.toggle <module>`
- `.toggle basefinder` then `.bf list | clear | status | set radius N | set lights N | lights on/off | amethyst on/off`

## Modules
- **BaseFinder**: scans continuously (time-budgeted, queue rebuilt every 0.25 s, new chunks scanned the tick they arrive). Also finds beds/workstations, farmland, nether portals, item frames/armor stands. 3+ different signs in one chunk = `LIKELY BASE`. `.bf nearest`, `.bf log on|off` (finds saved to config/hackerclient/).
  Flags chunks with man-placed light (torches, lanterns, lit lamps, campfires, glowstone, froglights...)
  and grown amethyst (buds/clusters next to player-made blocks like hoppers, pistons, observers).

## Build
`./gradlew build`, jar lands in `build/libs/`.
