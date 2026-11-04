# Notices

## NEMESIS

HMG-API builds on [NEMESIS](https://github.com/xwtk/hmg_nemesis) by xwtk (AGPL-3.0), which opened these head units to the community: HMG-API installs as a NEMESIS package and runs alongside it. Parts derived from NEMESIS keep its copyright:

- `core/hmgapid/hmgapid.c`: the root daemon design (socket service, the `/system` remount that keeps the mount's flags) comes from NEMESIS's `native/su.c` and `native/util.c`.
- The DSP OTA install path (`install/usb`) uses the same entry point NEMESIS documented.

## Xposed

The Dalvik hook mechanism (`core/loader`) follows what Xposed (rovo89, Apache-2.0) did on Android 4.x; no Xposed code is included.

## HMG Leafia Plus

`tools/lpatch`, `tools/mkrecipe.py` and `tools/mkdelta.py` come from HMG Leafia Plus 1.x (same author and licence).
