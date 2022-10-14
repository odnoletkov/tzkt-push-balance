(ns server.core
  (:require org.httpkit.server
            org.httpkit.client
            compojure.core
            clojure.core.async))

(compojure.core/defroutes routes
  (compojure.core/GET "/" []
                      {:status 200}))

(def state (atom {}))

(defn poll []
  (let [level (:level @state)
        level-query (if (nil? level) "level.lt=1" (str "level.gt=" level))
        response (-> (str (System/getenv "TZKT_API") "/operations/transactions?" level-query)
                     org.httpkit.client/get
                     deref)
        ; TODO: handle limit
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
