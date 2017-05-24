(ns points-demo.core
 (:require  [config.core :refer [env]]
            [clj-time.core :as clj-time]
            [clj-time.coerce :as coerce]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.query :as q]
            ; Not directly used, but converts clj-time dates in the background.
            [monger.joda-time]
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

  (:import [org.bson.types ObjectId]
           [com.mongodb DB WriteConcern])
  (:gen-class))

(def db (delay (:db (mg/connect-via-uri (:mongodb-uri env)))))

(defn host-or-prefix
  [url-str]
  (let [url (new java.net.URL url-str)
        host (.getHost url)]
    (if (#{"doi.org" "dx.doi.org"} host)
      (str (.getProtocol url) "://" host "/" (second (.split (.getPath url) "/")))
      (str (.getProtocol url) "://" host "/"))))

(defn points-rank
  [source-id start-date-str end-date-str]
  (let [start (coerce/from-string start-date-str)
        end (coerce/from-string end-date-str)
        query {"_timestamp-date" {"$gt" start "$lt" end} "source_id" source-id}
        result (mc/find-maps @db "events" query 
                 {"subj_id" 1 "obj_id" 1 "_timestamp-date" 1})
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
                                (- now (-> % :_timestamp-date coerce/to-long))) result)

        subj-properties (vec (select-keys subj-rank subj-properties))
        obj-properties (vec (select-keys obj-rank obj-properties))]
      {:subj-properties subj-properties
        :obj-properties obj-properties
        :coords coords}))

(defresource events
  [source start end]
  :available-media-types ["application/json"]
  :handle-ok (fn [ctx]
               (let [results (points-rank source start end)]
                {:status "ok"
                 :message-type "event-coords"
                 :message results})))

(defroutes app-routes
  (GET "/events/:source/:start/:end" [source start end] (events source start end)))

(def app
  (-> app-routes
     (middleware-resource/wrap-resource "public")
     (middleware-content-type/wrap-content-type)))


(defn -main [& args]
  (let [port (Integer/parseInt (:port env))]
    
    (log/info "Start server on " port)
    (server/run-server app {:port port})))
