package client

import (
	"encoding/hex"
	"testing"
)

func TestHistoricalCanonicalVectors(t *testing.T) {
	vectors := []struct {
		name   string
		domain HashDomain
		raw    string
		hash   string
	}{
		{"key-order-null", ContentHash, `{"b":1,"a":null}`, "441b275ad8b530cd797b599987bc509603b2fb64ecc7422e658046651de5d56d"},
		{"unicode-controls", ContentHash, "{\"title\":\"中文😀\",\"text\":\"a\\r\\nb\\t/\u2028\",\"control\":\"\\u0000\"}", "80d4a7aa5c0acd6cc74fdcd62b325ba003d12d79ba6d9dd94d480b7ae0edc66a"},
		{"scalar-key-order", ContentHash, `{"𐀀":1,"":2}`, "aecd3c70a1eeb1de896fb043db84c1c21c69e9c811e854abcc4cb6c278fda24a"},
		{"empty-manifest", ManifestHash, `{"run_id":"run-001","expected_count":0,"parts":[]}`, "6a1c37cdf9fdaa9e55d661c45a4b582e5f39a61784498cba25ff3d8c7b410f9f"},
		{"empty-review", ReviewHash, `{"body_hash":"0000000000000000000000000000000000000000000000000000000000000000","input_job_id":"intent-001","input_hash":"1111111111111111111111111111111111111111111111111111111111111111","dependencies":[]}`, "af4207ae9d1031dcc8db21d6a58201a2e3b00b26d2d5bca5dfdd8d9299571c61"},
		{"safe-integer", ContentHash, `{"score":"1.20","count":9007199254740991,"ok":true}`, "6521cd39f4e72ca61337b9379d4cc68508ae8825635f450f8bf10b5e75b9e463"},
		{"array-a", ContentHash, `{"items":[1,2]}`, "f38af94e44f3accde5a36667e401e0d14e0485fd152d3d0f847f3ecec1a47632"},
		{"array-b", ContentHash, `{"items":[2,1]}`, "4f791413388bed70fe5d3097c184e24fded1e56147fdc6e264d994bd8fc5b682"},
		{"observation", ObservationHash, `{"protocol_revision":"0.10","observation_id":"obs-001","member_id":"member-001","run_id":"run-001","business_date":"2026-09-26","source_id":"source-example","source_revision":1,"source_item_id":"item-001","kind":"article","retrieved_at":"2026-09-27T00:00:00.000000Z","replaces_observation_id":null,"payload":{"title":"示例","source_url":"https://example.com/item","published_at":null,"updated_at":null,"time_basis":"unknown","source_summary":null,"body_text":"text\n","content_status":"complete","body_truncated":false,"parser_version":"1","config_version":1,"source_order":{"basis":"unknown","domain":null,"value":null,"evidence_ref":null}}}`, "104eed018164444d811132953885cc0881e03d4757e374aa31d7248b8009cc45"},
		{"manifest-part", ManifestPartHash, `{"run_id":"run-001","part_no":0,"members":[{"member_id":"member-001","source_item_id":"item-001","observation_id":"obs-001","payload_hash":"104eed018164444d811132953885cc0881e03d4757e374aa31d7248b8009cc45","replaces_observation_id":null}]}`, "2437a1d6b087ddcbc41ecaadb003f2f5c5237b6390a46420a4ea702caf3625f9"},
		{"manifest-root", ManifestHash, `{"run_id":"run-001","expected_count":1,"parts":[{"part_no":0,"part_hash":"2437a1d6b087ddcbc41ecaadb003f2f5c5237b6390a46420a4ea702caf3625f9","member_count":1}]}`, "d6b618be9d830b38b3f6cfbfb611314bcaed533cbcad6b354d245309b0d1a230"},
	}
	for _, vector := range vectors {
		t.Run(vector.name, func(t *testing.T) {
			value, err := ParseStrict([]byte(vector.raw))
			if err != nil {
				t.Fatal(err)
			}
			actual, err := Hash(vector.domain, value)
			if err != nil {
				t.Fatal(err)
			}
			if actual != vector.hash {
				t.Fatalf("hash %s != %s", actual, vector.hash)
			}
		})
	}
	value, _ := ParseStrict([]byte(`{"b":1,"a":null}`))
	canonical, _ := CanonicalBytes(value)
	if hex.EncodeToString(canonical) != "7b2261223a6e756c6c2c2262223a317d" {
		t.Fatal("canonical bytes drifted")
	}
}

func TestStrictRejectsInvalidJSON(t *testing.T) {
	for _, raw := range [][]byte{
		[]byte(`{"x":1,"x":2}`), []byte(`{"x":1.0}`), []byte(`{"x":1e3}`),
		[]byte(`{"x":NaN}`), []byte(`{"x":9007199254740992}`),
		[]byte(`"\ud800"`), []byte(`"\udc00"`), []byte(`{"x":-0}`),
		[]byte(`{} {}`), {0xef, 0xbb, 0xbf, '{', '}'}, {0xc3, 0x28},
	} {
		if _, err := ParseStrict(raw); err == nil {
			t.Fatalf("accepted %q", raw)
		}
	}
	if _, err := Hash(HashDomain("unknown-v1"), map[string]any{}); err == nil {
		t.Fatal("unknown domain accepted")
	}
}
