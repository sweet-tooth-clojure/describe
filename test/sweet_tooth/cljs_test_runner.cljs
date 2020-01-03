(ns sweet-tooth.cljs-test-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [sweet-tooth.describe-test]))

(doo-tests 'sweet-tooth.describe-test)
