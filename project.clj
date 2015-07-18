(defproject bgg-export "1.0.0"
  :description "scrapes boardgamegeek data into neo4j and basex"
  :url "http://yobriefca.se"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure        "1.7.0"]
                 [enlive                     "1.1.6"]
                 [clojurewerkz/neocons       "3.0.0"]
                 [factual/durable-queue      "0.1.5"]
                 [com.taoensso/timbre        "4.0.2"]
                 [com.stuartsierra/component "0.2.3"]
                 [clj-http                   "1.1.2"]
                 [circuit-breaker            "0.1.7"]
                 [basex                      "1.0.0"]
                 [metrics-clojure            "2.5.1"]
                 ; WEB STUFF
                 [ring/ring-jetty-adapter    "1.4.0"]
                 [compojure                  "1.4.0"]
                 [hiccup                     "1.0.5"]
                 [metrics-clojure-ring       "2.5.1"]]
  :main bgg-export.core
  :aliases { "prime"      ["run" "-m" "bgg-export.prime"     ]
             "scrape-ids" ["run" "-m" "bgg-export.id-scraper"]
             "download"   ["run" "-m" "bgg-export.downloader"]
             "graph"      ["run" "-m" "bgg-export.grapher"   ]
             "scrape"     ["run"                             ]
             "index"      ["run" "-m" "bgg-export.indexer"   ]
             "web"        ["run" "-m" "bgg-export.web.core"  ]})
