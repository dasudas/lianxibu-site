package parsers

import (
	"bytes"
	"encoding/json"
	"strings"
)

// LicenseReviews are host-owned, exact-byte exceptions for unrecognized SPDX
// metadata. They never authorize another repository, path or revision.
type LicenseReviews struct{ entries []licenseReview }
type licenseReview struct {
	Repository string `json:"repository"`
	Commit     string `json:"commit"`
	Path       string `json:"path"`
	SHA256     string `json:"sha256"`
}

func ParseLicenseReviews(raw []byte) (LicenseReviews, error) {
	if len(raw) > 65536 || checkedJSON(raw) != nil {
		return LicenseReviews{}, ErrSourceConfig
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	var entries []licenseReview
	if d.Decode(&entries) != nil || len(entries) > 128 {
		return LicenseReviews{}, ErrSourceConfig
	}
	seen := map[licenseReview]bool{}
	for _, r := range entries {
		parts := strings.Split(r.Repository, "/")
		if len(parts) != 2 || !repoPart(parts[0]) || !repoPart(parts[1]) || !gitCommit(r.Commit) || !catalogPath(r.Path) || len(r.SHA256) != 64 || strings.Trim(r.SHA256, "0123456789abcdef") != "" || seen[r] {
			return LicenseReviews{}, ErrSourceConfig
		}
		seen[r] = true
	}
	return LicenseReviews{entries: entries}, nil
}
func (r LicenseReviews) matches(repo, commit, path, hash string) bool {
	for _, entry := range r.entries {
		if entry == (licenseReview{repo, commit, path, hash}) {
			return true
		}
	}
	return false
}

func (r LicenseReviews) containsRepository(repo string) bool {
	for _, entry := range r.entries {
		if entry.Repository == repo {
			return true
		}
	}
	return false
}
func (a *RepositoryCatalogAdapter) SetLicenseReviews(reviews LicenseReviews) { a.reviews = reviews }
