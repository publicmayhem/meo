(ns meo.common.utils.parse
  "Parsing functions, tested in 'meo.jvm.parse-test' namespace."
  (:require [clojure.string :as s]))

(def tag-char-cls "[\\w\\-\\u00C0-\\u017F]")

(def search-tag-regex (re-pattern (str "(?m)(?:^|[^~])(#" tag-char-cls "+)")))
(def search-not-tags-regex (re-pattern (str "(?m)~#" tag-char-cls "+")))
(def search-mention-regex (re-pattern (str "(?m)@" tag-char-cls "+")))
(def search-opts-regex (re-pattern "(?m)(?:[^c])(:[a-zA-Z]+)"))
(def cc-regex (re-pattern "(?m)(cc:)([a-zA-Z]{2})"))
(def entry-tag-regex
  (re-pattern (str "(?m)(?!^) ?#" tag-char-cls "+(?!" tag-char-cls ")")))
(def entry-mentions-regex
  (re-pattern (str "(?m) ?@" tag-char-cls "+(?!" tag-char-cls ")")))
(def date-regex #"(?m)(?:^|[^:])([0-9]{4}-[0-9]{2}-[0-9]{2})")
(def id-regex #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
(def linked-regex #"l:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
(def briefing-date-regex #"(?m)(?:b:)([0-9]{4}-[0-9]{2}-[0-9]{2})")

(defn parse-entry
  "Parses entry for hashtags and mentions. Either can consist of any of the word
   characters, dashes and unicode characters that for example comprise German
   'Umlaute'.
   Code blocks and inline code is removed before parsing for tags."
  [text]
  (let [no-codeblocks (s/replace text (re-pattern (str "```[^`]*```")) "")
        without-code (s/replace no-codeblocks (re-pattern (str "`[^`]*`")) "")]
    {:md       text
     :tags     (set (map s/trim (re-seq entry-tag-regex without-code)))
     :mentions (set (map s/trim (re-seq entry-mentions-regex without-code)))}))

(defn parse-search
  "Parses search string for hashtags, mentions, and hashtags that should not be
   contained in the filtered entries. Such hashtags can for now be marked like
   this: ~#done. Finding tasks that are not done, which don't have #done in
   either the entry or any of its comments, can be found like this:
   #task ~#done."
  [text]
  (let [text (str text)]
    {:search-text text
     :ft-search   (when-let [ft-search (re-find #"\".*\"" text)]
                    (s/replace ft-search "\"" ""))
     :tags        (set (map second (re-seq search-tag-regex text)))
     :not-tags    (set (map #(s/replace % "~" "")
                            (re-seq search-not-tags-regex text)))
     :mentions    (set (re-seq search-mention-regex text))
     :country     (get (re-find cc-regex text) 2)
     :opts        (set (map second (re-seq search-opts-regex text)))
     :briefing    (second (re-find briefing-date-regex text))
     :date-string (second (re-find date-regex text))
     :id          (re-find id-regex text)
     :timestamp   (second (re-find #"(?:^|[^:])([0-9]{13})" text))
     :linked      (second (re-find #"l:([0-9]{13})" text))
     :n           10}))

(defn add-search
  "Adds search by sending a message that'll open the specified search in a new
   tab."
  [query-string tab-group put-fn]
  (fn [_ev]
    (put-fn [:search/add
             {:tab-group (case tab-group
                           :off :off
                           :briefing :left
                           :left :right
                           :left)
              :query     (parse-search query-string)}])))

(defn autocomplete-tags
  "Determine autocomplete options for the partial tag (or mention) before the
   cursor."
  [before-cursor regex-prefix tags]
  (let [pattern (re-pattern (str "(?i)" regex-prefix tag-char-cls "+$"))
        current-tag (s/trim (str (re-find pattern before-cursor)))
        current-tag-regex (re-pattern (str "(?i)" current-tag))
        tag-substr-filter (fn [tag]
                            (when (seq current-tag)
                              (re-find current-tag-regex tag)))
        f-tags (filter tag-substr-filter tags)]
    [current-tag f-tags]))
