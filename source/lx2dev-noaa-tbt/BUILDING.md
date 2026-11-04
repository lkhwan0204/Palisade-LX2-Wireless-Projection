# Experimental no-AA-cluster-guidance build

**Vehicle installation and wireless CarPlay / Android Auto have not been tested for this build.** Do not describe it as equivalent to the reported lx2dev.12 vehicle result. The exact HMG-API build and LX2 hook-map setup used in that test have not been reproduced; inspect hooks and establish a tested recovery path before any vehicle experiment.

This source builds `wireless-carplay-aa-lx2dev` version `2.1.4-lx2dev.12-noaa-tbt.1`. It excludes both `aa-tbt-*.recipe` files and removes the GPM entry-point call that requests Android Auto cluster/HUD turn guidance. CarPlay turn guidance remains separately controlled by the `tbt` setting. Android Auto cluster/HUD guidance is unavailable. The three retained CarPlay recipes are byte-identical to the upstream HMG-API files at [4fab437](https://github.com/VysseCodes/HMG-API/tree/4fab4376adae65b67025046539963a68fb37f76f/mods/wireless-carplay-aa/recipes); Leafia identifies its original recipes and CarPlay patches as GPL-3.0 in [NOTICE_LEAFIA.md](NOTICE_LEAFIA.md), and HMG-API carries AGPL-3.0 in [LICENSE](LICENSE). Retain these license texts and notices when distributing the relevant material. No manufacturer APK, ODEX, SO or signing key is bundled in this source or mod ZIP.

## Build locally

Dependencies: a JDK with `javac --release 8`, a separately obtained Android 4.4.2/API 19 `android.jar`, R8/D8, Python 3, and a private ECDSA P-256 HMG-API author key with its `.pub` file. Do not publish the private key. This build was checked with OpenJDK 27 and R8/D8 8.3.37. Obtain and comply with the separate licenses for SDK/build tools; they are not included.

```sh
./build.sh /path/to/android.jar /path/to/r8.jar /private/path/author.key /path/to/output-directory
```

The script compiles runtime stubs and Java, mod stubs and Java; invokes D8 for API 19; copies the mod metadata, stock-hash lists, retained recipes and services; signs the local package and verifies the signature. To generate a new author key, compile the HMG-API engine Java sources and use `hmgapi.engine.Signing keygen` as described in the [HMG-API signing tooling](https://github.com/VysseCodes/HMG-API). A different key creates a different author identity; HMG-API will not accept it as an update signed by the old key. Keep keys private and backed up.

The uploaded candidate was signed with the same author identity as lx2dev.12. It has a **higher version number**: an existing lx2dev.12 install may accept it as an update, but an ordinary downgrade back to 2.1.3 (including older recovery packages) may be rejected. No candidate installation, downgrade, removal or recovery has been validated on a vehicle. Do not assume installing the older ZIP is a recovery plan.

## Host checks and limits

With identical tool inputs, two local builds had byte-identical `hooks.jar` and signed manifest; ECDSA signature bytes differed. The package signature and hashes verified, and the unsigned payload contains no `aa-tbt` recipes. Compared to lx2dev.12 source compiled with the same tools, 130 of 131 Java class files were identical; `Wireless.class` changed at the GPM entry point. The saved host test chain, including 65 PhoneProjection checks, passed; these are simulations, not in-car proof. The source compiled with this toolchain also differed from the original lx2dev.12 ZIP's DEX; thus the change cannot be proven equivalent to that original binary. The user-reported success is specifically for lx2dev.12, not this candidate.
