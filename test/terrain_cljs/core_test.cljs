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
;; WARN: Be aware that the make-srng keeps it's state outside itself, so don't do this:
;;   (let [rand-1 (make-srng 1)
;;         rand-2 (make-srng 1)]
;;     ...)
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
;; WARN: Be aware that js-make-rnorm and make-rnorm are subtly different, the clojure version's state is fully outside the function,
;;   whereas the js one isn't, so:
;;   - these are the same:
;;   ```
;;   [(let [clj-r (make-rnorm    (make-srng 1))]
;;      [(clj-r) (clj-r)])
;;    (let [js-r  (js-make-rnorm (make-srng 1))]
;;      [(js-r)  (js-r)])
;;    (let [clj-rng (make-srng 1)]
;;      [((make-rnorm clj-rng)) ((make-rnorm clj-rng))])]
;;   #_#_=>
;;   [[0.46296593520418794 0.6209115428284142]
;;    [0.46296593520418794 0.6209115428284142]
;;    [0.46296593520418794 0.6209115428284142]]
;;   ```
;;   - but this is not!
;;   ```
;;   (let [js-rng (make-srng 1)]
;;     [((js-make-rnorm js-rng)) ((js-make-rnorm js-rng))])
;;   #_#_=>                          v------  this second value is the next value generated by the above generators, for the js version,
;;   [0.46296593520418794 1.0456922800932489]   the function must be called twice as it still retains some internal state.
;;   ```
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


;; --- randomVector
;; This is creates a vector of random magnitude and direction
;;
;; TODO: Same as before, made a functional version
(defn js-random-vector1 [scale]
  (.randomVector terrain scale))

(defn js-random-vector [rand scale]
  (.random_vector terrain rand scale))

(defn random-vector [rng scale]
  [(* scale ((make-rnorm rng))) (* scale ((make-rnorm rng)))])

(deftest random-vector-test
  (testing "Check that random-vector = js-random-vector"
    (let [_ (reset! z2 nil)]
      (is (= (js->clj
               (gen-rand-seq make-srng #(js-random-vector % 4) 100))
             (gen-rand-seq make-srng #(random-vector % 4) 100))))))


;; --- generatePoints
;; This is creates bunch of randomly positioned points
;;
;; TODO: Same as before, made a functional version
(defn js-generate-points1 [n extent]
  (.generatePoints terrain n extent))

(defn js-generate-points [rand n extent]
  (.generate_points terrain rand n extent))

(def default-extent
  {:width  1
   :height 1})

(defn generate-points
  ([rng n] (generate-points rng n default-extent))
  ([rng n extent]
   (let [{:keys [width height]} extent]
     (into []
       (comp
         (partition-all 2)
         (map (fn [[x y]] [(* (- x 0.5) width) (* (- y 0.5) height)])))
       (repeatedly (* n 2) rng)))))

(deftest generate-points-test
  (testing "Check that generate-points = js-generate-points"
    (is (= (js->clj
             (js-generate-points (make-srng 1) 100 (clj->js default-extent)))
           (generate-points      (make-srng 1) 100 default-extent)))))


;; --- centroid
;; This computes the centroid of a bunch of points
;;
(defn js-centroid [pts]
  (.centroid terrain pts))

(defn centroid [points]
  (let [size (count points)
        [sum-x sum-y]
        (reduce
          (fn [[x y] [point-x point-y]]
            [(+ x point-x)
             (+ y point-y)])
          [0 0]
          points)]
    [(/ sum-x size)
     (/ sum-y size)]))

(deftest centroid-test
  (testing "Check that centroid = js-centroid"
    (let [points (generate-points (make-srng 1) 1000 {:width 100 :height 100})]
      (is (= (js->clj
               (js-centroid (clj->js points)))
             (centroid points))))))

