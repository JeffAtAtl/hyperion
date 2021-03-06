(ns hyperion.riak
  (:require [chee.util :refer [->options]]
            [chee.coerce :refer [->int]]
            [cheshire.core :refer [generate-string parse-string]]
            [clojure.data.codec.base64 :refer [encode decode]]
            [clojure.set :refer [intersection]]
            [clojure.string :as string]
            [hyperion.abstr :refer [Datastore]]
            [hyperion.filtering :as filter]
            [hyperion.key :refer (compose-key decompose-key)]
            [hyperion.log :as log]
            [hyperion.memory :as memory]
            [hyperion.sorting :as sort]
            [hyperion.riak.map-reduce.filter :refer [filter-js]]
            [hyperion.riak.map-reduce.sort :refer [sort-js]]
            [hyperion.riak.map-reduce.offset :refer [offset-js]]
            [hyperion.riak.map-reduce.limit :refer [limit-js]]
            [hyperion.riak.map-reduce.count :refer [count-js]]
            [hyperion.riak.map-reduce.ids :refer [ids-js]]
            [hyperion.riak.map-reduce.pass-thru :refer [pass-thru-js]]
            [hyperion.riak.index-optimizer :refer [build-mr index-type]]
            [hyperion.riak.types])
  (:import [com.basho.riak.client.builders RiakObjectBuilder]
           [com.basho.riak.client.query.functions JSSourceFunction]
           [com.basho.riak.client.query.indexes BinIndex KeyIndex IntIndex]
           [com.basho.riak.client.query IndexMapReduce BucketMapReduce]
           [com.basho.riak.client.raw.http HTTPClientConfig$Builder HTTPRiakClientFactory]
           [com.basho.riak.client.raw.pbc PBClientConfig$Builder PBRiakClientFactory]
           [com.basho.riak.client.raw.query.indexes BinValueQuery BinRangeQuery IntValueQuery IntRangeQuery]
           [com.basho.riak.client.raw RawClient]
           [com.basho.riak.client.raw StoreMeta StoreMeta$Builder]))

(defn pbc-config [{:keys [host port connection-timeout-millis idle-connection-ttl-millis initial-pool-size pool-size socket-buffer-size-kb]}]
  (let [^PBClientConfig$Builder config (PBClientConfig$Builder.)]
    (when host (.withHost config host))
    (when port (.withPort config port))
    (when connection-timeout-millis (.withConnectionTimeoutMillis config connection-timeout-millis))
    (when idle-connection-ttl-millis (.withIdleConnectionTTLMillis config idle-connection-ttl-millis))
    (when initial-pool-size (.withInitialPoolSize config initial-pool-size))
    (when pool-size (.withPoolSize config pool-size))
    (when socket-buffer-size-kb (.withSocketBufferSizeKb config socket-buffer-size-kb))
    (.build config)))

(defn http-config [{:keys [host port http-client mapreduce-path max-connections retry-handler riak-path scheme timeout url]}]
  (let [^HTTPClientConfig$Builder config (HTTPClientConfig$Builder.)]
    (when host (.withHost config host))
    (when port (.withPort config port))
    (when http-client (.withHttpClient config http-client))
    (when mapreduce-path (.withMapreducePath config mapreduce-path))
    (when max-connections (.withMaxConnctions config max-connections)) ; typo intended!
    (when retry-handler (.withRetryHandler config retry-handler))
    (when riak-path (.withRiakPath config riak-path))
    (when scheme (.withScheme config scheme))
    (when timeout (.withTimeout config timeout))
    (when url (.withUrl config url))
    (.build config)))

(defn build-connection-config [options]
  (case (.toLowerCase (name (:api options)))
    "pbc" (pbc-config options)
    "http" (http-config options)
    (throw (Exception. (str "Unrecognized Riak API: " (:api options))))))

(defn open-client
  "Create a Riak client. You may pass in a hashmap and/or
  key-value pairs of configuration options.
  Options:
    :api - [:pbc :http] *required
  HTTP Options:
    :host :port :http-client :mapreduce-path :max-connections
    :retry-handler :riak-path :scheme :timeout :url
    See: http://basho.github.com/riak-java-client/1.0.5/com/basho/riak/client/raw/http/HTTPClientConfig.Builder.html
  PBC Options:
    :host :port :connection-timeout-millis
    :idle-connection-ttl-millis :initial-pool-size
    :pool-size :socket-buffer-size-kb
    See: http://basho.github.com/riak-java-client/1.0.5/com/basho/riak/client/raw/pbc/PBClientConfig.Builder.html"
  [& args]
  (let [options (->options args)
        config (build-connection-config options)]
    (case (.toLowerCase (name (:api options)))
      "pbc" (.newClient (PBRiakClientFactory/getInstance) config)
      "http" (.newClient (HTTPRiakClientFactory/getInstance) config))))

(def ^String ^:dynamic *app* "Hyperion")

(defn bucket-name [kind]
  (str *app* kind))

(def ^StoreMeta store-options
  (-> (StoreMeta$Builder.)
    (.returnBody true)
    (.build)))

(defmulti add-index (fn [builder k v] (index-type v)))

(defmethod add-index :int [builder k v]
  (.addIndex builder (name k) (->int v)))

(defmethod add-index :bin [builder k v]
  (.addIndex builder (name k) (str v)))

(defmethod add-index nil [_ _ _])

