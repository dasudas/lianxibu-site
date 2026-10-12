package fetcher

import (
	"compress/gzip"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type fakeResolver map[string][]net.IPAddr

func (r fakeResolver) LookupIPAddr(_ context.Context, host string) ([]net.IPAddr, error) {
	return r[host], nil
}

func stub(t *testing.T, handler http.HandlerFunc) (*Fetcher, *httptest.Server, *[]string) {
	t.Helper()
	server := httptest.NewUnstartedServer(handler)
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	certTemplate := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "fixture"},
		DNSNames: []string{"feed.example.test", "cdn.example.test", "api.github.com"}, NotBefore: time.Now().Add(-time.Hour),
		NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, certTemplate, certTemplate, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := tls.X509KeyPair(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}))
	if err != nil {
		t.Fatal(err)
	}
	server.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	server.StartTLS()
	t.Cleanup(server.Close)
	// Parse the issued certificate to trust only this test server.
	issued, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(issued)
	f := New()
	// The TLS stub dials locally while DNS returns a documentation-only address.
	// Production Fetchers keep the publicIP validator installed by New.
	f.allowIP = func(address netip.Addr) bool { return address.Unmap() == netip.MustParseAddr("192.0.2.1") }
	f.roots = pool
	f.resolver = fakeResolver{
		"feed.example.test":  {{IP: net.ParseIP("192.0.2.1")}},
		"cdn.example.test":   {{IP: net.ParseIP("192.0.2.1")}},
		"other.example.test": {{IP: net.ParseIP("192.0.2.1")}},
	}
	addresses := &[]string{}
	var mu sync.Mutex
	f.dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		mu.Lock()
		*addresses = append(*addresses, address)
		mu.Unlock()
		return (&net.Dialer{}).DialContext(ctx, network, server.Listener.Addr().String())
	}
	return f, server, addresses
}

// LoopbackFixture is linked only into the external integration test binary.
func LoopbackFixture(t *testing.T, handler http.HandlerFunc) *Fetcher {
	f, _, _ := stub(t, handler)
	f.resolver.(fakeResolver)["api.github.com"] = []net.IPAddr{{IP: net.ParseIP("192.0.2.1")}}
	return f
}

func fixtureRequest() Request {
	return Request{SourceID: "fixture-source", URL: "https://feed.example.test/rss",
		AllowedHosts: []string{"feed.example.test", "cdn.example.test"}, Format: RSS}
}

func TestOptionalNotFoundRequiresOptInAndPreservesResponseBounds(t *testing.T) {
	for _, scenario := range []struct {
		name       string
		allow      bool
		status     int
		mime, body string
		accept     bool
	}{
		{"optional", true, 404, "application/json", `{"message":"Not Found"}`, true},
		{"ordinary", false, 404, "application/json", `{}`, false},
		{"forbidden", true, 403, "application/json", `{}`, false},
		{"server", true, 500, "application/json", `{}`, false},
		{"binary", true, 404, "application/octet-stream", `{}`, false},
		{"oversize", true, 404, "application/json", strings.Repeat("x", maxBodyBytes+1), false},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			f, _, _ := stub(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", scenario.mime)
				w.WriteHeader(scenario.status)
				w.Write([]byte(scenario.body))
			})
			request := fixtureRequest()
			request.Format = JSON
			request.AllowNotFound = scenario.allow
			result, err := f.Fetch(context.Background(), request)
			if scenario.accept {
				if err != nil || result.StatusCode != 404 || string(result.Body) != scenario.body || result.FinalURL != request.URL {
					t.Fatal(result, err)
				}
			} else if err == nil {
				t.Fatal("unsafe response accepted")
			}
		})
	}
}

