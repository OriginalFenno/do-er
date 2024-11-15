(defproject org.clojars.tafenton/do-er "2.0.0-alpha2"
  :description "A scheduling library inspired by my favourite bits of chime and overtone.at-at"
  :url "https://github.com/tafenton/do-er"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :dependencies [[com.taoensso/nippy "3.4.2"]
                 [duratom "0.5.9"]
                 [org.clojure/clojure "1.12.0"]
                 [org.clojure/core.async "1.6.681"]]
  :repl-options {:init-ns do-er.core})