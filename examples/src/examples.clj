(ns examples
  (:require [describe.core :as d]))

(defn username-taken?
  [username db]
  (some #(= username (:username %)) db))

(def username-empty (d/empty :username))
(def username-invalid-length (d/count-not-in-range :username 6 24))
(def username-not-alnum (d/not-alnum :username))
(def username-taken
  {:pred username-taken?
   :args [:username (d/context :db)]
   :dscr [::username-taken]})

(def password-empty (d/empty :password))
(def passwords-dont-match (-> (d/not= :password :confirmation)
                              (assoc :dscr [::passwords-dont-match])))
(def password-no-special-chars (-> (d/does-not-match :password #"[^a-zA-Z\d\s:]")
                                   (assoc :dscr [::no-special-chars])))

(def skip-when-empty
  {:pred empty?
   :args [identity]
   :dscr [::d/skip]})

(def street-empty (d/empty :street))
(def city-empty (d/empty :city))
(def address-invalid
  (d/key-describer :address #{{skip-when-empty [street-empty city-empty]}}))

(def new-user-describers
  [[username-empty username-invalid-length username-taken]
   [username-empty username-not-alnum username-taken]

   {password-empty [password-no-special-chars passwords-dont-match]}

   address-invalid])

(d/describe {} new-user-describers)
;; #{[:address #{[identity [:describe.core/skip]]}]
;;   [:username [:describe.core/empty]]
;;   [:password [:describe.core/empty]]}

(d/describe {:username "b3"} new-user-describers)
;; #{[:address #{[identity [:describe.core/skip]]}]
;;   [:username [:describe.core/count-not-in-range 6 24]]
;;   [:password [:describe.core/empty]]}

(d/describe {:address {:street "street"}} new-user-describers)
;; #{[:address #{[:city [:describe.core/empty]]}]
;;   [:username [:describe.core/empty]]
;;   [:password [:describe.core/empty]]}

(d/describe {:password "x"} new-user-describers)
;; #{[:address #{[identity [:describe.core/skip]]}]
;;   [:username [:describe.core/empty]]
;;   [:password [:examples/passwords-dont-match]]
;;   [:password [:examples/no-special-chars]]}

(def encrypt reverse)

(def current-password-incorrect
  {:pred (fn [current-password encrypted-existing-password]
           (not= (encrypt current-password) encrypted-existing-password))
   :args [:current-password (d/context :encrypted-existing-password)]
   :dscr [::current-password-incorrect]})

(def change-password-describer
  [{password-empty [password-no-special-chars passwords-dont-match]}
   current-password-incorrect])

(d/describe {:password "blub"}
            change-password-describer
            {:encrypted-existing-password "blib"})

;; #{[:password [:examples/passwords-dont-match]]
;;   [:current-password [:examples/current-password-incorrect]]
;;   [:password [:examples/no-special-chars]]}
