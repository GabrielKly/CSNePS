(ns csneps.util
  (:use [clojure.string :only (join)]
        [clojure.pprint])
  (:require [clojure.set :as set])
  (:import (java.util.regex Pattern)))

(defmacro typecase [e & clauses]
  `(condp = (type ~e)
      ~@clauses))

;;; Enhanced records originally by David McNeil, enhanced further by Dan Schlegel
;;; to support default values. Original is here:
;;; http://cemerick.com/2010/08/02/defrecord-slot-defaults/

;;;; enhanced records

;; internal helper for maps

(defn remove-values-from-map [value-predicate m]
  (select-keys m (filter #(not (value-predicate (get m %))) (keys m))))

; internal helpers for name conversion

(defn take-even [x]
  (take-nth 2 x))

(defn take-odd [x]
  (take-nth 2 (drop 1 x)))

(defn re-partition
  "FROM THE OLD CONTRIB
  Splits the string into a lazy sequence of substrings, alternating
  between substrings that match the patthern and the substrings
  between the matches.  The sequence always starts with the substring
  before the first match, or an empty string if the beginning of the
  string matches.

  For example: (partition \"abc123def\" #\"[a-z]+\")
  returns: (\"\" \"abc\" \"123\" \"def\")"
  [#^String s #^Pattern re]
  (let [m (re-matcher re s)]
    ((fn step [prevend]
       (lazy-seq
        (if (.find m)
          (cons (.subSequence s prevend (.start m))
                (cons (re-groups m)
                      (step (+ (.start m) (count (.group m))))))
          (when (< prevend (.length s))
            (list (.subSequence s prevend (.length s)))))))
     0)))

(defn camel-to-dashed
  "Convert a name like 'BigBlueCar' to 'big-blue-car'."
  [s]
  (let [parts (drop 1 (re-partition s #"[A-Z]"))]
    (join "-" (map #(str %1 %2)
                   (map #(.toLowerCase ^String %) (take-even parts))
                   (take-odd parts)))))

;; internal helpers for changing records via maps

(defn set-record-field
  "Set a single field on a record."
  [source [key value]]
  (assoc source key value))

(defn set-record-fields
  "Set many fields on a record, from a map."
  [initial value-map]
  (reduce set-record-field initial value-map))

;; NOTE: The way that fields are set in this approach is inefficient and garbage
;; objects are thrown off when setting multiple fields, but it does present the
;; abstraction we want of setting fields via maps.

;; internal helper for generating constructor function

(defmacro make-record-constructor
  "Define the constructor functions used to instantiate a record."
  [ctor-name default-record]
  `(defn ~ctor-name
     ([value-map#]
        (~ctor-name ~default-record value-map#))
     ([initial# value-map#]
        (set-record-fields initial# value-map#))))

;; internal helpers for printing

(defmacro print-record
  "Low-level function to print a record to a stream using the specified constructor
   name in the print output and using the provided write-contents function to write
   out the contents of the record (represented as a map)."
  [ctor-name record stream write-contents]
  `(do
     (.write ^java.io.OutputStream ~stream (str "(" ~ctor-name " "))
     (~write-contents (remove-values-from-map nil? ~record))
     (.write ^java.io.OutputStream ~stream  ")")))

(defn print-record-contents
  "Simply write the contents of a record to a stream as a string. Used for basic
   printing."
  [stream contents]
  (.write ^java.io.OutputStream stream (str contents)))

(defmacro setup-print-record-method [ctor-name type-name method-name]
  `(defmethod ~method-name ~type-name [record# writer#]
    (print-record ~ctor-name record# writer# (partial print-record-contents writer#))))

(defmacro setup-print-record
  "Define the print methods to print a record nicely (so that records will print in
   a form that can be evaluated as itself)."
  [ctor-name type-name]
  `(do
     (setup-print-record-method ~ctor-name ~type-name print-method)
     (setup-print-record-method ~ctor-name ~type-name print-dup)))

(defn pprint-map [amap]
  (pprint-logical-block :prefix "{" :suffix "}"
    (loop [aseq (seq amap)]
      (when aseq
	(pprint-logical-block 
          (write-out (ffirst aseq))
          (.write #^java.io.Writer *out* " ")
          (pprint-newline :linear)
          (write-out (fnext (first aseq))))
        (when (next aseq)
          (.write #^java.io.Writer *out* ", ")
          (pprint-newline :linear)
          (recur (next aseq)))))))

(defn generate-record-pprint
  "Return a function that can be used in the pprint dispatch mechanism to handle a
   specific constructor name."
  [ctor-name]
  (fn [record]
    (print-record ctor-name record *out* pprint-map)))

;; public entry point

(defmacro defrecord2
  "Defines a record and sets up constructor functions, printing, and pprinting for
   the new record type."
  [type-name field-list & etc]
     `(defrecord2-builder ~type-name ~field-list
        ;; invoke defrecord2 with default constructor function name
        ~(symbol (str "new-" (camel-to-dashed (str type-name)))) ~@etc))

(defn use-method
  "Installs a function as a new method of multimethod associated with dispatch-value. "
  [multifn dispatch-val func]
  (. multifn addMethod dispatch-val func))

(defmacro defrecord2-builder
  [type-name field-list ctor-name & etc]
    (let [fields (->> field-list (partition 2) (map first) vec)
          defaults (->> field-list (partition 2) (map second))]
      `(do
         ;; define the record
         (defrecord ~type-name ~fields ~@etc)
         ;; define the constructor functions
         (make-record-constructor ~ctor-name
                                  (~(symbol (str type-name "."))
                                     ~@defaults))
         ;; setup printing
         ;(setup-print-record (quote ~ctor-name) ~type-name)
         ;; setup pprinting
         (use-method simple-dispatch ~type-name
               (generate-record-pprint (quote ~ctor-name))))))


;;;Usage:
;(defrecord2 Name
;  [item1 nil
;   item2 0])

(defn derive-list
  [h tag parents]
    (let [refh (ref h)]
      (doseq [p parents]
        (let [newtypekey (if (keyword? p) p (keyword p))]
          (alter refh derive tag newtypekey)))
      @refh))

      ;(map #(derive h tag %) parents)) ;;This doesn't work, we need to get a single hierarchy back.

(defn digit-char-p
  [char]
  (try
    (do (Integer/parseInt (str char))
        true)
    (catch NumberFormatException e false)))

;(defmacro interleave-quote
;  [c1 c2]
;  (let [s1 (seq c1) s2 (seq c2)]
;    (when (and s1 s2)
;      (cons (first s1) (cons (quote (first s2))
;                             (interleave-quote (rest s1) (rest s2)))))))

(defn error
  [& strs]
  (throw (Exception. (apply str strs))))

(defn make-set-if-not-set
  "Returns the argument if it is a set, and the set containing only that argument otherwise"
  [element]
  (if (set? element)
    element
    (set
      (if (not (seq? element))
        (list element)
	      element))))

(defn setof [& args]
  (set args))

(defn third [list]
  (if
    (> (count list) 2)
      (nth list 2)))

(defn atom? [x]
  (not
    (or
      (instance? java.util.Collection x)
      (instance? java.util.Map x))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Removing reliance on the monolithic Clojure.contrib for the move to 1.3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;From the old 1.2 version of Clojure.contrib.def
(defmacro defvar
  "Defines a var with an optional intializer and doc string"
  ([name]
     (list `def name))
  ([name init]
     (list `def name init))
  ([name init doc]
     (list `def (with-meta name (assoc (meta name) :doc doc)) init)))

; name-with-attributes by Konrad Hinsen:
(defn name-with-attributes
  "To be used in macro definitions.
   Handles optional docstrings and attribute maps for a name to be defined
   in a list of macro arguments. If the first macro argument is a string,
   it is added as a docstring to name and removed from the macro argument
   list. If afterwards the first macro argument is a map, its entries are
   added to the name's metadata map and the map is removed from the
   macro argument list. The return value is a vector containing the name
   with its extended metadata map and the list of unprocessed macro
   arguments."
  [name macro-args]
  (let [[docstring macro-args] (if (string? (first macro-args))
                                 [(first macro-args) (next macro-args)]
                                 [nil macro-args])
    [attr macro-args]          (if (map? (first macro-args))
                                 [(first macro-args) (next macro-args)]
                                 [{} macro-args])
    attr                       (if docstring
                                 (assoc attr :doc docstring)
                                 attr)
    attr                       (if (meta name)
                                 (conj (meta name) attr)
                                 attr)]
    [(with-meta name attr) macro-args]))

;;Detect enclojure by examining classpaths.
(defn with-enclojure?
  []
  (.contains ^String (apply str (seq (.getURLs ^java.lang.ClassLoader (java.lang.ClassLoader/getSystemClassLoader)))) "enclojure-repl"))

;;; Currently have the problem that enclojure doesn't capture stdin. We'll need
;;; to write a GUI version of this at some point for that case.
(defn menuChoice
  "Presents the msgString,
        the set of choices, plus an added \"cancel\" choice,
        as a numbered list;
     reads the number of the user's choice;
     and returns that number minus one."
  [msgString choices & rest]
  (when-not choices
    (error "chooseFromMenu called with no choices."))
  (let [len (count choices)]
    (cl-format true "~%~A~2%" msgString)
    (doseq [n (range len)]
      (cl-format true "~&~D. ~A~%" (+ 1 n) (nth choices n)))
    (cl-format true "~&~D. ~A~2%" (+ 1 len) "Cancel")
    (- (Integer/parseInt (read-line)) 1)))

(defn menuChooseFromList
  "Uses menuChoice to present the msgString and the choices.
       returns the choice chosen, or nil if the user chooses 'cancel'."
  [msgString choices & rest]
  (if with-enclojure?
    (first choices) ;;;TODO: GUI VERSION.
    (nth choices (menuChoice msgString choices))))

(defmacro do-while
  [test & body]
  `(loop []
     ~@body
     (when ~test
       (recur))))

(defmacro setOr 
  "Returns the evaluation of the first set expression
       that evaluates to a non-empty set;
    or the empty set if none do."
  [& setexprs]
  (if (empty? setexprs)
    '#{}
    (let [expr (gensym)]
      `(let [~expr ~(first setexprs)]
	 (if (empty? ~expr)
	     (setOr ~@(rest setexprs))
	     ~expr)))))

(defn cons?
  [expr]
  (= (type expr) clojure.lang.Cons))

(defmacro ignore-errors
  "Returns the result of evaluating e, or nil if it throws an exception"
  [e]
  `(try ~e (catch java.lang.Exception _# nil)))


(defn indexed
  "FROM OLD CONTRIB
  Returns a lazy sequence of [index, item] pairs, where items come
  from 's' and indexes count up from zero.

  (indexed '(a b c d))  =>  ([0 a] [1 b] [2 c] [3 d])"
  [s]
  (map vector (iterate inc 0) s))

(defn positions
  "FROM OLD CONTRIB
   Returns a lazy sequence containing the positions at which pred
   is true for items in coll."
  [pred coll]
  (for [[idx elt] (indexed coll) :when (pred elt)] idx))

(defn map-difference [m1 m2]
  (let [ks1 (set (keys m1))
        ks2 (set (keys m2))
        ks1-ks2 (set/difference ks1 ks2)
        ks2-ks1 (set/difference ks2 ks1)
        ks1*ks2 (set/intersection ks1 ks2)]
    (merge (select-keys m1 ks1-ks2)
           (select-keys m2 ks2-ks1)
           (select-keys m1
                        (remove (fn [k] (= (m1 k) (m2 k)))
                                ks1*ks2)))))

(defn seqable? 
  [x]
  (instance? clojure.lang.Seqable x))

(defn subsat
  [s char]
  (subs s (.indexOf s char)))

