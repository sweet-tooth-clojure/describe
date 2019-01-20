(ns describe.core
  (:require [loom.graph :as lg]
            [loom.alg :as la]))

(defn context
  [cfn]
  {:pre [(ifn? cfn)]}
  (let [context-fn (if (fn? cfn) cfn #(cfn %))]
    (with-meta context-fn {::ctx true})))

(defn map-deps
  [describers]
  (->> describers
       (map (juxt :key (comp vec rest :pre)))
       (filter (comp seq second))
       (into {})))

(defn sort-describers
  [describers]
  (let [dep-map (map-deps describers)]
    (if (empty? dep-map)
      (map :key describers)
      (->> dep-map
           (lg/digraph)
           (la/topsort)
           (reverse)))))

(defn extract-args
  [ctx ks]
  (reduce (fn [args k]
            (conj args (cond (::ctx (meta k)) (k ctx)
                             (ifn? k)         (k (::subject ctx))
                             :else            k)))
          []
          ks))

(defn apply-fn-vec
  [ctx fn-vec]
  (let [[f & ks] fn-vec]
    (apply f (extract-args ctx ks))))

(defn apply-describer
  [ctx descriptions in-results {:keys [pre in out as]}]
  (if-not (or (not pre)
              (apply-fn-vec {::subject in-results} pre))
    {:result       nil
     :descriptions descriptions}

    (let [result (apply-fn-vec ctx in)]
      {:result       result
       :descriptions (if-not result
                       descriptions
                       (conj descriptions [(or as (second in)) out]))})))

(defn describe 
  [x describers & [additional-ctx]]
  (let [by-key (group-by :key describers)
        ctx    (merge additional-ctx {::subject x})]
    (loop [desc-keys    (sort-describers describers)
           descriptions #{}
           in-results   {}]
      (if-not (seq desc-keys)
        descriptions
        (let [desc-key                      (first desc-keys)
              {:keys [result descriptions]} (apply-describer ctx descriptions in-results (first (desc-key by-key)))]
          (recur (rest desc-keys)
                 descriptions
                 (assoc in-results desc-key result)))))))
