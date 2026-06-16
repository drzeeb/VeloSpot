# `:brouter` — BRouter routing engine (built from source)

This Gradle module replaces the previously bundled `brouter-1.7.9-all.jar`
binary. It compiles the **on-device routing modules of BRouter from source**,
so the whole app can be built reproducibly with a plain Gradle invocation — no
prebuild scripts, no binary blobs.

## Where the source comes from

The BRouter source is pinned as a **git submodule** at the repository root:

```
brouter-upstream/   →  https://github.com/abrensch/brouter @ tag v1.7.9
```

The submodule is locked to a specific commit (recorded in the parent repo), so
every checkout — yours, CI, and F-Droid — builds the exact same source.

Initialise it once after cloning:

```bash
git submodule update --init --recursive
```

## What gets compiled

Only the five modules that are actually used on-device (identical to the old
slimmed JAR):

| Module                | Java package        |
|-----------------------|---------------------|
| `brouter-util`        | `btools.util`       |
| `brouter-codec`       | `btools.codec`      |
| `brouter-expressions` | `btools.expressions`|
| `brouter-mapaccess`   | `btools.mapaccess`  |
| `brouter-core`        | `btools.router`     |

The server / map-creation modules and their protobuf/osmosis dependencies are
intentionally excluded — they are only needed to *build* map data, never to
route on-device.

## License

BRouter is MIT-licensed (© BRouter authors). See
[`brouter-upstream/LICENSE`](../brouter-upstream/) after initialising the
submodule, and `ATTRIBUTIONS.md` in the repo root.

