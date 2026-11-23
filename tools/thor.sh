#!/bin/bash
# Thor helper over wireless ADB.
#   tools/thor.sh shot [name]      screenshot of the top screen -> /tmp/<name>.png
#   tools/thor.sh shot2 [name]     screenshot of the bottom screen
#   tools/thor.sh tap X Y          tap the top screen      | tap2 X Y  tap the bottom screen
#   tools/thor.sh key CODE         input keyevent CODE     | sh ...    adb shell ...
# The serial changes when wireless debugging restarts; find it with `adb mdns services`.
S=${THOR_SERIAL:-$(adb devices | awk 'NR>1 && /device$/ {print $1; exit}')}
TOP=${THOR_TOP_DISPLAY:-4630946441858561667}       # physical display ids (screencap -d)
BOTTOM=${THOR_BOTTOM_DISPLAY:-4630946482288158084}
OUT=${THOR_OUT:-/tmp}
case "$1" in
  shot) adb -s "$S" exec-out screencap -p -d "$TOP" > "$OUT/${2:-top}.png" && echo "$OUT/${2:-top}.png" ;;
  shot2) adb -s "$S" exec-out screencap -p -d "$BOTTOM" > "$OUT/${2:-bottom}.png" && echo "$OUT/${2:-bottom}.png" ;;
  tap) adb -s "$S" shell input -d 0 tap "$2" "$3" ;;
  tap2) adb -s "$S" shell input -d 4 tap "$2" "$3" ;;
  key) adb -s "$S" shell input keyevent "$2" ;;
  sh) shift; adb -s "$S" shell "$@" ;;
  *) sed -n 2,8p "$0" ;;
esac
