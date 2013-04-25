(ns shadow.object
  (:require [shadow.dom :as dom]
            [clojure.string :as str]
            [clojure.data :as data]))

(defn console-friendly [a]
  (cond
   (nil? a) "nil"
   (string? a) a
   (number? a) a
   (satisfies? IPrintWithWriter a) (pr-str a)
   :else a
   ))

(defn log
  ([a1]
     (.log js/console (console-friendly a1)))
  ([a1 a2]
     (.log js/console (console-friendly a1) (console-friendly a2)))
  ([a1 a2 a3]
     (.log js/console (console-friendly a1) (console-friendly a2) (console-friendly a3)))
  ([a1 a2 a3 a4]
     (.log js/console (console-friendly a1) (console-friendly a2) (console-friendly a3) (console-friendly a4)))
  ([a1 a2 a3 a4 a5]
     (.log js/console (console-friendly a1) (console-friendly a2) (console-friendly a3) (console-friendly a4) (console-friendly a5)))
  ([a1 a2 a3 a4 a5 a6]
     (.log js/console (console-friendly a1) (console-friendly a2) (console-friendly a3) (console-friendly a4) (console-friendly a5) (console-friendly a6)))
  ([a1 a2 a3 a4 a5 a6 & more]
     (.log js/console (console-friendly a1) (console-friendly a2) (console-friendly a3) (console-friendly a4) (console-friendly a5) (console-friendly a6) "more:" (pr-str more))))

(def debug log)

(def info log)

(def warn log)

(def error log)

(def default-ex-info js/cljs.core.ex-info)

(set! (.. js/cljs -core -ex-info)
      (fn shadow-ex-info
        ([msg map]
           (log "EX-INFO:" msg map)
           (default-ex-info msg map))
        ([msg map cause]
           (log "EX-INFO:" msg map cause)
           (default-ex-info msg map cause))
        ))

(def obj-id (atom 0))
(defn next-id []
  (swap! obj-id inc))

(def object-defs (atom {}))
(def behavior-fns (atom {}))

(def instances (atom {}))
(def instance-parent (atom {}))
(def instance-children (atom {}))

(def events (atom {}))

(defn define-event [event-name desc args]
  (swap! events assoc event-name {:id event-name
                                  :description desc
                                  :args args}))

