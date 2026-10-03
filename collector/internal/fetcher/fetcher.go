// Package fetcher performs bounded, pinned-IP HTTPS source reads. It never executes source content.
package fetcher

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"path"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	maxBodyBytes   = 2 << 20
	maxRedirects   = 3
	requestTimeout = 30 * time.Second
	connectTimeout = 5 * time.Second
)

type Format string

const (
	RSS  Format = "rss"
	Atom Format = "atom"
	JSON Format = "json"
	Text Format = "text"
)

type Request struct {
	SourceID        string
	URL             string
	AllowedHosts    []string
	Format          Format
	Authorization   string
	MinInterval     time.Duration
	IfNoneMatch     string
	IfModifiedSince string
	// Only explicitly optional documents may return a bounded 404 representation.
	AllowNotFound bool
}

type Result struct {
	StatusCode   int
	Body         []byte
	ContentType  string
	ETag         string
	LastModified string
	FinalURL     string
}

type resolver interface {
	LookupIPAddr(context.Context, string) ([]net.IPAddr, error)
}

type dialFunc func(context.Context, string, string) (net.Conn, error)

type sourceLimit struct {
	permit chan struct{}
	next   time.Time
}

type Fetcher struct {
	resolver resolver
	dial     dialFunc
	roots    *x509.CertPool
	allowIP  func(netip.Addr) bool
	global   chan struct{}
	mu       sync.Mutex
	sources  map[string]*sourceLimit
}

func New() *Fetcher {
	dialer := &net.Dialer{Timeout: connectTimeout}
	return &Fetcher{resolver: net.DefaultResolver, dial: dialer.DialContext, allowIP: publicIP,
		global: make(chan struct{}, 2), sources: make(map[string]*sourceLimit)}
}

func (f *Fetcher) Fetch(parent context.Context, input Request) (Result, error) {
	if f == nil || f.resolver == nil || f.dial == nil || f.allowIP == nil || f.global == nil || input.SourceID == "" ||
		input.MinInterval < 0 || len(input.AllowedHosts) == 0 || !validFormat(input.Format) ||
		!safeHeader(input.Authorization) || !safeHeader(input.IfNoneMatch) || !safeHeader(input.IfModifiedSince) {
		return Result{}, errors.New("invalid fetch request")
	}
	allowed, err := hostSet(input.AllowedHosts)
	if err != nil {
		return Result{}, err
	}
	ctx, cancel := context.WithTimeout(parent, requestTimeout)
	defer cancel()
	release, err := f.acquire(ctx, input.SourceID, input.MinInterval)
	if err != nil {
		return Result{}, err
	}
	defer release()
	current, err := url.Parse(input.URL)
	if err != nil {
		return Result{}, errors.New("invalid source URL")
	}
	initialHost := strings.ToLower(current.Hostname())
	for redirects := 0; redirects <= maxRedirects; redirects++ {
		host, addresses, err := f.endpoint(ctx, current, allowed)
		if err != nil {
			return Result{}, err
		}
		transport := f.transport(host, addresses)
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, current.String(), nil)
		if err != nil {
			return Result{}, errors.New("invalid source URL")
		}
		request.Header.Set("Accept", accept(input.Format))
		request.Header.Set("User-Agent", "lianxibu-collector/1")
		if input.Authorization != "" && host == initialHost {
			request.Header.Set("Authorization", input.Authorization)
		}
		if input.IfNoneMatch != "" {
			request.Header.Set("If-None-Match", input.IfNoneMatch)
		}
		if input.IfModifiedSince != "" {
			request.Header.Set("If-Modified-Since", input.IfModifiedSince)
		}
		response, err := transport.RoundTrip(request)
		if err != nil {
			transport.CloseIdleConnections()
			return Result{}, fmt.Errorf("source HTTPS request failed: %w", err)
		}
		if isRedirect(response.StatusCode) {
			location := response.Header.Get("Location")
			response.Body.Close()
			transport.CloseIdleConnections()
			if redirects == maxRedirects {
				return Result{}, errors.New("redirect limit exceeded")
			}
			if location == "" {
				return Result{}, errors.New("redirect has no Location")
			}
			relative, err := url.Parse(location)
			if err != nil {
				return Result{}, errors.New("invalid redirect Location")
			}
			current = current.ResolveReference(relative)
			continue
		}
		result, err := readResponse(response, current.String(), input.Format, input.AllowNotFound)
		response.Body.Close()
		transport.CloseIdleConnections()
		return result, err
	}
	return Result{}, errors.New("redirect limit exceeded")
}

