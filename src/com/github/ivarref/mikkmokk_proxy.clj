(ns com.github.ivarref.mikkmokk-proxy
  (:require [aleph.http :as http]
            [manifold.deferred :as d]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.util.regex Pattern)))


(defn get-env [k v]
  (or (System/getenv k)
      (System/getProperty k v)))

(defn env-settings []
  {:destination-url (get-env "DESTINATION_URL" nil)})

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

   :destination-url         nil})


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
    (get-mikkmokk-keys headers)
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
    (select-keys (keys (default-headers)))))

(defn parse-headers [headers]
  (merge-with (fn [a b] (or b a))
    (env-settings)
    (default-headers)
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

(defn matches-method? [string-method method-kw]
  (cond
    (= "*" string-method)
    true
    (= (str/upper-case string-method) (str/upper-case (name method-kw)))
    true
    :else
    false))

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
        (log/warn "Duplicate request returned different HTTP status codes" (:status resp1) "vs" (:status resp2) "for" (str/upper-case (name request-method)) uri)
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
                destination-url]} (parse-headers headers)
        host (second (str/split destination-url (re-pattern (Pattern/quote "://"))))
        method-uri (str (str/upper-case (name request-method)) " " uri)
        dest-headers (assoc headers "host" host)
        url (str destination-url uri)
        match? (and (matches-uri? match-uri uri)
                    (matches-method? match-method request-method))
        delay-before-ms (if (and (> delay-before-percentage (rand-int 100)) match?)
                          delay-before-ms
                          0)
        delay-after-ms (if (and (> delay-after-percentage (rand-int 100)) match?)
                         delay-after-ms
                         0)]
    (when (pos-int? delay-before-ms)
      (log/info "before-delay" delay-before-ms "ms")
      @(d/timeout! (d/deferred) delay-before-ms nil))
    (if (and (> fail-before-percentage (rand-int 100)) match?)
      (do
        (log/info "HTTP" fail-before-code method-uri "fail-before")
        {:status  fail-before-code
         :headers {"content-type" "application/json"}
         :body    (str "{" (json-kv "error" "fail-before") "}" body-trailer)})
      (let [{:keys [headers status body]} (make-request match? duplicate-percentage request-method uri url dest-headers body)]
        (when (pos-int? delay-after-ms)
          (log/info "delay-after" delay-after-ms "ms")
          (d/timeout! (d/deferred) delay-after-ms))
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
             :body    body}))))))

(defn admin-map->response [adm]
  (let [adm (into (sorted-map) (merge-with (fn [a b] (or b a))
                                           (env-settings)
                                           (default-headers)
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


(comment
  (http/start-server (fn [req] (admin-handler req)) {:port 7070}))

(comment
  (http/start-server (fn [req] (handler (merge nil #_{:destination-url "http://example.com"} req))) {:port 8080}))
