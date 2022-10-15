# TZKT Push Balance Demo

## Run

    brew install clojure

    PORT=3000 TZKT_API=https://api.tzkt.io/v1 POLL_MS=1000 clj -M -m server.core

    curl --no-buffer \
        --header "Upgrade: websocket" \
        --header "Sec-WebSocket-Key: ." \
        localhost:3000/ws/tz1dtzgLYUHMhP6sWeFtFsHkHqyPezBBPLsZ
