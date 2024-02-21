(ns malli.swagger
  (:require [clojure.set :as set]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.json-schema :as json-schema]))

(defprotocol SwaggerSchema
  (-accept [this children options] "transforms schema to Swagger Schema"))

(defmulti accept (fn [name _schema _children _options] name) :default ::default)

(defmethod accept ::default [name schema children options] (json-schema/accept name schema children options))
(defmethod accept 'nil? [_ _ _ _] {})

(defmethod accept :not [_ _ children _] {:x-not (first children)})
(defmethod accept :and [_ _ children _] (assoc (first children) :x-allOf children))
(defmethod accept :or [_ _ children _] (assoc (first children) :x-anyOf children))
(defmethod accept :multi [_ _ children _] (let [cs (mapv last children)] (assoc (first cs) :x-anyOf cs)))

(defmethod accept :maybe [_ _ children {:keys [type in]}]
  (let [k (if (and (= type :parameter) (not= in :body)) :allowEmptyValue :x-nullable)]
    (assoc (first children) k true)))

(defmethod accept :tuple [_ _ children _] {:type "array" :items {} :x-items children})

;; Number formats are only defined in Swagger/OpenAPI spec.

(defmethod accept 'number? [_ _ _ _] {:type "number" :format "double"})
(defmethod accept 'integer? [_ _ _ _] {:type "integer" :format "int32"})
(defmethod accept 'int? [_ _ _ _] {:type "integer" :format "int64"})
(defmethod accept 'pos-int? [_ _ _ _] {:type "integer", :format "int64", :minimum 1})
(defmethod accept 'neg-int? [_ _ _ _] {:type "integer", :format "int64", :maximum -1})
(defmethod accept 'nat-int? [_ _ _ _] {:type "integer", :format "int64" :minimum 0})
(defmethod accept 'float? [_ _ _ _] {:type "number" :format "float"})
(defmethod accept 'double? [_ _ _ _] {:type "number" :format "double"})

(defmethod accept :int [_ schema _ _]
  (merge {:type "integer" :format "int64"}
         (-> schema m/properties (select-keys [:min :max]) (set/rename-keys {:min :minimum, :max :maximum}))))

(defmethod accept :double [_ schema _ _]
  (merge {:type "number" :format "double"}
         (-> schema m/properties (select-keys [:min :max]) (set/rename-keys {:min :minimum, :max :maximum}))))

(defn- -swagger-walker [schema _ children options]
  (let [p (merge (m/type-properties schema) (m/properties schema))]
    (or (get p :swagger)
        (get p :json-schema)
        (merge (json-schema/select p)
               (if (satisfies? SwaggerSchema schema)
                 (-accept schema children options)
                 (accept (m/type schema) schema children options))
               (m/-unlift-keys p :json-schema)
               (m/-unlift-keys p :swagger)))))

(defn -transform [?schema options] (m/walk ?schema -swagger-walker options))

(defn -remove-empty-keys
  [m]
  (into (empty m) (filter (comp not nil? val) m)))

;;
;; public api
;;

(defn transform
  ([?schema]
   (transform ?schema nil))
  ([?schema options]
   (let [definitions (atom {})
         options (merge options {::m/walk-entry-vals true
                                 ::json-schema/definitions definitions
                                 ::json-schema/transform -transform})]
     (cond-> (-transform ?schema options) (seq @definitions) (assoc :definitions @definitions)))))

(defmulti extract-parameter (fn [in _] in))

(defmethod extract-parameter :body [_ schema]
  (let [swagger-schema (transform schema {:in :body, :type :parameter})]
    [{:in "body"
      :name (:title swagger-schema "body")
      :description (:description swagger-schema "")
      :required (not= :maybe (m/type schema))
      :schema swagger-schema}]))

(defmethod extract-parameter :default [in schema]
  (let [{:keys [properties required]} (transform schema {:in in, :type :parameter})]
    (mapv
     (fn [[k {:keys [type] :as schema}]]
       (merge
        {:in (name in)
         :name k
         :description (:description schema "")
         :type type
         :required (contains? (set required) k)}
        schema))
     properties)))

(defmulti expand (fn [k _ _ _] k))

(defmethod expand ::responses [_ v acc _]
  {:responses
   (into
    (or (:responses acc) {})
    (for [[status response] v]
      [status (cond-> response
                (:schema response) (update :schema transform {:type :schema})
                true (update :description (fnil identity ""))
                true -remove-empty-keys)]))})

(defmethod expand ::parameters [_ v acc _]
  (let [old (or (:parameters acc) [])
        new (mapcat (fn [[in spec]] (extract-parameter in spec)) v)
        merged (->> (into old new)
                    reverse
                    (reduce
                     (fn [[ps cache :as acc] p]
                       (let [c (select-keys p [:in :name])]
                         (if (cache c)
                           acc
                           [(conj ps p) (conj cache c)])))
                     [[] #{}])
                    first
                    reverse
                    vec)]
    {:parameters merged}))

(defn dissoc-non-root-definitions
  [{:keys [parameters responses] :as x}]
  (cond-> x
    parameters (update :parameters
                       #(mapv (fn [p]
                                (if (contains? p :schema)
                                  (update p :schema dissoc :definitions)
                                  p))
                              %))
    responses (update :responses
                      #(reduce-kv (fn [rs k v]
                                    (assoc rs k
                                           (if (contains? v :schema)
                                             (update v :schema
                                                     dissoc :definitions)
                                             v)))
                                  {} %))))

(defn expand-qualified-keywords
  [x options]
  (let [accept? (-> expand methods keys set)]
    (walk/postwalk
     (fn [x]
       (if (map? x)
         (reduce-kv
          (fn [acc k v]
            (if (accept? k)
              (let [expanded (expand k v acc options)
                    parameters (:parameters expanded)
                    responses (:responses expanded)
                    definitions (if parameters
                                  (-> parameters first :schema :definitions)
                                  (->> responses vals (map :schema)
                                       (map :definitions) (apply merge)))]
                (-> acc (dissoc k) (merge expanded)
                    (update :definitions merge definitions)
                    dissoc-non-root-definitions))
              acc))
          x x)
         x))
     x)))

(defn swagger-spec
  ([x]
   (swagger-spec x nil))
  ([x options]
   (expand-qualified-keywords x options)))
