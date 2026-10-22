#!/bin/sh
# Test double for libxodus_cli.so: speaks the same line protocols.
if [ "$1" = "--external-webview-stdio" ] && [ "$2" = "login" ]; then
  echo 'NEXUS_AUTH_V1 {"type":"ready","version":1}'
  read hello
  case "$hello" in *'"hello"'*) ;; *) echo "bad hello" >&2; exit 3;; esac
  echo 'NEXUS_AUTH_V1 {"type":"open","session":1,"title":"Xodus sign in","url":"https://login.live.com/ppsecure/InlineLogin.srf?id=80604","headers":{"hostapp":"CloudExperienceHost","cxh-protocol":"TokenBroker"}}'
  read ipc1
  case "$ipc1" in *'"ipc"'*) ;; *) echo "expected ipc" >&2; exit 4;; esac
  case "$ipc1" in *getContext*) ;; *) echo "expected context request" >&2; exit 4;; esac
  echo 'NEXUS_AUTH_V1 {"type":"context","session":1,"context":"ctx \"quoted\""}'
  read ipc2
  case "$ipc2" in *'"ipc"'*) ;; *) echo "expected ipc" >&2; exit 5;; esac
  case "$ipc2" in *sDAToken*) ;; *) echo "expected token" >&2; exit 5;; esac
  echo 'NEXUS_AUTH_V1 {"type":"close","session":1}'
  echo "Login successful"
  exit 0
fi
case "$1" in
  account)
    echo "log line"
    echo '{"stored":true,"username":"player@example.com"}'
    ;;
  library)
    echo "NEXUS_CREDENTIAL_ERROR_V1 keyring-missing-data" >&2
    exit 1
    ;;
  streaming)
    dest="$3"
    echo 'NEXUS_PROGRESS {"schema":1,"sequence":1,"event":"progress","phase":"metadata","completedBytes":0,"totalBytes":null,"bytesPerSecond":0,"paused":false,"canPause":false}'
    echo 'NEXUS_PROGRESS {"schema":1,"sequence":2,"event":"progress","phase":"downloading","completedBytes":50,"totalBytes":100,"bytesPerSecond":10,"paused":false,"canPause":true}'
    read cmd
    case "$cmd" in *pause*) echo 'NEXUS_PROGRESS {"schema":1,"sequence":3,"event":"progress","phase":"downloading","completedBytes":50,"totalBytes":100,"bytesPerSecond":0,"paused":true,"canPause":true}';; esac
    read cmd
    echo 'NEXUS_PROGRESS {"schema":1,"sequence":2,"event":"progress","phase":"downloading","completedBytes":1,"totalBytes":100,"bytesPerSecond":10,"paused":false,"canPause":true}'
    echo 'NEXUS_PROGRESS {"schema":1,"sequence":4,"event":"progress","phase":"complete","completedBytes":100,"totalBytes":100,"bytesPerSecond":0,"paused":false,"canPause":false}'
    : > "$dest/.xodus-streaming.msixvc"
    ;;
  prepare-launch)
    root=""; prev=""
    for a in "$@"; do [ "$prev" = "--view-root" ] && root="$a"; prev="$a"; done
    view="$root/xodus-view-test"
    mkdir -p "$view/Bin" && printf 'MZ' > "$view/Bin/Game.exe"
    echo "NEXUS_LAUNCH_V1 {\"schema\":1,\"event\":\"ready\",\"executable\":\"$view/Bin/Game.exe\",\"workingDirectory\":\"$view/Bin\",\"view\":\"$view\",\"environment\":{\"XODUS_XSTS_PLAYFAB\":\"XBL3.0 x=1;t\",\"LD_PRELOAD\":\"/evil.so\"}}"
    while read line; do case "$line" in *stop*) break;; esac; done
    rm -rf "$view"
    echo 'NEXUS_LAUNCH_V1 {"schema":1,"event":"stopped"}'
    ;;
  *) echo "unknown $*" >&2; exit 2;;
esac
