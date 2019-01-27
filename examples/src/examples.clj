(ns examples
  (:require [describe.core :as d]))

(defn check-username-exists
  [username db]
  (some #(= username (:username %)) db))

(def username-empty (d/empty :username))
(def username-invalid-length (d/length-not-in :username 6 24))
(def username-not-alnum (d/not-alnum :username))
(def username-exists?
  {:pred check-username-exists
   :args [:username (d/context :db)]
   :dscr [::username-exists]})

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
  (d/key-describer :address [{skip-when-empty }]))
