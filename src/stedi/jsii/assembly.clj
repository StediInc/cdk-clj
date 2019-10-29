(ns stedi.jsii.assembly
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.util.zip GZIPInputStream)
           (org.apache.commons.compress.archivers.tar TarArchiveInputStream)))

(defn- classpath-jsii-archives
  []
  (->> (-> (System/getProperty "java.class.path")
           (string/split #":"))
       (filter #(string/ends-with? % ".jar"))
       (map io/file)
       (map #(java.util.jar.JarFile. %))
       (mapcat (comp enumeration-seq #(.entries %)))
       (filter (comp #(.endsWith % "jsii.tgz") str))
       (map str)))

(defn- keywordize-alpha
  [x]
  (if (and (string? x)
           (re-find #"^[A-Za-z]" x))
    (keyword x)
    x))

(defn- read-spec
  [input-stream]
  (-> input-stream
      (io/reader)
      (json/read :key-fn keywordize-alpha)))

(defn- load-assembly-resource [resource]
  (with-open [is (-> resource
                     (io/resource)
                     (io/input-stream)
                     (GZIPInputStream.)
                     (TarArchiveInputStream.))]
    (loop []
      (when-let [entry (.getNextTarEntry is)]
        (if (= (.getName entry) "package/.jsii")
          (read-spec is)
          (recur))))))

(defn- load-all-assemblies
  []
  (into []
        (map load-assembly-resource)
        (classpath-jsii-archives)))

(defn- merge-named-colls
  [coll1 coll2]
  (mapv second
        (merge (into {} (map (juxt :name identity)) coll1)
               (into {} (map (juxt :name identity)) coll2))))

(defn- merge-types
  [t1 t2]
  (let [props      (merge-named-colls (:properties t1)
                                      (:properties t2))
        interfaces (-> (concat (:interfaces t1)
                               (:interfaces t2))
                       (distinct)
                       (vec))
        methods    (merge-named-colls (:methods t1)
                                      (:methods t2))]
    (-> (merge t1 t2)
        (assoc :methods methods)
        (assoc :properties props)
        (assoc :interfaces interfaces))))

(defn- expand-type
  [types t]
  (let [merged (if-let [base (some->> t (:base) (get types))]
                 (merge-types (expand-type types base) t)
                 t)

        interfaces (map #(get types %) (:interfaces t))]
    (reduce #(merge-types %2 %1) merged interfaces)))

(defn- indexed-types
  []
  (->> (load-all-assemblies)
       (map :types)
       (apply merge)))

(defonce all-types*
  (let [types (indexed-types)]
    (sequence (comp (map second)
                    (map (partial expand-type types)))
              types)))

(defn all-types [] all-types*)

(def get-type
  (memoize
    (fn [fqn]
      (->> (all-types)
           (filter (comp #{fqn} :fqn))
           (first)))))