(ns describe.core
  (:require [loom.graph :as lg]
            [loom.alg :as la]
            [loom.attr :as lat]
            [loom.derived :as ld]
            [clojure.spec.alpha :as s]
            [describe.core :as d]
            [clojure.walk :as walk])
  (:refer-clojure :exclude [empty not=]))

(s/def ::pred ifn?)
(s/def ::args seqable?)
(s/def ::dscr any?)

(s/def ::describer (s/keys :req-un [::pred]
                           :opt-un [::args ::dscr]))
(s/def ::describer-coll (s/coll-of ::describer))
(s/def ::describer-map (s/map-of ::describer ::describer-coll))

(s/def ::describer-node-def (s/or :describer ::describer
                                  :describer-coll ::describer-coll
                                  :describer-map ::describer-map))
(s/def ::describer-nodes (s/coll-of ::describer-node))

(defn context
  "Used in describers to access the context passed in to describe."
  [cfn]
  {:pre [(ifn? cfn)]}
  (let [context-fn (if (fn? cfn) cfn #(cfn %))]
    (with-meta context-fn {::ctx true})))

(defn graph
  "Allows slightly more compact syntax for defining graphs by treating a
  vector of [:a :b :c] as pairs [:a :b] [:b :c] and by treating
  describer maps as nodes instead of attempting to treat them as maps
  describing nodes and edges"
  [node-defs]
  (let [{:keys [describers describers-and-edges]} (group-by #(if (= :describer (first (s/conform ::describer-node-def %)))
                                                               :describers
                                                               :describers-and-edges)
                                                            node-defs)]
    (apply lg/add-nodes
           (apply lg/digraph (reduce (fn [all-nodes node-data]
                                       (if (or (map? node-data) (not (seqable? node-data)))
                                         (conj all-nodes node-data)
                                         (into all-nodes (partition 2 1 node-data))))
                                     []
                                     describers-and-edges))
           describers)))

(defn resolve-args
  "Describer args come in four flavors:
  * A function wrapped with `context`. This resolves by applying
    the fn to the context.
  * An ifn with the `:const` keyword in metadata. This resolves to itself.
  * An ifn. This resolves by being applied to the subject (the thing being described).
  * Something else. This resolves to itself."
  [ctx args]
  (reduce (fn [resolved arg]
            (let [arg-meta (meta arg)]
              (conj resolved (cond (::ctx arg-meta)  (arg ctx)
                                   (:const arg-meta) arg
                                   (ifn? arg)        (arg (::subject ctx))
                                   :else             arg))))
          []
          args))

(defn describer-applies?
  "Apply the predicate function to args."
  [ctx describer-graph describer]
  (let [{:keys [pred args]
         :or   {args [identity]}} describer]
    (apply pred (resolve-args ctx args))))

(defn describers->graph
  "Convert data structure describing graph to an actual graph."
  [describers]
  (if (lg/graph? describers)
    (if (lg/directed? describers)
      describers
      (throw (AssertionError. "when describers is a graph, it must be a digraph")))
    (graph describers)))

(defn add-description
  "If dscr is a function apply it to result of pred function
  Otherwise return dscr"
  [descriptions describer result]
  (let [{:keys [dscr args as]
         :or   {dscr identity
                args [identity]}} describer
        description (if (fn? dscr) (dscr result) dscr)]
    (conj descriptions [(or as (first args)) description])))

(defn remove-describer-subgraph
  "Remove all downstream subscribers so that we don't attempt to apply
  them"
  [describer-graph describer]
  (->> (ld/subgraph-reachable-from describer-graph describer)
       lg/nodes
       (apply lg/remove-nodes describer-graph)))

;; -----------------
;; Perform description
;; -----------------
(defn describe
  [x describers & [additional-ctx]]
  (let [ctx (merge additional-ctx {::subject x})]
    (loop [describer-graph (describers->graph describers)
           descriptions    #{}
           remaining       (la/topsort describer-graph)]
      (if (empty? remaining)
        (if (empty? descriptions)
          nil
          descriptions)
        (let [describer               (first remaining)
              applies?                (describer-applies? ctx describer-graph describer)
              updated-describer-graph (cond-> (lat/add-attr describer-graph describer :applied? applies?)
                                        applies? (remove-describer-subgraph describer))]
          (recur updated-describer-graph
                 (if applies?
                   (add-description descriptions describer applies?)
                   descriptions)
                 (->> (rest remaining)
                      (filter (set (lg/nodes updated-describer-graph))))))))))

;; More complex describing and describers

(defn map-describe
  "Apply describers to xs; return nil if all descriptions nil"
  ([describers xs]
   (map-describe describers nil xs))
  ([describers additional-ctx xs]
   (let [descriptions (map #(apply describe % describers additional-ctx) xs)]
     (when (some identity descriptions)
       descriptions))))

;; -----------------
;; Describer helpers
;; -----------------

(defn key-describer
  "Treats value returned by key-fn as new context that you're applying describers to"
  [key-fn describers]
  {:pred (fn [key-val ctx] (d/describe key-val describers ctx))
   :args [key-fn identity]})

(defn path-describer
  "Nested key describers"
  [key-fns describers]
  (let [key-fns (reverse key-fns)]
    (reduce (fn [describer key-fn]
              (d/key-describer key-fn [describer]))
            (d/key-describer (first key-fns) describers)
            (rest key-fns))))


;; built-in describers
(defn empty
  [arg]
  {:pred clojure.core/empty?
   :args [arg]
   :dscr [::empty]})

(defn not=
  ([compare-to]
   (fn [arg] (not= arg compare-to)))
  ([arg compare-to]
   {:pred clojure.core/not=
    :args [arg compare-to]
    :dscr [::not= compare-to]}))

(defn matches
  ([regex]
   (fn [arg] (matches arg regex)))
  ([arg regex]
   {:pred (partial re-find regex)
    :args [arg]
    :dscr [::matches regex]}))

(defn not-alnum
  [arg]
  (-> (matches arg #"^[a-zA-Z\d]$")
      (assoc :dscr [::not-alnum])))

(defn does-not-match
  ([regex] (fn [arg] (does-not-match arg regex)))
  ([arg regex]
   {:pred (complement (partial re-find regex))
    :args [arg]
    :dscr [::does-not-match regex]}))

(defn length-not-in
  ([m n] (fn [arg] (length-not-in arg m n)))
  ([arg m n]
   {:pred #(not (< m % n))
    :args [arg]
    :dscr [::length-not-in m n]}))

(defn spec-explain-data
  ([spec] (fn [arg] (spec-explain-data arg spec)))
  ([arg spec]
   {:pred (partial s/explain-data spec)
    :args [arg]
    :dscr (fn [explanation]
            [::spec-explain-data spec explanation])}))

(defn spec-not-valid
  ([spec] (fn [arg] (spec-not-valid arg spec)))
  ([arg spec]
   {:pred (complement (partial s/valid? spec))
    :args [arg]
    :dscr [::spec-not-valid spec]}))
