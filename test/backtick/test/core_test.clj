(ns backtick.test.core-test
  (:require
   [backtick.core :refer :all]
   [backtick.db :as db]
   [backtick.test.fixtures :refer [wrap-clean-data]]
   [clj-time.core :as t]
   [clj-time.coerce :as tc]
   [clojure.edn :as edn]
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]))

(use-fixtures :each wrap-clean-data)

(deftest register-test
  (let [nm (str "test-worker-" (rand-int 100))
        missing (str "missing-worker-" (rand-int 100))
        tag (rand-int 100)]
    (register nm (fn [] tag))
    (let [cf (get (registered-workers) nm)]
      (is (= tag (cf)))
      (is (nil? (get (registered-workers) missing))))))

(deftest registered-workers-test
  (with-redefs [backtick.engine/workers (atom {})]
    (let [f1 (fn [] :foo)
          f2 (fn [] :bar)]
      (register "foo" f1)
      (register "bar" f2)
      (is (= {"foo" f1 "bar" f2}
             (registered-workers))))))

(def my-atom (atom nil))

(define-worker my-test-worker [a b]
  (reset! my-atom (+ a b)))

(define-recurring my-test-cron (* 1234 100 60) []
  (reset! my-atom 'cron))

(defn schedule-test-job-0 []
  (+ 1 2))

(defn schedule-test-job-1 [x]
  x)

(defn- db-query-bt-queue []
  (jdbc/query db/spec "SELECT * FROM backtick_queue ORDER BY id ASC"))

(deftest schedule-test
  (register "schedule-test-job-0" schedule-test-job-0)
  (schedule schedule-test-job-0)
  (register "schedule-test-job-1" schedule-test-job-1)
  (schedule schedule-test-job-1 88)
  (schedule my-test-worker 2 3)
  (let [jobs (db-query-bt-queue)]
    (is (every? (comp nil? :run_at) jobs))
    (is (= [nil [88] [2 3]] (->> jobs (map :data) (map edn/read-string))))))

(deftest schedule-at-test
  (let [now (t/now)
        later (t/plus (t/now) (t/hours 1))
        laterer (t/plus (t/now) (t/days 1))]
    (register "at-job-test-0" schedule-test-job-0)
    (schedule-at now schedule-test-job-0)
    (register "at-job-test-1" schedule-test-job-1)
    (schedule-at later schedule-test-job-1 88)
    (schedule-at laterer my-test-worker 2 3)
    (let [jobs (db-query-bt-queue)]
      (is (= (map tc/to-sql-time [now later laterer]) (map :run_at jobs)))
      (is (= [nil [88] [2 3]] (->> jobs (map :data) (map edn/read-string)))))))

(deftest schedule-recurring-test
  (let [nm (str "test-worker-" (rand-int 100))
        missing (str "missing-worker-" (rand-int 100))
        tag (rand-int 100)]
    ;; new job
    (register nm (fn [] tag))
    (schedule-recurring 98700 nm)
    (let [cf (get (registered-workers) nm)
          cron1 (get (recurring-jobs) nm)]
      (is (= tag (cf)))
      (is (nil? (get (registered-workers) missing)))
      (is (= 98700 (:interval cron1))))
    ;; new interval
    (register nm (fn [] (inc tag)))
    (schedule-recurring 98701 nm)
    (let [cf (get (registered-workers) nm)
          cron2 (get (recurring-jobs) nm)]
      (is (= (inc tag) (cf)))
      (is (= 98701 (:interval cron2)))
      ;; same interval
      (register nm (fn [] (+ tag 2)))
      (schedule-recurring 98701 nm)
      (let [cf (get (registered-workers) nm)
            cron3 (get (recurring-jobs) nm)]
        (is (= (+ tag 2) (cf)))
        (is (= cron2 cron3))))))
