# Base Finder — Fabric 1.21.11

Client-side nearby utility-block scanner for Minecraft 1.21.11 + Fabric.

## Controls
- G: Toggle scanner
- R: Rescan
- [: Decrease radius
- ]: Increase radius
- F8: Open settings and block filters

Scans blocks currently loaded by the client. It does not load or request hidden chunks, inspect server files, or bypass server protections. Detection is not guaranteed to find remote bases.

## Build
Every push to `main` starts the **Build Base Finder** workflow. On a successful run, download the `base-finder-fabric-1.21.11` artifact from the run page:
https://github.com/rileyjosh407-cpu/chunk-finer-mod-asdawdasd/actions

Install Fabric Loader and Fabric API for Minecraft 1.21.11, then put the regular mod JAR in your instance's `mods` folder.
