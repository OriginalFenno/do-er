(ns do-er.scheduleDSL
  (:require [java-time :as t]))

(defn- get-int-function [key]
  (case key :years t/years
            :months t/months
            :weeks t/weeks
            :days t/days
            :hours t/hours
            :minutes t/minutes
            :seconds t/seconds
            :millis t/millis))

(def weekends t/weekend?)
(def weekdays t/weekday?)

(def mondays t/monday?)
(def tuesdays t/tuesday?)
(def wednesdays t/wednesday?)
(def thursdays t/thursday?)
(def fridays t/friday?)
(def saturdays t/saturday?)
(def sundays t/sunday?)

(defn today-at
  ([hours mins]
   (today-at hours mins 0))
  ([hours mins secs]
   (let [today (bean (t/local-date))
         year (:year today)
         month (:month today)
         day (:dayOfMonth today)]
     (t/local-date-time year month day hours mins secs))))

(defn every
  ([number interval]
   (every number interval (t/local-date-time)))
  ([number interval start]
   (let [int-function (get-int-function interval)]
     (iterate #(t/plus % (int-function number)) start))))

(defn in [number interval]
  (let [int-function (get-int-function interval)]
    (t/plus (t/local-date-time) (int-function number))))

(defn from [schedule start-time]
  (drop-while #(t/before? % start-time) schedule))

(defn once [date-time]
  (vector date-time))

(defn until [schedule end-time]
  (take-while #(t/before? % end-time) schedule))

(defn limit [schedule number]
  (take number schedule))

(defn only [schedule & predicates]
  (filter (apply some-fn predicates) schedule))