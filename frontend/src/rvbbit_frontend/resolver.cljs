(ns rvbbit-frontend.resolver
  (:require
    [clojure.edn             :as edn]
    [clojure.string          :as cstr]
    [clojure.walk            :as walk]
    [day8.re-frame.undo      :as    undo
                             :refer [undoable]]
    [goog.i18n.NumberFormat.Format]
    [re-frame.alpha          :as rfa]
    [re-frame.core           :as re-frame]
    [reagent.core            :as reagent]
    [rvbbit-frontend.connections :as conn]
    [rvbbit-frontend.db      :as db]
    [rvbbit-frontend.http    :as http]
    [rvbbit-frontend.utility :as ut]
    [websocket-fx.core       :as wfx]))

(declare logic-and-params)



(re-frame/reg-sub ::client-name (fn [db] (get db :client-name)))

(defn logic-and-params-fn
  [block-map panel-key]
  (if (try (and (ut/ne? block-map) (or (map? block-map) (vector? block-map) (keyword? block-map))) (catch :default _ false))
    (let [;;;valid-body-params      (vec (filter #(and (keyword? %) (cstr/includes? (str %)
          valid-body-params (vec (ut/get-compound-keys block-map))
          workspace-params (into {}
                                 (for [k valid-body-params] ;; deref here?
                                   {k @(rfa/sub ::conn/clicked-parameter-key-alpha {:keypath [k]})}))
          value-walks-targets (filter #(and (cstr/includes? (str %) ".") (not (cstr/includes? (str %) ".*"))) valid-body-params)
          value-walks (into {}
                            (for [k value-walks-targets] ;all-sql-call-keys]
                              (let [fs    (ut/splitter (ut/safe-name k) "/")
                                    gs    (ut/splitter (last fs) ".")
                                    ds    (keyword (first fs))
                                    row   (try (int (last gs)) (catch :default _ "label"))
                                    field (keyword (first gs))]
                                {k (if (not (integer? row))
                                     (str field)
                                     (get-in @(rfa/sub ::conn/sql-data-alpha {:keypath [ds]}) [row field]))})))
          condi-walks-targets (distinct (filter #(cstr/includes? (str %) "condi/") valid-body-params))
          condi-walks (into {}
                            (for [k condi-walks-targets]
                              {k @(ut/tracked-sub ::conn/condi-value
                                                  {:condi-key (keyword (last (ut/splitter (ut/safe-name k) "/")))})}))
          into-walk-map2 (fn [obody]
                           (let [;obody (ut/postwalk-replacer condi-walks orig-body)
                                 kps       (ut/extract-patterns obody :into 3) ;(kv-map-fn
                                                                               ;obody)
                                 logic-kps (into {} (for [v kps] (let [[_ this that] v] {v (into this that)})))]
                             (ut/postwalk-replacer logic-kps obody)))
          if-walk-map2 (fn [obody]
                         (let [;obody (ut/postwalk-replacer condi-walks orig-body)
                               kps       (ut/extract-patterns obody :if 4) ;(kv-map-fn
                                                                           ;obody)
                               logic-kps (into {}
                                               (for [v kps]
                                                 (let [[_ l this that] v]
                                                   {v (if (logic-and-params-fn l nil) ;; l
                                                        this
                                                        that)})))]
                           (ut/postwalk-replacer logic-kps obody)))
          when-walk-map2 (fn [obody]
                           (let [kps       (ut/extract-patterns obody :when 3)
                                 logic-kps (into {} (for [v kps] (let [[_ l this] v] {v (when l this)})))]
                             (ut/postwalk-replacer logic-kps obody)))
          =-walk-map2 (fn [obody]
                        (let [kps       (ut/extract-patterns obody := 3)
                              logic-kps (into {} (for [v kps] (let [[_ that this] v] {v (= (str that) (str this))})))]
                          (ut/postwalk-replacer logic-kps obody)))
          string-walk (fn [num obody]
                        (let [kps       (ut/extract-patterns obody :string3 num)
                              logic-kps (into {} (for [v kps] (let [[_ & this] v] {v (apply str this)})))]
                          (ut/postwalk-replacer logic-kps obody)))
          case-walk (fn [obody]
                      (let [kps       (ut/extract-patterns obody :case 2)
                            logic-kps (into {} (for [v kps] (let [[_ l] v] {v (ut/vectorized-case l)})))]
                        (ut/postwalk-replacer logic-kps obody)))
          singles {:text   str
                   :>>     (fn [[x y]] (true? (> x y)))
                   :<<     (fn [[x y]] (true? (< x y)))
                   :str    (fn [args] (if (vector? args) (cstr/join "" (apply str args)) (str args)))
                   :string (fn [args] (if (vector? args) (cstr/join "" (apply str args)) (str args)))}
          obody-key-set (ut/body-set block-map)
          client-name @(ut/tracked-sub ::client-name {})
          solver-clover-walk
            (fn [obody]
              (let [kps       (ut/extract-patterns obody :run-solver 2)
                    logic-kps (into
                                {}
                                (for [v kps]
                                  (let [[_ & this]                v
                                        [[solver-name input-map]] this
                                        panel-key                 :resolver
                                        unresolved-req-hash       (hash [solver-name input-map client-name])
                                        resolved-input-map        (logic-and-params-fn input-map panel-key) ;; and we need
                                                                                                            ;; to
                                                                                                            ;; 'pre-resolve'
                                                                                                            ;; it's inputs
                                                                                                            ;; i n case
                                                                                                            ;; they are
                                                                                                            ;; client
                                                                                                            ;; local
                                        new-solver-name           (str (ut/replacer (str solver-name) ":" "") unresolved-req-hash)
                                        sub-param                 (keyword (str "solver/" new-solver-name))
                                        req-map                   {:kind             :run-solver-custom ;; solver-name
                                                                                                        ;; temp-solver-name
                                                                                                        ;; client-name
                                                                                                        ;; input-map
                                                                   :solver-name      solver-name
                                                                   :temp-solver-name (keyword new-solver-name)
                                                                   :input-map        resolved-input-map
                                                                   :client-name      client-name}
                                        websocket-status          (get @(ut/tracked-sub ::http/websocket-status {}) :status)
                                        online?                   (true? (= websocket-status :connected))
                                        run?                      (= (get-in @db/solver-fn-runs [panel-key sub-param])
                                                                     resolved-input-map)
                                        lets-go?                  (and online? (not run?))
                                        ;; _ (when lets-go?
                                        ;;     (ut/tapp>> [:run-solver-req-map-resolver! (str (first this)) lets-go? (not run?)
                                        ;;                 req-map @db/solver-fn-runs]))
                                        _ (when lets-go? (ut/tracked-dispatch [::wfx/push :default req-map]))
                                        _ (when lets-go?
                                            ;(swap! db/solver-fn-runs ut/dissoc-in [panel-key])
                                            ;(swap! db/solver-fn-runs assoc-in [panel-key (hash resolved-input-map)]
                                            ;sub-param)
                                            (swap! db/solver-fn-lookup assoc (str (first kps)) sub-param)
                                            (swap! db/solver-fn-runs assoc-in [panel-key sub-param] resolved-input-map))]
                                    {v sub-param})))]
                (walk/postwalk-replace logic-kps obody)))
          has-fn? (fn [k] (some #(= % k) obody-key-set))
          out-block-map (cond->> block-map
                          true                      (ut/namespaced-swapper "this-block" (ut/replacer (str panel-key) #":" ""))
                          true                      (ut/postwalk-replacer {:*this-block* panel-key})
                          (has-fn? :run-solver)     solver-clover-walk ;;(conn/solver-clover-walk client-name
                                                                       ;;panel-key)
                          (ut/ne? value-walks)      (ut/postwalk-replacer value-walks)
                          (ut/ne? condi-walks)      (ut/postwalk-replacer condi-walks)
                          (ut/ne? workspace-params) (ut/postwalk-replacer workspace-params)
                          (has-fn? :string3)        (string-walk 2) ;; TODO, remove all these
                          (has-fn? :string3)        (string-walk 3)
                          (has-fn? :string3)        (string-walk 4)
                          (has-fn? :string3)        (string-walk 5)
                          (has-fn? :string3)        (string-walk 6) ;; TODO REMOVE ALL THIS
                          (has-fn? :=)              =-walk-map2
                          (has-fn? :if)             if-walk-map2
                          (has-fn? :when)           when-walk-map2
                          (has-fn? :into)           into-walk-map2
                          (ut/ne? singles)          (ut/postwalk-replacer singles)
                          (has-fn? :case)           case-walk)
          templated-strings-vals (vec (filter #(cstr/includes? (str %) "/") (ut/deep-template-find out-block-map))) ;; ignore
                                                                                                                    ;; non
          templates? (ut/ne? templated-strings-vals)
          _ (when templates? (ut/tapp>> [:replacing-string-templates... templated-strings-vals out-block-map]))
          templated-strings-walk (if templates?
                                   (ut/postwalk-replacer {nil ""}
                                                         (into {}
                                                               (for [k templated-strings-vals]
                                                                 {k @(rfa/sub ::conn/clicked-parameter-key-alpha
                                                                              {:keypath [k]})})))
                                   {})
          out-block-map (if templates? (ut/deep-template-replace templated-strings-walk out-block-map) out-block-map)]
      (if (ut/ne? (vec (ut/get-compound-keys out-block-map))) (logic-and-params-fn out-block-map panel-key) out-block-map))
    block-map))


(re-frame/reg-sub ::logic-and-params (fn [_ {:keys [m p]}] (logic-and-params-fn m p)))

(defn logic-and-params [m p] @(rfa/sub ::logic-and-params {:m m :p p}))