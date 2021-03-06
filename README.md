# describe

* [About describe](#about-describe)
* [Quick Example](#quick-example)
* [Tutorial](#tutorial)

## About describe

Describes data structures. Like validating, but less
assertive. Milquetoast validator. Still in its infancy.

Novel? Interesting? I hope so - I'm releasing this because I think
this approach to validation is new and somewhat fun to think about,
and I'd love to find some people to think about it with me (or at
least tell me it's done better elsewhere so I can use that and stop
working on this). I hope that's you!

```clj
[sweet-tooth/describe "0.4.0"]
```

Plenty of validation libraries already exist, like
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
* _UTAKEN_: username is already taken (compare db, an external value)

The control flow for these validations looks like this:

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

describe is built to accommodate this kind of scenario. It's still a
baby, and just as I plan to treat my own eventual human babies, I've
already put it to work but I'm not getting my hopes up yet. In
particular, describe is more verbose than other libraries. If that
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

(def new-user-rules
  [[username-empty username-invalid-length username-taken]
   [username-empty username-not-alnum username-taken]])

(d/describe {} new-user-rules)
;; =>
#{[:username [:sweet-tooth.describe/empty]]}

(d/describe {:username "b3!"} new-user-rules)
;; =>
#{[:username [:sweet-tooth.describe/count-not-in-range 6 24]]
  [:username [:sweet-tooth.describe/not-alnum]]}

(d/describe {:username "bubba56"}
            new-user-rules
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
* Writing rules
  * pred, aergs, dscr
  * customizing the description
  * context
* Creating a rule graph
* Nested maps (experimental)
* Map rollup (experimental)
* Translation
* Seqs

## Overview

You use the `sweet-tooth.describe/describe` function to _describe_
some value. To describe a value is to apply a set of predicate
functions to it. For each predicate that returns true, a _description_
is added to a set of descriptions which will constitute `describe`'s
return value. The following sections will cover all the details of
writing predicates and descriptions.

## The `describe` function

The `describe` function takes three arguments:

* The value to be described
* A graph of rules
* An optional context

You call it like this:

```clojure
(d/describe {:blub :dub}               ; thing described
            #{rule-1 rule-2} ; set of rules
            {:optional :context})      ; optional context
```

The first argument doesn't have to be a map, it can be any value. If
you pass in a map, then the rules should be written to handle a
map. If you pass in a sequential value, then the rules should be
written to handle a sequential value, and so on.

## Writing rules

A rule is a map with three keys:

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
`username-empty` rule. `username-empty`'s predicate function is
`empty?`, and `empty?` is applied to the value returned by
`:username` - in this case, `"hurmp"`. Since the predicate function
returns false, no description is applied, and when no descriptions are
applied `describe` returns `nil`.

Next we describe `{:username nil}`. Since `empty?` returns true, we
add a description, `[:username [::username-empty]]`, to a set of
descriptions. A description is a vector of two elements, an identifier
(`:username`) and details (`[::username-empty]`). The details are
specified with the `:dscr` key of the rule.

### `:pred`, `:args`, and `:dscr`

Above I said that `:args` is "a vector of functions that supply
arguments to the predicate function." In the previous example, you
applied a rule to the map `{:username "hurmp"}`. The rule's
predicate function was `empty?`, and the `:args` vector contained
`:username`. This specifies, "call `:username` as a function on the
value being described, `{:username "hurmp"}`. Pass the return value,
`"hurmp"`, to the predicate function `empty?`.

Since `:args` is a vector of functions, you can have predicate
functions that take more than value. One place you'd want to use this
is when validating that `:password` and `:password-confirmation`
fields match:

```clojure
(def passwords-dont-match
  {:pred not=
   :args [:password :password-confirmation]
   :dscr [::passwords-dont-match]})
   
(d/describe {:password "secure" :password-confirmation "$ecure"}
            #{passwords-dont-match})
;; =>
#{[:password [:examples/passwords-dont-match]]}
```

You need to take extra care when you want to pass in a keyword as a
constant. Check this out:

```clojure
(def missing-keys
  {:pred #(empty? (select-keys %1 %&))
   :args [identity (constantly :a) (constantly :b)]
   :dscr [::missing-keys]})

(d/describe {:username "hurmp"} #{missing-keys})
;; =>
#{[#function[clojure.core/identity] [:examples/missing-keys]]}
```

We want to apply this rule to a map as a whole; we don't want it
to apply to any particular key. Therefore, the first element in
`:args` is `identity` - this passes in the entire map to the predicate
function. The arguments `(constantly :a)` and `(constantly :b)` yield
the keys that we want to check for. We have to wrap keywords because
`describe` would tries to call the keywords as functions with
`{:username "hurmp"}` as their argument. It's as if you wrote this:

```clojure
(let [to-describe {:username "hurmp"}
      pred        #(empty? (select-keys %1 %&))]
  (pred (identity to-describe)
        ((constantly :a) to-describe)
        ((constantly :b) to-describe)))
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
  {:pred #(empty? (select-keys %1 %&))
   :args [identity (constantly :a) (constantly :b)]
   :dscr [::missing-keys]})

(d/describe {:username "hurmp"} #{missing-keys})
; => 
#{[:entire-map [:examples/missing-keys]]}
```

If you want to spice things up even more, you can also provide a
function for description details. That function should take one
argument, which will be the return value of the predicate
function. For example, you could take advantage of this feature to
write a rule that includes the return value of clojure.spec's
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
            new-user-rules
            {:db [{:username "bubba56"}]})
;; #{[:username [:examples/username-taken]]} 
```

`describe`'s third argument is the _context_. You can supply any value
you want here, but it probably makes sense to pass in a map - in this
case, we're passing in `{:db [{:username "bubba56"}]}`. Rules can
access the context by using the `context` function, which you can see
with `:args [:username (d/context :db)]`. Hopefully you can see how
the context value is threaded from the call to `describe`, through the
`context` function in `:args`, and finally passed to the predicate
function `username-taken?`.

`context` takes one argument, a function to apply to the context; the
return value is passed to the predicate function.

## Rule Graph

`describe`'s second argument takes a data structure that represents a
graph, and that graph is used to control when `describe` should
attempt to apply a rule. The next couple sections explain the
relationship between the rule graph and control flow, and how to
define a rule graph.

### The graph determines control flow

You can structure rules like this:

```
  B
 ↗
A
 ↘
  C
```

Which means, _If the rule A is applied, don't apply B or C. If A
is not applied, then it's possible for B and C to both apply._ You
could use this when describing passwords: The _A_ rule would
check whether the password exists. If it does, then _B_ would check
its length and _C_ would check whether it contains special characters.

You can also structure rules like this:

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

Rules form a directed graph, and the application of any rule
prevents the application of all rules in the subgraph reachable
from the parent rule.

### Representing graphs

The second argument to `describe` is a sequence (preferably a vector
or set for readability) representing a graph of rules. Here, the
set `#{username-empty}` is transformed into a graph with a single node:

```clojure
(d/describe {:username "hurmp"} #{username-empty})
```

Graph syntax is as follows, with arrows representing directed edges in
a digraph.

**Directed edges.** 

```clojure
#{[A B] [A C]}
;; => A → B, A → C
```

This set contains two vectors. Each vector represents two nodes, with
a directed edge from the first to the second. This establishes control
flow such that if rule A's predicate returns true, then its
description will be applied and `describe` will not attempt to apply B
or C.

**Two nodes pointing at one node.** 

```clojure
#{[B A] [C A] D}
;; => B → A, C → A
```

The data structure below describes a graph where both B and C are
pointing at A. If either B or C applies, describe will not attempt to
apply A.

**Unconnected nodes.** 

```clojure
#{A}
```

Describe will always attempt to apply unconnected nodes.

**Maps as digraphs.** 

```clojure
#{{A [B C]}}
;; => A → B, A → C
```

Maps of the form `{A [B C ...]}` will form a digraph where A points to
B, C, etc.

**Vectors as hierarchy.**

```clojure
#{[A B C]}
;; => A → B → C
```

A vector of `[A B C]` forms a digraph such that A points to B and B
points to C. If A is applied, describe will not attempt to apply B or
C.

**Don't get too fancy.** If you want to create a complex graph, don't
try to get too fancy by having deeply-nested vectors and maps.
Sometimes this will require you to write the same node multiple times
so that you can specify all of its edges. In the very first example at
the top of this README, we saw a graph defined like this:

```clojure
(def new-user-rules
  [[username-empty username-invalid-length username-taken]
   [username-empty username-not-alnum username-taken]])
```

This results in the following graph:

```
                 username-invalid-length
               ↗                         ↘
username-empty                             username-taken
               ↘                         ↗
                 username-not-alnum
```

## Nested maps (experimental)

Sometimes you want to validate nested maps? Check it:

```clojure
(def street-empty (d/empty :street))
(def city-empty (d/empty :city))
(def address-invalid
  (d/key-rule :address #{street-empty city-empty}))

(d/describe {:address {:street "street"}} new-user-rules)
; =>
#{[:address #{[:city [:describe.core/empty]]}]}
```

Using `key-rule`, we define a set of rules to apply to the
value associated with `:address`. The description for `:address` is a
set of descriptions. Indeed, the implementation for `key-rule`
recursively calls `describe` in its predicate function:

```clojure
(defn key-rule
  "Treats value returned by key-fn as new subject that you're applying rules to"
  [key-fn rules]
  {:pred (fn [key-val ctx] (describe key-val rules ctx))
   :args [key-fn (context identity)]})
```

Is this a good idea? Who knows?

You can also use `path-rule` for nested maps:

```clojure
(d/describe {:person {:address {}}}
            [(d/path-rule [:person :address] [street-empty city-empty])])
;; =>
#{[:person
   #{[:address
      #{[:city ::d/empty]
        [:street ::d/empty]}]}]}
```

It looks like it'd be difficult to relate the descriptions back to the
original map. That's where `map-rollup-descriptions` comes in...

**NOTE:** I would looooove suggestions on how to do this better :)

