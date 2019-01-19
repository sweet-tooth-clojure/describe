(ns describe.core
  (:require [loom.graph :as lg]
            [loom.alg :as la]))

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
  [x ks]
  ((apply juxt ks) x))

(defn apply-fn-vec
  [source fn-vec]
  (let [[f & ks] fn-vec]
    (apply f (extract-args source ks))))

(defn apply-describer
  [x descriptions in-results {:keys [pre in out as]}]
  (if-not (or (not pre)
              (apply-fn-vec in-results pre))
    {:result       nil
     :descriptions descriptions}

    (let [result (apply-fn-vec x in)]
      {:result       result
       :descriptions (if-not result
                       descriptions
                       (conj descriptions [(or as (second in)) out]))})))

(defn describe 
  [x describers]
  (if (empty? describers)
    {}
    (let [by-key (group-by :key describers)]
      (loop [desc-keys    (sort-describers describers)
             descriptions #{}
             in-results   {}]
        (if-not (seq desc-keys)
          descriptions
          (let [desc-key                      (first desc-keys)
                {:keys [result descriptions]} (apply-describer x descriptions in-results (first (desc-key by-key)))]
            (recur (rest desc-keys)
                   descriptions
                   (assoc in-results desc-key result))))))))
