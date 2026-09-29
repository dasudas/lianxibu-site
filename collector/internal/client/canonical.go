package client

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

const maxSafeInteger uint64 = 9007199254740991

type HashDomain string

const (
	ObservationHash       HashDomain = "observation-v1"
	ManifestPartHash      HashDomain = "manifest-part-v1"
	ManifestHash          HashDomain = "manifest-v1"
	ContentHash           HashDomain = "content-v1"
	InputPartHash         HashDomain = "input-part-v1"
	InputHash             HashDomain = "input-v1"
	ReviewHash            HashDomain = "review-v1"
	ActionHash            HashDomain = "action-v1"
	IdentityHash          HashDomain = "identity-v1"
	OccurrenceHash        HashDomain = "occurrence-v1"
	BusinessInputHash     HashDomain = "business-input-v1"
	ControlBusinessHash   HashDomain = "control-business-v1"
	ControlAttemptHash    HashDomain = "control-attempt-v1"
	SourceItemHash        HashDomain = "source-item-v1"
	LegacyExternalHash    HashDomain = "legacy-external-v1"
	ContentKeyHash        HashDomain = "content-key-v1"
	ArticleProjectionHash HashDomain = "article-projection-v1"
)

var allowedDomains = map[HashDomain]bool{
	ObservationHash: true, ManifestPartHash: true, ManifestHash: true,
	ContentHash: true, InputPartHash: true, InputHash: true,
	ReviewHash: true, ActionHash: true, IdentityHash: true, OccurrenceHash: true,
	BusinessInputHash: true, ControlBusinessHash: true, ControlAttemptHash: true,
	SourceItemHash: true, LegacyExternalHash: true, ContentKeyHash: true,
	ArticleProjectionHash: true,
}

// ParseStrict keeps integer lexemes and refuses duplicate keys before constructing a value.
func ParseStrict(raw []byte) (any, error) {
	if len(raw) == 0 || bytes.HasPrefix(raw, []byte{0xef, 0xbb, 0xbf}) || !utf8.Valid(raw) {
		return nil, errors.New("empty, BOM or invalid UTF-8 JSON")
	}
	if err := checkSurrogateEscapes(raw); err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	value, err := readValue(decoder)
	if err != nil {
		return nil, err
	}
	if _, err := decoder.Token(); err != io.EOF {
		return nil, errors.New("JSON requires exactly one root")
	}
	return value, nil
}

func readValue(decoder *json.Decoder) (any, error) {
	token, err := decoder.Token()
	if err != nil {
		return nil, err
	}
	switch value := token.(type) {
	case json.Delim:
		switch value {
		case '{':
			object := make(map[string]any)
			for decoder.More() {
				keyToken, err := decoder.Token()
				if err != nil {
					return nil, err
				}
				key, ok := keyToken.(string)
				if !ok {
					return nil, errors.New("object key must be string")
				}
				if _, exists := object[key]; exists {
					return nil, errors.New("duplicate JSON key")
				}
				child, err := readValue(decoder)
				if err != nil {
					return nil, err
				}
				object[key] = child
			}
			if end, err := decoder.Token(); err != nil || end != json.Delim('}') {
				return nil, errors.New("unterminated JSON object")
			}
			return object, nil
		case '[':
			array := make([]any, 0)
			for decoder.More() {
				child, err := readValue(decoder)
				if err != nil {
					return nil, err
				}
				array = append(array, child)
			}
			if end, err := decoder.Token(); err != nil || end != json.Delim(']') {
				return nil, errors.New("unterminated JSON array")
			}
			return array, nil
		}
	case json.Number:
		text := string(value)
		if !validIntegerLexeme(text) {
			return nil, errors.New("noncanonical JSON number")
		}
		number, err := strconv.ParseUint(text, 10, 64)
		if err != nil || number > maxSafeInteger {
			return nil, errors.New("integer outside wire bounds")
		}
		return value, nil
	case string, bool, nil:
		return value, nil
	}
	return nil, errors.New("unsupported JSON value")
}

func validIntegerLexeme(value string) bool {
	if value == "0" {
		return true
	}
	if len(value) == 0 || value[0] < '1' || value[0] > '9' {
		return false
	}
	for _, digit := range value[1:] {
		if digit < '0' || digit > '9' {
			return false
		}
	}
	return true
}

