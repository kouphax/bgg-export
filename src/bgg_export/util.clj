(ns bgg-export.util
  (:require [taoensso.timbre                           :as timbre]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor ]
            [durable-queue                             :as queue ]
            [clojure.string                            :as string]
            [net.cgrand.enlive-html                    :as html  ]

            [circuit-breaker.core :refer [wrap-with-circuit-breaker]]))

(defn configure-logging []
  (timbre/merge-config!
    { :appenders { :rotor (rotor/rotor-appender
                             { :path     "./workspace/logs/bgg-export.log"
                               :max-size (* 512 1024)
                               :backlog  10 }) } })
  (timbre/info "Logging configured" timbre/*config*))

(defn queue-manager [path] (queue/queues path {}))

(defn get-queue-name [queue-id]
  (-> queue-id
      (name)
      (string/replace #"-" "_")))

(defn get-stats-for-queue [queue-manager queue-id]
  (let [stats (queue/stats queue-manager)
        name  (get-queue-name queue-id)]
    (get stats name { :num-slabs        0
                      :num-active-slabs 0
                      :enqueued         0
                      :retried          0
                      :completed        0
                      :in-progress      0 })))

(defn calculate-remaining-items-in-queue [stats]
  (- (:enqueued    stats)
     (:completed   stats)
     (:in-progress stats)))

(defn next-item-in-queue
  "Grabs the next item in the queue. If the queue is empty and the blocking
   take times out we idle for ten seconds before trying again. We do this
   because it allows us to log this fact which can help identify a stuck queue
   (or the logic around the queue)"
  [queue-manager source-queue]
  (let [take-with-timeout #(queue/take! queue-manager source-queue 2000 :timed-out!)]
    (loop [item (take-with-timeout)]
      (if (= item :timed-out!)
        (do
          (timbre/info "timed out getting item from" source-queue "Idling")
          (Thread/sleep 10000)
          (recur (take-with-timeout)))
        item))))

(defn process-queue-safely
  "For a given source-queue this method will pull items from the queue using
   queue-manager indefinitely. Each item will be derefed and passed into the
   process function for processing. Should the function throw an exception
   the item will be put back on the queue for future processing and necessary
   information will be logged. Should it fail continuiously the circuit breaker
   will be tripped and no processing will happen unil the circuit is closed"
  [queue-manager source-queue circuit-breaker process]
  (loop [item (next-item-in-queue queue-manager source-queue)]
    (try
      (when-not
        (wrap-with-circuit-breaker
          circuit-breaker
          (fn []
            (process (deref item))
            (queue/complete! item)))
        (timbre/warn "Circuit breaker tripped, skipping")
        (queue/retry! item))
      (catch Exception e
        (timbre/error e)
        (queue/retry! item)))
    (Thread/sleep 1000)
    (recur (next-item-in-queue queue-manager source-queue))))

(defn process-queue
  "For a given source-queue this method will pull items from the queue using
   queue-manager indefinitely. Each item will be derefed and passed into the
   process function for processing. Should the function throw an exception
   the item will be put back on the queue for future processing and necessary
   information will be logged."
  [queue-manager source-queue process]
  (loop [item (next-item-in-queue queue-manager source-queue)]
    (try
      (process (deref item))
      (queue/complete! item)
      (catch Exception e
        (timbre/error e)
        (queue/retry! item)))
    (Thread/sleep 1000)
    (recur (next-item-in-queue queue-manager source-queue))))

(defn fetch-url [url]
  (html/html-resource (java.net.URL. url)))

(defn safe-int [string]
  (try
    (-> string string/trim Integer.)
    (catch Exception e nil)))
