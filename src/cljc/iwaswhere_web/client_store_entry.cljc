(ns iwaswhere-web.client-store-entry
  (:require #?(:cljs [iww.electron.renderer.localstorage :as sa])
    [matthiasn.systems-toolbox.component :as st]
    [iwaswhere-web.utils.misc :as u]
    [iwaswhere-web.utils.parse :as p]))

#?(:clj  (defonce new-entries-ls (atom {}))
   :cljs (defonce new-entries-ls (sa/local-storage
                                   (atom {}) "iWasWhere_new_entries")))

(defn update-local-storage
  "Updates local-storage with the provided new-entries."
  [state]
  (reset! new-entries-ls (:new-entries state)))

(defn toggle-set-fn
  "Toggles for example the visibility of a map or the edit mode for an individual
  journal entry. Requires the key to exist on the application state as a set."
  [{:keys [current-state msg-payload]}]
  (let [path (:path msg-payload)
        timestamp (:timestamp msg-payload)
        new-state (if (contains? (get-in current-state path) timestamp)
                    (update-in current-state path disj timestamp)
                    (update-in current-state path conj timestamp))]
    {:new-state new-state}))

(defn toggle-key-fn
  "Toggles config key."
  [{:keys [current-state msg-payload]}]
  (let [path (:path msg-payload)]
    {:new-state (update-in current-state path not)}))

(defn new-entry-fn
  "Create locally stored new entry for further edit."
  [{:keys [current-state msg-payload]}]
  (let [ts (:timestamp msg-payload)
        new-state (assoc-in current-state [:new-entries ts] msg-payload)]
    (update-local-storage new-state)
    {:new-state new-state}))

