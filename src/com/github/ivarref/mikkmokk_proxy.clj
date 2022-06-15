(ns com.github.ivarref.mikkmokk-proxy
  (:require [aleph.http :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [lambdaisland.regal :as regal])
  (:import (java.io Closeable)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors)
           (java.util.regex Pattern)
           (sun.misc Signal SignalHandler))
  (:gen-class))


(defn get-env [k v]
  (or (System/getenv k)
      (System/getProperty k v)))

(defonce admin-settings (atom {}))

(defn default-headers []
  {:fail-before-code        503
   :fail-before-percentage  0

   :fail-after-percentage   0
   :fail-after-code         502

   :duplicate-percentage    0

   :delay-before-percentage 0
   :delay-before-ms         0

   :delay-after-percentage  0
   :delay-after-ms          0

   :match-uri               "*"
   :match-method            "*"
   :match-uri-starts-with   "*"

   :match-header-name       "*"
   :match-header-value      "*"

   :destination-url         nil})

(defn env-settings []
  (->> (default-headers)
       (mapcat (fn [[k v]]
                 (let [env-k (-> (name k)
                                 (str/upper-case)
                                 (str/replace "-" "_"))]
                   (when-let [env-v (get-env env-k nil)]
                     [[k (if (or (nil? v) (string? v))
                           env-v
                           (parse-long env-v))]]))))
       (into (sorted-map))))

(defn parse-long-maybe [m k]
  (if-let [v (get m k)]
    (if (string? v)
      (assoc m k (parse-long v))
      m)
    m))

(defn set-string-maybe [m k]
  (if-let [v (get m k)]
    (if (string? v)
      (assoc m k v)
      m)
    m))

(def header-prefix "x-mikkmokk-")

(def body-trailer
  (if
    (= "true" (System/getenv "MIKKMOKK_DEVELOPMENT"))
    "\n"
    ""))

(defn downcase-headers [headers]
  (update-keys headers str/lower-case))

(defn get-mikkmokk-keys [headers]
  (reduce-kv
    (fn [o k v]
      (if-not (str/starts-with? k header-prefix)
        o
        (if (not (contains? (default-headers) (keyword (subs k (count header-prefix)))))
          (do
            (log/warn "Unknown header:" k ", ignoring")
            o)
          (assoc o (keyword (subs k (count header-prefix))) v))))
    {}
    headers))

(defn parse-headers-str-map [headers]
  (->
    (downcase-headers headers)
    (get-mikkmokk-keys)
    (parse-long-maybe :fail-before-percentage)
    (parse-long-maybe :fail-before-code)
    (parse-long-maybe :fail-after-percentage)
    (parse-long-maybe :fail-after-code)
    (parse-long-maybe :delay-before-percentage)
    (parse-long-maybe :delay-before-ms)
    (parse-long-maybe :delay-after-percentage)
    (parse-long-maybe :delay-after-ms)
    (parse-long-maybe :duplicate-percentage)
    (set-string-maybe :destination-url)
    (set-string-maybe :match-uri)
    (set-string-maybe :match-method)
    (set-string-maybe :match-uri-starts-with)
    (select-keys (keys (default-headers)))))

(defn parse-headers [headers]
  (merge-with (fn [a b] (or b a))
              (default-headers)
              (env-settings)
              @admin-settings
              (parse-headers-str-map headers)))


(defn json-kv [k v]
  (str "\""
       (if (keyword? k) (name k) k)
       "\":"
       (cond (int? v)
             v
             (nil? v)
             "null"
             :else
             (str "\"" v "\""))))

(defn matches-uri? [string-pat uri]
  (cond
    (= "*" string-pat)
    true
    (= string-pat uri)
    true
    :else
    false))

(defn matches-uri-starts-with? [s substr]
  (if (= "*" substr)
    true
    (str/starts-with? s substr)))

(defn matches-method? [string-method method-kw]
  (cond
    (= "*" string-method)
    true
    (= (str/upper-case string-method) (str/upper-case (name method-kw)))
    true
    :else
    false))

(defn match-header-kv? [given-headers {:keys [match-header-name match-header-value]}]
  (if (or (= "*" match-header-name)
          (= "*" match-header-value))
    true
    (= match-header-value (get given-headers match-header-name))))

