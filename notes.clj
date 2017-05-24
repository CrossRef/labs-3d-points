; Alphabet of space, punctuation, digits, upper-case A-Z.
; (def lower-ascii 32)
; (def upper-ascii 95)
; (def ascii-range (- upper-ascii lower-ascii))


; (defn project
;   [string]
;   (prn "project" string)
;   ; Array of char values clamped to a narrow range.
;   (let [chars (map #(Math/min (- (int %) lower-ascii) ascii-range) (.toCharArray (.toUpperCase string)))
;         length (.length string)]
;     (loop [i 0
;            value (double 10000)
;            denom (double 1)]
;            (prn i denom (/ (double (nth chars i)) denom) value)
;       (if (= i length)
;         (do (prn "got" value) value)
;         (recur (inc i)
;               (+ value (/ (double (nth chars i)) denom))
;               (* denom ascii-range))))))

; (defn project [string] (.hashCode string))



; (defn points
;   [source-id start-date-str end-date-str]
;   (let [start (coerce/from-string start-date-str)
;         end (coerce/from-string end-date-str)
;         query {"_timestamp-date" {"$gt" start "$lt" end} "source_id" source-id}

;         _ (log/info "Query" query)
;         result (mc/find-maps @db "events" query  {"subj_id" 1 "obj_id" 1 "_timestamp-date" 1})
;         result (take 100 result)
;         now (coerce/to-long (clj-time/now))
;         ; _ (log/info "Found results" (count result))
;         ; distinct-subj-count (atom 0)
;         ; distinct-obj-count (atom 0)
;         ; distinct-subj-urls (distinct (map (fn [event] (log/info (swap! distinct-subj-count inc)) (:subj_id event)) result))
;         ; distinct-obj-urls (distinct (map :obj_id result))

;         ; Find websites and/or DOI prefixes.
;         ; subj-properties (distinct (map host-or-prefix distinct-subj-urls))
;         ; obj-properties (distinct (map host-or-prefix distinct-obj-urls))

;         ; Sorted indexed set of each, including the properties themselves.
;         ; This allows us to pinpoint ranges of the indexes.
;         ; subj-urls (apply sorted-set (concat distinct-subj-urls subj-properties))
;         ; obj-urls (apply sorted-set (concat distinct-obj-urls obj-properties))

;         ; subj-rank (into (sorted-map) (map vector subj-urls (range)))
;         ; obj-rank (into (sorted-map) (map vector obj-urls (range)))
        

;         coords (mapcat #(vector (-> % :subj_id project)
;                                 (-> % :subj_id host-or-prefix project)
;                                 (-> % :obj_id project)
;                                 (-> % :obj_id host-or-prefix project)
;                                 (- now (-> % :_timestamp-date coerce/to-long))) result)

;         ;subj-properties (vec (select-keys subj-rank subj-properties))
;         ;obj-properties (vec (select-keys obj-rank obj-properties))
;         subj-properties []
;         obj-properties []
;         ]
;         (prn {:subj-properties subj-properties
;         :obj-properties obj-properties
;         :coords coords
;         })
;       {:subj-properties subj-properties
;         :obj-properties obj-properties
;         :coords coords
;         }))