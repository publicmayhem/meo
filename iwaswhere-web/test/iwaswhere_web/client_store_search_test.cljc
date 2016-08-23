(ns iwaswhere-web.client-store-search-test
  "Here, we test the search-related handler functions of the client side store
   component."
  (:require #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer-macros [deftest testing is]])
                    [iwaswhere-web.client-store :as store]
                    [iwaswhere-web.client-store-search :as search]
                    [iwaswhere-web.client-store-test :as st]))

(deftest update-query-test
  "Test that new query is updated properly in store component state"
  (let [current-state @(:state (store/initial-state-fn (fn [_put-fn])))
        handler-res (search/update-query-fn {:current-state current-state
                                             :msg-payload   st/empty-query})
        new-state (:new-state handler-res)
        toggle-msg {:timestamp (:timestamp st/test-entry) :query-id :query-1}
        new-state1 (:new-state (store/toggle-active-fn
                                 {:current-state new-state
                                  :msg-payload   toggle-msg}))
        new-state2 (:new-state (search/update-query-fn
                                 {:current-state new-state1
                                  :msg-payload   st/open-tasks-query}))]
    (testing
      "query is set locally"
      (is (= st/empty-query (:query-1 (:queries (:query-cfg new-state))))))
    (testing
      "query is sent, with additional :sort-by-upvotes key"
      (is (= (merge st/empty-query {:sort-by-upvotes nil})
             (second (first (:emit-msg handler-res))))))
    (testing
      "location change message is scheduled on change"
      (is (= (second (:emit-msg handler-res))
             [:cmd/schedule-new {:timeout 5000 :message [:search/set-hash]}])))
    (testing
      "active entry not set"
      (is (not (:active new-state))))
    (testing
      "active entry is set in base state for subseqent test"
      (is (= (:timestamp st/test-entry)
             (:query-1 (:active (:cfg new-state1))))))
    (testing
      "query is updated"
      (is (= st/open-tasks-query (:query-1 (:queries (:query-cfg new-state2))))))
    (testing
      "active entry not set after updating query"
      (is (not (:active new-state2))))))

(deftest update-query-upvotes-test
  "Test that new query is sent properly, with :sort-by-upvotes set"
  (let [current-state @(:state (store/initial-state-fn (fn [_put-fn])))
        handler-res (search/update-query-fn {:current-state current-state
                                             :msg-payload   st/open-tasks-query})
        new-state (:new-state handler-res)
        new-state1 (:new-state (store/toggle-key-fn
                                 {:current-state new-state
                                  :msg-payload   {:path [:sort-by-upvotes]}}))
        handler-res1 (search/update-query-fn {:current-state new-state1
                                              :msg-payload   st/open-tasks-query})]
    (testing
      "query is set locally"
      (is (= st/open-tasks-query (:query-1 (:queries (:query-cfg new-state))))))
    (testing
      "query is sent, with additional but false :sort-by-upvotes key"
      (is (= (merge st/open-tasks-query {:sort-by-upvotes nil})
             (second (first (:emit-msg handler-res))))))
    (testing
      "query is sent after upvotes-toggle, with additional :sort-by-upvotes key
       being true"
      (is (= (merge st/open-tasks-query {:sort-by-upvotes true})
             (second (first (:emit-msg handler-res1))))))
    (testing
      "location change message is scheduled on change"
      (is (= (second (:emit-msg handler-res1))
             [:cmd/schedule-new {:timeout 5000
                                 :message [:search/set-hash]}])))))
