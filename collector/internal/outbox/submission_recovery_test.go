package outbox

import (
	"bytes"
	"context"
	"path/filepath"
	"testing"
)

func TestMixedProtocolRecordsSurviveSQLiteRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "mixed.db")
	store, err := Open(path, "node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { store.Close() }()
	if err = store.PersistClaim(fixtureClaim()); err != nil {
		t.Fatal(err)
	}
	old, newer := fixtureRecord("1"), fixtureRecord("2")
	newer.ProtocolRevision = "0.12"
	if err = store.SavePage(context.Background(), "run-1", "token-a", 0, "", "next", []Record{old, newer}); err != nil {
		t.Fatal(err)
	}
	store.Close()
	store, err = Open(path, "node", fixtureClock)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	for _, original := range []Record{old, newer} {
		got, _, err := store.Record(context.Background(), original.ID)
		if err != nil || got.ProtocolRevision != original.ProtocolRevision || got.PayloadHash != original.PayloadHash || !bytes.Equal(got.Payload, original.Payload) {
			t.Fatal("protocol bytes/hash changed after restart")
		}
	}
	bad := fixtureRecord("3")
	bad.ProtocolRevision = "0.99"
	if err = store.SavePage(context.Background(), "run-1", "token-a", 1, "next", "later", []Record{bad}); err == nil {
		t.Fatal("unsupported queue protocol accepted")
	}
	state, revision, err := store.Run(context.Background(), "run-1")
	if err != nil || revision != 1 || state.Cursor != "next" {
		t.Fatal("failed page advanced cursor")
	}
}
