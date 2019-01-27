(ns describe.core-test
  (:require [clojure.test :refer :all]
            [describe.core :as d]
            [loom.graph :as lg]
            [clojure.spec.alpha :as s]))

(def name-required
  {:pred empty?
   :args [:name]
   :dscr "cannot be empty"})

(def name-length
  {:pred #(not (< 3 (count %) 11))
   :args [:name]
   :dscr "must be between 4 and 10 characters"})

(defn name-exists?
  [name db]
  (some (fn [x] (= name (:name x))) db))

(def name-alnum
  {:pred #(re-find #"[^a-zA-Z\d$]" %)
   :args [:name]
   :dscr "can only include letters and numbers"})

(def name-unique
  {:pred name-exists?
   :args [:name (d/context :db)]
   :dscr "that name is taken"})

(def password-required
  {:pred empty?
   :args [:password]
   :dscr "please supply a password"})

(def passwords-match
  {:pred not=
   :args [:password :confirmation]
   :dscr "password and confirmation must be the same"})

(def password-complexity
  {:pred #(re-find #"[^a-zA-Z\d\s:]" %)
   :args [:password]
   :dscr "must include non-alphanumeric character"})

(def describers
  (let [nne name-required]
    #{{password-required [passwords-match password-complexity]}
      [nne name-length name-unique]
      [nne name-alnum name-unique]}))

(deftest creates-graph
  (let [a name-required
        b name-length
        c name-alnum
        d name-unique
        e password-required
        f passwords-match
        g password-complexity]
    (is (= (-> (lg/digraph [a b] [b c] [c d] [c f] [f g] {a [f g]})
               (lg/add-nodes e))
           (d/graph #{[a b c d]
                      [c f g]
                      e
                      {a [f g]}})))))

(deftest describe-nothing
  (is (nil? (d/describe {} #{}))))

(deftest no-descriptions-applied
  (is (nil? (d/describe {:name         "birf"
                         :password     "abc"
                         :confirmation "abc"}
                        describers))))

(deftest basic-describe
  (is (= #{[:password "please supply a password"]
           [:name "cannot be empty"]}
         (d/describe {} describers))))

(deftest conditional-description
  (is (= #{[:name "must be between 4 and 10 characters"]
           [:password "password and confirmation must be the same"]}
         (d/describe {:name         "bir"
                      :password     "abc"
                      :confirmation "abcd"}
                     describers))))

(deftest handles-context
  (is (= #{[:name "that name is taken"]}
         (d/describe {:name "birb"}
                     [[name-required name-length name-unique]]
                     {:db [{:name "birb"}]}))))

(deftest handles-constants
  (is (= #{[:name "must be between 4 and 10 characters"]}
         (d/describe {:name "bir"}
                     [{:pred #(not (<= %2 (count %1) %3))
                       :args [:name 4 10]
                       :dscr "must be between 4 and 10 characters"}]))))

;; nested maps and seqs
(def street-required
  {:pred empty?
   :args [:street]
   :dscr "cannot be empty"})

(def city-required
  {:pred empty?
   :args [:city]
   :dscr "cannot be empty"})

(def address-describer
  (d/key-describer :address [street-required city-required]))

(def address-description
  #{[:address #{[:street "cannot be empty"] [:city "cannot be empty"]}]})

(def address {:address {:street "x" :city "y"}})

(deftest handle-nested-map
  (is (= address-description
         (d/describe {} [address-describer]))))

(deftest handle-undescribed-nested-map
  (is (nil? (d/describe address [address-describer]))))

(deftest handle-seq
  (is (= [address-description nil address-description]
         (d/map-describe [address-describer]
                         [{} address {}]))))

(deftest handle-scalar-seq
  (is (= [nil nil #{[identity true]}]
         (d/map-describe [{:pred pos-int?}]
                         [-1 0 1]))))

(def city-doesnt-exist?
  {:pred (fn [city db] (not (some #(= % city) db)))
   :args [:city (d/context :db)]})

(def address-city-describer
  (d/key-describer :address [city-doesnt-exist?]))

(deftest key-describers-pass-context
  (is (= #{[:address #{[:city true]}]}
         (d/describe address
                     [address-city-describer]
                     {:db ["y"]}))))

(def addresses-describer
  {:pred #(d/map-describe [address-describer] %)
   :args [:addresses]})

(deftest handle-nested-seq
  (is (= #{[:addresses [address-description nil address-description]]}
         (d/describe {:addresses [{} address {}]}
                     [addresses-describer]))))

(deftest handle-nested-map
  (is (= #{[:person
            #{[:address
               #{[:city "cannot be empty"]
                 [:street "cannot be empty"]}]}]}
         (d/describe {:person {:address {}}}
                     [(d/key-describer :person [address-describer])])
         (d/describe {:person {:address {}}}
                     [(d/path-describer [:person :address] [street-required city-required])]))))

;; -----------------
;; built in describers
;; -----------------

(deftest empty-describer
  (is (= #{[:name [::d/empty]]}
         (d/describe {} #{(d/empty :name)}))))

(deftest not=-describer
  (is (= #{[:password [::d/not= :confirmation]]}
         (d/describe {:password "x" :confirmation "y"}
                     #{(d/not= :password :confirmation)}))))

(deftest matches-describer
  (let [regex #"bib"]
    (is (= #{[:name [::d/matches regex]]}
           (d/describe {:name "bibbitt"}
                       #{(d/matches :name regex)})))))

(deftest does-not-match-describer
  (let [regex #"[0-9]"]
    (is (= #{[:name [::d/does-not-match regex]]}
           (d/describe {:name "bibbitt"}
                       #{(d/does-not-match :name regex)})))))

(deftest spec-explain-data-describer
  (let [spec pos-int?]
    (is (= [:count [::d/spec-explain-data spec {::s/problems
                                                [{:path [],
                                                  :pred 'clojure.core/pos-int?,
                                                  :val 0,
                                                  :via [],
                                                  :in []}],
                                                ::s/spec spec,
                                                ::s/value 0}]]
           (first (d/describe {:count 0} #{(d/spec-explain-data :count spec)}))))))
