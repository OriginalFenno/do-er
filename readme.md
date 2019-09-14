A scheduling library, inspired by my favourite features from:

overtone.at-at (https://github.com/overtone/at-at)
+Tasks are added to pools, allowing them to be interrogated at any time
-Originated in music programming; based around millisecond delays (dates/times only via other libraries)

chime (https://github.com/jarohen/chime)
+A nice DSL for scheduling; uses core.async in a way that makes sense to me
-When tasks are created they only leave behind their stop channel - no way to check their state
-Defaults to UTC rather than local time, which has caught me out on several occasions...

I've thrown my hat into the ring for java-time, and built a small chime-inspired DSL around it. If you
want to use any of the more advanced java-time predicates, just import it and use them as they come (see
below).

---

USAGE

First off, create your task pool:

(def mypool (make-task-pool))     ; Plot twist - it's just an empty map in an atom

Then start building your tasks. Successfully-created tasks will return their ids (a keywordized UUID
if none is provided).

(add-task {:id :hello-forever
           :task-pool mypool
           :function #(println "Hello, world")
           :schedule (every 5 :seconds)})
=> :hello-forever
Hello, world
Hello, world
Hello, world
...

This will rapidly become annoying, so:

(stop-task mypool :hello-forever)
=> true

Note this only stops future triggers, and does not stop any currently-executing task. Stopping a stopped
task will return nil.

Tasks that would never execute, i.e. because their entire schedules are in the past, will return
false on creation and won't be added to the task pool:

(add-task {:id :never-gonna-happen
           :task-pool mypool
           :function #(println "Help!")
           :schedule (-> (every 5 :minutes)
                         (from (java-time/local-date-time 2019 1 1))
                         (until (java-time/local-date-time 2019 2 1)))
           :on-complete #(println "Finally, I'm free!")})
=> false

Note the interaction with java-time. You can also specify tasks that run on completion:

(add-task {:id :send-help
           :task-pool mypool
           :function #(println "Help!")
           :schedule (-> (every 5 :seconds)
                         (until (in 15 :seconds)))
           :on-complete #(println "Finally, I'm free!")})
=> :send-help
Help!
Help!
Help!
Finally, I'm free!

And tasks that run on error, taking the caught exception as an argument:

(add-task {:id :doomed-to-fail
           :task-pool mypool
           :function #(println (/ 1 0))
           :schedule (once (in 2 :seconds))
           :on-error (fn [e] (timbre/error (-> e                ; Other logging libraries are available
                                               Throwable->map
                                               :cause)))})
=> :doomed-to-fail
19-09-14 13:43:44 MACHINE-NAME ERROR [do-er.core:5] - Divide by zero

Other examples with the scheduling DSL:

(add-task {:id :hello-briefly
           :task-pool mypool
           :function #(println "Hello, wo...")
           :schedule (-> (every 5 :seconds)
                         (limit 1))})
           
(add-task {:id :weekend-worker
           :task-pool mypool
           :function #(println "I need a vacation")
           :schedule (-> (every 15 :minutes)
                         (only saturdays sundays)})

---

Improvements that aren't necessary for my use case, but that I might get round to one day:

If you schedule a task without a start time, i.e. effective immediately, it may run immediately or it
may wait for the next scheduled execution

When a task finishes running, it will discard any triggers that have elapsed; e.g. if a task starts hourly
but takes 65 minutes to complete, it will wait 55 minutes before starting again. For me this is
preferable, but I may add the ability to vary this.

Schedules are NOT durable, nor are they likely to be. My use case is scheduled data-load applications,
so I want to have an application trigger a process recurrently and give detailed feedback about both the
outcome of the task and the state of the schedule (i.e. run count, next run, last successful run,
current state)