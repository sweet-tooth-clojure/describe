(ns sweet-tooth.describe
  (:require [loom.graph :as lg]
            [loom.alg :as la]
            [loom.attr :as lat]
            [loom.derived :as ld]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [clojure.string :as str])
  (:refer-clojure :exclude [empty not=])
  #?(:cljs (:require-macros [sweet-tooth.describe :refer [defrule]])))

(s/def ::pred ifn?)
(s/def ::args seqable?)
(s/def ::dscr any?)

(s/def ::rule (s/keys :req-un [::pred]
                           :opt-un [::args ::dscr]))
(s/def ::rule-coll (s/coll-of ::rule))
(s/def ::rule-map (s/map-of ::rule ::rule-coll))

(s/def ::rule-node-def (s/or :rule ::rule
                                  :rule-coll ::rule-coll
                                  :rule-map ::rule-map))
(s/def ::rule-nodes (s/coll-of ::rule-node))

(defn context
  "Used in rules to access the context passed in to describe."
  [cfn]
  {:pre [(ifn? cfn)]}
  (let [context-fn (if (fn? cfn) cfn #(cfn %))]
    (with-meta context-fn {::ctx true})))

(defn graph
  "Allows slightly more compact syntax for defining graphs by treating a
  vector of [:a :b :c] as pairs [:a :b] [:b :c] and by treating
  rule maps as nodes instead of attempting to treat them as maps
  describing nodes and edges"
  [node-defs]
  (let [{:keys [rules rules-and-edges]} (group-by #(if (= :rule (first (s/conform ::rule-node-def %)))
                                                               :rules
                                                               :rules-and-edges)
                                                            node-defs)]
    (apply lg/add-nodes
           (apply lg/digraph (reduce (fn [all-nodes node-data]
                                       (if (or (map? node-data) (not (seqable? node-data)))
                                         (conj all-nodes node-data)
                                         (into all-nodes (partition 2 1 node-data))))
                                     []
                                     rules-and-edges))
           rules)))

(defn resolve-args
  "Rule args come in four flavors:
  * A function wrapped with `context`. This resolves by applying
    the fn to the context.
  * A fn with the `:const` keyword in metadata. This resolves to itself.
  * A keyword or fn. This resolves by being applied to the subject (the thing being described).
  * Something else. This resolves to itself."
  [ctx args]
  (reduce (fn [resolved arg]
            (let [arg-meta (meta arg)]
              (conj resolved (cond (::ctx arg-meta)    (arg ctx)
                                   (:const arg-meta)   arg
                                   (or (fn? arg)
                                       (keyword? arg)) (arg (::subject ctx))

                                   :else arg))))
          []
          args))

(defn rule-applies?
  "Apply the predicate function to args."
  [ctx _rule-graph rule]
  (let [{:keys [pred args]
         :or   {args [identity]}} rule]
    (apply pred (resolve-args ctx args))))

(defn rules->graph
  "Convert data structure describing graph to an actual graph."
  [rules]
  (if (lg/graph? rules)
    (if (lg/directed? rules)
      rules
      (throw (#?(:clj AssertionError.
                 :cljs js/Error.)
               "when rules is a graph, it must be a digraph")))
    (graph rules)))

(defn add-description
  "If dscr is a function apply it to result of pred function
  Otherwise return dscr"
  [descriptions rule result]
  (let [{:keys [dscr args as]
         :or   {dscr identity
                args [identity]}} rule
        description (if (fn? dscr) (dscr result) dscr)]
    ;; ::ignore is used for control flow to allow rules to
    ;; indicate that the subgraph should not be applied, but the
    ;; current rule's description shouldn't be aded
    (if-not (= [::ignore] description)
      (conj descriptions ^:description [(or as (first args)) description])
      descriptions)))

(defn remove-rule-subgraph
  "Remove all downstream rules so that we don't attempt to apply them"
  [rule-graph rule]
  (->> (ld/subgraph-reachable-from rule-graph rule)
       lg/nodes
       (apply lg/remove-nodes rule-graph)))

;; -----------------
;; Perform description
;; -----------------
(defn describe
  [x rules & [additional-ctx]]
  (let [ctx (merge additional-ctx {::subject x})]
    (loop [rule-graph (rules->graph rules)
           descriptions    ^:descriptions #{}
           remaining       (la/topsort rule-graph)]
      (if (empty? remaining)
        (if (empty? descriptions)
          nil
          descriptions)
        (let [rule               (first remaining)
              applies?                (rule-applies? ctx rule-graph rule)
              updated-rule-graph (cond-> (lat/add-attr rule-graph rule :applied? applies?)
                                        applies? (remove-rule-subgraph rule))]
          (recur updated-rule-graph
                 ;; ignore indicates that the rule doesn't have a
                 ;; description, but that its subgraph shouldn't be
                 ;; applied. meant for control flow.
                 (if applies?
                   (add-description descriptions rule applies?)
                   descriptions)
                 (->> (rest remaining)
                      (filter (set (lg/nodes updated-rule-graph))))))))))

;; More complex describing and rules

(defn map-describe
  "Apply rules to xs; return nil if all descriptions nil"
  ([rules xs]
   (map-describe rules nil xs))
  ([rules additional-ctx xs]
   (let [descriptions (map #(apply describe % rules additional-ctx) xs)]
     (when (some identity descriptions)
       descriptions))))

;; -----------------
;; Rule helpers
;; -----------------

(defn key-rule
  "Treats value returned by key-fn as new context that you're applying rules to"
  [key-fn rules]
  {:pred (fn [key-val ctx] (describe key-val rules ctx))
   :args [key-fn (context identity)]})

(defn path-rule
  "Nested key rules"
  [key-fns rules]
  (let [key-fns (reverse key-fns)]
    (reduce (fn [rule key-fn]
              (key-rule key-fn [rule]))
            (key-rule (first key-fns) rules)
            (rest key-fns))))

(defn- base-arity
  "used to construct a rule"
  [args rule]
  [args (merge {:args args} rule)])

#?(:clj (defmacro defrule [name args rule]
          (cond-> `(defn ~name
                     (~@(base-arity args rule))
                     (~(conj args 'dscr)
                      (-> (~name ~@args)
                          (assoc :dscr ~(quote dscr)))))
            (> (count args) 1) (concat `[([~(first args)] (fn ~(vec (rest args)) (~name ~@args)))]))))

