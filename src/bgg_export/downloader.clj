(ns bgg-export.downloader
  (:require [taoensso.timbre            :as timbre   ]
            [bgg-export.util            :as util     ]
            [com.stuartsierra.component :as component]
            [durable-queue              :as queue    ]
            [clojure.string             :as string   ]
            [clojure.java.io            :as io       ]
            ; we use http-client instead of slurp and the user agent of slurp is blocked
            [clj-http.client            :as client   ]

            [bgg-export.config    :refer [system-config]]
            [circuit-breaker.core :refer [wrap-with-circuit-breaker defncircuitbreaker]]))

(defncircuitbreaker
  :download-circuit-breaker
  { :timeout   30
    :threshold 3 })

(defn- download [downloader]
  (let [queue-manager    (:queue-manager   downloader)
        index-queue      (:index-queue     downloader)
        graph-queue      (:graph-queue     downloader)
        id-queue         (:id-queue        downloader)
        download-folder  (:download-folder downloader)]
    (util/process-queue-safely
      queue-manager
      id-queue
      :download-circuit-breaker
      (fn [id]
        (let [url  (str "https://www.boardgamegeek.com/xmlapi/boardgame/" id)
              file (io/file download-folder (str id ".xml"))]
          (timbre/info "Downloading" url "to" file)
          (->> url (client/get) :body (spit file))
          (let [path (.getAbsolutePath file)]
            (timbre/info "Queueing xml file for indexing and graphing" path)
            (queue/put! queue-manager index-queue path)
            (queue/put! queue-manager graph-queue path)))))))

(defrecord Downloader [queue-manager id-queue index-queue graph-queue download-folder process]
  component/Lifecycle
  (start [this]
    (let [process (future (download this))]
      (assoc this :process process)))
  (stop [this]
    (future-cancel process)))

(defn new-downloader [config]
  (map->Downloader { :index-queue     (config :index-queue)
                     :id-queue        (config :id-queue)
                     :graph-queue     (config :graph-queue)
                     :download-folder (config :download-folder) }))

(defn -main
  "Entry point for simply downloading xml files. Creates a subset
   of the entire system so that indexer can be run standalone."
  [& args]
  (util/configure-logging)
  (let [indexing-system
        (component/system-map
          :queue-manager (util/queue-manager (system-config :queues-path))
          :downloader    (component/using
                           (new-downloader system-config)
                           [:queue-manager]))]
    (component/start indexing-system)))
