(ns server.core
  (:require org.httpkit.server
            org.httpkit.client
            compojure.core
            clojure.core.async
            clojure.data.json))

(def state (atom {:level nil
                  :addresses {"some-address" {:clients #{}
                                              :balance nil}}}))

(defn add-client [address ch]
  ; TODO: fetch balance
  (swap! state update-in [:addresses address :clients] #(-> % set (conj ch))))

(defn remove-client [address ch _]
  (swap! state update-in [:addresses address :clients] #(disj % ch)))

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
    (swap! state assoc :level new-level)))

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
