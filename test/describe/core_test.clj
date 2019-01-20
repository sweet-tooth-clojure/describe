(ns describe.core-test
  (:require [clojure.test :refer :all]
            [describe.core :as d]))

(def name-not-empty
  {:key :name-not-empty
   :in  [empty? :name]
   :out "name cannot be empty"})

(def name-length
  {:key :name-length
   :pre [not :name-not-empty]
   :in  [#(not (< 3 (count %) 11)) :name]
   :out "name must be between 4 and 10 characters"})

(defn name-exists?
  [name db]
  (some (fn [x] (= name (:name x))) db))

(def name-unique
  {:key :name-unique
   :pre [not :name-length]
   :in  [name-exists? :name (d/context :db)]
   :out "that name is taken"})

(def password-not-empty
  {:key :password-not-empty
   :in  [empty? :password]
   :out "please supply a password"})

(def passwords-match
  {:key :passwords-match
   :pre [not :password-not-empty]
   :in  [not= :password :confirmation]
   :out "password and confirmation must be the same"
   :as  :password})

(def password-complexity
  {:key :password-complexity
   :pre [not :password-not-empty]
   :in  [#(re-find #"[^a-zA-Z\d\s:]") :password]})

(def describers
  #{name-not-empty name-length
    password-not-empty passwords-match})

(deftest describe-nothing
  (is (= #{} (d/describe {} #{}))))

(deftest no-descriptions-applied
  (is (= #{} (d/describe {:name         "birf"
                          :password     "abc"
                          :confirmation "abc"}
                         describers))))

(deftest basic-describe
  (is (= #{[:password "please supply a password"]
           [:name "name cannot be empty"]}
         (d/describe {} describers))))

(deftest conditional-description
  (is (= #{[:name "name must be between 4 and 10 characters"]
           [:password "password and confirmation must be the same"]}
         (d/describe {:name         "bir"
                      :password     "abc"
                      :confirmation "abcd"}
                     describers))))

(deftest handles-context
  (is (= #{[:name "that name is taken"]}
         (d/describe {:name "birb"}
                     #{name-not-empty name-length name-unique}
                     {:db [{:name "birb"}]}))))

(deftest handles-constants
  (is (= #{[:name "name must be between 4 and 10 characters"]}
         (d/describe {:name "bir"}
                     #{{:key :_
                        :in  [#(not (<= %2 (count %1) %3)) :name 4 10]
                        :out "name must be between 4 and 10 characters"}}))))
