# Base Finder — Fabric 1.21.11

Client-side nearby utility-block scanner for single-player or servers where scanning is permitted.

## Controls
- G: Toggle scanner
- R: Rescan
- [: Decrease radius
- ]: Increase radius
- F8: Open settings and block filters

The scanner lists up to 12 nearby matching blocks, including storage/workstation clues, spawners, and ancient debris, with coordinates and distance. Use F8 to configure filters. Radius is 8–64 blocks.

This only checks block data currently available to the client. It does not request or force-load chunks, inspect server files, or bypass server protections. It is not a guaranteed remote-base finder.

## Build
The GitHub Actions workflow builds with Java 21 and Gradle 8.14.3. Open Actions and run "Build Base Finder"; download the base-finder-fabric-1.21.11 artifact from a successful run. Install Fabric Loader and Fabric API for Minecraft 1.21.1, then place the regular mod JAR in the instance's mods folder.
