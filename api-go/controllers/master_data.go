package controllers

// Master data (ana veriler): product cards with unit, prices and shelf life, units of measure,
// suppliers, customers and butchery recipes with their allocation key. The brief asks for these to
// be managed and defined in advance; until now they only came from the seed.
//
// What a record already means for past documents never changes: a product with lots keeps its unit
// and type, a supplier with deliveries or orders keeps its type, and a recipe is never edited (a
// new version gets a new code), so the stored butchery operations and their allocation stay valid.
// Every change is written to audit_log with the old and new values and the supervisor's name.

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"regexp"
	"strings"
)

var (
	codePattern = regexp.MustCompile(`^[A-Z0-9][A-Z0-9-]*$`)
	unitPattern = regexp.MustCompile(`^[A-Z]{1,10}$`)
)

var productTypes = map[string]bool{"RAW_MATERIAL": true, "FINISHED_GOOD": true, "CARCASS": true, "CUT": true, "BY_PRODUCT": true, "WASTE": true}
var supplierTypes = map[string]bool{"RAW_MATERIAL": true, "SUBCONTRACTOR": true, "LIVESTOCK": true}
var customerTypes = map[string]bool{"MARKET": true, "WHOLESALER": true, "RESTAURANT": true, "WALK_IN": true}

type MasterDataController struct {
	db *sql.DB
}

func NewMasterDataController(db *sql.DB) *MasterDataController {
	return &MasterDataController{db: db}
}

// ---------------------------------------------------------------- products

type ProductCardDto struct {
	SKU                   string   `json:"sku"`
	Name                  string   `json:"name"`
	ProductType           string   `json:"product_type"`
	Uom                   string   `json:"uom_code"`
	PackSizeKg            *float64 `json:"pack_size_kg"`
	Species               *string  `json:"species"`
	StandardPurchasePrice *float64 `json:"standard_purchase_price"`
	SalePrice             *float64 `json:"sale_price"`
	ShelfLifeDays         *int     `json:"shelf_life_days"`
	IsActive              bool     `json:"is_active"`
	Lots                  int      `json:"lots"`    // read only: lots of this product (unit and type are then fixed)
	Recipes               int      `json:"recipes"` // a predefined recipe also fixes unit and type
}

func validAmount(v *float64) bool {
	return v == nil || (!math.IsNaN(*v) && !math.IsInf(*v, 0) && *v >= 0 && *v <= 1e6)
}

func validateProduct(p *ProductCardDto) fieldErrors {
	fe := fieldErrors{}
	p.SKU, p.Name, p.ProductType, p.Uom = strings.ToUpper(strings.TrimSpace(p.SKU)), strings.TrimSpace(p.Name), strings.ToUpper(p.ProductType), strings.ToUpper(strings.TrimSpace(p.Uom))
	if p.SKU == "" || len(p.SKU) > 30 || !codePattern.MatchString(p.SKU) {
		fe["sku"] = "required, max 30 characters, A-Z 0-9 -"
	}
	if p.Name == "" || len([]rune(p.Name)) > 150 {
		fe["name"] = "required, max 150 characters"
	}
	if !productTypes[p.ProductType] {
		fe["product_type"] = "RAW_MATERIAL, FINISHED_GOOD, CARCASS, CUT, BY_PRODUCT or WASTE"
	}
	if p.Uom == "" {
		fe["uom_code"] = "required"
	}
	if !validAmount(p.PackSizeKg) {
		fe["pack_size_kg"] = "must be >= 0"
	}
	if p.Species != nil {
		s := strings.ToUpper(strings.TrimSpace(*p.Species))
		p.Species = &s
		if len(s) > 20 {
			fe["species"] = "max 20 characters"
		}
		if s == "" {
			p.Species = nil
		}
	}
	if !validAmount(p.StandardPurchasePrice) {
		fe["standard_purchase_price"] = "must be >= 0"
	}
	if !validAmount(p.SalePrice) {
		fe["sale_price"] = "must be >= 0"
	}
	if p.ShelfLifeDays != nil && (*p.ShelfLifeDays < 0 || *p.ShelfLifeDays > 3650) {
		fe["shelf_life_days"] = "0 to 3650 days"
	}
	return fe
}

