package controllers

import (
	"testing"
	"time"
)

func TestValidPIN(t *testing.T) {
	cases := map[string]bool{"1234": true, "4711": true, "123": false, "12a456": false, "": false, "1234567890123": false}
	for pin, want := range cases {
		if got := validPIN(pin); got != want {
			t.Errorf("validPIN(%q) = %v, want %v", pin, got, want)
		}
	}
}

func TestLockoutAfterFiveFailures(t *testing.T) {
	now := time.Date(2026, 10, 27, 12, 0, 0, 0, time.UTC)
	c := NewSupervisorAuthController(nil)
	c.now = func() time.Time { return now }

	for i := 1; i <= 4; i++ {
		if left := c.recordFailure("dev"); left != supervisorMaxFailures-i {
			t.Fatalf("attempt %d: left = %d", i, left)
		}
		if c.lockedFor("dev") != 0 {
			t.Fatalf("locked too early after %d failures", i)
		}
	}
	if left := c.recordFailure("dev"); left != 0 {
		t.Fatalf("5th failure should leave 0, got %d", left)
	}
	if c.lockedFor("dev") != supervisorLockout {
		t.Fatalf("expected %v lockout, got %v", supervisorLockout, c.lockedFor("dev"))
	}
	if c.lockedFor("other-device") != 0 {
		t.Fatal("lockout must be per device")
	}

	now = now.Add(supervisorLockout + time.Second)
	if c.lockedFor("dev") != 0 {
		t.Fatal("lockout should expire")
	}
	if left := c.recordFailure("dev"); left != supervisorMaxFailures-1 {
		t.Fatalf("counter should restart after lockout, left = %d", left)
	}
}
