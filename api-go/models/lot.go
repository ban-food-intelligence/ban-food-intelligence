package models

import "time"

// Lot represents an inventory batch in the ERP system
type Lot struct {
	ID              string    `json:"id"`
	LotNumber       string    `json:"lot_number"`
	ProductID       string    `json:"product_id"`
	ProductName     string    `json:"product_name,omitempty"`
	ProductCategory string    `json:"product_category,omitempty"`
	Quantity        float64   `json:"quantity"`
	CurrentQuantity float64   `json:"current_quantity"`
	Unit            string    `json:"unit"`
	Status          string    `json:"status"` // QUARANTINE, RELEASED, IN_PROCESS, CONSUMED, REJECTED
	ProductionDate  time.Time `json:"production_date"`
	ExpiryDate      time.Time `json:"expiry_date"`
	SupplierInfo    string    `json:"supplier_info,omitempty"`
}

// LotTraceNode represents a single step in the recursive lot genealogy graph
type LotTraceNode struct {
	Depth            int      `json:"depth"`
	CurrentLotNumber string   `json:"current_lot_number"`
	ProductName      string   `json:"product_name"`
	ProductCategory  string   `json:"product_category"`
	Quantity         float64  `json:"quantity"`
	Status           string   `json:"status"`
	ParentLotNumber  *string  `json:"parent_lot_number,omitempty"`
	QuantityConsumed float64  `json:"quantity_consumed,omitempty"`
	TracePath        string   `json:"trace_path"`
	Children         []*LotTraceNode `json:"children,omitempty"`
}

// TraceResponse wraps the backward and forward genealogy trees for a lot
type TraceResponse struct {
	RootLotNumber string          `json:"root_lot_number"`
	Direction     string          `json:"direction"` // backward or forward
	Nodes         []*LotTraceNode `json:"nodes"`
	TotalAncestors int            `json:"total_ancestors"`
}
