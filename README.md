# TZKT Push Balance Demo

## Run

    brew install clojure

    PORT=3000 TZKT_API=https://api.ghostnet.tzkt.io/v1 POLL_MS=1000 clj -M -m server.core

    curl --no-buffer \
        --header "Upgrade: websocket" \
        --header "Sec-WebSocket-Key: Key" \
        --header "Sec-WebSocket-Version: 13" \
        localhost:3000/ws/address

    watch -d 'curl -sSf https://api.ghostnet.tzkt.io/v1/head | jq'