(defn- ->native [record kind id]
  (let [record (dissoc record :id :kind )
        json (generate-string record)
        builder (RiakObjectBuilder/newBuilder (bucket-name kind) id)]
    (.withValue builder json)
    (doseq [[k v] record]
      (add-index builder k v))
    (.build builder)))

(defn json->record [json kind key]
  (assoc (parse-string json true)
    :kind kind
    :key key))

(defn native->record [native kind id]
  (let [record (parse-string (String. (.getValue native)) true)]
    (assoc record
      :kind kind
      :key (compose-key kind id))))

(defn- save-record [client record]
  (let [key (or (:key record) (compose-key (:kind record)))
        [kind id] (decompose-key key)
        native (->native record kind id)
        response (.store client native store-options)
        native-result (first (.getRiakObjects response))
        saved-json (.getValueAsString native-result)]
    (json->record saved-json kind key)))

(defn- find-by-key
  ([client key]
    (try
      (let [[kind id] (decompose-key key)]
        (find-by-key client (bucket-name kind) kind id))
      (catch Exception e
        (log/warn (format "find-by-key error: %s" (.getMessage e)))
        nil)))
  ([client bucket kind id]
    (let [response (.fetch client bucket id)]
      (when (.hasValue response)
        (when (.hasSiblings response)
          (log/warn "Whao! Siblings! Siblings are not allowed by default.  Someone must have tweaked things! bucket:" bucket " key:" id))
        (native->record (first (.getRiakObjects response)) kind id)))))

(defn- delete-by-key
  ([client key]
    (try
    (let [[kind id] (decompose-key key)]
      (delete-by-key client (bucket-name kind) id))
      (catch Exception e
        (log/warn (format "delete-by-key error: %s" (.getMessage e)))
        nil)))
  ([client bucket id] (.delete client bucket id)))

(defn- parse-record [kind raw-record]
  (assoc (dissoc raw-record :id)
         :key (compose-key kind (:id raw-record))
         :kind kind))

(defn- build-map-reduce [client kind filters sorts limit offset]
  (let [[mr filters] (build-mr client filters (bucket-name kind))]
    (-> mr
      (.addMapPhase (JSSourceFunction. (str (filter-js filters))) false)
      (#(if (not (or (nil? sorts) (empty? sorts)))
        (.addReducePhase % (JSSourceFunction. (str (sort-js sorts))) false) %))
      (#(if offset (.addReducePhase % (JSSourceFunction. (str (offset-js offset))) false) %))
      (#(if limit (.addReducePhase % (JSSourceFunction. (str (limit-js limit))) false) %)))))

(defn- execute-mr [mr]
  (-> mr
    (.execute)
    (.getResultRaw)
    (parse-string true)))

(defn- find-by-kind [client kind filters sorts limit offset]
  (-> (build-map-reduce client kind filters sorts limit offset)
    (.addReducePhase (JSSourceFunction. (str (pass-thru-js))) true)
    (execute-mr)
    (#(map (partial parse-record kind) %))))

(defn- count-by-kind [client kind filters]
  (-> (build-map-reduce client kind filters nil nil nil)
    (.addReducePhase (JSSourceFunction. (str (count-js))) true)
    (execute-mr)
    (first)))

(defn- delete-by-kind [client kind filters]
  (-> (build-map-reduce client kind filters nil nil nil)
    (.addReducePhase (JSSourceFunction. (str (ids-js))) true)
    (execute-mr)
    (#(doseq [id %] (delete-by-key client (bucket-name kind) id)))))

(defn- find-all-kinds [client]
  (let [buckets (.listBuckets client)
        buckets (filter #(.startsWith % *app*) buckets)]
    (map #(.substring % (count *app*)) buckets)))

(deftype RiakDatastore [^RawClient client]
  Datastore
  (ds-save [this records] (doall (map #(save-record client %) records)))
  (ds-delete-by-kind [this kind filters] (delete-by-kind client kind filters))
  (ds-delete-by-key [this key] (delete-by-key client key))
  (ds-count-by-kind [this kind filters] (count-by-kind client kind filters))
  (ds-find-by-key [this key] (find-by-key client key))
  (ds-find-by-kind [this kind filters sorts limit offset]
    (find-by-kind client kind filters sorts limit offset))
  (ds-all-kinds [this] (find-all-kinds client))
  (ds-pack-key [this value] (second (decompose-key value)))
  (ds-unpack-key [this kind value] (compose-key kind value)))

(defn new-riak-datastore
  "Creates a datastore implementation for Riak.
  There are several noteworthy aspects of this implementation.
  1. Records are stored as JSON in buckets that correspond to their :kind.
  2. Buckets are namespaced with the value of *app* as a prefix to the bucket name.
     ie. Given that *app* is bound to the value \"my_app_\", a record of kind \"widget\"
     will be stored in the \"my_app_widget\" bucket.
  3. All buckets are implicitly created with default options.  Siblings should not occur.
  4. All fields of each record are indexed to optimize searching.
  5. Only certain types of search operation are optimized.  They are [:= :<= :>=].
     Operations [:< :>] are mostly optimized but require some in memory filtering.
     Operations [!= :contains?] may have VERY poor performance because all the records
     of the specified kind will be loaded and filtered in memory.
  6. Sort, Offset, and Limit search options are handled in memory because Riak doesn't
     provide a facility for these.  Expect poor performance."
  [& args]
  (if (and (= 1 (count args)) (.isInstance RawClient (first args)))
    (RiakDatastore. (first args))
    (let [client (apply open-client args)]
      (RiakDatastore. client))))
