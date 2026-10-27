package controllers

import (
	"math"
	"sort"
)

// AllocateCost splits totalCost over lines in proportion to their allocation bases
// (QUANTITY: kg; VALUE: kg x sale price) and rounds to cents so that the result sums to
// totalCost exactly. Rounding uses the largest-remainder method: every line gets its
// floored cent amount, and the leftover cents go one by one to the lines with the largest
// fractional remainder (ties: larger basis first, then input order). Lines with a zero
// basis (waste) receive 0.
func AllocateCost(totalCost float64, bases []float64) []float64 {
	out := make([]float64, len(bases))
	totalCents := int64(math.Round(totalCost * 100))
	var basisSum float64
	for _, b := range bases {
		if b > 0 {
			basisSum += b
		}
	}
	if basisSum <= 0 || totalCents <= 0 {
		return out
	}

	type share struct {
		idx       int
		basis     float64
		cents     int64
		remainder float64
	}
	shares := make([]share, 0, len(bases))
	var assigned int64
	for i, b := range bases {
		if b <= 0 {
			continue
		}
		exact := float64(totalCents) * b / basisSum
		floor := int64(math.Floor(exact + 1e-9))
		shares = append(shares, share{idx: i, basis: b, cents: floor, remainder: exact - float64(floor)})
		assigned += floor
	}

	sort.SliceStable(shares, func(a, b int) bool {
		if math.Abs(shares[a].remainder-shares[b].remainder) > 1e-9 {
			return shares[a].remainder > shares[b].remainder
		}
		return shares[a].basis > shares[b].basis
	})
	for k := int64(0); k < totalCents-assigned; k++ {
		shares[int(k)%len(shares)].cents++
	}

	for _, s := range shares {
		out[s.idx] = float64(s.cents) / 100
	}
	return out
}
