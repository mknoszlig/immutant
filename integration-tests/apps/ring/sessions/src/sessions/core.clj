;; Copyright 2008-2014 Red Hat, Inc, and individual contributors.
;; 
;; This is free software; you can redistribute it and/or modify it
;; under the terms of the GNU Lesser General Public License as
;; published by the Free Software Foundation; either version 2.1 of
;; the License, or (at your option) any later version.
;; 
;; This software is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
;; Lesser General Public License for more details.
;; 
;; You should have received a copy of the GNU Lesser General Public
;; License along with this software; if not, write to the Free
;; Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
;; 02110-1301 USA, or see the FSF site: http://www.fsf.org.

(ns sessions.core
  (:require [immutant.web :as web]
            [immutant.web.session :as immutant-session]
            [immutant.util :as util]
            [ring.middleware.session :as ring-session]))

(defn query-map [query-string]
  (if-not (empty? query-string)
    (apply hash-map
           (clojure.string/split query-string #"(&|=)"))
    {}))

(defn respond [session]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :session session
   :cookies {"a-cookie" "a cookie value"}
   :body (pr-str session)})

(defn handler [request]
  (respond (merge (:session request) (query-map (:query-string request)))))

(defn session-attrs-handler [request]
  (let [rmap (query-map (:query-string request))
        sess-data (rmap "session")
        sess-attrs (if (rmap "attrs")
                     (-> (rmap "attrs")
                         ring.util.codec/url-decode
                         read-string
                         clojure.walk/keywordize-keys))]
    (util/mapply
      immutant-session/set-session-cookie-attributes! sess-attrs)
    (respond (merge (:session request)
                    (if sess-data
                      (apply hash-map (clojure.string/split sess-data #":"))
                      {})))))

(defn init-immutant-session [path & [cookie-name]]
  (let [opts {:store (immutant-session/servlet-store)}]
    (web/start path
               (ring-session/wrap-session
                handler
                (if cookie-name
                  (assoc opts :cookie-name cookie-name)
                  opts)))))

(defn init-ring-session [store]
  (web/start "/ring"
             (ring-session/wrap-session
              handler
              {:store store})))

(defn clear-handler
  [request]
  (respond nil))

(defn init-ring-clearer [store]
  (web/start "/clear-ring"
             (ring-session/wrap-session
              clear-handler
              {:store store})))

(defn init-immutant-clearer [path & [cookie-name]]
  (let [opts  {:store (immutant-session/servlet-store)}]
    (web/start path
               (ring-session/wrap-session
                clear-handler
                (if cookie-name
                  (assoc opts :cookie-name cookie-name)
                  opts)))))

(defn init-session-attrs []
  (web/start "/session-attrs"
             (ring-session/wrap-session
              session-attrs-handler
              {:store (immutant-session/servlet-store)})))

(defn init-all []
  (let [ring-mem-store (ring.middleware.session.memory/memory-store)]
    (init-ring-session ring-mem-store)
    (init-immutant-session "/immutant")
    (init-immutant-session "/immutant-jsessionid" "JSESSIONID")
    (init-ring-clearer ring-mem-store)
    (init-immutant-clearer "/clear")
    (init-immutant-clearer "/clear-jsessionid" "JSESSIONID")))
