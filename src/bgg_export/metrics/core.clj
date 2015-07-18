(ns bgg-export.metrics.core
  (:require [taoensso.timbre            :as timbre   ]
            [bgg-export.util            :as util     ]
            [com.stuartsierra.component :as component]
            [metrics.core               :as metrics  ]

            [metrics.gauges :refer [gauge-fn]]))

(defn- queue-gauge [metrics-registry queue-manager queue postfix stat-fn]
  (let [gauge-name ["bgg-export" "queues" (str (name queue) "-" postfix)]]
    (gauge-fn metrics-registry gauge-name
      (fn [] (-> queue-manager
                 (util/get-stats-for-queue queue)
                 (stat-fn))))))

(defn- build-queue-gauges [queue-manager metrics-registry queues]
  (doseq [queue queues
          :let [build-gauge-fn (partial queue-gauge metrics-registry queue-manager queue)]]
    (build-gauge-fn "size" util/calculate-remaining-items-in-queue)
    (build-gauge-fn "errors" :retried)))

(defrecord QueueStatusReporter [queue-manager metrics-registry queues]
  component/Lifecycle
  (start [this]
    (timbre/info "Building queue status reporter gauges")
    (build-queue-gauges queue-manager metrics-registry queues))
  (stop [this]))

(defn new-queue-status-reporter [queues]
  (map->QueueStatusReporter { :queues queues }))
