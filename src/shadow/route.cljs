(ns shadow.route
  (:import goog.history.Html5History)
  (:require [clojure.data :as data]
            [goog.events :as gev]
            [shadow.dom :as dom]
            [shadow.object :as so]
            ))


;; FIXME: rewrite this, its clearly broken in some places

(def history (Html5History.))
(def current-state (atom nil))
(def current-path (atom (-> js/document
                            (.-location)
                            (.-pathname))))

(defn pop-current []
  (let [current @current-state
        parent (so/get-parent current)]
    (so/log "exit-route" current parent)
    (so/destroy! current)
    (reset! current-state parent)))

(defn route-match? [parts tokens]
  (when (<= (count parts) (count tokens))
    (loop [parts parts
           tokens tokens
           route-depth 0
           route-args {}]
      (if (empty? parts)
        [(assoc route-args ::route-depth route-depth) tokens]
        (let [pf (first parts)
              tf (first tokens)]
          (cond
           (keyword? pf)
           (recur (rest parts) (rest tokens) (inc route-depth) (assoc route-args pf tf))
           (= pf tf)
           (recur (rest parts) (rest tokens) (inc route-depth) route-args)
           :else
           nil)
          )))))

(defn enter-route [route-type route-args]
  (let [current @current-state
        child (so/create route-type (assoc route-args :parent current))
        parent-dom (so/get-dom current)
        child-container (dom/query-one ".route-children" parent-dom)]
    (so/debug "enter-route" route-type route-args)
    (when-not child-container
      (throw (str "route " (pr-str current) " does not have a .route-children in its dom, please add")))

    (so/dom-enter child-container child)
    (reset! current-state child)))

(defn push-routes [tokens]
  (when (seq tokens)
    (let [current @current-state
          child-routes (so/get-type-attr current :routes)]
      (loop [routes (partition 2 child-routes)]
        (if (empty? routes)
          (throw (str "failed to route" (pr-str tokens)))

          (let [[route-parts route-type] (first routes)
                route-parts (if (vector? route-parts) route-parts [route-parts])]
            (if-let [[route-args remaining] (route-match? route-parts tokens)]
              (do
                (enter-route route-type route-args)
                (when (seq remaining)
                  (push-routes remaining)))
              (recur (rest routes))
              )))))))



(defn tokenize [path]
  (into [] (rest (.split (.substring path 1 (.-length path)) "/"))))

(defn do-reroute [before after depth]
  (let [route-depth (+ depth (::route-depth @current-state))
        base-path (drop-last route-depth before)
        base-length (count base-path)
        base-test (take base-length after)]
    (pop-current)
    (if (= base-path base-test)
      (do
        (so/log "will push now" @current-state)
        (push-routes (drop base-length after)))
      (recur before after route-depth))))

(defn reroute [path]
  (let [before (tokenize @current-path)
        after (tokenize path)]
    (when-not (= before after)
      (do-reroute before after 0)
      (.setToken history (.substring path 1))
      (reset! current-path path))
    ))

;; called from the app itself, should maybe do some extra checks?
(defn navigate! [path]
  (reroute path))

(defn intercept-clicks-on-a [e]
  (let [target (.-target e)]
    (when (= "A" (.-nodeName target))
      (dom/ev-stop e)
      (reroute (.-pathname target))
      )))

(defn init [root-state base-path]
  (.setUseFragment history false)
  (.setEnabled history true)

  (let [path (.getToken history) 
        path-tokens (tokenize path)]

    (reset! current-state root-state)

    (dom/on (dom/dom-node root-state) :click intercept-clicks-on-a)

    (gev/listen history "navigate"
                (fn [e]
                  (when (.-isNavigation e)
                    (reroute (str "/" (.-token e)))
                    )))

    (push-routes path-tokens)
    ))
