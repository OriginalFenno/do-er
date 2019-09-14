(ns do-er.core
  (:require [clojure.core.async :refer [go-loop chan close! alt! >!! timeout]]
            [do-er.scheduleDSL :refer :all]
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