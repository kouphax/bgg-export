(ns bgg-export.core
  (:require [taoensso.timbre            :as timbre    ]
            [bgg-export.util            :as util      ]
            [com.stuartsierra.component :as component ]
            [durable-queue              :as queue     ]

            [bgg-export.system :refer [scraper-system]]
            [bgg-export.config :refer [system-config]])
  (:gen-class))

(defn- bootstrap []
  (util/configure-logging)
  (timbre/info "Starting up"))

(defn- shutdown [system]
  (shutdown-agents))

(defn -main [& args]
  (bootstrap)
  (component/start
    (scraper-system system-config)))
