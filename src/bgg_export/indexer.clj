(ns bgg-export.indexer
  (:require [taoensso.timbre            :as timbre   ]
            [bgg-export.util            :as util     ]
            [com.stuartsierra.component :as component]
            [durable-queue              :as queue    ]
            [clojure.string             :as string   ]
            [basex.session              :as basex    ]
            [clojure.java.io            :as io       ]

            [bgg-export.config :refer [system-config]])
  (:gen-class))

(defn- index [indexer]
  (let [queue-manager    (:queue-manager    indexer)
        index-queue      (:index-queue      indexer)
        basex-connection (:basex-connection indexer)]
    (basex/with-session [session (basex/create-session basex-connection)]
      (basex/execute session "CHECK board-game-geek")
      (util/process-queue queue-manager index-queue
        (fn [path]
          (let [file     (io/input-stream path)
                filename (last (string/split path #"\/"))
                db-path  (str "/" filename)]
            (timbre/info "Indexing" path "to" db-path)
            (basex/replace session db-path file)))))))

(defrecord Indexer [queue-manager index-queue basex-connection process]
  component/Lifecycle
  (start [this]
    (let [process (future (index this))]
      (assoc this :process process)))
  (stop [this]
    (future-cancel process)))

(defn new-indexer [config]
  (map->Indexer { :index-queue      (config :index-queue)
                  :basex-connection (config :basex-connection) }))

(defn -main
  "Entry point for simply indexing the downloaded xml files. Creates a subset
   of the entire system so that indexer can be run standalone."
  [& args]
  (util/configure-logging)
  (let [indexing-system
        (component/system-map
          :queue-manager (util/queue-manager (system-config :queues-path))
          :indexer       (component/using
                           (new-indexer system-config)
                           [:queue-manager]))]
    (component/start indexing-system)))
