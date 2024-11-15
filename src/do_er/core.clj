(ns do-er.core
  (:require [clojure.core.async :refer [go-loop chan close! alt! >!! timeout]]
            ;[duratom.core :refer [duratom]]
            [taoensso.nippy :as nippy])
  (:import [java.time DayOfWeek Duration Instant LocalDate LocalDateTime]
           [java.time.temporal ChronoUnit]
           [java.util UUID]))

;; At the moment I have one thread per schedule
;;   Instead, every time the schedule is changed, recalculate the next execution waiter in a single thread and then start execution only in a new thread(?)
;;   Sort by msec until next execution, then thread-sleep for shortest duration?
;;   How to handle inserting an earlier execution into the schedule?

;; Day-of-month function
;; xth-weekday-of-month function
;; option to run on working day if schedule falls on a weekend

;; Deterministic behaviour if start date is in the past? - via an explicit "start now" vs explicitly NOT running if no start is specified?

;; Define behaviour for overlapping tasks:
;;   Start another instance on time
;;   Start another instance on completion
;;   Skip occurrence (default)

(defn- ms-until [future-time]
  (let [now (LocalDateTime/now)]
    (if (.isBefore future-time now)
      0
      (.toMillis (Duration/between now future-time)))))

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

(defn drop-elapsed [schedule]
  (drop-while #(.isBefore % (LocalDateTime/now)) schedule))

(defn- update-task [pool id new-map]
  (swap! pool update-in [id] merge new-map))

(defn stop-task [pool id]
  (if-let [{:keys [state stop-chan]} (get @pool id)]
    (if-not (= state :stopped)
      (do (>!! stop-chan :stop)
          (close! stop-chan)
          (update-task pool id {:state :stopped
                                :next-run nil})
          true)
      false)
    false))

(defn finish-task [pool id]
  (let [stop-chan (get-in @pool [id :stop-chan])]
    (close! stop-chan))
  (update-task pool id {:next-run nil
                        :state :finished}))

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

(defn make-task-pool []
  (atom {}))

(defmacro add-task [{:keys [task-pool function schedule on-complete on-error id last-completed run-count start-time]
                     :or {on-complete '(fn [])
                          on-error '(fn [])
                          run-count 0
                          id (keyword (str (UUID/randomUUID)))}}]
  `(let [future-runs# (drop-elapsed ~schedule)
         stop-chan# (chan)]
     (if-not (nil? (get (deref ~task-pool) ~id))
       (stop-task ~task-pool ~id))
    (if (empty? future-runs#)
      false
      (do (swap! ~task-pool conj {~id {:id ~id
                                       :start-time (or ~start-time (Instant/now))
                                       :run-count  ~run-count
                                       :schedule   (quote ~schedule)
                                       :function   (quote ~function)
                                       :on-complete (quote ~on-complete)
                                       :on-error (quote ~on-error)
                                       :next-run   nil
                                       :state      :waiting
                                       :last-completed (or ~last-completed nil)
                                       :stop-chan stop-chan#}})
          (go-loop [remaining-runs# future-runs#]
            (let [next-run# (first remaining-runs#)]
              (swap! ~task-pool assoc-in [~id :next-run] next-run#)
              (alt! stop-chan# nil
                    (timeout (ms-until next-run#)) (do (try (swap! ~task-pool assoc-in [~id :state] :running)
                                                            (~function)
                                                            (swap! ~task-pool update-in [~id :run-count] inc)
                                                            (update-task ~task-pool ~id {:last-completed (LocalDateTime/now)
                                                                                         :state :waiting})
                                                            (catch Exception e# (swap! ~task-pool assoc-in [~id :state] :failed)
                                                                               (~on-error e#)))
                                                       (let [new-schedule# (drop-elapsed remaining-runs#)]
                                                         (if-not (empty? new-schedule#)
                                                           (recur new-schedule#)
                                                           (do (~on-complete)
                                                               (finish-task ~task-pool ~id))))))))
          ~id))))


;; Scheduling DSL

(defn- get-chrono-unit [key]
  (case key :years ChronoUnit/YEARS
            :months ChronoUnit/MONTHS
            :weeks ChronoUnit/WEEKS
            :days ChronoUnit/DAYS
            :hours ChronoUnit/HOURS
            :minutes ChronoUnit/MINUTES
            :seconds ChronoUnit/SECONDS
            :millis ChronoUnit/MILLIS))

(def mondays #(= (:dayOfWeek (bean %)) DayOfWeek/MONDAY))
(def tuesdays #(= (:dayOfWeek (bean %)) DayOfWeek/TUESDAY))
(def wednesdays #(= (:dayOfWeek (bean %)) DayOfWeek/WEDNESDAY))
(def thursdays #(= (:dayOfWeek (bean %)) DayOfWeek/THURSDAY))
(def fridays #(= (:dayOfWeek (bean %)) DayOfWeek/FRIDAY))
(def saturdays #(= (:dayOfWeek (bean %)) DayOfWeek/SATURDAY))
(def sundays #(= (:dayOfWeek (bean %)) DayOfWeek/SUNDAY))

(def weekends #(or (saturdays %) (sundays %)))
(def weekdays #(not (weekends %)))

(defn today-at
  ([hours mins]
   (today-at hours mins 0))
  ([hours mins secs]
   (let [today (bean (LocalDate/now))
         year (:year today)
         month (:monthValue today)
         day (:dayOfMonth today)]
     (LocalDateTime/of ^int year ^int month ^int day ^int hours ^int mins ^int secs))))

(defn every
  ([number interval]
   (every (LocalDateTime/now) number interval))
  ([start number interval]
   (let [chrono-unit (get-chrono-unit interval)]
     (iterate #(.plus % (Duration/of number chrono-unit)) start))))

(defn in [number interval]
  (let [chrono-unit (get-chrono-unit interval)]
    (.plus (LocalDateTime/now) (Duration/of number chrono-unit))))

(defn from [start-time]
  start-time)

(defn once [date-time]
  (vector date-time))

(defn between-hours [schedule start end]
  (filter #(and (> (:hour (bean %)) start)
                (< (:hour (bean %)) end)) schedule))

(defn until [schedule end-time]
  (take-while #(.isBefore % end-time) schedule))

(defn limit [number schedule]
  (take number schedule))

(defn only [schedule & predicates]
  (filter (apply some-fn predicates) schedule))

(defn date [year month day]
  (LocalDateTime/of ^int year ^int month ^int day 0 0 0))

(defn date-time [year month date hours minutes seconds]
  (LocalDateTime/of ^int year ^int month ^int date ^int hours ^int minutes ^int seconds))


;; Durable Pools - WIP

;(defn initialise-task-pool [{:keys [durable? path]}]
;  (if durable?
;    (if-not path
;      :error/path-required
;      (def task-pool (duratom :local-file
;                              :file-path path
;                              :init {}
;    (def task-pool (atom {}))))

(defn thaw-pool [path pool]
  (let [stored-tasks (nippy/thaw-from-file path)]
    (for [{:keys [id function schedule on-error on-complete start-time last-completed run-count]} stored-tasks]
      (add-task {:id id
                 :task-pool   pool
                 :function    (eval function)
                 :schedule    (eval schedule)
                 :on-error    (eval on-error)
                 :on-complete (eval on-complete)
                 :start-time  start-time
                 :last-completed last-completed
                 :run-count   run-count}))))

(defn freeze-pool [pool path]
  (let [trimmed-pool (map #(dissoc (second %) :stop-chan) (seq @pool))]
    (nippy/freeze-to-file path trimmed-pool)))

(defn make-pool-durable [pool-name path]
  (add-watch pool-name
             (keyword (str pool-name "watcher"))
             (fn [_ _ _ _]
               (freeze-pool pool-name path))))