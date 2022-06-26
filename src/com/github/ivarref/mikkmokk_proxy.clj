(ns com.github.ivarref.mikkmokk-proxy
  (:require [aleph.http :as http]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.term.colors :as colors]
            [clojure.tools.logging :as log]
            [dom-top.core :refer [letr]]
            [lambdaisland.regal :as regal])
  (:import (java.io Closeable)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors)
           (java.util.regex Pattern)
           (sun.misc Signal SignalHandler))
  (:gen-class))

(declare return)

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
   :match-uri-regex         "*"
   :match-method            "*"
   :match-uri-starts-with   "*"

   :match-host              "*"

   :match-header-name       "*"
   :match-header-value      "*"

   :destination-url         nil})

(defn color-code [code]
  (cond (< code 400)
        (colors/green (str code))

        (< code 500)
        (colors/yellow (str code))

        :else
        (colors/red (str code))))

(defn parse-items [headers]
  (reduce-kv
    (fn [o k v]
      (let [vv (get headers k)]
        (if (string? vv)
          (if (or (nil? v) (string? v))
            (assoc o k vv)
            (assoc o k (parse-long vv)))
          o)))
    {}
    (default-headers)))

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
    (parse-items)))

(defn parse-headers [env headers]
  (merge-with (fn [a b] (or b a))
              (default-headers)
              env
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

(defn matches-uri-regex? [pat uri]
  (cond
    (= "*" pat)
    true
    (re-matches (Pattern/compile pat) uri)
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

(defn single-request [request-method url headers body]
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
  (let [req1 (future (single-request request-method url headers body))
        duplicate? (and (> duplicate-percentage (rand-int 100)) match?)
        req2 (future (when duplicate?
                       (single-request request-method url headers body)))
        resp1 @req1
        resp2 @req2]
    (if duplicate?
      (if (not= (:status resp1) (:status resp2))
        (log/info "Duplicate request returned different HTTP status codes" (color-code (:status resp1)) "vs" (color-code (:status resp2)) "for" (str/upper-case (name request-method)) url)
        (log/info "Duplicate request returned identical HTTP status code" (color-code (:status resp1)) "for" (str/upper-case (name request-method)) url))
      (log/debug "No duplicate request"))
    (rand-nth (filterv some? [resp1 resp2]))))

(defn destination-url->host [destination-url]
  (second (str/split destination-url (re-pattern (Pattern/quote "://")))))

(defn destination-url->scheme [destination-url]
  (first (str/split destination-url (re-pattern (Pattern/quote "://")))))

(defn matches-host? [match-host destination-url]
  (when destination-url
    (if (= "*" match-host)
      true
      (= (destination-url->host destination-url) match-host))))

(defn matches? [{:keys [request-method uri headers]}
                {:keys [destination-url]}
                {:keys [match-uri match-uri-regex match-host match-uri-starts-with match-method] :as parsed-headers}]
  (and (matches-uri? match-uri uri)
       (matches-uri-regex? match-uri-regex uri)
       (matches-host? match-host destination-url)
       (matches-uri-starts-with? uri match-uri-starts-with)
       (matches-method? match-method request-method)
       (match-header-kv? headers parsed-headers)))

(defn maybe-disj-one-off [request def-headers one-off-set]
  (let [match (->> one-off-set
                   (filter (partial matches? request def-headers))
                   (first))]
    (disj one-off-set match)))

(defn maybe-pop-one-off! [one-off request def-headers]
  (let [[old new] (swap-vals! one-off (partial maybe-disj-one-off request def-headers))]
    (if-some [found (first (set/difference old new))]
      (assoc found :destination-url (:destination-url def-headers))
      def-headers)))


(defn handler [{:keys [env one-off]} {:keys [request-method uri headers body] :as request}]
  (letr [{:keys [fail-before-percentage
                 fail-before-code
                 fail-after-percentage
                 fail-after-code
                 delay-before-percentage
                 delay-before-ms
                 delay-after-percentage
                 delay-after-ms
                 duplicate-percentage
                 destination-url] :as parsed-headers} (maybe-pop-one-off! one-off request (parse-headers env headers))
         _ (when (empty? destination-url)
             (let [error-code 500]
               (log/warn "HTTP" (color-code error-code) (str/upper-case (name request-method))
                         uri "Missing destination-url, returning" (color-code error-code))
               (return {:status  error-code
                        :headers {"content-type" "application/json"}
                        :body    (str "{" (json-kv "error" "missing-destination-url") "}" body-trailer)})))
         method-uri-from (str (str/upper-case (name request-method)) " " uri " from " (destination-url->host destination-url))
         dest-headers (-> headers
                          (assoc "host" (destination-url->host destination-url))
                          (merge
                            (when (not-empty (get headers "origin"))
                              {"origin" (str (destination-url->scheme destination-url)
                                             "://"
                                             (destination-url->host destination-url))})))
         url (str destination-url uri)
         match? (matches? request parsed-headers parsed-headers)
         delay-before-ms (if (and (> delay-before-percentage (rand-int 100)) match?)
                           delay-before-ms
                           0)
         delay-after-ms (if (and (> delay-after-percentage (rand-int 100)) match?)
                          delay-after-ms
                          0)
         _ (when (pos-int? delay-before-ms)
             (log/info "before-delay" delay-before-ms "ms")
             (Thread/sleep delay-before-ms))
         _ (when (and (> fail-before-percentage (rand-int 100)) match?)
             (log/info "HTTP" (color-code fail-before-code) method-uri-from "fail-before")
             (return {:status  fail-before-code
                      :headers {"content-type" "application/json"}
                      :body    (str "{" (json-kv "error" "fail-before") "}" body-trailer)}))
         {:keys [headers status body]} (make-request match? duplicate-percentage request-method uri url dest-headers body)
         _ (when (pos-int? delay-after-ms)
             (log/info "delay-after" delay-after-ms "ms")
             (Thread/sleep delay-after-ms))
         _ (when (and (> fail-after-percentage (rand-int 100)) match?)
             (log/info "HTTP" (color-code fail-after-code) method-uri-from "fail-after. Destination response code:" (color-code status))
             (return {:status  fail-after-code
                      :headers {"content-type" "application/json"}
                      :body    (str "{" (json-kv "error" "fail-after") ","
                                    (json-kv "destination-response-code" status) "}"
                                    body-trailer)}))
         _ (if (or (= 0
                      fail-before-percentage
                      fail-after-percentage
                      duplicate-percentage
                      delay-before-percentage
                      delay-after-percentage)
                   (not match?))
             (log/info "HTTP" (color-code status) (str method-uri-from ". No match / all percentages were zero."))
             (log/info "HTTP" (color-code status) method-uri-from))]
    {:status  status
     :headers headers
     :body    body}))

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

(defn outer-handler [cfg {:keys [uri] :as request}]
  (if-let [[_ scheme host uri] (not-empty (re-find fwd-http uri))]
    (let [uri (or uri "/")]
      (handler cfg (-> request
                       (assoc :uri uri)
                       (update :headers downcase-headers)
                       (assoc-in [:headers "x-mikkmokk-destination-url"] (str scheme "://" host)))))
    (handler cfg request)))

(defn admin-map->response [env adm]
  (let [adm (into (sorted-map) (merge-with (fn [a b] (or b a))
                                           (default-headers)
                                           env
                                           adm))]
    {:status  200
     :headers {"content-type" "application/json"}
     :body    (str "{"
                   (str/join ",\n " (mapv (fn [[k v]] (json-kv k v)) adm))
                   "}" body-trailer)}))

(defn admin-handler [{:keys [one-off env]} {:keys [headers uri request-method]}]
  (cond
    (and (= request-method :post) (= uri "/api/v1/update"))
    (admin-map->response env (swap! admin-settings merge (parse-headers-str-map headers)))

    (and (= request-method :post) (= uri "/api/v1/one-off"))
    (do
      (swap! one-off conj (-> (merge-with
                                (fn [a b] (or b a))
                                {:one-off/id (random-uuid)}
                                (default-headers)
                                (parse-headers-str-map headers))
                              (dissoc :destination-url)))
      {:status  200
       :headers {"content-type" "application/json"}
       :body    (str "{"
                     (json-kv "service" "mikkmokk") ","
                     (json-kv "message" "Added one-off")
                     "}" body-trailer)})

    (and (= request-method :post) (= uri "/api/v1/list-headers"))
    (do
      (doseq [header-key (sort (keys headers))]
        (when (str/starts-with? (str/lower-case header-key) "x-mikkmokk-")
          (log/info "x-mikkmokk- Header" header-key "=>" (get headers header-key))))
      (doseq [header-key (sort (keys headers))]
        (when (not (str/starts-with? (str/lower-case header-key) "x-mikkmokk-"))
          (log/info "Other header" header-key "=>" (get headers header-key))))
      {:status  200
       :headers {"content-type" "application/json"}
       :body    (str "["
                     (str/join ", "
                               (mapv #(str "\"" % "\"")
                                     (sort (keys headers))))
                     "]"
                     body-trailer)})

    (and (= request-method :post) (= uri "/api/v1/reset"))
    (admin-map->response env (reset! admin-settings (parse-headers-str-map headers)))

    (and (= request-method :get) (= uri "/api/v1/list"))
    (admin-map->response env @admin-settings)

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
        admin-port (get-env "ADMIN_PORT" "7070")
        one-off (atom #{})
        env (env-settings)
        cfg {:one-off one-off
             :env     env}]
    (swap! servers
           (fn [curr]
             (stop! curr)
             {:admin (http/start-server (fn [req] (admin-handler cfg req))
                                        {:executor       (Executors/newFixedThreadPool 8)
                                         :socket-address (InetSocketAddress. ^String admin-bind (parse-long admin-port))})
              :proxy (http/start-server (fn [req] (outer-handler cfg req))
                                        {:executor       (Executors/newFixedThreadPool 256)
                                         :socket-address (InetSocketAddress. ^String proxy-bind (parse-long proxy-port))})}))
    (log/info "Started admin server at" (str admin-bind ":" admin-port))
    (log/info "Started proxy server at" (str proxy-bind ":" proxy-port))
    (doseq [[k v] (into (sorted-map) env)]
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

