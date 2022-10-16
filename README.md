# TZKT Push Balance Demo

WebSocket server that tracks Tezos blockchain progress and pushes balance updates to connected clients.

* Client POV:
  * Connects via WebSocket to track specific Tezos address
  * Receives current balance on initial connection
  * Receives 'real time' balance updates
  * Exactly one update per each relevant transaction
  * Balance updates are strictly ordered

* Server POV:
  * Fetches balance for specific Tezos address ~once (barring racing clients and level coordination between APIs)
  * Tracks blockchain progress via poll
  * Fails explicitly on network errors
  * Uses [TZKT REST APIs](https://api.tzkt.io):
    * [/operations/transactions](https://api.tzkt.io/#operation/Operations_GetTransactions)
    * [/accounts/{address}](https://api.tzkt.io/#operation/Accounts_GetByAddress)
  * Assumes response body is always consistent with `tzkt-level` header
  * Relies on eventual `tzkt-level` coordination between APIs for progress (not for correctness)

## Install

    brew install clojure

## Run Server

    PORT=3000 clj -M -m server.core

## Test with curl

    curl --no-buffer \
            --header "Upgrade: websocket" \
            --header "Sec-WebSocket-Key: ." \
            localhost:3000/ws/tz1dtzgLYUHMhP6sWeFtFsHkHqyPezBBPLsZ

Sample output:

```
?
5405017337?
5405067337?
5405073562?
5405081062?
5405106062
```
