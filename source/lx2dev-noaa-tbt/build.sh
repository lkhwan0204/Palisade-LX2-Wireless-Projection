#!/usr/bin/env bash
# Build local, experimental no-AA-turn-guidance candidate. Never bundle signing key.
set -euo pipefail
if [ "$#" != 4 ]; then echo 'Usage: build.sh ANDROID_JAR R8_JAR PRIVATE_SIGNING_KEY OUTPUT_DIR' >&2; exit 2; fi
android=$1; r8=$2; key=$3; out=$4
for f in "$android" "$r8" "$key" "$key.pub"; do [ -f "$f" ] || { echo 'Missing input' >&2; exit 2; }; done
root=$(cd "$(dirname "$0")/hmg-api" && pwd); mod="$root/mods/wireless-carplay-aa"; w=$(mktemp -d); trap 'rm -rf "$w"' EXIT
mkdir -p "$w/engine" "$w/rs" "$w/rt" "$w/ms" "$w/mc" "$w/pkg" "$out"
for spec in "engine:$root/core/runtime/java/hmgapi/engine" "rs:$root/core/runtime/stubs" "rt:$root/core/runtime/java" "ms:$mod/stubs" "mc:$mod/src"; do n=${spec%%:*}; f=${spec#*:}; find "$f" -name '*.java' | LC_ALL=C sort > "$w/$n.txt"; done
javac --release 8 -Xlint:-options -nowarn -d "$w/engine" @"$w/engine.txt"
javac --release 8 -Xlint:-options -nowarn -cp "$android" -d "$w/rs" @"$w/rs.txt"
javac --release 8 -Xlint:-options -nowarn -cp "$android:$w/rs" -d "$w/rt" @"$w/rt.txt"
javac --release 8 -Xlint:-options -nowarn -cp "$android" -d "$w/ms" @"$w/ms.txt"
javac --release 8 -Xlint:-options -nowarn -cp "$android:$w/rt:$w/ms:$w/rs" -d "$w/mc" @"$w/mc.txt"
find "$w/mc" -name '*.class' | LC_ALL=C sort > "$w/classes.txt"
java -cp "$r8" com.android.tools.r8.D8 --release --min-api 19 --lib "$android" --classpath "$w/rt" --classpath "$w/ms" --output "$w/pkg/hooks.jar" @"$w/classes.txt"
for f in mod.json patches.json settings.json patches recipes svc; do cp -R "$mod/$f" "$w/pkg/"; done
java -cp "$w/engine" hmgapi.engine.Signing sign "$w/pkg" "$key"
java -cp "$w/engine" hmgapi.engine.Signing verify "$w/pkg"
zipfile="$out/wireless-carplay-aa-lx2dev-2.1.4-lx2dev.12-noaa-tbt.1.zip"
[ ! -e "$zipfile" ] || { echo 'Output exists' >&2; exit 2; }
python3 - "$w/pkg" "$zipfile" <<'PY'
from pathlib import Path
import sys,zipfile
p=Path(sys.argv[1]); z=Path(sys.argv[2]).resolve()
with zipfile.ZipFile(z,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as out:
 for f in sorted(x for x in p.rglob('*') if x.is_file()):
  info=zipfile.ZipInfo(f.relative_to(p).as_posix(),(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;out.writestr(info,f.read_bytes())
PY
printf 'Experimental host-built candidate: %s\n' "$zipfile"