(defn request [request-method url headers body]
  (try
    @(http/request
       {:request-method request-method
        :headers        headers
        :body           body
        :url            url})
    (catch Throwable t
      (let [m (ex-data t)]
        (if (and (map? m) (contains? m :status) (contains? m :headers) (contains? m :body))
          m
          (do
            (log/warn t "Unexpected error when" request-method "for" (str/upper-case (name request-method)) url)
            {:status  500
             :headers {"content-type" "application/json"}
             :body    (str "{"
                           (json-kv "error" "unexpected-error")
                           ","
                           (json-kv "url" url)
                           "}"
                           body-trailer)}))))))

(defn make-request [match? duplicate-percentage request-method uri url headers body]
  (let [req1 (future (request request-method url headers body))
        duplicate? (and (> duplicate-percentage (rand-int 100)) match?)
        req2 (future (when duplicate?
                       (request request-method url headers body)))
        resp1 @req1
        resp2 @req2]
    (if duplicate?
      (if (not= (:status resp1) (:status resp2))
        (log/info "Duplicate request returned different HTTP status codes" (:status resp1) "vs" (:status resp2) "for" (str/upper-case (name request-method)) uri)
        (log/info "Duplicate request returned identical HTTP status code" (:status resp1) "for" (str/upper-case (name request-method)) uri))
      (log/debug "No duplicate request"))
    (rand-nth (filterv some? [resp1 resp2]))))


(defn handler [{:keys [request-method uri headers body]}]
  (let [{:keys [fail-before-percentage
                fail-before-code
                fail-after-percentage
                fail-after-code
                delay-before-percentage
                delay-before-ms
                delay-after-percentage
                delay-after-ms
                duplicate-percentage
                match-uri
                match-method
                match-uri-starts-with
                destination-url] :as parsed-headers} (parse-headers headers)]
    (if (empty? destination-url)
      (do
        (log/warn "missing destination-url")
        {:status  500
         :headers {"content-type" "application/json"}
         :body    (str "{" (json-kv "error" "missing-destination-url") "}" body-trailer)})
      (let [host (second (str/split destination-url (re-pattern (Pattern/quote "://"))))
            method-uri (str (str/upper-case (name request-method)) " " uri)
            dest-headers (assoc headers "host" host)
            url (str destination-url uri)
            match? (and (matches-uri? match-uri uri)
                        (matches-uri-starts-with? uri match-uri-starts-with)
                        (matches-method? match-method request-method)
                        (match-header-kv? headers parsed-headers))
            delay-before-ms (if (and (> delay-before-percentage (rand-int 100)) match?)
                              delay-before-ms
                              0)
            delay-after-ms (if (and (> delay-after-percentage (rand-int 100)) match?)
                             delay-after-ms
                             0)]
        (when (pos-int? delay-before-ms)
          (log/info "before-delay" delay-before-ms "ms")
          (Thread/sleep delay-before-ms))
        (if (and (> fail-before-percentage (rand-int 100)) match?)
          (do
            (log/info "HTTP" fail-before-code method-uri "fail-before")
            {:status  fail-before-code
             :headers {"content-type" "application/json"}
             :body    (str "{" (json-kv "error" "fail-before") "}" body-trailer)})
          (let [{:keys [headers status body]} (make-request match? duplicate-percentage request-method uri url dest-headers body)]
            (when (pos-int? delay-after-ms)
              (log/info "delay-after" delay-after-ms "ms")
              (Thread/sleep delay-after-ms))
            (if (and (> fail-after-percentage (rand-int 100)) match?)
              (do
                (log/info "HTTP" fail-after-code method-uri "fail-after. Destination response code:" status)
                {:status  fail-after-code
                 :headers {"content-type" "application/json"}
                 :body    (str "{"
                               (json-kv "error" "fail-after")
                               ","
                               (json-kv "destination-response-code" status)
                               "}"
                               body-trailer)})
              (do
                (log/info "HTTP" status method-uri "from destination")
                {:status  status
                 :headers headers
                 :body    body}))))))))

(def fwd-http
  (regal/regex
    [:cat
     [:alt
      "/mikkmokk-fwd-"
      "/mikkmokk-forward-"]
     [:capture
      [:alt "http" "https"]]
     "/"
     [:capture
      [:+ [:not \/]]]
     [:?? [:capture
           [:cat "/"
            [:* :any]]]]
     :end]))

