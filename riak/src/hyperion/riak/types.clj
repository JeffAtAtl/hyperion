(ns hyperion.riak.types
  (:require [chee.coerce :refer [->string ->int ->keyword]]
            [hyperion.coerce :refer [->float ->double ->long ->byte ->short ->char]]
            [hyperion.api :refer [pack unpack]])
  (:import  [java.math BigInteger]))

(defmethod pack java.lang.Number [_ value]
  (->string value))

(defmethod unpack java.lang.Byte [_ value]
  (->byte value))

(defmethod unpack java.lang.Short [_ value]
  (->short value))

(defmethod unpack java.lang.Integer [_ value]
  (->int value))

(defmethod unpack java.lang.Long [_ value]
  (->long value))

(defmethod unpack java.lang.Float [_ value]
  (->float value))

(defmethod unpack java.lang.Double [_ value]
  (->double value))

(defmethod pack clojure.lang.Keyword [_ value]
  (->string value))

(defmethod unpack clojure.lang.Keyword [_ value]
  (->keyword value))

(defmethod pack java.lang.Character [_ value]
  (->string value))

(defmethod unpack java.lang.Character [_ value]
  (->char value))
