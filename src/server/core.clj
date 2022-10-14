(ns server.core
  (:require org.httpkit.server
            compojure.core))

(compojure.core/defroutes routes
  (compojure.core/GET "/" []
                      {:status 200}))

(defn -main [& args]
  (let [port (Integer/parseInt (System/getenv "PORT"))]
    (org.httpkit.server/run-server #'routes {:port port})
    (println (str "Running at http://localhost:" port))))
