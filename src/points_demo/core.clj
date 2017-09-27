(ns points-demo.core
 (:require  [config.core :refer [env]]
            [clj-time.core :as clj-time]
            [clj-time.coerce :as coerce]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            
            [qbits.spandex.utils :as s-utils]
            [qbits.spandex :as s]

            [clojure.data.json :as json]
            [clojure.java.io :as io]

            [org.httpkit.server :as server]
            
            [config.core :refer [env]]
            [compojure.core :refer [defroutes GET POST]]
            [ring.middleware.params :as middleware-params]
            [ring.middleware.resource :as middleware-resource]
            [ring.middleware.content-type :as middleware-content-type]
            [liberator.core :refer [defresource]]
            [liberator.representation :as representation]
            [ring.util.response :as ring-response])


  (:gen-class))

(def connection (delay
  (s/client {:hosts [(:query-elastic-uri env)]})))


(def index-name "event-data-query")
(def type-name "event")
(def max-size 10000)


(defn hash2 [x] (hash (str x "-")))
(defn hash3 [x] (hash (str x "--")))

(defn search
  [source-id page-size start end]
  (let [body {:size page-size
               :sort [{:timestamp "asc"} {:_uid "desc"}]
               :query {:bool {:filter [{:term {:source  source-id}}
                                       {:range {:timestamp {:gte (coerce/to-long start) :lte (coerce/to-long end)}}}]}}}
        result (s/request @connection
                {:url (str index-name "/" type-name "/_search")
                 :method :POST
                 :body body})
        events (->> result :body :hits :hits (map (comp :event :_source)))]
    events))

(defn host-or-prefix
  [url-str]
  (let [url (new java.net.URL url-str)
        host (.getHost url)]
    (if (#{"doi.org" "dx.doi.org"} host)
      (str (.getProtocol url) "://" host "/" (second (.split (.getPath url) "/")))
      (str (.getProtocol url) "://" host "/"))))


(defn points-rank
  "List of points as [subject-id, subject-property-id, object-id, object-property-id]
   Also return list of properties."
  [source-id start-date-str end-date-str]
  (let [start (coerce/from-string start-date-str)
        end (coerce/from-string end-date-str)

        result (search source-id max-size start end)

        now (coerce/to-long (clj-time/now))
        distinct-subj-count (atom 0)
        
        distinct-subj-urls (distinct
                             (map (fn [event]
                                    ; Log as the lazy realization happens.
                                    (swap! distinct-subj-count inc)
                                    (when (zero? (rem @distinct-subj-count 1000))
                                      (log/info @distinct-subj-count))
                                    (:subj_id event)) result))

        distinct-obj-urls (distinct (map :obj_id result))

        ; Find websites and/or DOI prefixes.
        subj-properties (distinct (map host-or-prefix distinct-subj-urls))
        obj-properties (distinct (map host-or-prefix distinct-obj-urls))

        ; Sorted indexed set of each, including the properties themselves.
        ; This allows us to pinpoint ranges of the indexes.
        subj-urls (apply sorted-set (concat distinct-subj-urls subj-properties))
        obj-urls (apply sorted-set (concat distinct-obj-urls obj-properties))

        subj-rank (into (sorted-map) (map vector subj-urls (range)))
        obj-rank (into (sorted-map) (map vector obj-urls (range)))
        
        coords (mapcat #(vector (-> % :subj_id subj-rank)
                                (-> % :subj_id host-or-prefix subj-rank)
                                (-> % :obj_id obj-rank)
                                (-> % :obj_id host-or-prefix obj-rank)
                                (- now (-> % :timestamp coerce/to-long))) result)

        subj-properties (vec (select-keys subj-rank subj-properties))
        obj-properties (vec (select-keys obj-rank obj-properties))]
      {:subj-properties subj-properties
        :obj-properties obj-properties
        :coords coords}))

(defn points-connections
  "Return pairs of coords of connected points as [x y z xx yy zz]
   x is subject, y is subject-property (domain or doi prefix)"
  [source-id start-date-str end-date-str]
  (let [start (coerce/from-string start-date-str)
        end (coerce/from-string end-date-str)

        result (search source-id max-size start end)
        
        connections (mapcat #(vector
                               (-> % :subj_id hash)
                               (-> % :subj_id hash2)
                               (-> % :subj_id hash3)
                               (-> % :obj_id hash)
                               (-> % :obj_id hash2)
                               (-> % :obj_id hash3)) result)]
    {:coords connections}))

(defresource connections
  [source start end]
  :available-media-types ["application/json"]
  :handle-ok (fn [ctx]
               (let [results (points-connections source start end)]
                {:status "ok"
                 :message-type "event-coords"
                 :message results})))

(defresource events
  [source start end]
  :available-media-types ["application/json"]
  :handle-ok (fn [ctx]
               (let [results (points-rank source start end)]
                {:status "ok"
                 :message-type "event-coords"
                 :message results})))

(defroutes app-routes
  (GET "/events/:source/:start/:end" [source start end] (events source start end))
  (GET "/connections/:source/:start/:end" [source start end] (connections source start end)))

(def app
  (-> app-routes
     (middleware-resource/wrap-resource "public")
     (middleware-content-type/wrap-content-type)))


(defn -main [& args]
  (let [port (Integer/parseInt (:port env))]
    
    (log/info "Start server on " port)
    (server/run-server app {:port port})))