func TestVerifiedTLSPinnedDNSAndCrossHostRedirectDropsAuthorization(t *testing.T) {
	var initialAuth, redirectedAuth string
	f, _, dials := stub(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Host == "feed.example.test" {
			initialAuth = r.Header.Get("Authorization")
			http.Redirect(w, r, "https://cdn.example.test/feed", http.StatusFound)
			return
		}
		redirectedAuth = r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "application/atom+xml")
		w.Header().Set("ETag", "fixture-etag")
		w.Write([]byte("<feed><title>Fixture</title></feed>"))
	})
	input := fixtureRequest()
	input.Authorization = "Bearer fictional-source-token"
	result, err := f.Fetch(context.Background(), input)
	if err != nil {
		t.Fatal(err)
	}
	if result.StatusCode != 200 || result.ETag != "fixture-etag" || result.FinalURL != "https://cdn.example.test/feed" ||
		!strings.Contains(string(result.Body), "Fixture") {
		t.Fatalf("unexpected result: %+v", result)
	}
	if initialAuth != input.Authorization || redirectedAuth != "" {
		t.Fatal("source authorization crossed a host boundary")
	}
	if len(*dials) != 2 || (*dials)[0] != "192.0.2.1:443" || (*dials)[1] != "192.0.2.1:443" {
		t.Fatalf("connection was not pinned to validated address: %v", *dials)
	}
}