## Map rollup (experimental)

`map-rollup-descriptions` will create maps where it's possible:

```clojure
(d/map-rollup-descriptions
  (d/describe {:person {:address {}}}
              [(d/path-rule [:person :address] [street-empty city-empty])]))

;; =>
{:person
 #{{:address
    #{{:city #{::d/empty}
       :street #{::d/empty}}}}}}

;; compare to the un-rolled-up-version:
#{[:person
   #{[:address
      #{[:city ::d/empty]
        [:street ::d/empty]}]}]}
```

The value associated with `:person` is a set with one member, a map
with the `:address` key. `:address`'s value is also a set with one
member, the map `#{:city ::d/empty, :street ::d/empty}`.

This is in keeping with describe's feature of enabling multiple
descriptions per keyword. It lets you, for example, associate multiple
descriptions with `:city`: you could describe it with both "contains
non-alphanumeric text" and "must be less than 500 characters". It's a
bit unwieldy, though, and I'd love ideas for improvements.

## Translation

TODO: explain how to use translation to transform values like
`[::d/empty]` or `[::d/not-in-range 6 24]` into user-friendly text.

## Seqs

TODO: explain `map-describe`.

# Contributing

I am not the world's best open source project maintainer. It often
takes me weeks or months to respond to issues, PRs, and other
pro-social forms of communication. I apologize for this shortcoming.

That being said, I _do_ very much appreciate any feedback: how could
this be better? Is something broken? What am I missing? Please do open
issues and PRs with your ideas. Thank you in advance for deciding to
engage with someone as lacking in joie de maintainership as myself!

# License

Copyright © 2019 Daniel Higginbotham

Distributed under the MIT License
