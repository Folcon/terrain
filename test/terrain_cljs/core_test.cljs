(ns terrain-cljs.core-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [terrain-cljs.core :as core]
            ["/js/terrain.js" :as terrain]
            ["/js/language.js" :as language]))


(comment
  (let [hm (.generateCoast terrain #js{:npts 4096 :extent (-> terrain .-defaultParams .-extent)})
          mesh (.-mesh hm)])
  (js-keys mesh))

(comment
  (.makeWord language (.makeRandomLanguage language) "city"))