func TestPersistentBudgetContinuesRedirectWithoutRestartingOriginalURL(t *testing.T) {
	var original, destination int
	f := LoopbackFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/old" {
			original++
			http.Redirect(w, r, "https://api.github.com/new", http.StatusMovedPermanently)
			return
		}
		destination++
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"fixture":true}`))
	})
	var err error
	f.budget, err = openBudget(filepath.Join(t.TempDir(), "budget.db"), time.Now)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	result, err := f.Fetch(context.Background(), Request{SourceID: "fictional-source", URL: "https://api.github.com/old", AllowedHosts: []string{"api.github.com"}, Format: JSON})
	if err != nil || result.FinalURL != "https://api.github.com/new" || original != 1 || destination != 1 {
		t.Fatalf("redirect failed: original=%d destination=%d error=%v", original, destination, err)
	}
	var used int
	if err = f.budget.db.QueryRow("SELECT used FROM host_budget WHERE host='api.github.com'").Scan(&used); err != nil || used != 2 {
		t.Fatalf("redirect budget incorrect: %d %v", used, err)
	}
}

func TestRedirectCannotWaitThroughLongPersistedPause(t *testing.T) {
	requests := 0
	f := LoopbackFixture(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		w.Header().Set("X-RateLimit-Remaining", "0")
		http.Redirect(w, r, "https://api.github.com/new", http.StatusMovedPermanently)
	})
	var err error
	f.budget, err = openBudget(filepath.Join(t.TempDir(), "budget.db"), time.Now)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, err = f.Fetch(ctx, Request{SourceID: "fictional-source", URL: "https://api.github.com/old", AllowedHosts: []string{"api.github.com"}, Format: JSON})
	var deferred *HostDeferredError
	if !errors.As(err, &deferred) || deferred.RetryAt.Before(time.Now().Add(59*time.Minute)) || requests != 1 {
		t.Fatalf("pause ignored: requests=%d error=%v", requests, err)
	}
}

func TestRejectsPrivateOrMixedDNSAndHostEscape(t *testing.T) {
	f, _, dials := stub(t, func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "https://evil.example.test/feed", http.StatusFound)
	})
	input := fixtureRequest()
	f.resolver = fakeResolver{"feed.example.test": {{IP: net.ParseIP("127.0.0.1")}}}
	if _, err := f.Fetch(context.Background(), input); err == nil {
		t.Fatal("loopback DNS accepted")
	}
	f.resolver = fakeResolver{"feed.example.test": {{IP: net.ParseIP("192.0.2.1")},
		{IP: net.ParseIP("169.254.169.254")}}}
	if _, err := f.Fetch(context.Background(), input); err == nil {
		t.Fatal("mixed public/private DNS accepted")
	}
	f.resolver = fakeResolver{"feed.example.test": {{IP: net.ParseIP("192.0.2.1")}}}
	if _, err := f.Fetch(context.Background(), input); err == nil {
		t.Fatal("unlisted redirect host accepted")
	}
	if len(*dials) != 1 {
		t.Fatalf("unexpected follow-up dial: %v", *dials)
	}
}

func TestDefaultTLSVerificationAndUnsafeURLs(t *testing.T) {
	f, _, _ := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/rss+xml")
		w.Write([]byte("<rss/>"))
	})
	input := fixtureRequest()
	input.URL = "https://other.example.test/feed"
	input.AllowedHosts = []string{"other.example.test"}
	if _, err := f.Fetch(context.Background(), input); err == nil {
		t.Fatal("TLS name mismatch accepted")
	}
	for _, candidate := range []string{"http://feed.example.test/rss", "https://feed.example.test:8443/rss",
		"https://name:password@feed.example.test/rss", "https://feed.example.test/file.zip",
		"https://feed.example.test/weights.safetensors"} {
		input = fixtureRequest()
		input.URL = candidate
		if _, err := f.Fetch(context.Background(), input); err == nil {
			t.Fatalf("unsafe URL accepted: %s", candidate)
		}
	}
}

func TestRejectsDTDSizeExpansionAndBinaryRepresentation(t *testing.T) {
	mode := "dtd"
	f, _, _ := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		switch mode {
		case "dtd":
			w.Header().Set("Content-Type", "application/rss+xml")
			w.Write([]byte("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss/>"))
		case "gzip":
			w.Header().Set("Content-Type", "application/rss+xml")
			w.Header().Set("Content-Encoding", "gzip")
			writer := gzip.NewWriter(w)
			writer.Write([]byte(strings.Repeat("x", maxBodyBytes+1)))
			writer.Close()
		case "binary":
			w.Header().Set("Content-Type", "application/octet-stream")
			w.Write([]byte("fixture"))
		case "disguised-archive":
			w.Header().Set("Content-Type", "application/rss+xml")
			w.Write([]byte{'P', 'K', 3, 4, 0, 0})
		}
	})
	for _, value := range []string{"dtd", "gzip", "binary", "disguised-archive"} {
		mode = value
		if _, err := f.Fetch(context.Background(), fixtureRequest()); err == nil {
			t.Fatalf("%s response accepted", value)
		}
	}
}

func TestPerSourceGlobalLimitsAndCancellation(t *testing.T) {
	f := New()
	ctx := context.Background()
	releaseA, err := f.acquire(ctx, "a", 100*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	releaseB, err := f.acquire(ctx, "b", 0)
	if err != nil {
		t.Fatal(err)
	}
	shortA, cancelA := context.WithTimeout(ctx, 10*time.Millisecond)
	defer cancelA()
	if _, err := f.acquire(shortA, "a", 0); err == nil {
		t.Fatal("same-source overlap accepted")
	}
	shortC, cancelC := context.WithTimeout(ctx, 10*time.Millisecond)
	defer cancelC()
	if _, err := f.acquire(shortC, "c", 0); err == nil {
		t.Fatal("global third request accepted")
	}
	releaseA()
	releaseB()
	shortGap, cancelGap := context.WithTimeout(ctx, 10*time.Millisecond)
	defer cancelGap()
	if _, err := f.acquire(shortGap, "a", 0); err == nil {
		t.Fatal("source minimum interval skipped")
	}
}

func TestContextDeadlineAndRedirectCount(t *testing.T) {
	mode := "slow"
	f, _, _ := stub(t, func(w http.ResponseWriter, r *http.Request) {
		if mode == "slow" {
			<-r.Context().Done()
			return
		}
		http.Redirect(w, r, "https://feed.example.test/rss", http.StatusFound)
	})
	short, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, err := f.Fetch(short, fixtureRequest()); err == nil {
		t.Fatal("parent deadline ignored")
	}
	mode = "redirect"
	if _, err := f.Fetch(context.Background(), fixtureRequest()); err == nil {
		t.Fatal("redirect cap ignored")
	}
}

func TestPublicAddressClassification(t *testing.T) {
	for _, value := range []string{"127.0.0.1", "10.1.2.3", "169.254.169.254", "192.0.2.1",
		"198.51.100.2", "203.0.113.2", "::1", "fc00::1", "2001:db8::1"} {
		if publicIP(netip.MustParseAddr(value)) {
			t.Fatalf("forbidden address accepted: %s", value)
		}
	}
	if !publicIP(netip.AddrFrom4([4]byte{11, 22, 33, 44})) {
		t.Fatal("synthetic global-unicast address rejected")
	}
}
