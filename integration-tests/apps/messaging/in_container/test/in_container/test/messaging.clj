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

(ns in-container.test.messaging
  (:use clojure.test)
  (:require [immutant.messaging :as msg]
            [immutant.util      :as util]
            [immutant.registry  :as registry]
            [clojure.java.jmx :as jmx])
  (:import javax.management.InstanceNotFoundException))

(defn get-destination [name]
  (let [destinations (.getDestinations (registry/get "destinationizer"))]
    (.get destinations (str name))))

(testing "listen"
  (deftest listen-on-a-queue-should-raise-for-non-existent-destinations
    (is (thrown? IllegalStateException
                 (msg/listen "a.non-existent.queue" (constantly nil)))))

  (deftest listen-on-a-topic-should-raise-for-non-existent-destinations
    (is (thrown? IllegalStateException
                 (msg/listen "a.non-existent.topic" (constantly nil)))))

  (deftest remote-listen-on-a-queue-should-work
    (let [queue "remote.queue"
          response-q "remote.queue.response"]
      (msg/start queue) ;; it's in-container, but we'll pretend it isn't below
      (msg/start response-q)
      (msg/listen queue #(msg/publish response-q %) :host "integ-app1.torquebox.org" :port (util/hornetq-remoting-port))
      (msg/publish queue "ahoy" :host "integ-app1.torquebox.org" :port (util/hornetq-remoting-port))
      (is (= "ahoy" (msg/receive response-q))))))

(testing "start for queues"
  (deftest queue-start-should-be-synchronous
    (let [queue "queue.start.sync"]
      (msg/start queue)
      ;; this should throw if the queue doesn't yet exist
      (is (msg/listen queue (constantly true)))))

  (deftest queue-start-should-work-with-as-queue
    (let [q (msg/as-queue "hambone")]
      (msg/start q)
      (is (get-destination q))))

  (deftest queue-start-should-be-idempotent
    (let [queue "queue.id"]
      (msg/start queue)
      (try
        (msg/start queue)
        (is true)
        (catch Exception e
          (.printStackTrace e)
          (is false))))))

(testing "start for topics"
  (deftest topic-start-should-be-synchronous
    (let [topic "topic.start.sync"]
      (msg/start topic)
      ;; this should throw if the topic doesn't exist
      (is (msg/listen topic (constantly true)))))

  (deftest topic-start-should-work-with-as-topic
    (let [q (msg/as-topic "pigbone")]
      (msg/start q)
      (is (get-destination q))))

  (deftest topic-start-should-be-idempotent
    (let [topic "topic.id"]
      (msg/start topic)
      (try
        (msg/start topic)
        (is true)
        (catch Exception e
          (.printStackTrace e)
          (is false))))))

(testing "stop for queues"
  (deftest queue-stop-should-be-synchronous
    (let [queue "queue.stop.sync"]
      (msg/start queue)
      (msg/stop queue)
      (is (thrown? IllegalStateException
                   (msg/listen queue (constantly true))))))

  (deftest queue-stop-should-work-with-as-queue
    (let [queue (msg/as-queue "dogleg")]
      (msg/start queue)
      (msg/stop queue)
      (is (not (get-destination queue)))))

  (deftest force-stop-on-a-queue-should-remove-listeners
    (let [queue "queue.force"
          izer (registry/get "message-processor-groupizer")]
      (msg/start queue)
      (msg/listen queue (constantly nil))
      (msg/stop queue :force true)
      (is (not (seq (.installedGroupsFor izer queue)))))))

(testing "stop for topics"
  (deftest topic-stop-should-be-synchronous
    (let [topic "topic.stop.sync"]
      (msg/start topic)
      (msg/stop topic)
      (is (thrown? IllegalStateException
                   (msg/listen topic (constantly true))))))

  (deftest topic-stop-should-work-with-as-topic
    (let [topic (msg/as-topic "dogsnout")]
      (msg/start topic)
      (msg/stop topic)
      (is (not (get-destination topic)))))

  (deftest force-stop-on-a-topic-should-remove-listeners
    (let [topic "topic.force"
          izer (registry/get "message-processor-groupizer")]
      (msg/start topic)
      (msg/listen topic (constantly nil))
      (msg/stop topic :force true)
      (is (not (seq (.installedGroupsFor izer topic)))))))

(testing "unlisten"
  (deftest unlisten-on-a-queue-should-be-synchronous-when-derefed
    (let [queue "queue.ham"]
      (msg/start queue)
      @(msg/unlisten (msg/listen queue (constantly nil)))
      (is (= true (msg/stop queue)))))

  (deftest unlisten-on-a-topic-should-be-synchronous-when-derefed
    (let [topic "topic.ham"]
      (msg/start topic)
      @(msg/unlisten (msg/listen topic (constantly nil)))
      (is (= true (msg/stop topic)))))

  (deftest unlisten-should-return-true-when-it-does-something
    (let [queue "queue.ham"
          _ (msg/start queue)
          l (msg/listen queue (constantly nil))]
      (is @(msg/unlisten l))
      (is (not @(msg/unlisten l)))))

  (deftest unlisten-should-remove-its-mbean
    (let [queue "queue.fart"
          mbean-name "immutant.messaging:name=queue.fart.,app=messaging"
          _ (msg/start queue)
          l (msg/listen queue (constantly nil))]
      (is (jmx/mbean mbean-name))
      @(msg/unlisten l)
      (is (thrown? InstanceNotFoundException (jmx/mbean mbean-name))))))


(deftest encodings-smoketest
  (let [q "queue.encodings"]
    (msg/start q)
    (doseq [enc [:edn :json :clojure :fressian :text]]
      (msg/publish q "hi" :encoding enc)
      (is (= "hi" (msg/receive q))))))
