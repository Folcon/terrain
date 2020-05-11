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


;; --- makeSRNG
;; This is just a seeded pseudo-random number generator
;;
(defn js-make-srng [start]
  (.makeSRNG terrain start))

(def seed (atom nil))

(defn next-rand-from-seed [seed]
  (let [x (* (Math/sin seed) 10000)]
    (- x (Math/floor x))))

(defn make-srng [start]
  (reset! seed start)
  (fn []
    (let [current @seed]
      (swap! seed inc)
      (next-rand-from-seed current))))

(deftest make-srng-test
  (testing "Check that make-srng = js-make-srng"
    (let [js-rand (js-make-srng 1)
          rand    (make-srng 1)]
      (is (= (repeatedly 100 js-rand)
             (repeatedly 100 rand))))))


;; --- runif
;; This effectively generates a random number between low and high to return true or false, used in conditionals
;;
;; TODO: Figure out a way to test this properly, at the moment I'd have to do some binding magic,
;;   so I made a functional version, less for this, and more for the general technique.
(defn js-runif [lo hi]
  (.runif terrain lo hi))

(defn js-run-if [rng lo hi]
  (.run_if terrain rng lo hi))

(defn run-if [rng low high]
  (+ low (* (rng) (- high low))))

(defn gen-rand-seq [make-srng-fn rand-fn n]
  (->
    (reduce
      (fn [[rng out] f]
        (let [r (f rng)]
          [rng (conj out (if (fn? r) (r) r))]))
      [(make-srng-fn 1) []]
      (repeat n rand-fn))
    (second)))

(deftest run-if-test
  (testing "Check that run-if = js-runif"
    (is (= (gen-rand-seq make-srng #(js-run-if % 0.5 1) 100)
           (gen-rand-seq make-srng #(run-if % 0.5 1)    100)))))

;; --- rnorm
;; This is creates a pair of random numbers, you call it once for the first and then again for the second
;;
;; TODO: Same as before, made a functional version
(defn js-rnorm []
  (.rnorm terrain))

(defn js-make-rnorm [rand]
  (.make_seeded_rnorm terrain rand))

(defn rng->z1+z2 [rng]
  (let [x1 0
        x2 0
        [x1 x2 w] (loop [x1 x1 x2 x2]
                    (let [w (+ (* x1 x1) (* x2 x2))]
                      (if (and (not (zero? w)) (< w 1))
                        [x1 x2 w]
                        (recur
                          (run-if rng -1 1)
                          (run-if rng -1 1)))))
        ;w = Math.sqrt(-2 * Math.log(w) / w)
        w (Math/sqrt (/ (* -2 (Math/log w)) w))
        z2-val (* x2 w)
        z1-va1 (* x1 w)]
    [z1-va1 z2-val]))

;; TODO: Look to remove atom
(def z2 (atom nil))
(defn make-rnorm [rng]
  (fn []
    (if-let [z2-val @z2]
      (do (reset! z2 nil)
          z2-val)
      (let [[z1-val z2-val] (rng->z1+z2 rng)]
        (do (reset! z2 z2-val)
            z1-val)))))

(defn gen-rand-seq-2 [make-srng-fn rand-fn n]
  (->
    (reduce
      (fn [[rng out] f]
        (let [r (f rng)]
          [rng (into out [(r) (r)])]))
      [(make-srng-fn 1) []]
      (repeat n rand-fn))
    (second)))

(deftest make-rnorm-test
  (testing "Check that make-rnorm = js-make-rnorm"
    (let [_ (reset! z2 nil)]
      (is (= (gen-rand-seq-2 make-srng js-make-rnorm 100)
             (gen-rand-seq-2 make-srng make-rnorm 100))))))