// Products handles GET /products: every product card, inactive ones too, with its lot count.
func (c *MasterDataController) Products(w http.ResponseWriter, r *http.Request) {
	rows, err := c.db.QueryContext(r.Context(),
		`SELECT p.sku, p.name, p.product_type, p.uom_code, p.pack_size_kg, p.species, p.standard_purchase_price, p.sale_price,
		        p.shelf_life_days, p.is_active, (SELECT COUNT(*) FROM lot l WHERE l.product_id = p.id),
		        (SELECT COUNT(*) FROM butchery_recipe_line rl WHERE rl.product_id = p.id)
		 FROM product p ORDER BY p.product_type, p.sku`)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer rows.Close()
	out := []ProductCardDto{}
	for rows.Next() {
		var p ProductCardDto
		var pack, buy, sale sql.NullFloat64
		var species sql.NullString
		var shelf sql.NullInt64
		if err := rows.Scan(&p.SKU, &p.Name, &p.ProductType, &p.Uom, &pack, &species, &buy, &sale, &shelf, &p.IsActive, &p.Lots, &p.Recipes); err != nil {
			writeDBError(w, r, err)
			return
		}
		p.PackSizeKg, p.StandardPurchasePrice, p.SalePrice = nullFloat(pack), nullFloat(buy), nullFloat(sale)
		if species.Valid {
			p.Species = &species.String
		}
		if shelf.Valid {
			v := int(shelf.Int64)
			p.ShelfLifeDays = &v
		}
		out = append(out, p)
	}
	if err := rows.Err(); err != nil {
		writeDBError(w, r, err)
		return
	}
	writeJSON(w, out)
}

func nullFloat(v sql.NullFloat64) *float64 {
	if !v.Valid {
		return nil
	}
	f := v.Float64
	return &f
}

// CreateProduct handles POST /products (supervisor session): a new product card, active.
func (c *MasterDataController) CreateProduct(w http.ResponseWriter, r *http.Request) {
	c.saveProduct(w, r, false)
}

// UpdateProduct handles POST /products/update (supervisor session): the card of an existing SKU.
// With lots, the unit and the type stay as they are (409): the lots' quantities and rules depend on them.
func (c *MasterDataController) UpdateProduct(w http.ResponseWriter, r *http.Request) {
	c.saveProduct(w, r, true)
}

