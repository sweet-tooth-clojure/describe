# describe

* [About describe](#about-describe)
* [Quick Example](#quick-example)
* [Tutorial](#tutorial)

## About describe

Describes data structures. Like validating, but less
assertive. Milquetoast validator.

```clj
[sweet-tooth/describe "0.1.0"]
```

There are plenty of libraries to perform validation, like
[spec](https://clojure.org/guides/spec),
[schema](https://github.com/plumatic/schema),
[vlad](https://github.com/logaan/vlad), and
[bouncer](https://github.com/leonardoborges/bouncer). Why would I, a
human being with hopes and dreams and presumably better things to do,
bother to write another one?

There are three things I want in a validation library that I, in all
my travels on this earth, have not found in one package:

* Concise control flow for the application of validation predicates
* More than one validation message per value
* Easy comparison of the the value being validated with "external"
  values

All of these are exemplified in the most common usecase on the planet:
validating a username. When validating a username, I can apply these
validations:

* _UEMPTY_: username is empty
* _UCOUNT_: username is too long or short
* _UCHAR_: username contains invalid character
* _UTAKEN_: username is already taken

The control for these validations looks like this:

```
         UCOUNT
       ↗        ↘
UEMPTY            UTAKEN
       ↘        ↗
         UCHAR
```

Meaning, "If UEMPTY applies, don't attempt to apply any other
validations. If UEMPTY does not apply, attempt UCOUNT and UCHAR; it's
possible for both of these to apply to the username. If either UCOUNT
or UCHAR applies, don't apply UTAKEN so that you don't waste time
performing an unnecessary DB call."

If a username is empty, there's no point checking its length or if it
has invalid characters. If a username is too short, you don't want to
perform the costlier operation of checking whether it exists. Likewise
if the username contains invalid characters. At the same time, if the
username is both too short and contains invalid characters, you want
to convey both these errors to the user.

describe is built to accommodate this kind of scenario. It's still a
baby, and just as I plan to treat my own eventual human babies, I've
already put it to work but I don't expect it to be perfect. In
particular, describe is more verbose than spec or vlad. If that
doesn't appeal to you, [vlad](https://github.com/logaan/vlad) is
another good option.

## Quick example

Here's some code to give you an idea of how you would use describe:

```clojure
(ns examples
  (:require [sweet-tooth.describe :as d]))

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
;; =>
#{[:username [:sweet-tooth.describe/empty]]}

(d/describe {:username "b3!"} new-user-describers)
;; =>
#{[:username [:sweet-tooth.describe/count-not-in-range 6 24]]
  [:username [:sweet-tooth.describe/not-alnum]]}

(d/describe {:username "bubba56"}
            new-user-describers
            {:db [{:username "bubba56"}]})
;; =>
#{[:username [:examples/username-taken]]} 
```

(Note for the observant, `describe` returns values like `#{[:username
[:examples/username-taken]]}`, which obviously need to be transformed
before you can associate validation messages with invalid values. The
function `map-rollup-descriptions` helps with this.)

If you're using describe to validate data, then you can treat the
presence of a description to mean the value is invalid.

# Tutorial

* Overview
* The `describe` function
* Writing describers
  * pred, args, dscr
  * context
* Creating a describer graph
* Rolling up values

## Overview

You use the `sweet-tooth.describe/describe` function to _describe_
some value. To describe a value is to apply a set of predicate
functions to it. For each predicate that returns true, a _description_
is added to a set of descriptions which will constitute `describe`'s
return value. The following sections will cover all the details of
writing predications an descriptions.

## The `describe` function

The `describe` function takes three arguments:

* The value to be described
* A graph of describers
* An optional context

You call it like this:

```clojure
(d/describe {:blub :dub}               ; thing described
            #{describer-1 describer-2} ; set of describers
            {:optional :context})      ; optional context
```

The first argument doesn't have to be a map, it can be any value. If
you pass in a map, then the describers should be written to handle a
map. If you pass in a sequential value, then the describers should be
written to handle a sequential value, and so on.

## Writing describers

A describer is a map with three keys:

* `:pred`, a predicate function that determines whether the
  description should be applied
* `:args`, a vector of functions that supply arguments to the
  predicate function
* `:dscr`, description details, which can be any data or a
  function. If a function, it will be applied to the return value of
  the predicate function to get a final description details.

Here's a simple example:

```clojure
(in-ns 'tutorial)
(require '[sweet-tooth.describe :as d])

(def username-empty
  {:pred empty?
   :args [:username]
   :dscr [::username-empty]})

(d/describe {:username "hurmp"} #{username-empty})
;; =>
nil

(d/describe {:username nil} #{username-empty})
;; =>
#{[:username [::username-empty]]}
```

Here we're first describing the valaue `{:username "hurmp"}` using the
`username-empty` describer. `username-empty`'s predicate function is
`empty?`, and `empty?` is applied to the value returned by
`:username` - in this case, `"hurmp"`. Since the predicate function
returns false, no description is applied, and when no descriptions are
applied `describe` returns false.

Next we describe `{:username nil}`. Since `empty?` returns true, we
add a description, `[:username [::username-empty]]`, to a set of
descriptions. A description is a vector of two elements, an identifier
(`:username`) and details (`[::username-empty]`). The details are
specified with the `:dscr` key of the describer.

### `:pred`, `:args`, and `:dscr`

Above I said that `:args` is "a vector of functions that supply
arguments to the predicate function." In the previous example, you
applied a describer to the map `{:username "hurmp"}`. The describer's
predicate function was `empty?`, and the `:args` vector contained
`:username`. This specifies, "call `:username` as a function on the
value being described, `{:username "hurmp"}`. Pass the return value,
`"hurmp"`, to the predicate function `empty?`.

Since `:args` is a vector of functions, you can have predicate
functions that take more than value. You also have to take extra care
when you want to pass in an argument that can be treated as a function.

Check this out:

```clojure
(def missing-keys
  {:pred #(empty? (select-keys %1 %2))
   :args [identity (constantly [:a :b :c])]
   :dscr [::missing-keys]})

(d/describe {:username "hurmp"} #{missing-keys})
;; =>
#{[#function[clojure.core/identity] [:examples/missing-keys]]}
```

We want to apply this describer to a map as a whole; we don't want it
to apply to any particular key. Therefore, the first element in
`:args` is `identity` - this passes in the entire map to the predicate
function. The second argument `(constantly [:a :b :c])`, yields the
keys that we want to check for. We have to wrap the vector `[:a :b
:c]` because it implements `IFn`, and `describe` would try to call the
vector as a function with `{:username "hurmp"}` as its argument. It's
as if you wrote this:

```clojure
(let [to-describe {:username "hurmp"}
      pred        #(empty? (select-keys %1 %2))]
  (pred (identity to-describe) ((constantly [:a :b :c]) to-describe)))
```

### Customizing the description

The description returned in the previous example is funky:

```clojure
[#function[clojure.core/identity] [:examples/missing-keys]]
```

The identifier (first element of the description vector) is the
function `identity`. This is because, by default, describe uses the
first element in `args` as the identifier. You can specify your own
identifier with `:as`:

```clojure
(def missing-keys-custom-identifier
  {:pred #(empty? (select-keys %1 %2))
   :args [identity (constantly [:a :b :c])]
   :dscr [::missing-keys]})

(d/describe {:username "hurmp"} #{missing-keys})
; => 
#{[:entire-map [:examples/missing-keys]]}
```

If you want to spice things up even more, you can also provide a
function for description details. That function should take one
argument, which will be the return value of the predicate
function. For example, you could take advantage of this feature to
write a describer that includes the return value of clojure.spec's
`explain-data`:

```clojure
(:require '[clojure.spec.alpha :as s])

(s/def ::username string?)

(def username-explain-data
  {:pred (partial s/explain-data ::username)
   :args [:username]
   :dscr (fn [explanation]
           [::username-explan-data explanation])})

(d/describe {:username 3} #{username-explain-data})
; => 
#{[:username
   [:examples/username-explan-data
    #:clojure.spec.alpha{:problems
                         [{:path [],
                           :pred clojure.core/string?,
                           :val 3,
                           :via [:examples/username],
                           :in []}],
                         :spec :examples/username,
                         :value 3}]]}
```

### `context`



## Describer Graph

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

## License

Copyright © 2019 Daniel Higginbotham

Distributed under the MIT License
