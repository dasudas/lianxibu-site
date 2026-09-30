package telemetry

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type HTTPSender struct {
	endpoint string
	token    string
	client   *http.Client
}

func NewHTTPSender(origin, token string, transport *http.Client) (*HTTPSender, error) {
	u, err := url.Parse(origin)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" ||
		(u.Path != "" && u.Path != "/") || token == "" {
		return nil, errors.New("HTTPS origin and node token required")
	}
	if transport == nil {
		transport = &http.Client{Timeout: 15 * time.Second}
	}
	copyClient := *transport
	copyClient.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if copyClient.Timeout == 0 {
		copyClient.Timeout = 15 * time.Second
	}
	return &HTTPSender{endpoint: strings.TrimRight(origin, "/") + "/api/collector/v1/heartbeat", token: token, client: &copyClient}, nil
}

func (s *HTTPSender) SendHeartbeat(ctx context.Context, beat Heartbeat) error {
	body, err := json.Marshal(beat)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+s.token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := s.client.Do(req)
	if err != nil {
		return errors.New("heartbeat delivery failed")
	}
	defer resp.Body.Close()
	if _, err := io.Copy(io.Discard, io.LimitReader(resp.Body, 1024)); err != nil {
		return errors.New("heartbeat response unavailable")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return errors.New("heartbeat rejected")
	}
	return nil
}
