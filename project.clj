(defproject org.clojars.tafenton/do-er "1.0.4"
  :description "A scheduling library inspired by my favourite bits of chime and overtone.at-at"
  :url "https://github.com/tafenton/do-er"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :dependencies [[clojure.java-time "1.1.0"]
                 [org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.5.648"]]
  :repl-options {:init-ns do-er.core})