func (c *MasterDataController) saveProduct(w http.ResponseWriter, r *http.Request, update bool) {
	var p ProductCardDto
	if !decodeJSON(w, r, &p) {
		return
	}
	if !update {
		p.IsActive = true
	}
	fe := validateProduct(&p)
	if len(fe) > 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fe)
		return
	}
	ctx := r.Context()
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer tx.Rollback()
	var unitOK int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM unit_of_measure WHERE code = ?`, p.Uom).Scan(&unitOK); err != nil {
		writeDBError(w, r, err)
		return
	}
	if unitOK == 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fieldErrors{"uom_code": "unknown unit " + p.Uom})
		return
	}
	actor := nonEmpty(OperatorFrom(ctx), "api")
	var id int64
	var old []byte
	if update {
		var cur ProductCardDto
		var pack, buy, sale sql.NullFloat64
		var species sql.NullString
		var shelf sql.NullInt64
		err := tx.QueryRowContext(ctx,
			`SELECT p.id, p.name, p.product_type, p.uom_code, p.pack_size_kg, p.species, p.standard_purchase_price, p.sale_price,
			        p.shelf_life_days, p.is_active, (SELECT COUNT(*) FROM lot l WHERE l.product_id = p.id),
			        (SELECT COUNT(*) FROM butchery_recipe_line rl WHERE rl.product_id = p.id)
			 FROM product p WHERE p.sku = ? FOR UPDATE`, p.SKU).
			Scan(&id, &cur.Name, &cur.ProductType, &cur.Uom, &pack, &species, &buy, &sale, &shelf, &cur.IsActive, &cur.Lots, &cur.Recipes)
		if err == sql.ErrNoRows {
			writeAPIError(w, r, http.StatusNotFound, "NOT_FOUND", "product "+p.SKU)
			return
		}
		if err != nil {
			writeDBError(w, r, err)
			return
		}
		cur.SKU, cur.PackSizeKg, cur.StandardPurchasePrice, cur.SalePrice = p.SKU, nullFloat(pack), nullFloat(buy), nullFloat(sale)
		if species.Valid {
			cur.Species = &species.String
		}
		if shelf.Valid {
			v := int(shelf.Int64)
			cur.ShelfLifeDays = &v
		}
		if (cur.Lots > 0 || cur.Recipes > 0) && (cur.Uom != p.Uom || cur.ProductType != p.ProductType) {
			writeAPIError(w, r, http.StatusConflict, "CONFLICT",
				fmt.Sprintf("%s has %d lots and %d recipe lines: its unit and type cannot change; create a new product card", p.SKU, cur.Lots, cur.Recipes))
			return
		}
		old, _ = json.Marshal(cur)
		if _, err := tx.ExecContext(ctx,
			`UPDATE product SET name = ?, product_type = ?, uom_code = ?, pack_size_kg = ?, species = ?, standard_purchase_price = ?,
			        sale_price = ?, shelf_life_days = ?, is_active = ? WHERE id = ?`,
			p.Name, p.ProductType, p.Uom, p.PackSizeKg, p.Species, p.StandardPurchasePrice, p.SalePrice, p.ShelfLifeDays, p.IsActive, id); err != nil {
			writeDBError(w, r, err)
			return
		}
		p.Lots = cur.Lots
		p.Recipes = cur.Recipes
	} else {
		res, err := tx.ExecContext(ctx,
			`INSERT INTO product (sku, name, product_type, uom_code, pack_size_kg, species, standard_purchase_price, sale_price, shelf_life_days, is_active)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)`,
			p.SKU, p.Name, p.ProductType, p.Uom, p.PackSizeKg, p.Species, p.StandardPurchasePrice, p.SalePrice, p.ShelfLifeDays)
		if err != nil {
			writeDBError(w, r, err) // a used SKU is 409 CONFLICT
			return
		}
		id, _ = res.LastInsertId()
	}
	action := map[bool]string{false: "PRODUCT_CREATE", true: "PRODUCT_UPDATE"}[update]
	if err := masterAudit(ctx, tx, "product", id, action, old, p, actor); err != nil {
		writeDBError(w, r, err)
		return
	}
	if err := tx.Commit(); err != nil {
		writeDBError(w, r, err)
		return
	}
	writeSaved(w, update, p)
}

// masterAudit writes one audit_log row (old is nil for a new record).
func masterAudit(ctx context.Context, tx *sql.Tx, table string, id int64, action string, old []byte, current interface{}, actor string) error {
	cur, _ := json.Marshal(current)
	var oldVal interface{}
	if old != nil {
		oldVal = string(old)
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO audit_log (table_name, row_id, action, old_value, new_value, changed_by) VALUES (?, ?, ?, ?, ?, ?)`,
		table, id, action, oldVal, string(cur), actor)
	return err
}

func writeSaved(w http.ResponseWriter, update bool, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	if update {
		w.WriteHeader(http.StatusOK)
	} else {
		w.WriteHeader(http.StatusCreated)
	}
	_ = json.NewEncoder(w).Encode(v)
}

// ---------------------------------------------------------------- units

