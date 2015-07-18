(ns bgg-export.web.core
  (:require [ring.adapter.jetty         :as ring     ]
            [hiccup.page                :as page     ]
            [taoensso.timbre            :as timbre   ]
            [bgg-export.util            :as util     ]
            [com.stuartsierra.component :as component]
            [metrics.core               :as metrics  ]

            [compojure.core          :refer [defroutes GET POST]]
            [metrics.ring.expose     :refer [expose-metrics-as-json]]
            [bgg-export.config       :refer [system-config]]
            [bgg-export.downloader   :refer [new-downloader]]
            [bgg-export.metrics.core :refer [new-queue-status-reporter]])
  (:gen-class))

; we sort of do this stuff about the place in config too, perhaps we should
; make this a thing somewhere like mate
(def queues [:page-queue :id-queue :index-queue :graph-queue])

(defn index []
  (page/html5
    [:head
     [:title "Board Game Geek Scraper"]]
    [:body
     [:h1 "Board Game Geek Scraper"]]))

(defroutes routes
  (GET "/" [] (index)))

(defrecord Dashboard [queue-manager metrics-registry port server]
  component/Lifecycle
  (start [this]
    (timbre/info "Starting HTTP Server on port" port)
    (let [stack  (-> routes (expose-metrics-as-json "/metrics" metrics-registry))
          server (ring/run-jetty stack { :port port :join? false })]
      (assoc this :server server)))
  (stop [this]
    (timbre/info "Stopping HTTP server")
    (.stop server)
    (assoc this :server nil)))

(defn new-dashboard [port]
  (map->Dashboard { :port port }))

(defn -main [& args]
  (util/configure-logging)
  (let [indexing-system
        (component/system-map
          :queue-manager         (util/queue-manager (system-config :queues-path))
          :metrics-registry      (metrics/new-registry)
          :queue-status-reporter (component/using
                                   (new-queue-status-reporter queues)
                                   [:queue-manager :metrics-registry])
          :web                   (component/using
                                   (new-dashboard 1337)
                                   [:queue-manager :metrics-registry])
          :downloader            (component/using
                                   (new-downloader system-config)
                                   [:queue-manager]))]
    (component/start indexing-system)))
