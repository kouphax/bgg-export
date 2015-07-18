(ns bgg-export.id-scraper
  (:require [taoensso.timbre            :as timbre   ]
            [bgg-export.util            :as util     ]
            [com.stuartsierra.component :as component]
            [durable-queue              :as queue    ]
            [net.cgrand.enlive-html     :as html     ]
            [clojure.string             :as string   ]

            [circuit-breaker.core :refer [defncircuitbreaker]]
            [bgg-export.config    :refer [system-config]])
  (:gen-class))

(defncircuitbreaker
  :scraper-circuit-breaker
  { :timeout   30
    :threshold 3 })

(defn- extract-ids [html]
  (-> html
      (:attrs)
      (:href)
      (string/split #"\/")
      (nth 2)))

(defn- get-ids [url]
  (let [html        (util/fetch-url url)
        id-selector [:div#collection :table :tr [:td (html/nth-of-type 3)] :a]
        link-tags   (html/select html id-selector)
        ids         (map extract-ids link-tags)
        _           (timbre/info "Scraped" (count ids) "ids from" url)]
    ids))

(defn- put-id [queue-manager id-queue id]
  (timbre/info "Queueing " id)
  (queue/put! queue-manager id-queue id))

(defn- scrape [scraper]
  (let [queue-manager  (:queue-manager scraper)
        page-queue     (:page-queue    scraper)
        id-queue       (:id-queue      scraper)]
    (util/process-queue-safely
      queue-manager
      page-queue
      :scraper-circuit-breaker
      (fn [url]
        (timbre/info "Scraping " url)
        (->> url
             (get-ids)
             (map (partial put-id queue-manager id-queue))
             (dorun))))))

(defrecord IDScraper [queue-manager page-queue id-queue process]
  component/Lifecycle
  (start [this]
    (let [process (future (scrape this))]
      (assoc this :process process)))
  (stop [this]
    (future-cancel process)))

(defn new-id-scraper [config]
  (map->IDScraper { :page-queue (config :page-queue)
                    :id-queue   (config :id-queue) }))

(defn -main
  "Entry point for simply scraping the ids from BGG. Creates a subset
   of the entire system so that indexer can be run standalone."
  [& args]
  (util/configure-logging)
  (let [indexing-system
        (component/system-map
          :queue-manager (util/queue-manager (system-config :queues-path))
          :indexer       (component/using
                           (new-id-scraper system-config)
                           [:queue-manager]))]
    (component/start indexing-system)))