type UnitDto struct {
	Code     string `json:"code"`
	Name     string `json:"name"`
	IsWeight bool   `json:"is_weight"`
}

// Units handles GET /units.
func (c *MasterDataController) Units(w http.ResponseWriter, r *http.Request) {
	rows, err := c.db.QueryContext(r.Context(), `SELECT code, name, is_weight FROM unit_of_measure ORDER BY code`)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer rows.Close()
	out := []UnitDto{}
	for rows.Next() {
		var u UnitDto
		if err := rows.Scan(&u.Code, &u.Name, &u.IsWeight); err != nil {
			writeDBError(w, r, err)
			return
		}
		out = append(out, u)
	}
	if err := rows.Err(); err != nil {
		writeDBError(w, r, err)
		return
	}
	writeJSON(w, out)
}

// CreateUnit handles POST /units (supervisor session). Units are never changed: every quantity
// recorded in one depends on it.
func (c *MasterDataController) CreateUnit(w http.ResponseWriter, r *http.Request) {
	var u UnitDto
	if !decodeJSON(w, r, &u) {
		return
	}
	u.Code, u.Name = strings.ToUpper(strings.TrimSpace(u.Code)), strings.TrimSpace(u.Name)
	fe := fieldErrors{}
	if !unitPattern.MatchString(u.Code) {
		fe["code"] = "1 to 10 letters A-Z"
	}
	if u.Name == "" || len([]rune(u.Name)) > 50 {
		fe["name"] = "required, max 50 characters"
	}
	if len(fe) > 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fe)
		return
	}
	ctx := r.Context()
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `INSERT INTO unit_of_measure (code, name, is_weight) VALUES (?, ?, ?)`, u.Code, u.Name, u.IsWeight); err != nil {
		writeDBError(w, r, err)
		return
	}
	if err := masterAudit(ctx, tx, "unit_of_measure", 0, "UNIT_CREATE", nil, u, nonEmpty(OperatorFrom(ctx), "api")); err != nil {
		writeDBError(w, r, err)
		return
	}
	if err := tx.Commit(); err != nil {
		writeDBError(w, r, err)
		return
	}
	writeSaved(w, false, u)
}

// ---------------------------------------------------------------- suppliers and customers

type PartnerDto struct {
	Code          string `json:"code"`
	Name          string `json:"name"`
	Type          string `json:"type"`                    // the supplier's or the customer's type
	SupplierType  string `json:"supplier_type,omitempty"` // the same, under the name the older endpoints use
	CustomerType  string `json:"customer_type,omitempty"`
	ContactPerson string `json:"contact_person"`
	Phone         string `json:"phone"`
	Email         string `json:"email"`
	Address       string `json:"address"`
	Documents     int    `json:"documents"` // read only: deliveries, orders or shipments that use it
}

func validatePartner(p *PartnerDto, types map[string]bool, typeNames string) fieldErrors {
	fe := fieldErrors{}
	p.Type = nonEmpty(p.Type, nonEmpty(p.SupplierType, p.CustomerType))
	p.Code, p.Name, p.Type = strings.ToUpper(strings.TrimSpace(p.Code)), strings.TrimSpace(p.Name), strings.ToUpper(strings.TrimSpace(p.Type))
	p.ContactPerson, p.Phone, p.Email, p.Address = strings.TrimSpace(p.ContactPerson), strings.TrimSpace(p.Phone), strings.TrimSpace(p.Email), strings.TrimSpace(p.Address)
	if p.Code == "" || len(p.Code) > 20 || !codePattern.MatchString(p.Code) {
		fe["code"] = "required, max 20 characters, A-Z 0-9 -"
	}
	if p.Name == "" || len([]rune(p.Name)) > 150 {
		fe["name"] = "required, max 150 characters"
	}
	if !types[p.Type] {
		fe["type"] = typeNames
	}
	if len([]rune(p.ContactPerson)) > 100 {
		fe["contact_person"] = "max 100 characters"
	}
	if len(p.Phone) > 30 {
		fe["phone"] = "max 30 characters"
	}
	if len(p.Email) > 150 || (p.Email != "" && !strings.Contains(p.Email, "@")) {
		fe["email"] = "an e-mail address, max 150 characters"
	}
	if len([]rune(p.Address)) > 255 {
		fe["address"] = "max 255 characters"
	}
	return fe
}

