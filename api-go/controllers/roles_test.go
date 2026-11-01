package controllers

// Back-office roles: the role is signed into the session token and every write endpoint
// names the roles that may call it. Unit tests (no DB) and an integration test with the
// seeded demo users (MYSQL_TEST_DSN).

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func roleRequest(h http.HandlerFunc, method, token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, "/x", strings.NewReader("{}"))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	h(rec, req)
	return rec
}

func TestRoleIsSignedIntoTheToken(t *testing.T) {
	m := NewSessionManager()
	token, _ := m.IssueRole("uretim_sefi", RoleOperator)
	if u, role, ok := m.Claims(token); !ok || u != "uretim_sefi" || role != RoleOperator {
		t.Fatalf("claims: %q %q %v", u, role, ok)
	}
	// Changing the role in the payload breaks the signature.
	payload, sig, _ := strings.Cut(token, ".")
	raw, _ := base64.RawURLEncoding.DecodeString(payload)
	forged := base64.RawURLEncoding.EncodeToString([]byte(strings.Replace(string(raw), RoleOperator, RoleAdmin, 1))) + "." + sig
	if _, _, ok := m.Claims(forged); ok {
		t.Fatal("a token whose role was edited must not verify")
	}
	// An unknown role is refused even when correctly signed.
	bad, _ := m.IssueRole("x", "ROOT")
	if _, _, ok := m.Claims(bad); ok {
		t.Fatal("unknown role accepted")
	}
}

func TestRequireRolesAllowsOnlyNamedRolesAndAdmin(t *testing.T) {
	m := NewSessionManager()
	var gotUser, gotRole string
	h := m.RequireRoles(RoleQuality)(func(w http.ResponseWriter, r *http.Request) {
		gotUser, gotRole = OperatorFrom(r.Context()), RoleFrom(r.Context())
		w.WriteHeader(http.StatusCreated)
	})
	tok := func(role string) string { t, _ := m.IssueRole("u-"+role, role); return t }

	cases := []struct {
		role string
		want int
	}{
		{RoleQuality, http.StatusCreated},
		{RoleAdmin, http.StatusCreated},
		{RoleOperator, http.StatusForbidden},
		{RoleViewer, http.StatusForbidden},
	}
	for _, c := range cases {
		rec := roleRequest(h, http.MethodPost, tok(c.role))
		if rec.Code != c.want {
			t.Fatalf("%s: want %d, got %d %s", c.role, c.want, rec.Code, rec.Body)
		}
		if c.want == http.StatusForbidden {
			var e struct {
				Code    string `json:"code"`
				Details struct {
					Role    string   `json:"role"`
					Allowed []string `json:"allowed_roles"`
				} `json:"details"`
			}
			_ = json.Unmarshal(rec.Body.Bytes(), &e)
			if e.Code != "FORBIDDEN" || e.Details.Role != c.role || strings.Join(e.Details.Allowed, ",") != "ADMIN,QUALITY" {
				t.Fatalf("%s: 403 body %s", c.role, rec.Body)
			}
		}
	}
	if gotUser != "u-ADMIN" || gotRole != RoleAdmin {
		t.Fatalf("context: %q %q", gotUser, gotRole)
	}
	if rec := roleRequest(h, http.MethodPost, ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("no token: want 401, got %d", rec.Code)
	}
	if rec := roleRequest(h, http.MethodGet, ""); rec.Code != http.StatusCreated {
		t.Fatalf("reads stay open: got %d", rec.Code)
	}
	// RequireOperator: every role that writes, never VIEWER.
	op := m.RequireOperator(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusCreated) })
	for role, want := range map[string]int{RoleAdmin: 201, RoleQuality: 201, RoleOperator: 201, RoleViewer: 403} {
		if rec := roleRequest(op, http.MethodPost, tok(role)); rec.Code != want {
			t.Fatalf("RequireOperator %s: want %d, got %d", role, want, rec.Code)
		}
	}
}

// The seeded demo users: each PIN signs in with its role, and the role decides what the
// session may write. Also: only QUALITY or ADMIN may release a POS recall lock.
func TestIntegrationSeededRolesSignInAndAreEnforced(t *testing.T) {
	db := testDB(t)
	sessions := NewSessionManager()
	auth := NewSupervisorAuthController(db).WithSessions(sessions)

	signIn := func(pin string) OperatorSessionResponse {
		t.Helper()
		code, body := call(t, auth.Session, "POST", "/auth/session", SupervisorVerifyRequest{PIN: pin, DeviceID: "roles-test"})
		var s OperatorSessionResponse
		_ = json.Unmarshal(body, &s)
		if code != http.StatusOK || s.Token == "" {
			t.Fatalf("sign in %s: %d %s", pin, code, body)
		}
		return s
	}
	users := map[string]OperatorSessionResponse{
		RoleAdmin: signIn("246813"), RoleQuality: signIn("314159"), RoleOperator: signIn("271828"), RoleViewer: signIn("161803"),
	}
	for role, s := range users {
		if s.Role != role {
			t.Fatalf("PIN for %s signed in as %s (%s)", role, s.Role, s.Username)
		}
	}

	// A recall is quality work; an invalid body after the role check gives 422, a wrong role 403.
	quality := sessions.RequireRoles(RoleQuality)(NewRecallController(db).TriggerRecall)
	operator := sessions.RequireRoles(RoleOperator)(NewMovementsController(db).Waste)
	post := func(h http.HandlerFunc, token string) int {
		req := httptest.NewRequest("POST", "/x", bytes.NewBufferString(`{}`))
		req.Header.Set("Authorization", "Bearer "+token)
		rec := httptest.NewRecorder()
		h(rec, req)
		return rec.Code
	}
	want := map[string][2]int{ // role -> {recall, waste}
		RoleAdmin:    {http.StatusUnprocessableEntity, http.StatusBadRequest},
		RoleQuality:  {http.StatusUnprocessableEntity, http.StatusForbidden},
		RoleOperator: {http.StatusForbidden, http.StatusBadRequest},
		RoleViewer:   {http.StatusForbidden, http.StatusForbidden},
	}
	for role, w := range want {
		if got := post(quality, users[role].Token); got != w[0] {
			t.Fatalf("%s POST /recall: want %d, got %d", role, w[0], got)
		}
		if got := post(operator, users[role].Token); got != w[1] {
			t.Fatalf("%s POST /movements/waste: want %d, got %d", role, w[1], got)
		}
	}

	// POS recall lock: the operator's correct PIN is refused (403) and audited; quality may unlock.
	verify := func(pin string) int {
		code, _ := call(t, auth.Verify, "POST", "/auth/supervisor/verify",
			SupervisorVerifyRequest{PIN: pin, DeviceID: "roles-test-pos", LotNo: "SUC-2026-045", RecallCode: "RCL-ROLES"})
		return code
	}
	if code := verify("271828"); code != http.StatusForbidden {
		t.Fatalf("operator unlocking the POS: want 403, got %d", code)
	}
	var forbidden int
	_ = db.QueryRow(`SELECT COUNT(*) FROM audit_log WHERE table_name = 'terminal_lock'
		AND action = 'SUPERVISOR_UNLOCK_FORBIDDEN' AND changed_by = 'uretim_sefi'`).Scan(&forbidden)
	if forbidden == 0 {
		t.Fatal("refused unlock not audited")
	}
	if code := verify("314159"); code != http.StatusOK {
		t.Fatalf("quality unlocking the POS: want 200, got %d", code)
	}
}
