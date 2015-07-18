(ns bgg-export.config)

(def system-config { :queues-path             "./workspace/queues"
                     :page-queue              :page-queue
                     :id-queue                :id-queue
                     :index-queue             :index-queue
                     :graph-queue             :graph-queue
                     :download-folder         "./workspace/xml"
                     :basex-connection        { :host     "localhost"
                                                :port     1984
                                                :username "admin"
                                                :password "admin" }
                     :neo4j-connection-string "http://localhost:7474/db/data/" })