type partnerKind struct {
	table, typeColumn, typeNames, auditPrefix string
	types                                     map[string]bool
	documents                                 string // counts the documents that use the row (alias t)
	typeLocked                                bool   // the type cannot change once documents use it
}

var supplierKind = partnerKind{table: "supplier", typeColumn: "supplier_type", typeNames: "RAW_MATERIAL, SUBCONTRACTOR or LIVESTOCK",
	auditPrefix: "SUPPLIER", types: supplierTypes, typeLocked: true,
	documents: `(SELECT COUNT(*) FROM lot l WHERE l.supplier_id = t.id) + (SELECT COUNT(*) FROM purchase_receipt pr WHERE pr.supplier_id = t.id)
	          + (SELECT COUNT(*) FROM production_order po WHERE po.subcontractor_id = t.id)`}

var customerKind = partnerKind{table: "customer", typeColumn: "customer_type", typeNames: "MARKET, WHOLESALER, RESTAURANT or WALK_IN",
	auditPrefix: "CUSTOMER", types: customerTypes,
	documents: `(SELECT COUNT(*) FROM shipment s WHERE s.customer_id = t.id)`}

// named fills the type under the field name of this kind (supplier_type / customer_type).
func (k partnerKind) named(p *PartnerDto) {
	p.SupplierType, p.CustomerType = "", ""
	if k.table == "customer" {
		p.CustomerType = p.Type
	} else {
		p.SupplierType = p.Type
	}
}

// Suppliers handles GET /suppliers.
func (c *MasterDataController) Suppliers(w http.ResponseWriter, r *http.Request) {
	c.listPartners(w, r, supplierKind)
}

// CreateSupplier / UpdateSupplier handle POST /suppliers and /suppliers/update (supervisor session).
func (c *MasterDataController) CreateSupplier(w http.ResponseWriter, r *http.Request) {
	c.savePartner(w, r, supplierKind, false)
}
func (c *MasterDataController) UpdateSupplier(w http.ResponseWriter, r *http.Request) {
	c.savePartner(w, r, supplierKind, true)
}

// Customers handles GET /customers (code, name, customer_type, contacts, documents).
func (c *MasterDataController) Customers(w http.ResponseWriter, r *http.Request) {
	c.listPartners(w, r, customerKind)
}

// CreateCustomer / UpdateCustomer handle POST /customers and /customers/update (supervisor
// session). The contacts are what the recall call-down list prints.
func (c *MasterDataController) CreateCustomer(w http.ResponseWriter, r *http.Request) {
	c.savePartner(w, r, customerKind, false)
}
func (c *MasterDataController) UpdateCustomer(w http.ResponseWriter, r *http.Request) {
	c.savePartner(w, r, customerKind, true)
}

func (c *MasterDataController) listPartners(w http.ResponseWriter, r *http.Request, k partnerKind) {
	rows, err := c.db.QueryContext(r.Context(), `SELECT t.code, t.name, t.`+k.typeColumn+`, COALESCE(t.contact_person, ''), COALESCE(t.phone, ''),
		COALESCE(t.email, ''), COALESCE(t.address, ''), `+k.documents+` FROM `+k.table+` t ORDER BY t.code`)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer rows.Close()
	out := []PartnerDto{}
	for rows.Next() {
		var p PartnerDto
		if err := rows.Scan(&p.Code, &p.Name, &p.Type, &p.ContactPerson, &p.Phone, &p.Email, &p.Address, &p.Documents); err != nil {
			writeDBError(w, r, err)
			return
		}
		k.named(&p)
		out = append(out, p)
	}
	if err := rows.Err(); err != nil {
		writeDBError(w, r, err)
		return
	}
	writeJSON(w, out)
}

