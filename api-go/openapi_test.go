package main

import (
	"os"
	"regexp"
	"sort"
	"strings"
	"testing"
)

// docs/openapi.yaml must list exactly the routes main.go registers, and mark every route behind
// the supervisor session (op(...)) with bearerAuth, so the contract cannot drift from the code.
func TestOpenAPIMatchesRegisteredRoutes(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatal(err)
	}
	spec, err := os.ReadFile("../docs/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}

	route := regexp.MustCompile(`mux\.HandleFunc\("([^"]+)",\s*(.*)$`)
	registered := map[string]bool{} // path -> behind op(...)
	for _, line := range strings.Split(string(src), "\n") {
		m := route.FindStringSubmatch(strings.TrimSpace(line))
		if m == nil {
			continue
		}
		path := m[1]
		if path == "/trace/" {
			path = "/trace/{lot_no}"
		}
		registered[path] = strings.Contains(m[2], "op(")
	}
	if len(registered) < 40 {
		t.Fatalf("found only %d routes in main.go; did the registration style change?", len(registered))
	}

	// Path blocks: "  /path:" at two spaces under "paths:", up to the next one.
	header := regexp.MustCompile(`(?m)^  (/[^:\s]*):\s*$`)
	text := string(spec)
	idx := header.FindAllStringSubmatchIndex(text, -1)
	documented := map[string]string{}
	for i, m := range idx {
		end := len(text)
		if i+1 < len(idx) {
			end = idx[i+1][0]
		}
		documented[text[m[2]:m[3]]] = text[m[1]:end]
	}

	var missing, extra, unmarked, wronglyMarked []string
	for path, protected := range registered {
		block, ok := documented[path]
		switch {
		case !ok:
			missing = append(missing, path)
		case protected && !strings.Contains(block, "bearerAuth"):
			unmarked = append(unmarked, path)
		case !protected && strings.Contains(block, "bearerAuth"):
			wronglyMarked = append(wronglyMarked, path)
		}
	}
	for path := range documented {
		if _, ok := registered[path]; !ok {
			extra = append(extra, path)
		}
	}
	for _, l := range [][]string{missing, extra, unmarked, wronglyMarked} {
		sort.Strings(l)
	}
	if len(missing) > 0 {
		t.Errorf("routes missing from docs/openapi.yaml: %v", missing)
	}
	if len(extra) > 0 {
		t.Errorf("docs/openapi.yaml lists routes main.go does not register: %v", extra)
	}
	if len(unmarked) > 0 {
		t.Errorf("session-protected routes without bearerAuth in docs/openapi.yaml: %v", unmarked)
	}
	if len(wronglyMarked) > 0 {
		t.Errorf("open routes marked bearerAuth in docs/openapi.yaml: %v", wronglyMarked)
	}
}
