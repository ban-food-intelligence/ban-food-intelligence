package controllers

import (
	"math"
	"testing"
)

func sumCents(v []float64) int64 {
	var s int64
	for _, x := range v {
		s += int64(math.Round(x * 100))
	}
	return s
}

// Seed PRC-2026-001: 287.50 EUR over but/sirt/omuz/kaburga/kiyma (+3 kg waste),
// value key = kg x VKP. Expected values are the ones stored in seed.sql.
func TestAllocateCostValueKeyMatchesSeed(t *testing.T) {
	bases := []float64{6.5 * 22, 3.5 * 26, 5.0 * 18, 3.0 * 14, 4.0 * 16, 0}
	got := AllocateCost(287.50, bases)
	want := []float64{95.61, 60.84, 60.18, 28.08, 42.79, 0}
	for i := range want {
		if math.Abs(got[i]-want[i]) > 1e-9 {
			t.Fatalf("line %d: got %.2f want %.2f (all %v)", i, got[i], want[i], got)
		}
	}
	if sumCents(got) != 28750 {
		t.Fatalf("sum %d cents, want 28750", sumCents(got))
	}
}

func TestAllocateCostQuantityKeySumsExactly(t *testing.T) {
	// 287.50 over 22 kg of cuts: 13.068.. EUR/kg, cents do not divide evenly.
	got := AllocateCost(287.50, []float64{6.5, 3.5, 5.0, 3.0, 4.0, 0})
	if sumCents(got) != 28750 {
		t.Fatalf("sum %d cents, want 28750 (%v)", sumCents(got), got)
	}
	if got[5] != 0 {
		t.Fatalf("waste line must get 0, got %.2f", got[5])
	}
}

func TestAllocateCostManyEqualLines(t *testing.T) {
	// 1.00 over 3 equal lines -> 0.34 + 0.33 + 0.33, never 0.99 or 1.01.
	got := AllocateCost(1.00, []float64{1, 1, 1})
	if sumCents(got) != 100 {
		t.Fatalf("sum %d, want 100 (%v)", sumCents(got), got)
	}
}

func TestAllocateCostNoBasis(t *testing.T) {
	got := AllocateCost(50, []float64{0, 0})
	if got[0] != 0 || got[1] != 0 {
		t.Fatalf("no basis must allocate nothing, got %v", got)
	}
}
