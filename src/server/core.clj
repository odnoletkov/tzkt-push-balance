(ns server.core
  (:require [org.httpkit.server :as server]
            org.httpkit.client
            compojure.core
            [clojure.data.json :as json]))

(def state
  "State of the service"
  (agent {:level nil
          :addresses {"some-address" {:clients #{}
                                      :balance nil}}}))

(defn api
  "Call TZKT API synchronously"
  [& url-parts]
  (-> (or (System/getenv "TZKT_API") "https://api.tzkt.io/v1")
      (str (apply str url-parts))
      org.httpkit.client/get
      deref
      (doto (-> :status (= 200) assert))
      (update :body json/read-str)))

;;;; Manage WebSocket clients

(defn add-client-when-know-balance! [address ch st]
  ;; Send initial value.
  (server/send! ch (-> st
                       :addresses
                       (get address)
                       :balance
                       (doto (-> some? assert))
                       str))
  ;; Add client.
  (update-in st [:addresses address :clients] #(-> % set (conj ch))))

(defn get-balance [address]
  (let [{{level :tzkt-level} :headers
         {:strs [balance lastActivity]} :body} (api "/accounts/" address)]
    [balance [lastActivity (Integer/parseInt level)]]))

(defn try-add-address [address
                       [balance [last-activity level-of-balance]]
                       {current-level :level :as st}]
  ;; Is fetched balance snapshot consistent with local state?
  ;;  * No if it includes activity after local level (don't double count)
  ;;  * No if it is older than local level (don't miss events)
  (if (<= last-activity current-level level-of-balance)
    ;; Yes - add address to local state.
    (assoc-in st [:addresses address :balance] balance)
    ;; No - need to try again.
    st))

(defn add-client! [address ch]
  ;; Address tracked already?
  ;; Read state opportunistically - racing clients will cause redundant balance fetches
  (if (-> @state :addresses (get address) :balance some?)
    ;; Yes - just add new client.
    (send-off state (partial add-client-when-know-balance! address ch))
    ;; No - add new address.
    (do
      ;; Try to add new address.
      (await (send state (partial try-add-address address (get-balance address))))
      ;; Let local state progress.
      (Thread/sleep 1000)
      ;; Try to add client again.
      (recur address ch))))

(defn remove-client! [address ch _]
  (send state (fn [st] (update-in st [:addresses address :clients] #(disj % ch)))))

(defn websocket-handler [{{address :address} :params :as req}]
  (server/as-channel req
                     {:on-close (partial remove-client! address)
                      :on-open  (partial add-client! address)}))

(compojure.core/defroutes routes
  (compojure.core/GET "/ws/:address" [] websocket-handler))

;;;; Poll for new transactions and update state

(defn update-existing [m k f]
  (if-let [kv (find m k)] (assoc m k (f (val kv))) m))

(defn notify-address!
  "Notify all clients of the given address"
  [{:keys [balance clients]}]
  (doseq [ch clients]
    (server/send! ch (str balance))))

(defn update-address!
  "Update balance of the given address"
  [delta st]
  (-> st
      (update :balance #(+ % delta))
      (doto notify-address!)))

(defn handle-tx!
  "Update state with transaction"
  [st {{sender "address"} "sender"
       {target "address"} "target"
       amount "amount"}]
  (update st :addresses
          #(-> %
               (update-existing sender (partial update-address! (- amount)))
               (update-existing target (partial update-address! (+ amount))))))

(defn get-new-transactions [since-level]
  (let [limit 10000
        ;; Query filter for new transactions.
        level-query (if (nil? since-level)
                      "level.lt=1" ; request empty txns for the initial state
                      (str "level.gt=" since-level))
        ;; Get new transactions.
        {{new-level :tzkt-level} :headers
         txns :body} (api "/operations/transactions"
                          "?" level-query
                          "&amount.gt=0"
                          "&select=sender,target,amount"
                          "&sort.asc=id"
                          "&limit=" limit)]
    (assert (< (count txns) limit) "fetch all new transactions")
    [txns (Integer/parseInt new-level)]))

(defn poll!
  "Get new transactions and update state"
  []
  (let [[txns new-level] (get-new-transactions (:level @state))]
    (await (send-off state #(as-> % st
                              ;; Update level.
                              (assoc st :level new-level)
                              ;; Update balances and notify clients.
                              (reduce handle-tx! st txns))))))

(defn -main []

  ;; Start WebSocket server.
  (->> (some->>
         (System/getenv "PORT")
         Integer/parseInt
         (assoc {} :port))
       (merge {:legacy-return-value? false})
       (server/run-server #'routes)
       server/server-port
       (str "Listening on http://localhost:")
       println)

  ;; Poll and handle new transactions in a loop.
  (future
    (while true
      (do
        (poll!)
        ;; Sleep until next poll
        (Thread/sleep (-> (System/getenv "POLL_MS") (or "1000") Integer/parseInt))))))
