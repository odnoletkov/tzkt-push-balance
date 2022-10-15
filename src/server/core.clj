(ns server.core
  (:require org.httpkit.server
            org.httpkit.client
            compojure.core
            clojure.core.async
            clojure.data.json))

(def state (agent {:level nil
                   :addresses {"some-address" {:clients #{}
                                               :balance nil}}}))

(defn add-client [address ch]
  (if-some [balance (-> @state :addresses (get address) :balance)]

    (send-off
      state
      (fn [s]
        (org.httpkit.server/send! ch (clojure.data.json/write-str {:balance (-> s :addresses (get address) :balance)}))
        (update-in s [:addresses address :clients] #(-> % set (conj ch)))))

    (let [response (-> (str (System/getenv "TZKT_API") "/accounts/" address) org.httpkit.client/get deref)
          {{level :tzkt-level} :headers} response
          balance (-> response :body clojure.data.json/read-str (get "balance"))]
      ; TODO: optimize using lastActivity

      (send state (fn [s]
                    (if (= (:level s) level)
                      (assoc-in s [:addresses address :balance] balance)
                      s)))
      (await state)
      (Thread/sleep 1000)
      (recur address ch))))

(defn remove-client [address ch _]
  (send state (fn [s] (update-in s [:addresses address :clients] #(disj % ch)))))

(defn ws-handler [{{address :address} :params :as req}]
  (org.httpkit.server/as-channel req
                                 {:on-close (partial remove-client address)
                                  :on-open  (partial add-client address)}))

(compojure.core/defroutes routes
  (compojure.core/GET "/ws/:address" [] ws-handler))

(defn poll []
  (let [level (:level @state)
        level-query (if (nil? level) "level.lt=1" (str "level.gt=" level))
        response (-> (str (System/getenv "TZKT_API") "/operations/transactions?" level-query)
                     org.httpkit.client/get
                     deref)
        ; TODO: handle limit
        txns (-> response :body clojure.data.json/read-str)
        {{new-level :tzkt-level} :headers} response]
    (send state (fn [s] (assoc s :level new-level)))
    (await state)))

(defn -main [& args]
  (let [port (Integer/parseInt (System/getenv "PORT"))]

    (clojure.core.async/go-loop []
                                (do
                                  ; TODO: error handling
                                  (poll)
                                  (Thread/sleep (Integer/parseInt (System/getenv "POLL_MS")))
                                  (recur)))

    (org.httpkit.server/run-server #'routes {:port port})
    (println (str "Running at http://localhost:" port))))
