(ns meo.electron.renderer.ui.entry.actions
  (:require [meo.electron.renderer.ui.pomodoro :as p]
            [meo.common.utils.parse :as up]
            [meo.electron.renderer.helpers :as h]
            [reagent.core :as r]
            [meo.electron.renderer.ui.entry.utils :as eu]
            [meo.common.utils.misc :as u]
            [clojure.set :as set]
            [taoensso.timbre :refer-macros [info]]
            [cljs.pprint :as pp]))

(defn trash-icon [trash-fn]
  (let [local (r/atom {:visible false})
        toggle-visible (fn [_]
                         (swap! local update-in [:visible] not)
                         (.setTimeout js/window
                                      #(swap! local assoc-in [:visible] false)
                                      5000))]
    (fn [trash-fn]
      [:span.delete-btn
       [:i.fa.fa-trash-alt.toggle {:on-click toggle-visible}]
       (when (:visible @local)
         [:span.warn.delete
          {:on-click trash-fn}
          [:i.far.fa-trash-alt] " are you sure?"])])))

(defn edit-icon
  "Renders an edit icon, which transforms into a warning button that needs to be
   clicked again for actually discarding changes. This label is a little to the
   right, so it can't be clicked accidentally, and disappears again within 5
   seconds."
  [toggle-edit edit-mode? entry]
  (let [clicked (r/atom false)
        guarded-edit-fn (fn [_ev]
                          (swap! clicked not)
                          (.setTimeout js/window #(reset! clicked false) 5000))]
    (fn [toggle-edit edit-mode? entry]
      (when edit-mode?
        [:span.delete-btn
         [:i.fa.fa-edit.toggle {:on-click guarded-edit-fn}]
         (when @clicked
           (let [click #(do (toggle-edit)
                            (swap! clicked not)
                            (info "Discarding local changes:\n"
                                  (with-out-str (pp/pprint entry))))]
             [:span.warn.discard {:on-click click}
              [:i.far.fa-trash-alt] " discard changes?"]))]))))

(defn drop-linked-fn
  "Creates handler function for drop event, which takes the timestamp of the
   currently dragged element and links that entry to the one onto which it is
   dropped."
  [entry entries-map cfg put-fn]
  (fn [_ev]
    (if (= :story (:entry-type @entry))
      ; assign story
      (let [ts (:currently-dragged @cfg)
            dropped (get @entries-map ts)
            story (:timestamp @entry)
            updated (merge (-> dropped
                               (update-in [:linked-stories] #(set/union #{story} %))
                               (assoc-in [:primary-story] story))
                           (up/parse-entry (:md dropped)))]
        (when (and ts (not= ts story))
          (put-fn [:entry/update updated])))
      ; link two entries
      (let [ts (:currently-dragged @cfg)
            updated (update-in @entry [:linked-entries] #(set (conj % ts)))]
        (when (and ts (not= ts (:timestamp updated)))
          (put-fn [:entry/update (u/clean-entry updated)]))))))

(defn drag-start-fn [entry put-fn]
  (fn [ev]
    (let [dt (.-dataTransfer ev)]
      (put-fn [:cmd/set-dragged entry])
      (aset dt "effectAllowed" "move")
      (aset dt "dropEffect" "link"))))

(defn new-link
  "Renders input for adding link entry."
  [entry put-fn create-linked-entry]
  (let [local (r/atom {:visible false})
        toggle-visible (fn [_]
                         (swap! local update-in [:visible] not)
                         (.setTimeout js/window
                                      #(swap! local assoc-in [:visible] false)
                                      5000))
        on-drag-start (drag-start-fn entry put-fn)]
    (fn [entry put-fn create-linked-entry]
      [:span.new-link-btn
       [:i.fa.fa-link.toggle {:on-click      toggle-visible
                              :draggable     true
                              :on-drag-start on-drag-start}]
       (when (:visible @local)
         [:span.new-link
          {:on-click #(do (create-linked-entry) (toggle-visible %))}
          [:i.fas.fa-plus-square] "add linked"])])))

(defn add-location
  "Renders context menu for adding location."
  [entry put-fn]
  (let [local (r/atom {:visible false})
        toggle-visible #(swap! local update-in [:visible] not)]
    (fn [entry put-fn]
      (let [new-loc #(put-fn [:entry/update-local
                              (assoc-in % [:location :type] :location)])]
        (when-not (:location entry)
          [:span.new-link-btn
           [:i.fa.fa-map-marker.toggle
            {:on-click toggle-visible}]
           (when (:visible @local)
             [:span.new-link
              {:on-click #(do (toggle-visible) (new-loc entry))}
              [:i.fa.fa-plus-square] "add location"])])))))

(defn entry-actions
  "Entry-related action buttons. Hidden by default, become visible when mouse
   hovers over element, stays visible for a little while after mose leaves."
  [ts put-fn edit-mode? toggle-edit local-cfg]
  (let [visible (r/atom false)
        entry (:entry (eu/entry-reaction ts))
        hide-fn (fn [_ev] (.setTimeout js/window #(reset! visible false) 60000))
        query-id (:query-id local-cfg)
        tab-group (:tab-group local-cfg)
        toggle-map #(put-fn [:cmd/toggle
                             {:timestamp ts
                              :path      [:cfg :show-maps-for]}])
        show-hide-comments #(put-fn [:cmd/assoc-in
                                     {:path  [:cfg :show-comments-for ts]
                                      :value %}])
        show-comments #(show-hide-comments query-id)
        create-comment (h/new-entry-fn put-fn {:comment-for ts} show-comments)
        screenshot #(put-fn [:screenshot/take {:comment-for ts}])
        story (:primary-story entry)
        create-linked-entry (h/new-entry-fn put-fn {:linked-entries #{ts}
                                                    :primary-story  story
                                                    :linked-stories #{story}} nil)
        new-pomodoro (fn [_ev]
                       (let [new-entry-fn (h/new-entry-fn put-fn
                                                          (p/pomodoro-defaults ts)
                                                          show-comments)
                             new-entry (new-entry-fn)]
                         (put-fn [:cmd/pomodoro-start new-entry])))
        trash-entry (fn [_]
                      (put-fn [:search/remove-all {:story       (:linked-story @entry)
                                                   :search-text (str ts)}])
                      (if edit-mode?
                        (put-fn [:entry/remove-local {:timestamp ts}])
                        (put-fn [:entry/trash @entry])))
        open-external (up/add-search ts tab-group put-fn)
        star-entry #(put-fn [:entry/update-local (update-in @entry [:starred] not)])
        mouse-enter #(reset! visible true)]
    (fn entry-actions-render [ts put-fn edit-mode? toggle-edit local-cfg]
      (let [map? (:latitude @entry)
            prev-saved? (or (:last-saved @entry) (< ts 1479563777132))
            comment? (:comment-for @entry)
            starred (:starred @entry)]
        [:div.actions {:on-mouse-enter mouse-enter
                       :on-mouse-leave hide-fn}
         [:div.items {:style {:opacity (if (or edit-mode? @visible) 1 0)}}
          (when map? [:i.fa.fa-map.toggle {:on-click toggle-map}])
          (when prev-saved? [edit-icon toggle-edit edit-mode? @entry])
          (when-not comment? [:i.fa.fa-stopwatch.toggle {:on-click new-pomodoro}])
          (when-not comment?
            [:i.fa.fa-comment.toggle {:on-click create-comment}])
          (when-not comment?
            [:i.fa.fa-desktop.toggle {:on-click screenshot}])
          (when (and (not comment?) prev-saved?)
            [:i.fa.fa-external-link-alt.toggle {:on-click open-external}])
          (when-not comment? [new-link @entry put-fn create-linked-entry])
          ; [add-location @entry put-fn]
          [trash-icon trash-entry]]
         [:i.fa.toggle
          {:on-click star-entry
           :style    {:opacity (if (or starred edit-mode? @visible) 1 0)}
           :class    (if starred "fa-star starred" "fa-star")}]]))))


(defn briefing-actions
  "Entry-related action buttons. Hidden by default, become visible when mouse
   hovers over element, stays visible for a little while after mose leaves."
  [ts put-fn edit-mode? local-cfg]
  (let [visible (r/atom false)
        entry (:entry (eu/entry-reaction ts))
        hide-fn (fn [_ev] (.setTimeout js/window #(reset! visible false) 60000))
        query-id (:query-id local-cfg)
        show-hide-comments #(put-fn [:cmd/assoc-in
                                     {:path  [:cfg :show-comments-for ts]
                                      :value %}])
        show-comments #(show-hide-comments query-id)
        create-comment (h/new-entry-fn put-fn {:comment-for ts} show-comments)
        story (:primary-story entry)
        create-linked-entry (h/new-entry-fn put-fn {:linked-entries #{ts}
                                                    :primary-story  story
                                                    :linked-stories #{story}} nil)
        new-pomodoro (fn [_ev]
                       (let [new-entry-fn (h/new-entry-fn put-fn
                                                          (p/pomodoro-defaults ts)
                                                          show-comments)
                             new-entry (new-entry-fn)]
                         (put-fn [:cmd/pomodoro-start new-entry])))
        mouse-enter #(reset! visible true)]
    (fn entry-actions-render [ts put-fn edit-mode? local-cfg]
      (let [comment? (:comment-for @entry)]
        [:div.actions {:on-mouse-enter mouse-enter
                       :on-mouse-leave hide-fn}
         [:div.items
          (when-not comment? [:i.fa.fa-stopwatch.toggle {:on-click new-pomodoro}])
          (when-not comment?
            [:i.fa.fa-comment.toggle {:on-click create-comment}])
          [:i.fa.fa-plus-square.toggle
           {:on-click #(create-linked-entry)}]]]))))