(defn geo-enrich-fn
  "Enrich locally stored new entry with geolocation once it becomes available.
   Does nothing when entry is already saved in backend."
  [{:keys [current-state msg-payload put-fn]}]
  (let [ts (:timestamp msg-payload)
        geo-info (select-keys msg-payload [:timestamp :latitude :longitude])
        local-entry (get-in current-state [:new-entries ts])
        new-state (update-in current-state [:new-entries ts] #(merge geo-info %))]
    (if local-entry
      (do (update-local-storage new-state)
          {:new-state new-state
           :emit-msg  [:geonames/lookup geo-info]})
      {:emit-msg [:entry/update geo-info]})))

(defn entry-saved-fn
  "Remove new entry from local when saving is confirmed by backend."
  [{:keys [current-state msg-payload msg-meta]}]
  (let [ts (:timestamp msg-payload)
        curr-local (get-in current-state [:new-entries ts])
        parent-ts (:comment-for msg-payload)
        new-state (if (= (:md curr-local) (:md msg-payload))
                    (-> current-state
                        (update-in [:new-entries] dissoc ts)
                        (assoc-in [:busy] false)
                        (assoc-in [:entries-map ts] msg-payload))
                    current-state)]
    (update-local-storage new-state)
    {:new-state    new-state
     :send-to-self (with-meta [:search/refresh] msg-meta)}))

(defn play-audio
  "Start playing audio element with provided DOM id."
  [id]
  #?(:cljs (.play (.getElementById js/document id))))

(defn pomodoro-inc-fn
  "Increments completed time of entry. Plays next tick sound and schedules a new
   increment message. Finally plays completion sound."
  [{:keys [current-state msg-payload]}]
  (let [ts (:timestamp msg-payload)
        started (:started msg-payload)
        completed-time (:completed-time msg-payload)
        dur (+ completed-time
               (/ (- (st/now) started) 1000))
        new-state (assoc-in current-state [:new-entries ts :completed-time] dur)]
    (when (get-in current-state [:new-entries ts])
      (let [new-entry (get-in new-state [:new-entries ts])
            done? (= (:planned-dur new-entry) (:completed-time new-entry))
            cfg (:cfg current-state)
            new-state (-> new-state
                          (assoc-in [:busy] (not done?))
                          (assoc-in [:last-busy] (st/now)))
            new-state (if done?
                        (update-in new-state [:new-entries ts :pomodoro-running] not)
                        new-state)]
        (if (:pomodoro-running new-entry)
          (do (when-not (:mute cfg)
                (if done? (play-audio "ringer")
                          (when (:ticking-clock cfg)
                            (play-audio "ticking-clock"))))
              (update-local-storage new-state)
              {:new-state new-state
               :emit-msg  (when (not done?)
                            [[:blink/busy {:pomodoro-completed done?}]
                             [:cmd/schedule-new
                              {:timeout 1000
                               :message [:cmd/pomodoro-inc
                                         {:started        started
                                          :completed-time completed-time
                                          :timestamp      ts}]}]])})
          {:new-state current-state})))))

(defn pomodoro-start-fn
  "Start pomodoro for entry. Will toggle the :pomodoro-running status of the
   entry and schedule an initial increment message."
  [{:keys [current-state msg-payload]}]
  (let [ts (:timestamp msg-payload)
        new-state (-> current-state
                      (update-in [:new-entries ts :pomodoro-running] not)
                      (assoc-in [:busy] false))]
    (when-let [entry (get-in current-state [:new-entries ts])]
      (update-local-storage new-state)
      {:new-state new-state
       :emit-msg  [:cmd/schedule-new
                   {:message [:cmd/pomodoro-inc
                              {:started        (st/now)
                               :completed-time (:completed-time entry)
                               :timestamp      ts}]
                    :timeout 1}]})))

(defn update-local-fn
  "Update locally stored new entry with changes from edit element."
  [{:keys [current-state msg-payload put-fn]}]
  (let [ts (:timestamp msg-payload)
        {:keys [latitude longitude geoname]} msg-payload
        saved (get-in current-state [:entries-map ts])
        relevant #(select-keys % [:md :questionnaires :custom-fields :task
                                  :habit :completed-time :starred :img-size])
        changed? (not= (relevant saved) (relevant msg-payload))]
    (when (and latitude longitude (not geoname))
      (put-fn [:geonames/lookup {:timestamp ts
                                 :latitude  latitude
                                 :longitude longitude}]))
    (if changed?
      (let [new-entry (get-in current-state [:new-entries ts])
            entry (u/deep-merge saved new-entry msg-payload)
            parsed (p/parse-entry (:md entry))
            updated (merge entry parsed)
            updated (if (-> updated :questionnaires :pomo1)
                      (update-in updated [:tags] conj "#PSS")
                      updated)
            new-state (assoc-in current-state [:new-entries ts] updated)]
        (update-local-storage new-state)
        {:new-state new-state})
      {})))

(defn remove-local-fn [{:keys [current-state msg-payload]}]
  (let [ts (:timestamp msg-payload)
        new-state (update-in current-state [:new-entries] dissoc ts)]
    (update-local-storage new-state)
    {:new-state new-state}))

(defn found-entry-fn [{:keys [current-state msg-payload]}]
  (let [ts (:timestamp msg-payload)
        new-state (assoc-in current-state [:entries-map ts] msg-payload)]
    {:new-state new-state}))

(defn geo-res [{:keys [current-state msg-payload]}]
  (let [ts (:timestamp msg-payload)
        geoname (:geoname msg-payload)
        new-state (assoc-in current-state [:new-entries ts :geoname] geoname)]
    {:new-state new-state}))

(def entry-handler-map
  {:entry/new          new-entry-fn
   :entry/found        found-entry-fn
   :entry/geo-enrich   geo-enrich-fn
   :entry/update-local update-local-fn
   :entry/remove-local remove-local-fn
   :entry/saved        entry-saved-fn
   :geonames/res       geo-res
   :cmd/pomodoro-inc   pomodoro-inc-fn
   :cmd/pomodoro-start pomodoro-start-fn})