;; built-in rules
(defrule empty
  [arg]
  {:pred empty?
   :dscr [::empty]})

(defrule blank
  [arg]
  {:pred str/blank?
   :dscr [::blank]})

(defrule not=
  [arg compare-to]
  {:pred clojure.core/not=
   :dscr [::not= compare-to]})

(defrule matches
  [arg regex]
  {:pred #(re-find %2 %1)
   :dscr [::matches regex]})

(defn not-alnum
  [arg]
  (-> (matches arg #"[^a-zA-Z\d]")
      (assoc :dscr [::not-alnum])))

(defrule does-not-match
  [arg regex]
  {:pred #(not (re-find %2 %1))
   :dscr [::does-not-match regex]})

(defrule not-in-range
  [arg m n]
  {:pred #(not (< m % n))
   :dscr [::not-in-range m n]})

(defrule count-not-in-range
  [arg m n]
  {:pred #(not (< m (count %) n))
   :args [arg]
   :dscr [::count-not-in-range m n]})

(defrule spec-explain-data
  [arg spec]
  {:pred (partial s/explain-data spec)
   :args [arg]
   :dscr (fn [explanation]
           [::spec-explain-data spec explanation])})

(defrule spec-not-valid
  [arg spec]
  {:pred (complement (partial s/valid? spec))
   :args [arg]
   :dscr [::spec-not-valid spec]})

;; -----------------
;; Description rollup
;; -----------------
(defn map-rollup-descriptions
  "Converts set of descriptions to a map roughly reflecting the original
  map described. Descriptions take the form of:

  #{[:a [::y]]
    [:a [::z]]
    [:b [::x]]}

  This returns a map of the form:

  {:a #{::y ::z}
   :b #{::x}}

  A concession to people who insist that this is about validation and
  not describing."
  [descriptions]
  (walk/postwalk (fn [x]
                   (if (:descriptions (meta x))
                     (reduce (fn [rollup [key description]]
                               (update rollup key (fnil #(conj % description) #{})))
                             {}
                             x)
                     x))
                 descriptions))
;; -----------------
;; translation
;; -----------------

(def default-translations
  {::empty              "is required"
   ::blank              "is required"
   ::not=               "does not match its confirmation"
   ::matches            (fn [regex] (str "matches #\"" regex "\" but shouldn't"))
   ::not-alnum          "contains non-alphanumeric characters"
   ::does-not-match     (fn [regex] (str "does not match #\"" regex "\""))
   ::not-in-range       (fn [m n] (str "is not between " m " and " n))
   ::count-not-in-range (fn [m n] (str "length is not between " m " and " n))
   ::spec-explain-data  "is not valid"
   ::spec-not-valid     "is not valid"})

(defn translate-description
  [[_arg dscr] translations]
  (let [dscr-key (if (sequential? dscr)
                   (first dscr)
                   dscr)
        translation (get translations dscr-key dscr)]
    (if (fn? translation)
      (apply translation (rest dscr))
      translation)))

(defn translate
  ([descriptions]
   (translate default-translations descriptions))
  ([translations descriptions]
   (walk/postwalk (fn [x]
                    (if (:description (meta x))
                      (assoc x 1 (translate-description x translations))
                      x))
                  descriptions)))
