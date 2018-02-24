(ns meo.jvm.files
  "This namespace takes care of persisting new entries to the log files.
  All changes to the graph data structure are appended to a daily log.
  Then later on application startup, all these state changes can be
  replayed to recreate the application state. This mechanism is inspired
  by Event Sourcing (http://martinfowler.com/eaaDev/EventSourcing.html)."
  (:require [meo.jvm.graph.add :as ga]
            [clj-uuid :as uuid]
            [clj-time.core :as time]
            [clj-time.format :as tf]
            [taoensso.timbre :refer [info error warn]]
            [matthiasn.systems-toolbox.component :as st]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy]
            [meo.jvm.file-utils :as fu]
            [buddy.core.hash :as hash]
            [buddy.core.codecs.base64 :as b64]
            [buddy.sign.jwe :as jwe]
            [buddy.core.nonce :as nonce]
            [ubergraph.core :as uc]
            [meo.common.utils.vclock :as vc]
            [meo.common.utils.misc :as u])
  (:import [java.io DataInputStream DataOutputStream]))

(defn filter-by-name
  "Filter a sequence of files by their name, matched via regular expression."
  [file-s regexp]
  (filter (fn [f] (re-matches regexp (.getName f))) file-s))

;; e.g. from: openssl rand -base64 512 > data/secret.txt
(def secret (hash/sha512 (slurp (str (:data-path (fu/paths)) "/secret.txt"))))

(defn write-encrypted [filename]
  (let [unencrypted (slurp filename)
        opts {:alg :dir :enc :a256cbc-hs512}
        encrypted (jwe/encrypt unencrypted secret opts)
        enc-filename (str filename ".enc")
        _ (spit enc-filename encrypted)
        encrypted-file (slurp enc-filename)
        dec-filename (str enc-filename ".dec")
        decrypted (String. (jwe/decrypt encrypted-file secret opts))
        _ (spit dec-filename decrypted)
        identical? (= unencrypted (slurp dec-filename))]
    (info filename "encryption/decryption success:" identical?)))

(defn append-daily-log
  "Appends journal entry to the current day's log file."
  [cfg entry]
  (let [filename (str (:daily-logs-path (fu/paths))
                      (tf/unparse (tf/formatters :year-month-day) (time/now))
                      ".jrn")
        serialized (str (pr-str entry) "\n")]
    (spit filename serialized :append true)
    (write-encrypted filename)))

(defn entry-import-fn
  "Handler function for persisting an imported journal entry."
  [{:keys [current-state msg-payload]}]
  (let [id (or (:id msg-payload) (uuid/v1))
        entry (merge msg-payload {:last-saved (st/now) :id id})
        ts (:timestamp entry)
        graph (:graph current-state)
        exists? (uc/has-node? graph ts)
        existing (when exists? (uc/attrs graph ts))
        node-to-add (if exists?
                      (if (= (:md existing) "No departure recorded #visit")
                        entry
                        (merge existing
                               (select-keys msg-payload [:longitude
                                                         :latitude
                                                         :horizontal-accuracy
                                                         :gps-timestamp
                                                         :linked-entries])))
                      entry)
        cfg (:cfg current-state)]
    (when-not (= existing node-to-add)
      (append-daily-log cfg node-to-add))
    {:new-state (ga/add-node current-state node-to-add)
     :emit-msg  [[:ft/add entry]]}))

(defn persist-state! [state]
  (try
    (info "Persisting application state")
    (let [file-path (:app-cache (fu/paths))
          serializable (update-in state [:graph] uc/ubergraph->edn)]
      (with-open [writer (io/output-stream file-path)]
        (nippy/freeze-to-out! (DataOutputStream. writer) serializable))
      (info "Application state saved to" file-path))
    (catch Exception ex (error "Error persisting cache" ex))))