// encoding/json substitutes U+FFFD for isolated escaped surrogates, so check first.
func checkSurrogateEscapes(raw []byte) error {
	inString := false
	for index := 0; index < len(raw); index++ {
		switch raw[index] {
		case '"':
			inString = !inString
		case '\\':
			if !inString {
				continue
			}
			index++
			if index >= len(raw) {
				return errors.New("incomplete JSON escape")
			}
			if raw[index] != 'u' {
				continue
			}
			if index+4 >= len(raw) {
				return errors.New("incomplete Unicode escape")
			}
			first, err := strconv.ParseUint(string(raw[index+1:index+5]), 16, 16)
			if err != nil {
				return errors.New("invalid Unicode escape")
			}
			index += 4
			if first >= 0xdc00 && first <= 0xdfff {
				return errors.New("isolated low surrogate")
			}
			if first >= 0xd800 && first <= 0xdbff {
				if index+6 >= len(raw) || raw[index+1] != '\\' || raw[index+2] != 'u' {
					return errors.New("isolated high surrogate")
				}
				second, err := strconv.ParseUint(string(raw[index+3:index+7]), 16, 16)
				if err != nil || second < 0xdc00 || second > 0xdfff {
					return errors.New("invalid surrogate pair")
				}
				index += 6
			}
		}
	}
	return nil
}

// CanonicalBytes follows the project CanonicalJson-v1 rules.
func CanonicalBytes(value any) ([]byte, error) {
	var output strings.Builder
	if err := writeCanonical(&output, value); err != nil {
		return nil, err
	}
	return []byte(output.String()), nil
}

func writeCanonical(output *strings.Builder, value any) error {
	switch item := value.(type) {
	case nil:
		output.WriteString("null")
	case bool:
		if item {
			output.WriteString("true")
		} else {
			output.WriteString("false")
		}
	case string:
		if !utf8.ValidString(item) {
			return errors.New("invalid UTF-8 string")
		}
		writeString(output, item)
	case json.Number:
		text := string(item)
		if !validIntegerLexeme(text) {
			return errors.New("noncanonical JSON number")
		}
		number, err := strconv.ParseUint(text, 10, 64)
		if err != nil || number > maxSafeInteger {
			return errors.New("integer outside wire bounds")
		}
		output.WriteString(text)
	case int:
		if item < 0 || uint64(item) > maxSafeInteger {
			return errors.New("integer outside wire bounds")
		}
		output.WriteString(strconv.Itoa(item))
	case uint64:
		if item > maxSafeInteger {
			return errors.New("integer outside wire bounds")
		}
		output.WriteString(strconv.FormatUint(item, 10))
	case []any:
		output.WriteByte('[')
		for index, child := range item {
			if index > 0 {
				output.WriteByte(',')
			}
			if err := writeCanonical(output, child); err != nil {
				return err
			}
		}
		output.WriteByte(']')
	case map[string]any:
		output.WriteByte('{')
		keys := make([]string, 0, len(item))
		for key := range item {
			if !utf8.ValidString(key) {
				return errors.New("invalid UTF-8 key")
			}
			keys = append(keys, key)
		}
		sort.Strings(keys) // Valid UTF-8 byte order matches Unicode scalar order.
		for index, key := range keys {
			if index > 0 {
				output.WriteByte(',')
			}
			writeString(output, key)
			output.WriteByte(':')
			if err := writeCanonical(output, item[key]); err != nil {
				return err
			}
		}
		output.WriteByte('}')
	default:
		return fmt.Errorf("unsupported canonical type %T", value)
	}
	return nil
}

func writeString(output *strings.Builder, value string) {
	const digits = "0123456789abcdef"
	output.WriteByte('"')
	for _, scalar := range value {
		switch scalar {
		case '"':
			output.WriteString(`\"`)
		case '\\':
			output.WriteString(`\\`)
		case '\b':
			output.WriteString(`\b`)
		case '\t':
			output.WriteString(`\t`)
		case '\n':
			output.WriteString(`\n`)
		case '\f':
			output.WriteString(`\f`)
		case '\r':
			output.WriteString(`\r`)
		default:
			if scalar < 0x20 {
				output.WriteString(`\u00`)
				output.WriteByte(digits[scalar>>4])
				output.WriteByte(digits[scalar&0xf])
			} else {
				output.WriteRune(scalar)
			}
		}
	}
	output.WriteByte('"')
}

func Hash(domain HashDomain, value any) (string, error) {
	if !allowedDomains[domain] {
		return "", errors.New("unknown hash domain")
	}
	canonical, err := CanonicalBytes(value)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(append([]byte(string(domain)+"\n"), canonical...))
	return hex.EncodeToString(sum[:]), nil
}
