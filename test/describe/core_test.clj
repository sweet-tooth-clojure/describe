(ns describe.core-test
  (:require [clojure.test :refer :all]
            [describe.core :as d]))

(def describers
  #{{:key         :name-not-empty
     :input       [:name]
     :when        empty
     :output-data :x
     :output-text "name cannot be empty"}

    {:key    :name-length
     :apply? [[:name-not-empty not]]
     :input  [:name]
     :when   #(3 < (count %) 10)
     :output "name must be between 3 and 10 characters"}

    {:key    :password-not-empty
     :input  [:password]
     :when   empty
     :output "please supply a password"}

    {:key    :passwords-match
     :apply? [[:password-not-empty not]]
     :input  [:password :confirmation]
     :when   #(not= %1 %2)
     :output "password and confirmation must be the same"
     :on     [:password]}})

(def blinding-flash
  {:name         "Blinding Flash"
   :subtypes     #{:light}
   :casting-cost 7
   :action       :slow
   :range        [0 0]
   :targets      #{:zone}
   :school       [:holy 2]
   :dice         2
   :damage-type  :light
   :effects      #{[:daze 4 9] [:stun 10 12]}
   :traits       #{:ethereal :unavoidable [:nonliving 2]}
   :description  "Attacks all objects in the zone except the caster"
   :image        "MW1A01.jpg"})

(deftest describe-nothing
  (is (= {}
         (d/describe {} describers))))

(deftest describe-basic
  (is (= 
        (d/describe {} describers))))
