(ns server.core
  (:require [org.httpkit.server :as server]
            org.httpkit.client
            compojure.core
            [clojure.data.json :as json]))

(def state (agent {:level nil
                   :addresses {"some-address" {:clients #{}
                                               :balance nil}}}))

(defn api [& parts]
  (->> parts
       (apply str (or (System/getenv "TZKT_API") "https://api.tzkt.io/v1"))
       org.httpkit.client/get
       deref))

(defn add-client! [address ch]
  (if (-> @state :addresses (get address) :balance some?)

    (send-off
      state
      (fn [st]
        (server/send! ch (-> st :addresses (get address) :balance str))
        (update-in st [:addresses address :clients] #(-> % set (conj ch)))))

    (let [account (api "/accounts/" address)
          {{level-of-balance :tzkt-level} :headers} account
          {:strs [balance lastActivity]} (-> account :body json/read-str)]

      (await
        (send state (fn [{current-level :level :as st}]
                      (if (<= lastActivity current-level (Integer/parseInt level-of-balance))
                        (assoc-in st [:addresses address :balance] balance)
                        st))))

      (Thread/sleep 1000)
      (recur address ch))))

(defn remove-client! [address ch _]
  (send state (fn [st] (update-in st [:addresses address :clients] #(disj % ch)))))

(defn websocket-handler [{{address :address} :params :as req}]
  (server/as-channel req
                     {:on-close (partial remove-client! address)
                      :on-open  (partial add-client! address)}))

(compojure.core/defroutes routes
  (compojure.core/GET "/ws/:address" [] websocket-handler))

(defn update-existing [m k f]
  (if-let [kv (find m k)] (assoc m k (f (val kv))) m))

(defn notify-address! [{:keys [balance clients]}]
  (doseq [ch clients]
    (server/send! ch (str balance))))

(defn update-address! [delta st]
  (-> st
      (update :balance #(+ % delta))
      (doto notify-address!)))

(defn handle-tx! [st {{sender :address} :sender
                      {target :address} :target
                      amount :amount}]
  (update st :addresses
          #(-> %
               (update-existing sender (partial update-address! (- amount)))
               (update-existing target (partial update-address! (+ amount))))))

(defn poll! []
  (let [level (:level @state)
        level-query (if (nil? level) "level.lt=1" (str "level.gt=" level))
        response (api "/operations/transactions?amount.gt=0&select=sender,target,amount&" level-query)
        ; TODO: handle limit
        txns (-> response :body (json/read-str :key-fn keyword))
        {{new-level :tzkt-level} :headers} response]
    (await (send-off state #(as-> % st
                              (assoc st :level (Integer/parseInt new-level))
                              (reduce handle-tx! st txns))))))

(defn -main []

  (->> (some->>
         (System/getenv "PORT")
         Integer/parseInt
         (assoc {} :port))
       (merge {:legacy-return-value? false})
       (server/run-server #'routes)
       server/server-port
       (str "Listening at http://localhost:")
       println)

  (future
    (while true
      (do
        ; TODO: error handling
        (poll!)
        (Thread/sleep (-> (System/getenv "POLL_MS") (or "1000") Integer/parseInt))))))