func (f *Fetcher) acquire(ctx context.Context, source string, interval time.Duration) (func(), error) {
	f.mu.Lock()
	limit := f.sources[source]
	if limit == nil {
		limit = &sourceLimit{permit: make(chan struct{}, 1)}
		f.sources[source] = limit
	}
	f.mu.Unlock()
	select {
	case limit.permit <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	releaseSource := func() { <-limit.permit }
	f.mu.Lock()
	wait := time.Until(limit.next)
	f.mu.Unlock()
	if wait > 0 {
		timer := time.NewTimer(wait)
		defer timer.Stop()
		select {
		case <-timer.C:
		case <-ctx.Done():
			releaseSource()
			return nil, ctx.Err()
		}
	}
	select {
	case f.global <- struct{}{}:
	case <-ctx.Done():
		releaseSource()
		return nil, ctx.Err()
	}
	f.mu.Lock()
	limit.next = time.Now().Add(interval)
	f.mu.Unlock()
	return func() { <-f.global; releaseSource() }, nil
}

func (f *Fetcher) endpoint(ctx context.Context, target *url.URL, allowed map[string]struct{}) (string, []netip.Addr, error) {
	if target == nil || target.Scheme != "https" || target.User != nil || target.Fragment != "" ||
		(target.Port() != "" && target.Port() != "443") {
		return "", nil, errors.New("source URL must use default-port HTTPS without credentials or fragment")
	}
	host := strings.ToLower(target.Hostname())
	if _, ok := allowed[host]; !ok {
		return "", nil, errors.New("source host is not allowed")
	}
	if forbiddenDownload(target.Path) {
		return "", nil, errors.New("archive or weight download is forbidden")
	}
	resolved, err := f.resolver.LookupIPAddr(ctx, host)
	if err != nil || len(resolved) == 0 {
		return "", nil, errors.New("source DNS resolution failed")
	}
	addresses := make([]netip.Addr, 0, len(resolved))
	for _, entry := range resolved {
		addr, ok := netip.AddrFromSlice(entry.IP)
		if !ok || !f.allowIP(addr) {
			return "", nil, errors.New("source DNS contains a forbidden address")
		}
		addresses = append(addresses, addr.Unmap())
	}
	return host, addresses, nil
}

func (f *Fetcher) transport(host string, addresses []netip.Addr) *http.Transport {
	return &http.Transport{
		Proxy:                  nil,
		DisableKeepAlives:      true,
		MaxConnsPerHost:        1,
		TLSHandshakeTimeout:    connectTimeout,
		ResponseHeaderTimeout:  requestTimeout,
		MaxResponseHeaderBytes: 64 << 10,
		TLSClientConfig:        &tls.Config{MinVersion: tls.VersionTLS12, ServerName: host, RootCAs: f.roots},
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var last error
			for _, ip := range addresses {
				connection, err := f.dial(ctx, "tcp", net.JoinHostPort(ip.String(), "443"))
				if err == nil {
					return connection, nil
				}
				last = err
			}
			return nil, last
		},
	}
}

