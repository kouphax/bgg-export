(ns bgg-export.web.core
  (:require [ring.adapter.jetty         :as ring     ]
            [hiccup.page                :as page     ]
            [taoensso.timbre            :as timbre   ]
            [bgg-export.util            :as util     ]
            [com.stuartsierra.component :as component]
            [metrics.core               :as metrics  ]

            [metrics.gauges        :refer [gauge-fn]]
            [metrics.meters        :refer [defmeter mark!]]
            [compojure.core        :refer [defroutes GET POST]]
            [metrics.ring.expose   :refer [expose-metrics-as-json]]
            [bgg-export.config     :refer [system-config]]
            [bgg-export.downloader :refer [new-downloader]])
  (:gen-class))

; we sort of do this stuff about the place in config too, perhaps we should
; make this a thing somewhere like mate
(def queues [:page-queue :id-queue :index-queue :graph-queue])

(defrecord QueueStatusReporter [queue-manager metrics-registry]
  component/Lifecycle
  (start [this]
    (timbre/info "Building queue status reporter gauges")
    (doseq [queue queues]
      (gauge-fn
        metrics-registry
        (name queue)
        (fn [] (-> queue-manager
                   (util/get-stats-for-queue queue)
                   (util/calculate-remaining-items-in-queue))))))
  (stop [this]))

(defn new-queue-status-reporter []
  (map->QueueStatusReporter {}))

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
                                   (new-queue-status-reporter)
                                   [:queue-manager :metrics-registry])
          :web                   (component/using
                                   (new-dashboard 1337)
                                   [:queue-manager :metrics-registry])
          :downloader            (component/using
                                   (new-downloader system-config)
                                   [:queue-manager]))]
    (component/start indexing-system)))
