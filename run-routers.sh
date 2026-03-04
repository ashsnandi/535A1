#!/usr/bin/env bash
set -euo pipefail

# Run from this script's directory
cd "$(dirname "$0")"

JAR="target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar"

if [[ ! -f "$JAR" ]]; then
  echo "Jar not found: $JAR"
  echo "Build first with: mvn clean package"
  exit 1
fi

launch_router() {
  local title="$1"
  local conf="$2"
  local cmd="cd '$PWD' && java -jar '$JAR' '$conf'"

  if command -v gnome-terminal >/dev/null 2>&1; then
    gnome-terminal --title="$title" -- bash -lc "$cmd; exec bash"
  elif command -v konsole >/dev/null 2>&1; then
    konsole --new-tab -p tabtitle="$title" -e bash -lc "$cmd; exec bash"
  elif command -v xterm >/dev/null 2>&1; then
    xterm -T "$title" -hold -e bash -lc "$cmd" &
  else
    echo "No supported terminal emulator found (gnome-terminal, konsole, xterm)."
    echo "Launching in background from current shell instead."
    bash -lc "$cmd" &
  fi
}

launch_router "router1" "conf/router1.conf"
launch_router "router2" "conf/router2.conf"
launch_router "router3" "conf/router3.conf"
launch_router "router4" "conf/router4.conf"

wait
