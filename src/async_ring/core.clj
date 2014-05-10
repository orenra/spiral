(ns async-ring.core
  (:require [clojure.core.async :as async]
            [clojure.stacktrace]
            [compojure.core :refer (GET defroutes)]
            [ring.middleware.params :refer (wrap-params)]
            [hiccup.core :refer (html)]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as http-kit]))

;;; The fundamental unit is a channel. It expects to recieve Ring request maps, which each contain
;;; 2 extra keys: `:async-response` and `:async-error`, which contain channels onto which you can
;;; put Ring response maps or Exceptions, respectively.
;;;

(defn to-httpkit
  [req-chan]
  (fn httpkit-adapter [req]
    (println "lolhi")
    (flush)
    (http-kit/with-channel req http-kit-chan
      (let [resp-chan (async/chan)
            error-chan (async/chan)]
        (async/>!! req-chan (assoc req
                                   :async-response resp-chan
                                   :async-error error-chan))
        (async/go
          (async/alt!
            resp-chan ([resp]
                       (http-kit/send! http-kit-chan resp))
            error-chan ([e]
                        (clojure.stacktrace/print-cause-trace e)
                        (log/error e))))))))

(defn async->sync-adapter
  "Takes an async ring handler and coverts into a sync handler"
  [async-middleware]
  (fn ring-async-middleware-adapter [req]
    (let [resp-chan (async/chan)
          error-chan (async/chan)]
      (async/go (async/>! async-middleware
                          (assoc req
                                 :async-response resp-chan
                                 :async-error error-chan)))
      (async/alt!!
        resp-chan ([resp] resp)
        error-chan ([e] (throw e))))))

(defn sync->async-adapter
  "TODO: expose paralellism"
  [handler]
  (let [req-chan (async/chan)
        parallelism 5]
    (dotimes [i parallelism]
      (async/go
        (while true
          (let [req (async/<! req-chan)]
            (try
                (print (str "Making an attempt!"))
                (flush)
              (let [resp (handler req)]
                (print (str "Handler returned " resp))
                (flush)
                (if resp
                  (async/>! (:async-response req) resp)
                  (async/>! (:async-error req)
                            (ex-info "Handler returned null"
                                     {:req req :handler handler}))))
              (catch Exception e
                (async/>! (:async-error req) e)))))))
    req-chan))

(defn sync->async-middleware
  [async-middleware middleware & args]
  (let [handler (async->sync-adapter async-middleware)]
    (sync->async-adapter (apply middleware handler args))))

(defn constant-response
  [response]
  (let [req-chan (async/chan)]
    (async/go
      (while true
        (async/>! (:async-response (async/<! req-chan)) response)))
    req-chan))

#_(defn async-middleware
  [h & {request-transform :req
        response-tranform :resp
        error-transform :error
        parallelism :parallism
        :or {parallelism 5}}]
  (let [req-chan (async/chan)]
    (dotimes [i parallelism]
      (async/go
        (while true
          (let [request-map (async/<! req-chan)
                resp-chan (async/chan)
                error-chan (async/chan)
                tranformed-request (request-transform request-map)]
            (async/go (async/alt!
                        resp-chan ([resp] ((or response-tranform identity)
                                           (async/>! (:async-response request-map) resp)))
                        error-chan ([e] ((or error-transform identity)
                                         (async/>! (:async-error request-map) e)))))
            ;; nil = short circuit
            (when tranformed-request
              (async/>! h
                        (assoc tranformed-request
                               :async-response resp-chan
                               :async-error error-chan)))))))
    req-chan))

(defn work-shed
  "Sheds work when there are more than `threshold-concurrency` number
   of outstanding requests"
  [async-middleware threshold-concurrency]
  (let [req-chan (async/chan)
        outstanding (atom 0)]
    (async/go
      (while true
        (let [req (async/<! req-chan)
              resp-chan (async/chan)
              error-chan (async/chan)]
          (swap! outstanding inc)
          (when (< @outstanding threshold-concurrency)
            (async/go (async/alt!
                        resp-chan ([resp] (swap! outstanding dec) (async/>! (:async-response req) resp))
                        error-chan ([e] (swap! outstanding dec) (async/>! (:async-error req) e))))
            (async/>! async-middleware
                      (assoc req
                             :async-response resp-chan
                             :async-error error-chan))))))
    req-chan))

#_(defn work-shed
  [h threshold-concurrency]
  (let [outstanding (atom 0)
        bouncer (constant-response [:status 503 :body "overload"])]
    (async-middleware
      h
      :req (fn [req next-chan]
             (swap! outstanding inc)
             (if (< @outstanding threshold-concurrency)
               req
               (do
                 (async/>!! bouncer)
                 nil)))
      :resp (fn [resp] (swap! outstanding dec) resp)
      :error (fn [e] (swap! outstanding dec) e))))


(defroutes ring-app
  (GET "/" [q]
       (html [:html
              [:body
               (concat
                 (when q
                   [[:p (str "To " q)]])
                 [[:p
                   "Hello world, from Ring"]])]])))


(def app
  (-> ;(constant-response {:status 404 :body "nononon!"})
      (sync->async-adapter #'ring-app)
      (sync->async-middleware wrap-params)
      ;(work-shed 100)
      (to-httpkit)))

(comment
  (def server (http-kit/run-server #'app {:port 8080}))

  (server)
  )
