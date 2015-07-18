(ns bgg-export.prime
  (:require [taoensso.timbre        :as timbre]
            [bgg-export.util        :as util  ]
            [net.cgrand.enlive-html :as html  ]
            [durable-queue          :as queue ]
            [clojure.string         :as string])
  (:gen-class))


(defn- page-count []
  (-> (util/fetch-url "https://boardgamegeek.com/browse/boardgame")
      (html/select [[:div.fr html/first-of-type]
                    [:a (html/attr-contains :title "last page")]])
      (first)
      (html/text)
      (string/replace #"[\[\]]" "")
      (Integer.)))

(def ^:private queue-manager
  (util/queue-manager "./workspace/queues"))

(defn- queue [page]
  (let [url (str "https://boardgamegeek.com/browse/boardgame/page/" page)]
    (timbre/info "Queueing " url)
    (queue/put! queue-manager :page-queue url)))

(defn- seed-queue []
  (->> (page-count)
       (inc)
       (range 1)
       (map queue)
       (dorun)))

(defn -main [& args]
  (util/configure-logging)
  (timbre/info "Priming scrape queue")
  (seed-queue)
  (shutdown-agents))
