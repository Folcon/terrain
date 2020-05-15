(ns terrain-cljs.core-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [terrain-cljs.core :as core]
            ["d3" :as d3]
            ["d3-delaunay" :as delaunay]
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
  (if-not (nil? points)
    (let [points (remove nil? points)
          size (count points)
          [sum-x sum-y]
          (reduce
            (fn [[x y] [point-x point-y]]
              [(+ x point-x)
               (+ y point-y)])
            [0 0]
            points)]
      [(/ sum-x size)
       (/ sum-y size)])
    points))

(deftest centroid-test
  (testing "Check that centroid = js-centroid"
    (let [points (generate-points (make-srng 1) 1000 {:width 100 :height 100})]
      (is (= (js->clj
               (js-centroid (clj->js points)))
             (centroid points))))))


;; --- voronoi
;; This takes a group of points and computes the voronoi
;;
;; WARN: voronoi returns a raw js object, you must use `extract-voronoi` to get the data for the moment
(defn js-voronoi [pts extent]
  (.voronoi terrain pts extent))

(defn voronoi
  ([points] (voronoi points default-extent))
  ([points extent]
   (let [{:keys [width height]} extent
         w (/ width 2)
         h (/ height 2)
         js-points (clj->js points)]
     (-> d3
       (.voronoi)
       (.extent (clj->js [[(- w) (- h)] [w h]]))
       (#(% js-points))))))

(defn extract-voronoi [voronoi-state]
  (let [edges     (.-edges voronoi-state)
        cells     (.-cells voronoi-state)
        links     (.links voronoi-state)
        polygons  (.polygons voronoi-state)
        triangles (.triangles voronoi-state)]
    {:edges (js->clj edges)
     :cells (js->clj cells :keywordize-keys true)
     :links (js->clj links :keywordize-keys true)
     :polygons  (js->clj polygons)
     :triangles (js->clj triangles)}))

(deftest voronoi-test
  (testing "Check that voronoi = js-voronoi"
    (let [extent {:width 100 :height 100}
          points (generate-points (make-srng 1) 1000 extent)]
      (is (= (extract-voronoi
               (js-voronoi (clj->js points) (clj->js extent)))
            (extract-voronoi
              (voronoi points extent)))))))


;; --- improve-points
;; This takes a group of points and relaxes them by computing the voronoi and then relocating the points to the dual circumcenter,
;;   `n` here refers to the number of rounds of relaxation to perform.
;;
(defn js-improve-points [pts n extent]
  (.improvePoints terrain pts n extent))

(defn improve-points
  ([points]   (improve-points points 1 default-extent))
  ([points n] (improve-points points n default-extent))
  ([points n extent]
   (let [improve (fn [js-points]
                   (->
                     (voronoi (js->clj js-points) extent)
                     (.polygons js-points)
                     (.map (comp clj->js centroid js->clj))))]
     (->
       (nth (iterate improve (clj->js points)) n)
       (js->clj)))))

(deftest improve-points-test
  (testing "Check that improve-points = js-improve-points"
    (let [extent    {:width 100 :height 100}
          js-extent (clj->js extent)
          points (generate-points (make-srng 1) 10 extent)]
      (is (= (js->clj
               (js-improve-points (clj->js points) 1 js-extent))
            (improve-points points 1 extent)))
      (is (= (js->clj
               (js-improve-points (clj->js points) 2 js-extent))
            (improve-points points 2 extent)))
      (is (= (js->clj
               (js-improve-points (clj->js points) 3 js-extent))
            (improve-points points 3 extent))))))


;; --- generate-good-points
;; This generates a group of 'good' points, basically by improving them for one round and sorting them. `n` is the number of points.
;;
;; TODO: Same as before, made a functional version
(defn js-generate-good-points1 [n extent]
  (.generateGoodPoints terrain n extent))

(defn js-generate-good-points [rand n extent]
  (.generate_good_points terrain rand n extent))

(defn generate-good-points
  ([rng n] (generate-good-points rng n default-extent))
  ([rng n extent]
   (let [points (sort (generate-points rng n extent))]
     (improve-points points 1 extent))))

(deftest generate-good-points-test
  (testing "Check that generate-good-points = js-generate-good-points"
    (let [extent {:width 100 :height 100}
          n 1000]
      (is (= (js->clj
               (js-generate-good-points (make-srng 1) n (clj->js extent)))
             (generate-good-points (make-srng 1) n extent))))))


;; --- make-mesh
;; This takes a bunch of points and creates a mesh
;;
;; TODO: Change to using indexOf? (.indexOf [[3 1]] [3 1]) instead of contains? + set
(defn js-make-mesh [pts extent]
  (.makeMesh terrain pts extent))

(defn add-id+conj [{:keys [vxs] :as state} point]
  (-> state
    (assoc-in [:vxids point] (count vxs))
    (update :vxs conj point)))

(defn add-adj [{:keys [vxids] :as state} left right]
  (let [left-idx  (get vxids left)
        right-idx (get vxids right)]
    (-> state
      (update-in [:adj left-idx]  (fnil conj []) right-idx)
      (update-in [:adj right-idx] (fnil conj []) left-idx))))

(defn add-edge [{:keys [vxids] :as state} edge]
  (let [[left right] edge
        [left' right']  [(js->clj left) (js->clj right)]
        left-idx (get vxids left')
        right-idx (get vxids right')
        left-edge  (.-left edge)
        right-edge (.-right edge)]
    (update state :edges (fnil conj []) [left-idx right-idx left-edge right-edge])))

(defn add-triangles [{:keys [vxids tris] :as state} edge]
  (let [[left right] edge
        [left' right']  [(js->clj left) (js->clj right)]
        left-idx (get vxids left')
        right-idx (get vxids right')
        left-edge  (.-left edge)
        right-edge (.-right edge)]
    (cond-> state
      (not (contains? (set (get tris left-idx)) left-edge))
      (update-in [:tris left-idx] (fnil conj []) left-edge)

      (and right-edge (not (contains? (set (get tris left-idx)) right-edge)))
      (update-in [:tris left-idx] (fnil conj []) right-edge)

      (not (contains? (set (get tris right-idx)) left-edge))
      (update-in [:tris right-idx] (fnil conj []) left-edge)

      (and right-edge (not (contains? (set (get tris right-idx)) right-edge)))
      (update-in [:tris right-idx] (fnil conj []) right-edge))))

(defn make-mesh
  ([points] (make-mesh points default-extent))
  ([points extent]
   (let [voronoi-state (voronoi points extent)
         extracted-state (extract-voronoi voronoi-state)
         voronoi-edges (.-edges voronoi-state)]
     (reduce
       (fn [{:keys [vxids vxs] :as mesh-state} edge]
         (if-let [[left right] edge]
           (let [[left' right']  [(js->clj left) (js->clj right)]
                 left-idx (get vxids left')
                 right-idx (get vxids right')]
             (-> (cond-> mesh-state

                   (nil? left-idx)
                   (add-id+conj left')

                   (nil? right-idx)
                   (add-id+conj right'))
               (add-adj left' right')
               (add-edge edge)
               (add-triangles edge)))
           mesh-state))
       {:points  points
        :voronoi voronoi-state
        :extent  extent
        :vxs   []
        :vxids {}
        :adj   []
        :edges []
        :tris  []}
       voronoi-edges))))


(defn extract-mesh [mesh-state]
  (let [vxs (:vxs mesh-state)
        vxids (:vxids mesh-state)
        adj (:adj mesh-state)
        tris (:tris mesh-state)
        edges (:edges mesh-state)
        extent (:extent mesh-state)]
    {:points (:points mesh-state)
     :voronoi (extract-voronoi (:voronoi mesh-state))
     :vxs (js->clj vxs)
     :adj (js->clj adj)
     :tris (js->clj tris)
     :edges (js->clj edges)
     :extent (js->clj extent :keywordize-keys true)}))

(defn extract-js-mesh [js-mesh-state]
  (let [points (.-pts js-mesh-state)
        voronoi (.-vor js-mesh-state)
        vxs (.-vxs js-mesh-state)
        adj (.-adj js-mesh-state)
        tris (.-tris js-mesh-state)
        edges     (.-edges js-mesh-state)
        extent (.-extent js-mesh-state)]
    {:points (js->clj points)
     :voronoi (extract-voronoi voronoi)
     :vxs (js->clj vxs)
     :adj  (js->clj adj)
     :tris (js->clj tris)
     :edges (js->clj edges)
     :extent (js->clj extent :keywordize-keys true)}))


(deftest make-mesh-test
  (testing "Check that make-mesh = js-make-mesh"
    (let [extent {:width 100 :height 100}
          points (generate-good-points (make-srng 1) 1000 extent)]
      (is (= (js->clj
               (extract-js-mesh (js-make-mesh (clj->js points) (clj->js extent))))
            (extract-mesh (make-mesh points extent)))))))


;; --- generate-good-mesh
;; This makes a better mesh by using generate-good-points
;;
;; TODO: Same as before, made a functional version
(defn js-generate-good-mesh1 [n extent]
  (.generateGoodMesh terrain n extent))

(defn js-generate-good-mesh [rand n extent]
  (.generate_good_mesh terrain rand n extent))

(defn generate-good-mesh
  ([rng n] (generate-good-mesh rng n default-extent))
  ([rng n extent]
   (let [points (generate-good-points rng n extent)]
     (make-mesh points extent))))

(deftest generate-good-mesh-test
  (testing "Check that generate-good-mesh = js-generate-good-mesh"
    (let [extent {:width 100 :height 100}
          n 1000]
      (is (= (js->clj
               (extract-js-mesh (js-generate-good-mesh (make-srng 1) n (clj->js extent))))
            (extract-mesh (generate-good-mesh (make-srng 1) n extent)))))))


;; --- edge-idx?
;; This checks if index given is on the edge, by looking at the adjacency graph
;;
;; TODO: Same as before, made a functional version
(defn js-edge-idx? [mesh idx]
  (.isedge terrain mesh idx))

(defn edge-idx? [{:keys [adj] :as _mesh} idx]
  (< (count (nth adj idx)) 3))

(deftest edge-idx?-test
  (testing "Check that for a given mesh edge-idx? = js-edge-idx?"
    (let [extent {:width 100 :height 100}
          n 10
          js-mesh (js-generate-good-mesh (make-srng 1) n (clj->js extent))
          mesh (js->clj (extract-js-mesh js-mesh))
          edges (:edges mesh)]
      (is (= (map (fn [[left-idx right-idx _ _]]
                    [(js-edge-idx? js-mesh left-idx) (js-edge-idx? js-mesh right-idx)]) edges)
             (map (fn [[left-idx right-idx _ _]]
                    [(edge-idx? mesh left-idx) (edge-idx? mesh right-idx)]) edges))))))


;; --- near-edge-idx?
;; This checks if the point at the index given is near the edge, by checking it's distance from it
;;
(defn js-near-edge-idx? [mesh idx]
  (.isnearedge terrain mesh idx))

(defn near-edge-idx? [{:keys [vxs extent] :as mesh} idx]
  (let [[x y] (nth vxs idx)
        {:keys [width height]} extent]
    (println :x x :y y :w width :h height)
    (or
      (< x (* -0.45 width))
      (> x (*  0.45 width))
      (< y (* -0.45 height))
      (> y (*  0.45 height)))))

(deftest near-edge-idx?-test
  (testing "Check that for a given mesh near-edge-idx? = near-edge-idx?"
    (let [extent {:width 100 :height 100}
          n 10
          js-mesh (js-generate-good-mesh (make-srng 1) n (clj->js extent))
          mesh (js->clj (extract-js-mesh js-mesh))
          edges (:edges mesh)]
      (is (= (map (fn [[left-idx right-idx _ _]]
                    [(js-near-edge-idx? js-mesh left-idx) (js-near-edge-idx? js-mesh right-idx)]) edges)
             (map (fn [[left-idx right-idx _ _]]
                    [(near-edge-idx? mesh left-idx) (near-edge-idx? mesh right-idx)]) edges))))))
