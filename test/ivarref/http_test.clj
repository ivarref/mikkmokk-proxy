(ns ivarref.http-test
  (:require [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.github.ivarref.mikkmokk-proxy :as mm])
  (:import (java.util.concurrent Executors)))

(defn with-server [f]
  (let [server (http/start-server (fn [req] (mm/outer-handler req)) {:executor (Executors/newFixedThreadPool 8)
                                                                     :port     8090})]
    (try
      (with-redefs [mm/request (fn [_request-method url _headers _body]
                                 {:status  200
                                  :body    url
                                  :headers {}})]
        (f))
      (finally
        (.close server)))))

(use-fixtures :each with-server)

(defn get-uri [uri headers]
  (try
    (-> @(http/get (str "http://localhost:8090" uri) {:headers headers})
        (update :body bs/to-string))
    (catch Throwable t
      (ex-data t))))

(deftest basic
  (is (= 500 (:status (get-uri "/" {}))))
  (is (= "http://example.com/" (:body (get-uri "/" {"x-mikkmokk-destination-url" "http://example.com"}))))
  (is (= 200 (:status (get-uri "/" {"x-mikkmokk-destination-url" "http://example.com"}))))
  (is (= "http://example.com/" (:body (get-uri "/mikkmokk-fwd-http/example.com" {}))))
  (is (= "http://example.com/" (:body (get-uri "/mikkmokk-fwd-http/example.com/" {})))))

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
