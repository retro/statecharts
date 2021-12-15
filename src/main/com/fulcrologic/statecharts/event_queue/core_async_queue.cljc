(ns com.fulcrologic.statecharts.event-queue.core-async-queue
  "ALPHA. NOT API STABLE."
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.protocols :as sp]
    [taoensso.timbre :as log]))


(deftype CoreAsyncQueue [Q delayed-events cancelled-events]
  sp/EventQueue
  (send! [_ {:keys [event delay]}]
    (async/go
      (if delay
        (let [nm (evts/event-name event)]
          (swap! delayed-events update nm (fnil inc 0))
          (async/<! (async/timeout delay))
          (when-not (contains? @cancelled-events nm)
            (async/>! Q event))
          (when (zero? (swap! delayed-events update nm dec))
            (swap! cancelled-events disj nm)))
        (async/>! Q event))))
  (cancel! [_ _ send-id]
    (let [nm send-id]
      (when (pos? (get @delayed-events nm))
        (swap! @cancelled-events conj nm))))
  (receive-events! [_ _ handler]
    (async/go
      (let [event (async/<! Q)]
        (try
          (handler event)
          (catch #?(:clj Throwable :cljs :default) e
            (log/error e "Event handler threw an execption")))
        :ok))))

(defn new-async-queue
  "Creates an event queue that uses core.async to support async event delivery, delayed event processing,
   and external event queuing.

   NOTE: At present this queue is tied to a single session, and cannot be used for multiple charts at the
   same time (to cross-communicate).

   See `run-event-loop!`.
   "
  []
  (->CoreAsyncQueue (async/chan 1000) (atom {}) (atom #{})))

(defn run-event-loop!
  "Initializes a new session using `sp/start!` on the processor with `session-id`.
   Then runs a continuous loop polling the event-queue for new events and processing them.

   `wmem-atom` is an atom that will be updated with the latest working memory of the state
   machine, and allows you to look at the state of it from outside.  It is safe to read the active states
   from ::sc/configuration of the working memory, but you should leverage the data-model protocol for
   interfacing with the data a machine might need to see or manipulate.

   Returns a channel that will stay open until the session ends."
  [processor wmem-atom session-id extra-env]
  (let [s0  (sp/start! processor session-id)
        {::sc/keys [event-queue] :as base-env} (sp/get-base-env processor)
        env (merge extra-env base-env)]
    (reset! wmem-atom s0)
    (log/trace "Event loop started")
    (async/go-loop []
      (async/<!
        (sp/receive-events! event-queue {:session-id session-id}
          (fn [event]
            (reset! wmem-atom (sp/process-event! processor @wmem-atom event)))))
      (if (::sc/running? @wmem-atom)
        (recur)
        (log/trace "Event loop ended")))))