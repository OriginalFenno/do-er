(ns do-er.core
  (:require [clojure.core.async :refer [go-loop chan close! alt! >!! timeout]]
            [java-time :as t])
  (:import [java.util UUID]))

(defn- ms-until [future-time]
  (let [now (t/local-date-time)]
    (if (t/before? future-time now)
      0
      (.toMillis (t/duration now future-time)))))

(defn- get-state [pool entity]
  (get-in pool [(first entity) :state]))

(defn- is-active? [pool-state id]
  (let [state (get-state pool-state id)]
    (if (or (= state :waiting)
            (= state :running)
            (= state :failed))
      true)))

(defn- is-finished? [pool-state id]
  (let [state (get-state pool-state id)]
    (if (or (= state :stopped)
            (= state :finished))
      true)))

(defn- drop-elapsed [schedule]
  (drop-while #(t/before? % (t/local-date-time)) schedule))

(defn stop-task [pool id]
  (let [task-state (get-in @pool [id :state])
        stop-chan (get-in @pool [id :stop-chan])]
    (if-not (= task-state :stopped)
      (do (>!! stop-chan :stop)
          (close! stop-chan)
          (swap! pool assoc-in [id :state] :stopped)
          (swap! pool assoc-in [id :next-run] nil)
          true))))

(defn finish-task [pool id]
  (let [stop-chan (get-in @pool [id :stop-chan])]
    (close! stop-chan))
  (swap! pool assoc-in [id :next-run] nil)
  (swap! pool assoc-in [id :state] :finished))

(defn make-task-pool []
  (atom {}))

(defn add-task [{:keys [task-pool function schedule on-complete on-error id]
                 :or {on-complete (fn []) on-error (fn []) id (keyword (str (UUID/randomUUID)))}}]
  (let [future-runs (drop-elapsed schedule)
        stop-chan (chan)]
    (if-not (nil? (get @task-pool id))
      (stop-task task-pool id))
    (if (empty? future-runs)
      false
      (do (swap! task-pool conj {id {:run-count 0 :next-run nil :state :waiting :last-completed nil :stop-chan stop-chan}})
          (go-loop [remaining-runs future-runs]
            (let [next-run (first remaining-runs)]
              (swap! task-pool assoc-in [id :next-run] next-run)
              (alt! stop-chan nil
                    (timeout (ms-until next-run)) (do (try (swap! task-pool assoc-in [id :state] :running)
                                                           (function)
                                                           (swap! task-pool update-in [id :run-count] inc)
                                                           (swap! task-pool assoc-in [id :last-completed] (t/local-date-time))
                                                           (swap! task-pool assoc-in [id :state] :waiting)
                                                           (catch Exception e (swap! task-pool assoc-in [id :state] :failed)
                                                                              (on-error e)))
                                                      (let [new-schedule (drop-elapsed remaining-runs)]
                                                        (if-not (empty? new-schedule)
                                                          (recur new-schedule)
                                                          (do (on-complete)
                                                              (finish-task task-pool id))))))))
          id))))


(defn finished-tasks [pool]
  (let [pool-state @pool]
    (filter #(is-finished? pool-state %) pool-state)))

(defn active-tasks [pool]
  (let [pool-state @pool]
    (filter #(is-active? pool-state %) pool-state)))

(defn stop-pool [pool]
  (let [active-tasks (active-tasks pool)]
    (for [task (keys active-tasks)]
      (stop-task pool task))))

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
   (every (t/local-date-time) number interval))
  ([start number interval]
   (let [int-function (get-int-function interval)]
     (iterate #(t/plus % (int-function number)) start))))

(defn in [number interval]
  (let [int-function (get-int-function interval)]
    (t/plus (t/local-date-time) (int-function number))))

(defn from [start-time]
  start-time)

(defn once [date-time]
  (vector date-time))

(defn until [schedule end-time]
  (take-while #(t/before? % end-time) schedule))

(defn limit [number schedule]
  (take number schedule))

(defn only [schedule & predicates]
  (filter (apply some-fn predicates) schedule))

(defn date [year month day]
  (t/local-date-time year month day 0 0 0))

(defn date-time [year month date hours minutes seconds]
  (t/local-date-time year month date hours minutes seconds))