func (c *MasterDataController) savePartner(w http.ResponseWriter, r *http.Request, k partnerKind, update bool) {
	var p PartnerDto
	if !decodeJSON(w, r, &p) {
		return
	}
	if fe := validatePartner(&p, k.types, k.typeNames); len(fe) > 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fe)
		return
	}
	ctx := r.Context()
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer tx.Rollback()
	actor := nonEmpty(OperatorFrom(ctx), "api")
	var id int64
	var old []byte
	if update {
		var cur PartnerDto
		err := tx.QueryRowContext(ctx, `SELECT t.id, t.code, t.name, t.`+k.typeColumn+`, COALESCE(t.contact_person, ''), COALESCE(t.phone, ''),
			COALESCE(t.email, ''), COALESCE(t.address, ''), `+k.documents+` FROM `+k.table+` t WHERE t.code = ? FOR UPDATE`, p.Code).
			Scan(&id, &cur.Code, &cur.Name, &cur.Type, &cur.ContactPerson, &cur.Phone, &cur.Email, &cur.Address, &cur.Documents)
		if err == sql.ErrNoRows {
			writeAPIError(w, r, http.StatusNotFound, "NOT_FOUND", k.table+" "+p.Code)
			return
		}
		if err != nil {
			writeDBError(w, r, err)
			return
		}
		if k.typeLocked && cur.Documents > 0 && cur.Type != p.Type {
			writeAPIError(w, r, http.StatusConflict, "CONFLICT",
				fmt.Sprintf("%s is used by %d documents: its type cannot change", p.Code, cur.Documents))
			return
		}
		old, _ = json.Marshal(cur)
		if _, err := tx.ExecContext(ctx, `UPDATE `+k.table+` SET name = ?, `+k.typeColumn+` = ?, contact_person = NULLIF(?, ''), phone = NULLIF(?, ''),
			email = NULLIF(?, ''), address = NULLIF(?, '') WHERE id = ?`, p.Name, p.Type, p.ContactPerson, p.Phone, p.Email, p.Address, id); err != nil {
			writeDBError(w, r, err)
			return
		}
		p.Documents = cur.Documents
	} else {
		res, err := tx.ExecContext(ctx, `INSERT INTO `+k.table+` (code, name, `+k.typeColumn+`, contact_person, phone, email, address)
			VALUES (?, ?, ?, NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''))`, p.Code, p.Name, p.Type, p.ContactPerson, p.Phone, p.Email, p.Address)
		if err != nil {
			writeDBError(w, r, err)
			return
		}
		id, _ = res.LastInsertId()
	}
	k.named(&p)
	action := k.auditPrefix + map[bool]string{false: "_CREATE", true: "_UPDATE"}[update]
	if err := masterAudit(ctx, tx, k.table, id, action, old, p, actor); err != nil {
		writeDBError(w, r, err)
		return
	}
	if err := tx.Commit(); err != nil {
		writeDBError(w, r, err)
		return
	}
	writeSaved(w, update, p)
}

// ---------------------------------------------------------------- butchery recipes

type RecipeLineDto struct {
	SKU              string   `json:"sku"`
	Name             string   `json:"name,omitempty"`
	OutputKind       string   `json:"output_kind"` // CUT | BY_PRODUCT | WASTE
	ExpectedYieldPct float64  `json:"expected_yield_pct"`
	ValuePricePerKg  *float64 `json:"value_price_per_kg"` // the VALUE key's price; none for waste
}

