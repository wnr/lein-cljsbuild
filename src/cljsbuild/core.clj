(ns cljsbuild.core
  (:require
    [clojure.string :as string]
    [clojure.set :as cset]
    [clj-stacktrace.repl :as st]
    [fs :as fs] 
    [cljs.closure :as cljsc]))

(def tmpdir ".clojurescript-output")

(defn- filter-cljs [files types]
  (let [ext #(last (string/split % #"\."))]
    (filter #(types (ext %)) files)))

(defn- find-dir-cljs [root files types]
  (for [cljs (filter-cljs files types)] (fs/join root cljs)))

(defn- find-cljs [dir types]
  (let [iter (fs/iterdir dir)]
    (mapcat
      (fn [[root _ files]]
        (find-dir-cljs root files types))
      iter)))

(defn- elapsed [started-at]
  (let [elapsed-us (- (. System (nanoTime)) started-at)]
    (with-precision 2
      (str (/ (double elapsed-us) 1000000000) " seconds"))))

(defn- compile-cljs [source-dir output-file optimizations pretty?]
  (print (str "Compiling " output-file " from " source-dir "..."))
  (flush)
  (fs/mkdirs (fs/dirname output-file))
  (let [started-at (. System (nanoTime))]
    (try
      (cljsc/build
        source-dir
        {:optimizations optimizations
         :pretty-print pretty?
         :output-to output-file
         :output-dir tmpdir})
      (println (str " Done in " (elapsed started-at) "."))
      (catch Exception e
        (println " Failed!")
        (st/pst+ e)))))

(defn- is-macro-file? [file]
  (not (neg? (.indexOf (slurp file) ";*CLJSBUILD-MACRO-FILE*;"))))

; There is a little bit of madness here to share macros between Clojure
; and ClojureScript.  The latter needs a  (:require-macros ...) whereas the
; former just wants  (:require ...).  Thus, we have a ;*CLJSBUILD-REMOVE*;
; conditional comment to allow different code to be used for ClojureScript files.
(defn- filtered-crossover-file [file]
  (str
    "; DO NOT EDIT THIS FILE! IT WAS AUTOMATICALLY GENERATED BY\n"
    "; lein-cljsbuild FROM THE FOLLOWING SOURCE FILE:\n"
    "; " file "\n\n"
    (string/replace (slurp file) ";*CLJSBUILD-REMOVE*;" "")))

(defn- crossover-to [from-dir to-dir from-file]
  (let [abspath (fs/abspath from-file)
        subpath (string/replace-first
                  (fs/abspath from-file)
                  (fs/abspath from-dir) "")
        to-file (fs/normpath
                  (fs/join (fs/abspath to-dir) subpath))]
    (if (is-macro-file? from-file)
      to-file
      (string/replace to-file #"\.clj$" ".cljs"))))

(defn- delete-extraneous-files [expected-files to-dir]
  (let [real-files (map fs/abspath
                     (find-cljs to-dir #{"clj" "cljs"}))
        extraneous-files (cset/difference
                           (set real-files)
                           (set expected-files))]
    (for [file extraneous-files]
      (do
        (fs/delete file)   
        :updated))))

(defmacro dofor [seq-exprs body-expr]
  `(doall (for ~seq-exprs ~body-expr)))

(defn- copy-crossovers [crossovers]
  (dofor [{:keys [from-dir to-dir]} crossovers]
    (let [from-files (find-cljs from-dir #{"clj"})
          to-files (map (partial crossover-to from-dir to-dir) from-files)]
      (fs/mkdirs to-dir)
      (concat
        (delete-extraneous-files to-files to-dir) 
        (dofor [[from-file to-file] (zipmap from-files to-files)]
          (when
            (or
              (not (fs/exists? to-file))
              (> (fs/mtime from-file) (fs/mtime to-file)))
            (do
              (spit to-file (filtered-crossover-file from-file))
              :updated)))))))

(defn run-compiler [source-dir crossovers output-file optimizations pretty? watch?]
  (println "Compiler started.")
  (loop [last-input-mtimes {}]
    (let [output-mtime (if (fs/exists? output-file) (fs/mtime output-file) 0)
          ; Need to return *.clj as well as *.cljs because ClojureScript
          ; macros are written in Clojure.
          input-files (find-cljs source-dir #{"clj" "cljs"})
          input-mtimes (map fs/mtime input-files)
          crossover-updated? (some #{:updated}
                               (flatten (copy-crossovers crossovers)))]
      (when (or
              (and
                (not= last-input-mtimes input-mtimes) 
                (some #(< output-mtime %) input-mtimes)) 
              crossover-updated?)
        (compile-cljs source-dir output-file optimizations pretty?))
      (when watch?
        (Thread/sleep 100)
        (recur input-mtimes)))))
