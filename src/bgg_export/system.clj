(ns bgg-export.system
  (:require [bgg-export.util            :as util      ]
            [com.stuartsierra.component :as component ]

            [bgg-export.id-scraper :refer [new-id-scraper]]
            [bgg-export.indexer    :refer [new-indexer]]
            [bgg-export.downloader :refer [new-downloader]]
            [bgg-export.config     :refer [system-config]]))

(defn scraper-system [config]
  (component/system-map
    :queue-manager (util/queue-manager (config :queues-path))
    :id-scraper    (component/using
                     (new-id-scraper config)
                     [:queue-manager])
    :downloader    (component/using
                     (new-downloader config)
                     [:queue-manager])
    ;:indexer       (component/using
    ;                 (new-indexer config)
    ;                 [:queue-manager])
    ))