(defn unmunge [s]
  (-> s
      (str/replace #"_DOT_" ".")
      (str/replace #"_" "-")
      ))

(defn get-func-signature [func]
  (let [name (.-name func)]
    (when (seq name)
      (let [[_ ns fn-name line :as x] (re-find #"fn_(.+)_SLASH_(.+)_(\d+)" name)]
        (if x
          (str (unmunge ns) "/" (unmunge fn-name) " line:" line)
          name)))
    ))

(extend-type js/Function
  IPrintWithWriter
  (-pr-writer [this writer opts]
    (if-let [sig (get-func-signature this)]
      (-write writer (str "#<function " sig ">"))
      (-write writer (str "#<" this ">"))
      )))

(define-event :init
  "object initialization"
  [])

(define-event :destroy
  "object descruction"
  [[:cause "cause of destruction, :parent means the parent was destroyed, :direct is default"]])

(define-event :dom-init
  "called after the dom was created, use it to put it into the document"
  [[:dom "the dom that was created"]])

(define-event :dom-entered
  "should be called whenever a dom node is added to the document, since that
   is not in control of this library its up to you to call this
   use (so/notify-tree! your-obj :dom-entered) to notify the node and every child you created"
  [])

(define-event :updated
  "called after so/update! completed"
  [])

(define-event :bind-children-update
  "need to rethink this"
  [])

(defn behavior* [& args]
  (warn "defbehavior is gone" (first args)))

(defprotocol IObject
  (-id [this])
  (-type [this])
  (-data [this])
  (-update [this update-fn] "update and notify watches")
  (-update-direct [this update-fn] "update but dont notify watches"))

(defn get-type-attr
  ([oref key] (get-in @object-defs [(-type oref) key]))
  ([oref key default] (get-in @object-defs [(-type oref) key] default)))

(defn ^:export get-dom [oref]
  (::dom oref))

(defn ^:export get-by-id [id]
  (get @instances id))

(defn ^:export get-from-dom [dom]
  (let [oid (dom/data dom :oid)]
    (when-not oid
      (throw (ex-info "get-from-dom only works on nodes created via so/create" {:dom dom})))

    (get-by-id (js/parseInt oid))
    ))

(defn ^:export get-parent [oref]
  (when-let [parent-id (get @instance-parent (-id oref))]
    (get @instances parent-id)
    ))

(defn ^:export get-parent-of-type [oref parent-type]
  (loop [parent (:parent oref)]
    (when parent
      (if (= (-type parent) parent-type)
        parent
        (recur (:parent parent))))))

(defn ^:export get-children [parent]
  (let [parent-id (-id parent)
        child-ids (get @instance-children parent-id [])
        instances @instances]
    (vec (map #(get instances %) child-ids))
    ))

(defn get-children-of-type [oref type]
  (let [type-kw (if (keyword? type) type (-type type))]
    (filter #(= type-kw (-type %)) (get-children oref))
    ))

(defn get-collection-item [oref]
  (let [item-key (::coll-item-key oref)]
    (get oref item-key)
    ))

(defn notify! [oref ev & args]
  (when-not (contains? @events ev)
    (debug "triggering undefined notifiction" (pr-str ev) " with " (pr-str args)))

  (let [reactions-to-trigger (get-in oref [::reactions ev] (list))]
    ;; (debug "notify!" (-id oref) (-type oref) ev reactions-to-trigger args)
    (doseq [rfn reactions-to-trigger]
      (apply rfn (cons oref args)))
    ))

(defn visit-tree [root-obj visit-fn]
  (visit-fn root-obj)
  (doseq [child (get-children root-obj)]
    (visit-tree child visit-fn)
    ))

(defn notify-tree! [oref ev & args]
  (let [notify-fn (fn [obj]
                    (apply notify! obj ev args))]
    (visit-tree oref notify-fn)))

(defn update! [oref update-fn & args]
  (when-not (fn? update-fn)
    (throw (str "update! expects a fn as second arg, not " (pr-str update-fn))))

  (let [id (-id oref)
        data (-data oref)
        work-fn (fn [data] (apply update-fn data args))]
    (-update oref work-fn)
    (notify! oref :updated)
    ))

(defn update-direct! [oref update-fn & args]
  (when-not (fn? update-fn)
    (throw (str "update-direct! expects a fn as second arg, not " (pr-str update-fn))))

  (let [id (-id oref)
        data (-data oref)
        work-fn (fn [data] (apply update-fn data args))]
    (-update-direct oref work-fn)
    (notify! oref :updated)
    ))


(defn- set-parent! [child parent]
  (let [child-id (-id child)
        parent-id (-id parent)]
    (swap! instance-parent assoc child-id parent-id)
    (swap! instance-children (fn [x]
                               (let [current (get x parent-id #{})]
                                 (assoc x parent-id (conj current child-id))
                                 )))
    true))

(defn dom-destroy [this cause]
  (if (= cause :parent)
    ;; no messin arround when parent is dead
    (dom/remove (::dom this))
    ;; custom removals are allowed when removing direct
    (if-let [custom-remove (get-type-attr this :dom-remove)]
      (custom-remove this (::dom this))
      (dom/remove (::dom this))
      )))

(defn destroy!
  ([oref]
     (destroy! oref :direct))
  ([oref cause]
     (let [obj-id (-id oref)
           parent-id (get @instance-parent obj-id)]

       ;; destroy children before destroying parent
       (doseq [child (get-children oref)]
         (destroy! child :parent))

       (dom-destroy oref cause)
       (notify! oref :destroy cause)

       (swap! instances dissoc obj-id)
       (swap! instance-parent dissoc obj-id)
       (when parent-id
         (swap! instance-children update-in [parent-id] disj obj-id)))

     ))

(defn bind-dom-events [oref dom dom-events]
  (when-not (zero? (rem (count dom-events) 2))
    (throw (ex-info "object defined invalid event" {:object-type (-type oref) :dom-events dom-events})))

  (doseq [[ev handler :as ev-def] (partition 2 dom-events)]
    (when (nil? handler)
      (throw (str "ev with nil handler " (pr-str ev))))

    (let [handler (if (keyword? handler)
                    (fn [this e]
                      (notify! this handler e))
                    handler)]
      (dom/on dom ev (fn dom-event-handler [e]
                       (dom/ev-stop e)
                       (handler oref e))))))



(defn- reaction-merge [result [event handler]]
  (let [current (get result event (list))]
    (assoc result event (conj current handler))))

(defn- merge-reactions [result behavior]
  (when-not (vector? behavior)
    (throw (ex-info "behaviors must be vectors" {:behavior behavior})))
  (when-not (even? (count behavior))
    (throw (ex-info "invalid behavior" {:behavior behavior})))

  (reduce reaction-merge result (reverse (partition 2 behavior))))

(defn define [id & args]
  (when-not (even? (count args))
    (throw (str "invalid object definition " (str id) " args: "(pr-str args))))
  (when (contains? @object-defs id)
    (throw (str "object already defined " id)))

  (let [odef (apply hash-map args)

        reactions (merge-reactions {} (:on odef []))
        reactions (reduce merge-reactions reactions (:behaviors odef []))

        odef (assoc odef
               ::id id
               ::reactions reactions
               )]

    (swap! object-defs assoc id odef)))


(defn- merge-defaults [data type]
  (let [defaults (get-in @object-defs [type :defaults])]
    (cond
     (nil? defaults) data
     (map? defaults) (merge defaults data)
     (fn? defaults) (merge (defaults) data)
     :else (throw (ex-info "invalid object defaults" {:defaults defaults :type type}))
     )))

(defn make-dom
  ([oref dom-key events-key]
     (make-dom oref dom-key events-key nil))
  ([oref dom-key events-key value]
      (let [dom-fn (if (keyword? dom-key) (get-type-attr oref dom-key) dom-key)
            events (if (keyword? events-key) (get-type-attr oref events-key []) events-key)
            dom (dom/build (dom-fn oref value))]
        (bind-dom-events oref dom events)
        dom
        )))


(defn alive? [obj]
  (contains? @instances (-id obj)))

(defrecord Watch [key handler])

(deftype ObjectRef [id type ^:mutable data ^:mutable watches]
  IObject
  (-id [this] id)
  (-type [this] type)
  (-data [this] data)
  (-update [this update-fn]
    (let [old data
          new (update-fn data)]
      (set! data new)
      (doseq [{:keys [key handler] :as watch} watches]
        ;; watches may destroy the current object
        ;; if that happens we should not continue
        ;; FIXME: this seems really dirty, there must be a cleaner way?
        ;; maybe queue all other modifications until this is finished?
        (when (alive? this)
          (handler key this old new)))))

  (-update-direct [this update-fn]
    (set! data (update-fn data)))

  IEquiv
  (-equiv [this other]
    (and (instance? ObjectRef other)
         (= (-id this) (-id other))))

  IDeref
  (-deref [this] data)

  IPrintWithWriter
  (-pr-writer [this writer opts]
    (-write writer (str "#<ObjectRef {:id " id ", :type " type "}>")))

  IWatchable
  (-notify-watches [this oldval newval]
    (throw (js/Error. "who be calling?")))
  (-add-watch [this key f]
    (set! watches (conj watches (Watch. key f))))
  (-remove-watch [this key]
    (set! watches (remove #(= key (:key %)) watches)))

  ILookup
  (-lookup [this k]
    (if (= :parent k)
      (get-parent this)
      (get data k)))
  (-lookup [this k d]
    (if (= :parent k)
      (or (get-parent this) k)
      (get data k d)))

  Object
  (toString [this]
    (pr-str this))

  dom/IElement
  (-to-dom [this] (::dom data))
  )

(defn add-reaction!
  ([oref ev handler-fn]
     (add-reaction! oref [ev handler-fn]))
  ([oref list]
     (update! oref update-in [::reactions] merge-reactions list)
     ))

(defn bind-change
  ([oref attr callback]
     (bind-change oref attr callback (gensym "bind-change")))
  ([oref attr callback watch-key]
     (when-not (satisfies? IObject oref)
       (throw (ex-info "binding currently only supports shadow objects, other atoms might leak, may add later" {:oref oref :attr attr})))

     (let [attr (if (vector? attr) attr [attr])]
       (add-watch oref watch-key
                  (fn bind-change-watch [_ _ old new]
                    (let [ov (get-in old attr)
                          nv (get-in new attr)]
                      (when-not (= ov nv)
                        (callback ov nv)))))) 
     ))

(defn dom-enter [parent child]
  (dom/append parent child)
  (notify-tree! child :dom-entered))

(defn create [type args]
  (when-not (contains? @object-defs type)
    (throw (ex-info (str "cannot create unknown child type: " type) {:type type :args args})))
  (when-not (map? args)
    (throw (ex-info "so/create second arg must be a map" {:args args})))

  (let [oid (next-id)
        parent (:parent args)

        odef (get @object-defs type)

        obj (-> args
                (assoc ::object-id oid
                       ::reactions (get odef ::reactions {}))
                (merge-defaults type)
                (dissoc :parent :dom))

        oref (ObjectRef. oid type obj [])]

    ;; dont use oref before this
    (swap! instances assoc oid oref)

    (when parent
      (set-parent! oref parent))

    (notify! oref :init)

    (let [dom-events (:dom-events odef [])]
      (if-let [dom (:dom args)]
        ;; attach+events
        (do
          (dom/set-data dom :oid oid)
          (bind-dom-events oref dom dom-events)
          (update! oref assoc ::dom dom)
          (notify! oref :dom-init dom))
        ;; create+events
        (when-let [dom-fn (:dom odef)]
          (let [dom (dom/build (dom-fn oref))]

            (dom/set-data dom :oid oid)

            (update! oref assoc ::dom dom)
            (bind-dom-events oref dom dom-events)
            (notify! oref :dom-init dom)
            ))))

    (when-let [watches (:watch odef)]
      (doseq [[attr handler] (partition 2 watches)]
        (bind-change oref attr (fn [old new]
                                 (handler oref old new)))))

    oref))

(defn bind-simple
  "[oref attr node-gen] produces a node via (node-gen new-value)
   watches obj for changes and replaces the generated node on change (node-gen defaults to str)

  only use if the node has no attached behavior like clicks, use bind with an extra object for those"
  ([oref attr] (bind-simple oref attr str))
  ([oref attr node-gen]
     (let [attr (if (vector? attr) attr [attr])
           node-get #(dom/build (node-gen %))
           node (atom (node-get (get-in oref attr)))
           bind-key (gensym "bind")]

       (bind-change oref attr
                    (fn [old new]
                      (let [new-node (node-get new)
                            current-node @node]
                        (dom/replace-node current-node new-node)
                        (reset! node new-node))))

       @node)
     ))

(defn bind
  "bind the given attribute a child item
  the item will be recreated whenever the value changes (old one will be destroyed)"
  ([oref attr item-type item-key item-attrs]
     (let [attr (if (vector? attr) attr [attr])
           curval (get-in oref attr)

           make-child-fn (fn [value]
                           (create item-type (merge
                                              item-attrs
                                              {:parent oref
                                               item-key value})))

           child (atom (make-child-fn curval))]

       (bind-change oref attr
                    (fn [old new]
                      (let [new-child (make-child-fn new)
                            current-node @child]

                        (dom/replace-node current-node new-child)
                        (destroy! @child)
                        (reset! child new-child))))

       @child)
     ))

(defn coll-destroy-children [children c diff]
  ;; whats more efficient in the DOM, drop head or tail?
  ;; diff is neg!
  (doseq [obj (subvec children (+ c diff) c)]
    (let [obj (get-from-dom obj)]
      (destroy! obj)))

  (subvec children 0 (+ c diff)))

(defn bind-children
  ([node parent attr item-type item-key]
     (bind-children node parent attr item-type item-key #(map-indexed vector %)))
  ([node parent attr item-type item-key coll-transform]
     (let [attr (if (vector? attr) attr [attr])

           coll-dom (dom/build node)

           make-item-fn (fn [[key val]]
                          (let [obj (create item-type {:parent parent
                                                       ::coll-path attr
                                                       ::coll-key key
                                                       ::coll-item-key item-key
                                                       item-key val})]

                            (bind-change obj item-key
                                         (fn [old new]
                                           ;; FIXME: this wont work for sets, only vec and map

                                           ;; this is also called when the parent coll is updated
                                           ;; kinda doing double work
                                           (let [parent-key (conj attr (::coll-key obj))
                                                 coll-val (get-in parent parent-key)]
                                             ;; only update when its not current, aka child updated itself
                                             (when-not (= coll-val new)
                                               (log "direct child update" parent obj key parent-key new coll-val)
                                               (update! parent assoc-in parent-key new)
                                               ))))
                            obj
                            ))

           ]

       (doseq [item (coll-transform (get-in parent attr))]
         (dom-enter coll-dom (make-item-fn item)))

       (bind-change parent attr
                    (fn bind-children-watch [old new]
                      (let [children (into [] (dom/children coll-dom))
                            new-coll (vec (coll-transform new))
                            count-children (count children)
                            count-new (count new)
                            diff (- count-new count-children)

                            ;; exit lost children
                            children (if (neg? diff)
                                       (coll-destroy-children children count-children diff)
                                       children)
                            count-children (min count-new count-children)]

                        ;; update current
                        (dotimes [idx count-children]
                          (let [cn (nth children idx)
                                cc (get-from-dom cn)
                                ckey (::coll-key cc)
                                cval (get cc item-key)
                                [nkey nval] (nth new-coll idx)]

                            ;; only update when something changes
                            (when-not (and (= ckey nkey) (= cval nval))
                              (update! cc assoc item-key nval ::coll-key nkey)
                              (notify! cc :bind-child-update ckey nkey cval nval)
                              )))

                        ;; enter new
                        (when (pos? diff)
                          (doseq [item (subvec new-coll count-children count-new)]
                            (dom-enter coll-dom (make-item-fn item))))

                        (notify! parent :bind-children-update)
                        )))

       coll-dom)))

(defn remove-from-vector [coll key]
  (log "remove-from-vector" key coll)
  (let [c (count coll)]
    (cond
     (= 0 key) (vec (rest coll))
     (= (dec c) key) (vec (butlast coll)) 
     :else ;; item in teh middle
     (vec (concat (subvec coll 0 key)
                  (subvec coll (inc key) c))))
    ))

(defn remove-item-from-coll [coll key]
  (cond
   (satisfies? IVector coll)
   (remove-from-vector coll key)
   (satisfies? IMap coll)
   (dissoc coll key)
   (satisfies? ISet coll)
   (disj coll key)
   :else (throw "unknown coll type")
   ))

(defn remove-in-parent! [oref]
  (let [parent (get-parent oref)
        key (::coll-key oref)
        path (::coll-path oref)]

    (when-not (and key path)
      (throw (ex-info "remove-in-parent! should only be called from items created via so/bind-children" {:oref oref})))

    (log "remove in parent" key path parent (get-in parent path))
    (update! parent update-in path remove-item-from-coll key)
    (log "after remove in parent" key path (get-in parent path))
    ))

(defn inspect! [oref]
  (info "inspect!" (-id oref) (str (-type oref)) (clj->js @(-data oref))))

(defn ^:export dump! []
  (info "--------------- LIVE OBJECTS ------------")
  (doseq [[id oref] (seq @instances)]
    (info "dump" id (pr-str (-type oref)) @(-data oref)))
  (info "--------------- //LIVE OBJECTS ------------")
  )
