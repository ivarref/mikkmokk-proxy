(ns ivarref.http-test
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [clj-commons.byte-streams :as bs]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.github.ivarref.mikkmokk-proxy :as mm])
  (:import (java.util.concurrent Executors)))

(def ^:dynamic *server-port* nil)
(def ^:dynamic *admin-port* nil)

(defn with-server [f]
  (let [state {:one-off (atom #{})
               :env     {}}
        server (http/start-server (fn [req] (mm/outer-handler state req)) {:executor (Executors/newFixedThreadPool 8) :port 0})
        admin (http/start-server (fn [req] (mm/admin-handler state req)) {:executor (Executors/newFixedThreadPool 2) :port 0})]
    (try
      (with-redefs [mm/single-request (fn [_request-method url headers _body]
                                        {:status  200
                                         :body    url
                                         :headers (dissoc headers "content-length")})]
        (binding [*server-port* (netty/port server)
                  *admin-port* (netty/port admin)]
          (f)))
      (finally
        (.close server)
        (.close admin)))))

(use-fixtures :each with-server)

(defn get-uri [uri headers]
  (try
    (-> @(http/get (str "http://localhost:" *server-port* uri) {:headers headers})
        (update :body bs/to-string))
    (catch Throwable t
      (-> (ex-data t)
          (update :body bs/to-string)))))

(defn post-admin-uri [uri headers]
  (try
    (-> @(http/post (str "http://localhost:" *admin-port* uri) {:headers headers})
        (update :body bs/to-string))
    (catch Throwable t
      (ex-data t))))

(deftest basic
  (is (= 500 (:status (get-uri "/" {}))))
  (is (= "http://example.com/" (:body (get-uri "/" {"x-mikkmokk-destination-url" "http://example.com"}))))
  (is (= 200 (:status (get-uri "/" {"x-mikkmokk-destination-url" "http://example.com"}))))
  (is (= "http://example.com/" (:body (get-uri "/mikkmokk-fwd-http/example.com" {}))))
  (is (= "http://example.com/" (:body (get-uri "/mikkmokk-fwd-http/example.com/" {}))))
  (is (= "http://example.com/" (:body (get-uri "/mikkmokk-forward-http/example.com/" {})))))

(deftest match-uri-starts-with
  (is (= 200 (:status (get-uri "/no-match" {"x-mikkmokk-destination-url"        "http://example.com"
                                            "x-mikkmokk-match-uri-starts-with"  "/match"
                                            "x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 503 (:status (get-uri "/match" {"x-mikkmokk-destination-url"        "http://example.com"
                                         "x-mikkmokk-match-uri-starts-with"  "/match"
                                         "x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 503 (:status (get-uri "/match/more" {"x-mikkmokk-destination-url"        "http://example.com"
                                              "x-mikkmokk-match-uri-starts-with"  "/match"
                                              "x-mikkmokk-fail-before-percentage" "100"})))))

(deftest match-header
  (is (= 200 (:status (get-uri "/" {"x-mikkmokk-destination-url"        "http://example.com"
                                    "x-mikkmokk-match-header-name"      "x-user-id"
                                    "x-mikkmokk-match-header-value"     "some-user-id"
                                    "x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 503 (:status (get-uri "/" {"x-mikkmokk-destination-url"        "http://example.com"
                                    "x-mikkmokk-match-header-name"      "x-user-id"
                                    "x-mikkmokk-match-header-value"     "some-user-id"
                                    "x-user-id"                         "some-user-id"
                                    "x-mikkmokk-fail-before-percentage" "100"})))))


(deftest match-host
  (is (= 200 (:status (get-uri "/mikkmokk-forward-http/example.com/"
                               {"x-mikkmokk-match-host"             "peggy.gmbh.com"
                                "x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 503 (:status (get-uri "/mikkmokk-forward-http/example.com/"
                               {"x-mikkmokk-match-host"             "example.com"
                                "x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 200 (:status (get-uri "/mikkmokk-forward-http/peggy.gmbh.com/some-endpoint"
                               {"x-mikkmokk-match-host"             "peggy.gmbh.com"
                                "x-mikkmokk-match-uri"              "/some-endpoint2"
                                "x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 503 (:status (get-uri "/mikkmokk-forward-http/peggy.gmbh.com/some-endpoint"
                               {"x-mikkmokk-match-host"             "peggy.gmbh.com"
                                "x-mikkmokk-match-uri"              "/some-endpoint"
                                "x-mikkmokk-fail-before-percentage" "100"})))))



(deftest one-off
  (is (= "http://example.com/" (:body (get-uri "/mikkmokk-forward-http/example.com/" {}))))
  (is (= 200 (:status (post-admin-uri "/api/v1/one-off" {"x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 503 (:status (get-uri "/mikkmokk-forward-http/example.com/" {}))))
  (is (= 200 (:status (get-uri "/mikkmokk-forward-http/example.com/" {}))))

  (is (= 200 (:status (post-admin-uri "/api/v1/one-off" {"x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 200 (:status (post-admin-uri "/api/v1/one-off" {"x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 503 (:status (get-uri "/mikkmokk-forward-http/example.com/" {}))))
  (is (= 503 (:status (get-uri "/mikkmokk-forward-http/example.com/" {}))))
  (is (= 200 (:status (get-uri "/mikkmokk-forward-http/example.com/" {}))))

  (is (= 200 (:status (post-admin-uri "/api/v1/one-off" {"x-mikkmokk-match-host"             "example.com"
                                                         "x-mikkmokk-match-uri"              "/some-endpoint"
                                                         "x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 200 (:status (get-uri "/mikkmokk-forward-http/example.com/some-endpoint2" {}))))
  (is (= 200 (:status (get-uri "/mikkmokk-forward-http/peggy.gmbh.com/some-endpoint" {}))))
  (is (= 503 (:status (get-uri "/mikkmokk-forward-http/example.com/some-endpoint" {}))))
  #_(is (= 503 (:status (get-uri "/" {"x-mikkmokk-destination-url"        "http://example.com"
                                      "x-mikkmokk-match-header-name"      "x-user-id"
                                      "x-mikkmokk-match-header-value"     "some-user-id"
                                      "x-user-id"                         "some-user-id"
                                      "x-mikkmokk-fail-before-percentage" "100"})))))

(defn origin [resp]
  (get-in resp [:headers "origin"]))

(deftest modified-headers
  (is (= {"host" "example.com"}
         (-> (get-uri "/mikkmokk-forward-http/example.com/" {})
             :headers
             (select-keys ["host" "origin"]))))
  (is (= {"host"   "example.com"
          "origin" "http://example.com"}
         (-> (get-uri "/mikkmokk-forward-http/example.com/" {"origin" "http://localhost:8090"})
             :headers
             (select-keys ["host" "origin"]))))
  (is (= "http://example.com:8080"
         (origin (get-uri "/mikkmokk-forward-http/example.com:8080/" {"origin" "http://localhost:8090"}))))
  (is (= "https://example.com:8080"
         (origin (get-uri "/mikkmokk-forward-https/example.com:8080/" {"origin" "http://localhost:8090"}))))
  (is (= "https://example.com:8080"
         (origin (get-uri "/mikkmokk-forward-https/example.com:8080/api" {"origin" "http://localhost:8090"})))))

(deftest test-Access-Control-Allow-Origin
  (is (= "http://localhost:8090"
         (-> (get-in (get-uri "/mikkmokk-forward-http/example.com:8080/" {"origin" "http://localhost:8090"
                                                                          "Access-Control-Allow-Origin" "demo"})
                     [:headers "Access-Control-ALLOW-Origin"])))))


(deftest matches-uri-regex
  (is (= 200 (:status (get-uri "/mikkmokk-forward-http/example.com/a123123123"
                               {"x-mikkmokk-match-uri-regex"        "/[0-9]+"
                                "x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 503 (:status (get-uri "/mikkmokk-forward-http/example.com/123123123"
                               {"x-mikkmokk-match-uri-regex"        "/[0-9]+"
                                "x-mikkmokk-fail-before-percentage" "100"}))))
  (is (= 503 (:status (get-uri "/mikkmokk-forward-http/example.com/api/uuid/af9facf3-f679-4245-aa83-1b95cea52a1d"
                               {"x-mikkmokk-match-uri-regex"        "/api/uuid/([a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12})"
                                "x-mikkmokk-fail-before-percentage" "100"})))))
