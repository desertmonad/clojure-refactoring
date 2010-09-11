(ns clojure-refactoring.support.parsley
  (:require [net.cgrand.parsley.glr :as glr])
  (:use clojure.walk
        clojure-refactoring.support.core
        net.cgrand.parsley
        [clojure.contrib.def :only [defonce-]]
        [clojure.contrib.seq-utils :only [find-first]]
        [clojure.contrib.str-utils :only [str-join]])
  (:refer-clojure :exclude [symbol])
  (:require [clojure.core :as core])
  (:require [clojure-refactoring.support.parser :as parser]))

(defn make-node [tag content]
  {:tag tag :content content})

(defn symbol [sym]
  (make-node :atom (list (name sym))))

(def parsley-empty-map (make-node :map (list "{" "}")))

(def parsley-whitespace (make-node :whitespace '(" ")))

(def composite-tag? (complement
                     #{:atom :regex :space :var :char :string}))

(defn replace-content [ast new-content]
  (assoc ast
    :content
    new-content))

(declare parsley-walk)

(defn- replacement-for-composite [tag f]
  (if (composite-tag? tag)
    #(parsley-walk f %)
    f))

(defn- replacement-for-content [tag f content]
  (replace-when
   (complement string?)
   (replacement-for-composite tag f)
   content))

(defn- walk-replace-content [f ast]
  (let [{tag :tag content :content} ast]
    (replace-content
     ast
     (replacement-for-content tag f content))))

(defn parsley-walk [f ast]
  (if (map? ast)
    (f
     (walk-replace-content f ast))
    (vec (map #(parsley-walk f %) ast))))

(defn- expand-ast-nodes [ast]
  (if (sequential? ast)
    (seq ast)
    (:content ast)))

(defn parsley-sub-nodes [ast]
  (tree-seq (any-of? sequential? composite-tag?)
            expand-ast-nodes
            ast))

(defn parsley-tree-contains [ast obj]
  (some #{obj} (parsley-sub-nodes ast)))

(defn parsley-to-string [ast]
  (str-join "" (filter string? (parsley-sub-nodes ast))))

(def sexp->parsley (comp parser/parse1 format-code))

(defn parsley-tree-replace [old new ast]
  (parsley-walk
   (fn [node] (if (= node old) new node))
   ast))

(defn replace-symbol-in-ast-node [old new ast]
  (parsley-tree-replace (symbol old) (symbol new) ast))

(defn- parsley-get-first-node [ast]
  (if (map? ast) ast (first ast)))

(defn tag=
  ([x] #(tag= x %)) ;;Curried
  ([x ast]
     (= (:tag ast) x)))

;; atom?
(defn- parsley-atom? [ast]
  (tag= :atom ast))

;;content->str
(defn- ast-content [ast]
  (str-join "" (:content ast)))

;;symbol?
(def parsley-symbol?
     (all-of? map? parsley-atom?
              (comp symbol? read-string ast-content)))

;;keyword?
(def parsley-keyword?
     (all-of? parsley-atom?
              #(first= (ast-content %) \:)))

(def ignored-node?
     (any-of? string? (tag= :whitespace) (tag= :comment)))

;;TODO: needs a better name
(defn relevant-content [ast]
  (remove ignored-node? (:content ast)))

(defn intersperse [coll item]
  "After every element in coll, add item."
  (interleave coll (repeat item)))

(defn add-whitespace [coll]
  (butlast (intersperse coll parsley-whitespace)))

(defn- coll-fn [tag start end elems]
  (make-node tag `(~start ~@elems ~end)))

;; list
(defn parsley-list [coll]
  (coll-fn :list "(" ")" (add-whitespace coll)))

;;vector
(defn parsley-vector [coll]
  (coll-fn :vector "[" "]" (add-whitespace coll)))

(defn list-without-whitespace [& elems]
  (coll-fn :list "(" ")" elems))

(defn vector-without-whitespace [& elems]
  (coll-fn :vector "[" "]" elems))

;;newline
(def parsley-newline (make-node :whitespace '("\n")))

(defn first-vector [ast]
  (find-first (tag= :vector) (:content ast)))

(defn parsley-fn-args [ast]
  (first-vector (parsley-get-first-node ast)))

(def parsley-bindings
     (comp relevant-content parsley-fn-args))

;; conj
(defn content-conj [{content :content :as ast} & xs]
  (replace-content ast
                   `(~(first content)
                     ~@xs ~@(butlast (drop 1 content))
                     ~(last content))))

(defn strip-whitespace [ast]
  (parsley-walk
   (fn [node]
     (if (composite-tag? (:tag node))
       (replace-content node
                        (remove (tag= :whitespace) (:content node)))
       node))
   ast))

(def empty-parsley-list (parsley-list nil))

(def drop-first-and-last (comp rest butlast))

(def first-content (comp first :content))

(defn first-symbol [ast]
  (first-content (first (relevant-content  ast))))

(def parsley-binding-node?
     (all-of? map?
              (tag= :list)
              (comp binding-forms core/symbol
                    #(apply str %) :content second :content)))

(defn- expand-args-with-parse1 [args]
  "Takes arguments from a function and returns a vector that
  (in a let form) rebinds them by parsing them."
  (->> (mapcat #(list % (list 'clojure-refactoring.support.parser/parse1 %)) args) vec))

(defmacro defparsed-fn [name args docstring & body]
  "Defines a function in which all of the args are rebound by parsing them using parse1."
  `(defn ~name ~args ~docstring
     (let ~(expand-args-with-parse1 args)
       ~@body)))
