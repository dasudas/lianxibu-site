package main

import (
	"go/parser"
	"go/token"
	"io/fs"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func forbiddenPath(path string) bool {
	normalized := filepath.ToSlash(path)
	return strings.HasSuffix(normalized, ".java") ||
		strings.HasSuffix(normalized, ".js") ||
		strings.Contains(normalized, "/backend/") ||
		strings.Contains(normalized, "/frontend/")
}

func forbiddenImport(path string) bool {
	return strings.Contains(path, "/backend") || strings.Contains(path, "/frontend")
}

func TestCollectorContainsNoJavaOrFrontendSource(t *testing.T) {
	root := filepath.Join("..", "..")
	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if !entry.IsDir() && forbiddenPath(path) {
			t.Fatalf("foreign source in collector: %s", path)
		}
		if !entry.IsDir() && strings.HasSuffix(path, ".go") {
			source, err := parser.ParseFile(token.NewFileSet(), path, nil, parser.ImportsOnly)
			if err != nil {
				return err
			}
			for _, imported := range source.Imports {
				name, err := strconv.Unquote(imported.Path.Value)
				if err != nil {
					return err
				}
				if forbiddenImport(name) {
					t.Fatalf("foreign module import in collector: %s", path)
				}
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestForbiddenPathRejectsOtherProducts(t *testing.T) {
	for _, path := range []string{"collector/backend/Service.java", "collector/frontend/app.js"} {
		if !forbiddenPath(path) {
			t.Fatalf("did not reject %s", path)
		}
	}
	if !forbiddenImport("github.com/example/project/backend") {
		t.Fatal("foreign backend import was accepted")
	}
}
