(ns bgg-export.grapher
  (:require [taoensso.timbre                         :as timbre   ]
            [bgg-export.util                         :as util     ]
            [com.stuartsierra.component              :as component]
            [durable-queue                           :as queue    ]
            [clojure.string                          :as string   ]
            [clojure.java.io                         :as io       ]
            [net.cgrand.enlive-html                  :as html :refer [attr= first-of-type but attr?]]
            [clojurewerkz.neocons.rest               :as nr       ]
            [clojurewerkz.neocons.rest.nodes         :as nn       ]
            [clojurewerkz.neocons.rest.labels        :as nl       ]
            [clojurewerkz.neocons.rest.cypher        :as cy       ]
            [clojurewerkz.neocons.rest.index         :as ni       ]
            [clojurewerkz.neocons.rest.constraints   :as nc       ]
            [clojurewerkz.neocons.rest.relationships :as nrl      ]

            [bgg-export.config :refer [system-config]])
  (:gen-class))

(defn- read-ref-node [n]
  { :key     (-> n :attrs :objectid keyword)
    :name    (html/text n)
    ; some reference data can be marked as inbound, like expansions
    ; all other times we just set this to false
    :inbound (-> n :attrs :inbound (or "false") read-string ) })

(defn- ref-nodes [xml selector]
  (map read-ref-node (html/select xml selector)))

(defn- str-nodes [xml selector]
  (-> xml (html/select selector) html/texts))

(defn- str-node [xml selector]
  (-> xml (str-nodes selector) first))

(defn- int-node [xml selector]
  (-> xml (str-node selector) util/safe-int))