type RecipeDto struct {
	Code              string          `json:"code"`
	Name              string          `json:"name"`
	Species           string          `json:"species"`
	DefaultAllocation string          `json:"default_allocation"` // QUANTITY | VALUE
	Operations        int             `json:"operations"`         // read only: butchery operations that used it
	Lines             []RecipeLineDto `json:"lines"`
}

// validateRecipe: the lines describe where the whole carcass goes (cuts + by-products + waste =
// 100 % within a point), every sellable output has a value price and waste carries no cost.
func validateRecipe(rc *RecipeDto) fieldErrors {
	fe := fieldErrors{}
	rc.Code, rc.Name = strings.ToUpper(strings.TrimSpace(rc.Code)), strings.TrimSpace(rc.Name)
	rc.Species, rc.DefaultAllocation = strings.ToUpper(strings.TrimSpace(rc.Species)), strings.ToUpper(strings.TrimSpace(rc.DefaultAllocation))
	if rc.Code == "" || len(rc.Code) > 30 || !codePattern.MatchString(rc.Code) {
		fe["code"] = "required, max 30 characters, A-Z 0-9 -"
	}
	if rc.Name == "" || len([]rune(rc.Name)) > 100 {
		fe["name"] = "required, max 100 characters"
	}
	if rc.Species == "" || len(rc.Species) > 20 {
		fe["species"] = "required, max 20 characters"
	}
	if rc.DefaultAllocation != "QUANTITY" && rc.DefaultAllocation != "VALUE" {
		fe["default_allocation"] = "QUANTITY or VALUE"
	}
	if len(rc.Lines) == 0 || len(rc.Lines) > 30 {
		fe["lines"] = "1 to 30 lines"
		return fe
	}
	seen := map[string]bool{}
	sum := 0.0
	for i, l := range rc.Lines {
		k := fmt.Sprintf("lines[%d].", i)
		l.SKU, l.OutputKind = strings.ToUpper(strings.TrimSpace(l.SKU)), strings.ToUpper(strings.TrimSpace(l.OutputKind))
		if l.SKU == "" {
			fe[k+"sku"] = "required"
		} else if seen[l.SKU] {
			fe[k+"sku"] = "listed twice"
		}
		seen[l.SKU] = true
		if l.OutputKind != "CUT" && l.OutputKind != "BY_PRODUCT" && l.OutputKind != "WASTE" {
			fe[k+"output_kind"] = "CUT, BY_PRODUCT or WASTE"
		}
		if math.IsNaN(l.ExpectedYieldPct) || l.ExpectedYieldPct <= 0 || l.ExpectedYieldPct > 100 {
			fe[k+"expected_yield_pct"] = "more than 0, at most 100"
		} else {
			sum += l.ExpectedYieldPct
		}
		if l.OutputKind == "WASTE" {
			l.ValuePricePerKg = nil // waste takes no cost under either key
		} else if l.ValuePricePerKg == nil || !validAmount(l.ValuePricePerKg) || *l.ValuePricePerKg <= 0 {
			fe[k+"value_price_per_kg"] = "required for the value key, more than 0"
		}
		rc.Lines[i] = l
	}
	if _, bad := fe["lines"]; !bad && (sum < 99 || sum > 101) {
		fe["lines"] = fmt.Sprintf("expected yields add up to %.1f %%; cuts, by-products and waste must make 100 %% of the carcass", sum)
	}
	return fe
}

// Recipes handles GET /butchery/recipes: every recipe with its lines and how often it was used.
func (c *MasterDataController) Recipes(w http.ResponseWriter, r *http.Request) {
	out, err := loadRecipes(r.Context(), c.db)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	writeJSON(w, out)
}

