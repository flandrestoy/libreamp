#!/bin/bash
set -u
GRADLE=/home/u0_a318/gradle-dist/gradle-8.7/bin/gradle
PROJECT=/home/u0_a318/player
LOG=/tmp/gradle_build.log
: > "$LOG"

nice -n 19 ionice -c3 "$GRADLE" --no-daemon --max-workers=1 -p "$PROJECT" assembleDebug >>"$LOG" 2>&1 &
GPID=$!

LOW_COUNT=0
while kill -0 "$GPID" 2>/dev/null; do
  AVAIL_KB=$(awk '/MemAvailable/ {print $2}' /proc/meminfo)
  if [ "$AVAIL_KB" -lt 40000 ]; then
    LOW_COUNT=$((LOW_COUNT+1))
  else
    LOW_COUNT=0
  fi
  echo "$(date +%H:%M:%S) avail=${AVAIL_KB}KB low_count=$LOW_COUNT" >> /tmp/gradle_watchdog.log
  if [ "$LOW_COUNT" -ge 4 ]; then
    echo "WATCHDOG: available memory critical for 4 consecutive checks, killing build tree" >> "$LOG"
    pkill -9 -P "$GPID" 2>/dev/null
    kill -9 "$GPID" 2>/dev/null
    pkill -9 -f "gradle-8.7" 2>/dev/null
    echo "WATCHDOG_KILLED" >> /tmp/gradle_watchdog.log
    exit 2
  fi
  sleep 5
done

wait "$GPID"
EXIT=$?
echo "BUILD_EXIT=$EXIT" >> /tmp/gradle_watchdog.log
exit $EXIT
