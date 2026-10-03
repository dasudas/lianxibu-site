import http.server
import threading
import unittest
import load_read

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(503 if self.path == "/reports" else 200)
        self.send_header("Content-Length", "2")
        self.end_headers(); self.wfile.write(b"{}")
    def log_message(self, *args): pass

class LoadReadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True); cls.thread.start()
        cls.base = "http://127.0.0.1:" + str(cls.server.server_port)
    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join()
    def test_mixed_statuses_do_not_infer_acceptance_without_conditions(self):
        result = load_read.run(self.base, ["/home", "/reports"], 3, .12, 1, .001)
        self.assertGreater(result["requests"], 3)
        self.assertGreater(result["errors"], 0)
        self.assertEqual(result["errors"], result["status_counts"]["503"])
        self.assertFalse(result["criteria_evaluated"]); self.assertIsNone(result["criteria_met"])
        self.assertLessEqual(result["peak_inflight_requests"], 3)
        self.assertFalse(result["capacity_qualified"])
    def test_explicit_criteria_fail_on_errors_but_never_qualify_capacity(self):
        result = load_read.run(self.base, ["/reports"], 2, .08, 1, .001, "fictional-loopback", 0, 1000)
        self.assertTrue(result["criteria_evaluated"]); self.assertFalse(result["criteria_met"])
        self.assertEqual(1, result["error_rate"]); self.assertFalse(result["capacity_qualified"])
    def test_closed_paths_credentials_and_unbounded_options_reject(self):
        for base, path in [("http://example.invalid", "/home"), ("https://owner:secret@example.invalid", "/home"), (self.base, "/admin/jobs"), (self.base, "/reports?token=fictional")]:
            with self.assertRaises(ValueError): load_read.target(base, path)
        with self.assertRaises(ValueError): load_read.run(self.base, ["/home"], 201, .1, 1, .001)
        with self.assertRaises(ValueError): load_read.run(self.base, ["/home"], 2, float("nan"), 1, .001)

if __name__ == "__main__": unittest.main()