func loadRecipes(ctx context.Context, q sqlRunner) ([]RecipeDto, error) {
	rows, err := q.QueryContext(ctx,
		`SELECT r.code, r.name, r.species, r.default_allocation,
		        (SELECT COUNT(*) FROM butchery_operation bo WHERE bo.recipe_id = r.id),
		        p.sku, p.name, rl.output_kind, rl.expected_yield_pct, rl.value_price_per_kg
		 FROM butchery_recipe r
		 LEFT JOIN butchery_recipe_line rl ON rl.recipe_id = r.id
		 LEFT JOIN product p ON p.id = rl.product_id
		 ORDER BY r.code, FIELD(rl.output_kind, 'CUT', 'BY_PRODUCT', 'WASTE'), rl.expected_yield_pct DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []RecipeDto{}
	for rows.Next() {
		var rc RecipeDto
		var sku, name, kind sql.NullString
		var pct, price sql.NullFloat64
		if err := rows.Scan(&rc.Code, &rc.Name, &rc.Species, &rc.DefaultAllocation, &rc.Operations, &sku, &name, &kind, &pct, &price); err != nil {
			return nil, err
		}
		if n := len(out); n == 0 || out[n-1].Code != rc.Code {
			rc.Lines = []RecipeLineDto{}
			out = append(out, rc)
		}
		if sku.Valid {
			last := &out[len(out)-1]
			last.Lines = append(last.Lines, RecipeLineDto{SKU: sku.String, Name: name.String, OutputKind: kind.String,
				ExpectedYieldPct: pct.Float64, ValuePricePerKg: nullFloat(price)})
		}
	}
	return out, rows.Err()
}

// CreateRecipe handles POST /butchery/recipes (supervisor session). A recipe is never edited: the
// operations recorded with it keep meaning what they meant; a new version gets a new code.
func (c *MasterDataController) CreateRecipe(w http.ResponseWriter, r *http.Request) {
	var rc RecipeDto
	if !decodeJSON(w, r, &rc) {
		return
	}
	if fe := validateRecipe(&rc); len(fe) > 0 {
		writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fe)
		return
	}
	ctx := r.Context()
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		writeDBError(w, r, err)
		return
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `INSERT INTO butchery_recipe (code, name, species, default_allocation) VALUES (?, ?, ?, ?)`,
		rc.Code, rc.Name, rc.Species, rc.DefaultAllocation)
	if err != nil {
		writeDBError(w, r, err) // a used code is 409 CONFLICT
		return
	}
	recipeID, _ := res.LastInsertId()
	for i, l := range rc.Lines {
		var productID int64
		var ptype, uom string
		var active bool
		err := tx.QueryRowContext(ctx, `SELECT id, product_type, uom_code, is_active FROM product WHERE sku = ? FOR UPDATE`, l.SKU).Scan(&productID, &ptype, &uom, &active)
		if err == sql.ErrNoRows {
			writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT", fieldErrors{fmt.Sprintf("lines[%d].sku", i): "unknown product " + l.SKU})
			return
		}
		if err != nil {
			writeDBError(w, r, err)
			return
		}
		if ptype != l.OutputKind || uom != "KG" || !active {
			writeAPIError(w, r, http.StatusUnprocessableEntity, "INVALID_INPUT",
				fieldErrors{fmt.Sprintf("lines[%d].sku", i): fmt.Sprintf("%s must be an active %s product in KG", l.SKU, l.OutputKind)})
			return
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO butchery_recipe_line (recipe_id, product_id, output_kind, expected_yield_pct, value_price_per_kg)
			VALUES (?, ?, ?, ?, ?)`, recipeID, productID, l.OutputKind, l.ExpectedYieldPct, l.ValuePricePerKg); err != nil {
			writeDBError(w, r, err)
			return
		}
	}
	if err := masterAudit(ctx, tx, "butchery_recipe", recipeID, "RECIPE_CREATE", nil, rc, nonEmpty(OperatorFrom(ctx), "api")); err != nil {
		writeDBError(w, r, err)
		return
	}
	if err := tx.Commit(); err != nil {
		writeDBError(w, r, err)
		return
	}
	writeSaved(w, false, rc)
}