func readResponse(response *http.Response, finalURL string, format Format, allowNotFound bool) (Result, error) {
	if response.StatusCode == http.StatusNotModified {
		return Result{StatusCode: response.StatusCode, FinalURL: finalURL,
			ETag: response.Header.Get("ETag"), LastModified: response.Header.Get("Last-Modified")}, nil
	}
	if response.StatusCode != http.StatusOK && !(allowNotFound && response.StatusCode == http.StatusNotFound) {
		return Result{}, fmt.Errorf("source returned HTTP %d", response.StatusCode)
	}
	if encoding := response.Header.Get("Content-Encoding"); encoding != "" && !strings.EqualFold(encoding, "identity") {
		return Result{}, errors.New("unsupported content encoding")
	}
	mediaType, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if err != nil || !allowedMediaType(format, mediaType) ||
		strings.Contains(strings.ToLower(response.Header.Get("Content-Disposition")), "attachment") {
		return Result{}, errors.New("source content type is not an allowed text representation")
	}
	if response.ContentLength > maxBodyBytes {
		return Result{}, errors.New("source response exceeds size limit")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxBodyBytes+1))
	if err != nil {
		return Result{}, err
	}
	if len(body) > maxBodyBytes {
		return Result{}, errors.New("source response exceeds size limit")
	}
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) == 0 || !utf8.Valid(trimmed) || bytes.IndexByte(trimmed, 0) >= 0 {
		return Result{}, errors.New("source representation is not valid text")
	}
	if format == RSS || format == Atom {
		if trimmed[0] != '<' {
			return Result{}, errors.New("source is not an XML representation")
		}
		upper := bytes.ToUpper(body)
		if bytes.Contains(upper, []byte("<!DOCTYPE")) || bytes.Contains(upper, []byte("<!ENTITY")) {
			return Result{}, errors.New("XML DTD or entity is forbidden")
		}
	}
	if format == JSON && trimmed[0] != '{' && trimmed[0] != '[' {
		return Result{}, errors.New("source is not a JSON representation")
	}
	return Result{StatusCode: response.StatusCode, Body: body, ContentType: mediaType,
		ETag: response.Header.Get("ETag"), LastModified: response.Header.Get("Last-Modified"), FinalURL: finalURL}, nil
}

func validFormat(format Format) bool {
	return format == RSS || format == Atom || format == JSON || format == Text
}
func safeHeader(value string) bool {
	if len(value) > 1024 {
		return false
	}
	for _, ch := range value {
		if ch < 32 || ch > 126 {
			return false
		}
	}
	return true
}
func isRedirect(status int) bool {
	return status == 301 || status == 302 || status == 303 || status == 307 || status == 308
}
func accept(format Format) string {
	switch format {
	case RSS:
		return "application/rss+xml, application/xml, text/xml"
	case Atom:
		return "application/atom+xml, application/xml, text/xml"
	case JSON:
		return "application/json"
	default:
		return "text/plain"
	}
}
func allowedMediaType(format Format, mediaType string) bool {
	switch format {
	case RSS, Atom:
		return mediaType == "application/rss+xml" || mediaType == "application/atom+xml" ||
			mediaType == "application/xml" || mediaType == "text/xml"
	case JSON:
		return mediaType == "application/json" || strings.HasSuffix(mediaType, "+json")
	case Text:
		return mediaType == "text/plain" || mediaType == "text/markdown"
	}
	return false
}
func forbiddenDownload(rawPath string) bool {
	extension := strings.ToLower(path.Ext(rawPath))
	switch extension {
	case ".zip", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar", ".jar", ".whl",
		".bin", ".pt", ".pth", ".ckpt", ".safetensors", ".gguf", ".onnx", ".h5", ".hdf5":
		return true
	}
	return false
}

func hostSet(hosts []string) (map[string]struct{}, error) {
	set := make(map[string]struct{}, len(hosts))
	for _, raw := range hosts {
		host := strings.ToLower(raw)
		if raw == "" || raw != host || len(host) > 253 || strings.HasSuffix(host, ".") ||
			strings.ContainsAny(host, ":/@\\ ") || net.ParseIP(host) != nil || !asciiHost(host) {
			return nil, errors.New("invalid allowed host")
		}
		set[host] = struct{}{}
	}
	return set, nil
}

func asciiHost(host string) bool {
	if !strings.Contains(host, ".") {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, ch := range label {
			if !((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-') {
				return false
			}
		}
	}
	return true
}

var forbiddenV4 = prefixes("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
	"169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
	"198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4")
var forbiddenV6 = prefixes("2001::/23", "2001:db8::/32", "2002::/16")

func prefixes(values ...string) []netip.Prefix {
	result := make([]netip.Prefix, len(values))
	for i, value := range values {
		result[i] = netip.MustParsePrefix(value)
	}
	return result
}

func publicIP(address netip.Addr) bool {
	address = address.Unmap()
	if !address.IsValid() || !address.IsGlobalUnicast() {
		return false
	}
	if address.Is4() {
		for _, prefix := range forbiddenV4 {
			if prefix.Contains(address) {
				return false
			}
		}
		return true
	}
	if !netip.MustParsePrefix("2000::/3").Contains(address) {
		return false
	}
	for _, prefix := range forbiddenV6 {
		if prefix.Contains(address) {
			return false
		}
	}
	return true
}