(comment
  (re-find fwd-http "/mikkmokk-fwd-http/pvo-backend-service.private.nsd.no"))

(comment
  (re-find fwd-http "/mikkmokk-fwd-http/pvo-backend-service.private.nsd.no/api/w00t"))

(defn outer-handler [{:keys [uri] :as request}]
  (if-let [[_ scheme host uri] (not-empty (re-find fwd-http uri))]
    (let [uri (or uri "/")]
      (handler (-> request
                   (assoc :uri uri)
                   (update :headers downcase-headers)
                   (assoc-in [:headers "x-mikkmokk-destination-url"] (str scheme "://" host)))))
    (handler request)))

(defn admin-map->response [adm]
  (let [adm (into (sorted-map) (merge-with (fn [a b] (or b a))
                                           (default-headers)
                                           (env-settings)
                                           adm))]
    {:status  200
     :headers {"content-type" "application/json"}
     :body    (str "{"
                   (str/join ",\n " (mapv (fn [[k v]] (json-kv k v)) adm))
                   "}" body-trailer)}))

(defn admin-handler [{:keys [headers uri request-method]}]
  (cond
    (and (= request-method :post) (= uri "/api/v1/update"))
    (admin-map->response (swap! admin-settings merge (parse-headers-str-map headers)))

    (and (= request-method :post) (= uri "/api/v1/reset"))
    (admin-map->response (reset! admin-settings (parse-headers-str-map headers)))

    (and (= request-method :get) (= uri "/api/v1/list"))
    (admin-map->response @admin-settings)

    (and (= request-method :get) (= uri "/"))
    {:status  200
     :headers {"content-type" "application/json"}
     :body    (str "{"
                   (json-kv "service" "mikkmokk")
                   "}" body-trailer)}

    (and (= request-method :get) (contains? #{"/health" "/healthcheck"} uri))
    {:status  200
     :headers {"content-type" "application/json"}
     :body    (str "{"
                   (json-kv "service" "mikkmokk") ","
                   (json-kv "status" "healthy")
                   "}" body-trailer)}

    :else {:status  404
           :headers {"content-type" "application/json"}
           :body    (str "{" (json-kv "message" "not-found") "}" body-trailer)}))

(defonce servers (atom {}))

(defn stop! [curr]
  (doseq [[nam inst] (into (sorted-map) curr)]
    (when (and inst (instance? Closeable inst))
      (try
        (.close ^Closeable inst)
        (catch Throwable t
          (log/warn "Could not shutdown" (name nam) ":" (ex-message t))))
      (log/info "Stopped" (name nam) "server")))
  nil)

(defn run-main [_]
  (let [proxy-bind (get-env "PROXY_BIND" "127.0.0.1")
        proxy-port (get-env "PROXY_PORT" "8080")
        admin-bind (get-env "ADMIN_BIND" "127.0.0.1")
        admin-port (get-env "ADMIN_PORT" "7070")]
    (swap! servers
           (fn [curr]
             (stop! curr)
             {:admin (http/start-server (fn [req] (admin-handler req))
                                        {:executor       (Executors/newFixedThreadPool 8)
                                         :socket-address (InetSocketAddress. ^String admin-bind (parse-long admin-port))})
              :proxy (http/start-server (fn [req] (outer-handler req))
                                        {:executor       (Executors/newFixedThreadPool 256)
                                         :socket-address (InetSocketAddress. ^String proxy-bind (parse-long proxy-port))})}))
    (log/info "Started admin server at" (str admin-bind ":" admin-port))
    (log/info "Started proxy server at" (str proxy-bind ":" proxy-port))
    (doseq [[k v] (into (sorted-map) (env-settings))]
      (log/info "env setting" k v))))

(defn -main
  "Main method used to start the system from a JAR file."
  [& _args]
  (let [p (promise)]
    (Signal/handle
      (Signal. "INT")
      (reify SignalHandler
        (handle [_ _]
          (log/debug "Received SIGINT")
          (deliver p :shutdown))))
    (run-main nil)
    @p
    (swap! servers stop!)
    (shutdown-agents)))

(comment
  (run-main {}))

