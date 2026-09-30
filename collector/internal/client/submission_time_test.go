package client

import (
	"encoding/json"
	"os"
	"testing"
)

func TestSubmissionTimeDualVersionFixtures(t *testing.T) {
	raw, err := os.ReadFile("../../../contracts/hn-time-wire-examples.json")
	if err != nil {
		t.Fatal(err)
	}
	var data struct {
		Cases []struct {
			Name  string
			Value json.RawMessage
			Valid bool
		}
	}
	if err = json.Unmarshal(raw, &data); err != nil {
		t.Fatal(err)
	}
	for _, sample := range data.Cases {
		observation, err := DecodeObservation(sample.Value)
		if (err == nil) != sample.Valid {
			t.Fatalf("%s: %v", sample.Name, err)
		}
		if err == nil && string(observation.FrozenWireBytes()) != string(sample.Value) {
			t.Fatal("frozen input changed")
		}
	}
}
