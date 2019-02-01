# describe

Describes data structures. Like validating, but less
assertive. Milquetoast validator.

```clj
[sweet-tooth/describe "0.1.0"]
```

## Quick example

```clojure
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

(def new-user-describers
  [[username-empty username-invalid-length username-taken]
   [username-empty username-not-alnum username-taken]])

(d/describe {} new-user-describers)
;; #{[:username [:describe.core/empty]]}

(d/describe {:username "b3"} new-user-describers)
;; #{[:username [:describe.core/count-not-in-range 6 24]]}

(d/describe {:username "bubba56"}
            new-user-describers
            {:db [{:username "bubba56"}]})
;; #{[:username [:examples/username-taken]]} 
```

If you're using describe to validate data, then you can treat the
presence of a description to mean the value is invalid.

## What makes describe so special???

There are already many Clojure validation libraries. My favorites are
[vlad](https://github.com/logaan/vlad) and
[bouncer](https://github.com/leonardoborges/bouncer).

describe has two features that set it apart: First, it uses a graph to
structure describers (_validators_, if you must insist), allowing you
to determine the control flow of your describers. If a username is too
short, you don't want to perform the costlier operation of checking
whether it exists. Likewise if the username contains invalid
characters. At the same time, if the username is both too short and
contains invalid characters, you want to convey both these errors to
the user.

Second, you can pass in other values to use when describing a
value. You might want to do the latter if, for example, you want to
check if a username exists in a datomic database.

### Describer Graph

You can structure describers like this:

```
  B
 ↗
A
 ↘
  C
```

Which means, _If the describer A is applied, don't apply B or C. If A
is not applied, then it's possible for B and C to both apply._ You
could use this when describing passwords: The _A_ describer would
check whether the password exists. If it does, then _B_ would check
its length and _C_ would check whether it contains special characters.

You can also structure describers like this:

```
A
 ↘
  C
 ↗
B
```

This means, _Try to apply both A and C. If either applies, don't try
to apply B._ You might use this when checking usernames: _A_ would
check username length, and _B_ would check that it doesn't contain
special characters. _C_ would check whether the username exists. Since
that involves a database operation, we don't want to perform it unless
we know that it's a valid username.

Describers form a directed graph, and the application of any describer
prevents the application of all describers in the subgraph reachable
from the parent describer.

### Description context

Sometimes validating input requires comparing it to data outside the
input. A common usecase is to check whether a username is
taken. Here's how you could do that with describe:

```clojure
(defn username-taken?
  [username db]
  (some #(= username (:username %)) db))
  
(def username-taken
  {:pred username-taken?
   :args [:username (d/context :db)]
   :dscr [::username-taken]})

(d/describe {:username "bubba56"}
            new-user-describers
            {:db [{:username "bubba56"}]})
;; #{[:username [:examples/username-taken]]} 
```

The call to `describe` takes the following arguments:

1. The value to be described
2. A set of describers
3. An optional context

## Usage



## License

Copyright © 2019 Daniel Higginbotham

Distributed under the MIT License