(defn- game-data [xml]
  { :key              (-> xml :attrs :objectid)
    :year-published   (int-node  xml [:yearpublished])
    :min-players      (int-node  xml [:minplayers])
    :max-players      (int-node  xml [:maxplayers])
    :playing-time     (int-node  xml [:playingtime])
    :min-playing-time (int-node  xml [:minplaytime])
    :max-playing-time (int-node  xml [:maxplaytime])
    :age              (int-node  xml [:age])
    :name             (str-node  xml [[:name (attr= :primary "true")]])
    :alternate-names  (str-nodes xml [[:name #{(but (attr= :primary "true")) (but (attr? :primary))}]])
    :description      (str-node  xml [:description])
    :image            (str-node  xml [:image])
    :honors           (ref-nodes xml [:boardgamehonor])
    :mechanics        (ref-nodes xml [:boardgamemechanic])
    :artists          (ref-nodes xml [:boardgameartist])
    :categories       (ref-nodes xml [:boardgamecategory])
    :publishers       (ref-nodes xml [:boardgamepublisher])
    :podcasts         (ref-nodes xml [:boardgamepodcastepisode])
    :subdomains       (ref-nodes xml [:boardgamesubdomain])
    :families         (ref-nodes xml [:boardgamefamily])
    :expansions       (ref-nodes xml [:boardgameexpansion])
    :accessories      (ref-nodes xml [:boardgameaccessory])
    :versions         (ref-nodes xml [:boardgameversion])
    :designers        (ref-nodes xml [:boardgamedesigner]) })

(defn- node-data [data]
  (into {} (remove (comp nil? second)
    (select-keys data [:key :year-published :min-players :max-players
                       :playing-time :min-playing-time :max-playing-time
                       :age :name :description :image]))))


(defn- extract-data [xml]
  (let [games (html/select xml [:boardgame])]
    (->> games (map game-data) doall)))

(defn find-node-id
  "Returns the node id if it exists otherwise nil"
  [connection key label]
  ; YEAH THATS RIGHT STRING CONCATENATION OF A QUERY!
  (let  [query  (str "MATCH (n:" label " { key: '" (name key) "' }) RETURN n")
         result (cy/tquery connection query)]
    (if (not (empty? result))
      (-> result first (get "n") :metadata :id))))

(defn- get-node [connection key label]
  (let [id (find-node-id connection key label)]
    (if id
      (nn/get connection id)
      (let [node (nn/create connection { :key key })
            _    (nl/add connection node label)]
        node))))

(defn- link-simple-nodes [connection parent values label]
  (doseq [value values
          :let  [child (nn/create connection { :value value })
                 _     (nl/add connection child label)]]
    (nrl/maybe-create connection parent child label)))

(defn- link-ref-nodes [connection parent values label]
  (when-not (empty? values)
    (timbre/info "Linking" label "node to game")
    (doseq [value values
            :let  [ref-node  (get-node connection (:key value) label)
                   node-data (select-keys value [:key :name])
                   _         (nn/update connection ref-node node-data)]]
      (if (:inbound value)
        (nrl/maybe-create connection ref-node parent label)
        (nrl/maybe-create connection parent ref-node label)))))

(defn- insert [connection data]
  (let [expansion? (some #(:inbound %) (:expansions data))
        label      (if expansion? "expansion" "boardgame")
        node       (get-node connection (:key data) label)
        alt-names  (:alternate-names data)]
    (timbre/info "Updating game node data")
    (nn/update connection node (node-data data))
    (when-not (empty? alt-names)
      (timbre/info "Adding alternate names")
      (link-simple-nodes connection node alt-names "alternate_name"))
    (let [{ expansions false boardgames true } (group-by :inbound (:expansions data))]
      (timbre/info "Linking expansions")
      (link-ref-nodes connection node expansions "expansion")
      (timbre/info "Linking boardgames")
      (link-ref-nodes connection node boardgames "boardgame"))
    (doseq [[data-key label] { :honors      "honor"
                               :mechanics   "mechanic"
                               :artists     "artist"
                               :categories  "category"
                               :publishers  "publisher"
                               :podcasts    "podcast"
                               :subdomains  "subdomain"
                               :families    "family"
                               :accessories "accessory"
                               :versions    "version"
                               :designers   "designer" }]
      (link-ref-nodes connection node (data-key data) label))))

(defn- unique-constraint-for-label? [constraint label]
  (= { :property_keys ["key"] :label label :type "UNIQUENESS" }
     constraint))

(defn- setup-neo4j [connection]
  (let [current-labels (nc/get-all connection)]
    (doseq [label [:boardgame :honor :mechanic :artist :category
                   :publisher :podcasts :subdomain :family
                   :expansion :accessory :version :designer]
            :when (empty? (filter #(unique-constraint-for-label? % label) current-labels))]
      (try
        (timbre/info "Creating unique constraint for :key properties on" label)
        (nc/create-unique connection label :key)
        (catch Exception e
          (timbre/error e))))))

(defn- graph [grapher]
  (let [connection    (nr/connect (:neo4j-connection-string grapher))
        queue-manager (:queue-manager grapher)
        graph-queue   (:graph-queue   grapher)]
    (setup-neo4j connection)
    (timbre/info "Processing grapher queue")
    (util/process-queue queue-manager graph-queue
      (fn [path]
        (timbre/info "Graphing" path)
        (let [file  (io/file path)
              xml   (html/xml-resource file)
              games (extract-data xml)]
          (doseq [game games]
            (insert connection game)))))))

(defrecord Grapher [queue-manager graph-queue neo4j-connection-string process]
  component/Lifecycle
  (start [this]
    (let [process (future (graph this))]
      (assoc this :process process)))
  (stop [this]
    (future-cancel process)))

(defn new-grapher [config]
  (map->Grapher { :graph-queue             (config :graph-queue)
                  :neo4j-connection-string (config :neo4j-connection-string) }))

(defn -main
  "Entry point for simply graphing the downloaded xml files. Creates a subset
   of the entire system so that grapher can be run standalone."
  [& args]
  (util/configure-logging)
  (let [graphing-system
        (component/system-map
          :queue-manager (util/queue-manager (system-config :queues-path))
          :grapher       (component/using
                           (new-grapher system-config)
                           [:queue-manager]))]
    (component/start graphing-system)))
