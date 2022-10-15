(ns server.core
  (:require [org.httpkit.server :as server]
            [org.httpkit.client :as client]
            compojure.core
            [clojure.data.json :as json]))

(def state (agent {:level nil
                   :addresses {"some-address" {:clients #{}
                                               :balance nil}}}))

(defn add-client! [address ch]
  (if-some [balance (-> @state :addresses (get address) :balance)]

    (send-off
      state
      (fn [s]
        (server/send! ch (-> s :addresses (get address) :balance str))
        (update-in s [:addresses address :clients] #(-> % set (conj ch)))))

    (let [response (-> (str (System/getenv "TZKT_API") "/accounts/" address) client/get deref)
          {{level :tzkt-level} :headers} response
          balance (-> response :body json/read-str (get "balance"))]
      ; TODO: optimize using lastActivity

      (send state (fn [s]
                    (if (= (:level s) level)
                      (assoc-in s [:addresses address :balance] balance)
                      s)))
      (await state)
      (Thread/sleep 1000)
      (recur address ch))))

(defn remove-client! [address ch _]
  (send state (fn [s] (update-in s [:addresses address :clients] #(disj % ch)))))

(defn ws-handler [{{address :address} :params :as req}]
  (server/as-channel req
                     {:on-close (partial remove-client! address)
                      :on-open  (partial add-client! address)}))

(compojure.core/defroutes routes
  (compojure.core/GET "/ws/:address" [] ws-handler))

(defn update-existing [m k f]
  (if-let [kv (find m k)] (assoc m k (f (val kv))) m))

(defn notify-address! [{:keys [balance clients]}]
  (doseq [ch clients]
    (server/send! ch (str balance))))

(defn update-address! [delta s]
  (-> s
      (update :balance #(+ % delta))
      (doto notify-address!)))

(defn handle-tx! [s {{sender :address} :sender
                     {target :address} :target
                     amount :amount}]
  (update s :addresses
          #(-> %
               (update-existing sender (partial update-address! (- amount)))
               (update-existing target (partial update-address! (+ amount))))))

(defn poll! []
  (let [level (:level @state)
        level-query (if (nil? level) "level.lt=1" (str "level.gt=" level))
        response (-> (str (System/getenv "TZKT_API") "/operations/transactions?amount.gt=0&select=sender,target,amount&" level-query)
                     client/get
                     deref)
        ; TODO: handle limit
        txns (-> response :body (json/read-str :key-fn keyword))
        {{new-level :tzkt-level} :headers} response]
    (send-off state #(as-> % s
                       (assoc s :level new-level)
                       (reduce handle-tx! s txns)))
    (await state)))

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