(defn state-from-file []
  (let [file-path (:app-cache (fu/paths))
        thawed (with-open [reader (io/input-stream file-path)]
                 (nippy/thaw-from-in! (DataInputStream. reader)))
        state (-> thawed
                  (update-in [:sorted-entries] #(into (sorted-set-by >) %))
                  (update-in [:graph] uc/edn->ubergraph))]
    (info "Application state read from" file-path)
    {:state (atom state)}))

(defn geo-entry-persist-fn
  "Handler function for persisting journal entry."
  [{:keys [current-state msg-payload msg-meta put-fn]}]
  (let [ts (:timestamp msg-payload)
        node-id (-> current-state :cfg :node-id)
        new-global-vclock (vc/next-global-vclock current-state)
        entry (u/clean-entry msg-payload)
        entry (assoc-in entry [:last-saved] (st/now))
        entry (assoc-in entry [:id] (or (:id msg-payload) (uuid/v1)))
        entry (vc/set-latest-vclock entry node-id new-global-vclock)
        g (:graph current-state)
        prev (when (uc/has-node? g ts) (uc/attrs g ts))
        new-state (ga/add-node current-state entry)
        new-state (assoc-in new-state [:global-vclock] new-global-vclock)
        vclock-offset (get-in entry [:vclock node-id])
        new-state (assoc-in new-state [:vclock-map vclock-offset] entry)
        broadcast-meta (merge msg-meta {:sente-uid :broadcast})]
    #_(when (System/getenv "CACHED_APPSTATE")
        (future (persist-state! new-state)))
    (when (not= (dissoc prev :last-saved :vclock)
                (dissoc entry :last-saved :vclock))
      (append-daily-log (:cfg current-state) entry)
      (when-not (:silent msg-meta)
        (put-fn (with-meta [:entry/saved entry] broadcast-meta)))
      {:new-state    new-state
       :send-to-self (when-let [comment-for (:comment-for msg-payload)]
                       (with-meta [:entry/find {:timestamp comment-for}] msg-meta))
       :emit-msg     [[:ft/add entry]]})))

(defn sync-receive
  "Handler function for syncing journal entry."
  [{:keys [current-state msg-payload msg-meta put-fn]}]
  (let [ts (:timestamp msg-payload)
        entry msg-payload
        received-vclock (:vclock entry)
        g (:graph current-state)
        prev (when (uc/has-node? g ts) (uc/attrs g ts))
        new-state (ga/add-node current-state entry)
        new-meta (update-in msg-meta [:cmp-seq] #(vec (take-last 10 %)))
        broadcast-meta (merge new-meta {:sente-uid :broadcast})
        vclocks-compared (when prev
                           (vc/vclock-compare (:vclock prev) received-vclock))]
    (info vclocks-compared)
    (put-fn (with-meta [:sync/next {:newer-than    ts
                                    :newer-than-vc received-vclock}] new-meta))
    (when (= vclocks-compared :concurrent)
      (warn "conflict:" prev entry))
    (when-not (contains? #{:a>b :concurrent :equal} vclocks-compared)
      (when (= :b>a vclocks-compared)
        (put-fn (with-meta [:entry/saved entry] broadcast-meta)))
      (append-daily-log (:cfg current-state) entry)
      {:new-state new-state
       :emit-msg  [:ft/add entry]})))

(defn move-attachment-to-trash [cfg entry dir k]
  (when-let [filename (k entry)]
    (let [{:keys [data-path trash-path]} (fu/paths)]
      (fs/rename (str data-path "/" dir "/" filename)
                 (str trash-path filename))
      (info "Moved file to trash:" filename))))

(defn trash-entry-fn [{:keys [current-state msg-payload]}]
  (let [entry-ts (:timestamp msg-payload)
        new-state (ga/remove-node current-state entry-ts)
        cfg (:cfg current-state)]
    (info "Entry" entry-ts "marked as deleted.")
    (append-daily-log cfg {:timestamp (:timestamp msg-payload)
                           :deleted   true})
    (move-attachment-to-trash cfg msg-payload "images" :img-file)
    (move-attachment-to-trash cfg msg-payload "audio" :audio-file)
    (move-attachment-to-trash cfg msg-payload "videos" :video-file)
    {:new-state new-state
     :emit-msg  [[:ft/remove {:timestamp entry-ts}]
                 [:search/refresh]]}))
