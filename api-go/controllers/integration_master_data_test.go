package controllers

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// Exercise the same supervisor boundary as the registered routes, including the audit actor.
func masterCall(t *testing.T, h http.HandlerFunc, body interface{}, want int) []byte {
	t.Helper()
	m := NewSessionManager()
	token, _ := m.Issue("master-data-test")
	var buf bytes.Buffer
	if err := json.NewEncoder(&buf).Encode(body); err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest("POST", "/", &buf)
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	m.RequireOperator(h)(w, r)
	if w.Code != want {
		t.Fatalf("want %d, got %d: %s", want, w.Code, w.Body.Bytes())
	}
	return w.Body.Bytes()
}

func masterNumber(v float64) *float64 { return &v }

func TestIntegrationMasterDataProductsProtectPastCostsAndAudit(t *testing.T) {
	db := testDB(t)
	defer db.Close()
	c := NewMasterDataController(db)
	run := runSuffix()
	p := ProductCardDto{SKU: " md-" + run + " ", Name: "Yeni hammadde", ProductType: "RAW_MATERIAL", Uom: "NOPE"}
	masterCall(t, c.CreateProduct, p, 422)
	p.Uom = "kg"
	var saved ProductCardDto
	if err := json.Unmarshal(masterCall(t, c.CreateProduct, p, 201), &saved); err != nil {
		t.Fatal(err)
	}
	if saved.SKU != "MD-"+run || saved.Uom != "KG" || !saved.IsActive {
		t.Fatalf("normalization: %+v", saved)
	}
	masterCall(t, c.CreateProduct, saved, 409)
	saved.IsActive = false
	masterCall(t, c.UpdateProduct, saved, 200)
	today := time.Now().UTC().Format("2006-01-02")
	purchase := PurchaseRequest{ReceiptNo: "MDA-" + run, SupplierCode: "TED-ET-01", ReceiptDate: today,
		Lines: []PurchaseLineRequest{{SKU: saved.SKU, LotNo: "MDL-" + run, Qty: 5, UnitPrice: 9.7}}}
	masterCall(t, NewEntryController(db).Purchase, purchase, 422)
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM purchase_receipt WHERE receipt_no=?`, purchase.ReceiptNo).Scan(&n); err != nil || n != 0 {
		t.Fatalf("failed purchase left receipt: %d %v", n, err)
	}
	saved.IsActive = true
	masterCall(t, c.UpdateProduct, saved, 200)
	masterCall(t, NewEntryController(db).Purchase, purchase, 201)
	changed := saved
	changed.Uom = "ADET"
	masterCall(t, c.UpdateProduct, changed, 409)
	changed = saved
	changed.ProductType = "CUT"
	masterCall(t, c.UpdateProduct, changed, 409)
	saved.Name, saved.StandardPurchasePrice = "Yeni ad", masterNumber(500)
	masterCall(t, c.UpdateProduct, saved, 200)
	var cost, stock float64
	if err := db.QueryRow(`SELECT l.unit_cost,sb.qty FROM lot l JOIN stock_balance sb ON sb.lot_id=l.id WHERE l.lot_no=?`, purchase.Lines[0].LotNo).Scan(&cost, &stock); err != nil {
		t.Fatal(err)
	}
	if cost != 9.7 || stock != 5 {
		t.Fatalf("past lot changed: cost %v qty %v", cost, stock)
	}
	var actor, oldName, newName string
	if err := db.QueryRow(`SELECT a.changed_by, JSON_UNQUOTE(JSON_EXTRACT(a.old_value,'$.name')), JSON_UNQUOTE(JSON_EXTRACT(a.new_value,'$.name'))
		FROM audit_log a JOIN product p ON p.id=a.row_id WHERE a.table_name='product' AND p.sku=? ORDER BY a.id DESC LIMIT 1`, saved.SKU).Scan(&actor, &oldName, &newName); err != nil {
		t.Fatal(err)
	}
	if actor != "master-data-test" || oldName != "Yeni hammadde" || newName != saved.Name {
		t.Fatalf("audit: %q %q %q", actor, oldName, newName)
	}
	code, body := call(t, c.Products, "GET", "/products", nil)
	var cards []ProductCardDto
	if code != 200 || json.Unmarshal(body, &cards) != nil {
		t.Fatalf("listing: %d %s", code, body)
	}
	found := false
	for _, card := range cards {
		if card.SKU == saved.SKU {
			found = card.Lots == 1 && card.Name == saved.Name
		}
	}
	if !found {
		t.Fatal("saved product and lot count missing from list")
	}
}

func TestIntegrationMasterDataUnitsPartnersAndRecallContacts(t *testing.T) {
	db := testDB(t)
	defer db.Close()
	c := NewMasterDataController(db)
	run := runSuffix()
	unitCode := "MD" + strings.Map(func(r rune) rune { return 'A' + r - '0' }, run)
	unit := UnitDto{Code: unitCode, Name: "Koli", IsWeight: false}
	masterCall(t, c.CreateUnit, unit, 201)
	masterCall(t, c.CreateUnit, unit, 409)
	masterCall(t, c.CreateUnit, UnitDto{Code: "!", Name: "Invalid"}, 422)
	code, body := call(t, c.Units, "GET", "/units", nil)
	if code != 200 || !bytes.Contains(body, []byte(unitCode)) {
		t.Fatalf("units: %d %s", code, body)
	}
	supplier := PartnerDto{Code: "MDT-" + run, Name: "Tedarikçi", Type: "RAW_MATERIAL", Phone: "123", Email: "test@example.test"}
	masterCall(t, c.CreateSupplier, supplier, 201)
	masterCall(t, c.CreateSupplier, supplier, 409)
	today := time.Now().UTC().Format("2006-01-02")
	purchase := PurchaseRequest{ReceiptNo: "MDP-" + run, SupplierCode: supplier.Code, ReceiptDate: today,
		Lines: []PurchaseLineRequest{{SKU: "HM-BAHARAT", LotNo: "MDP-L-" + run, Qty: 1, UnitPrice: 24, ExpiryDate: time.Now().UTC().AddDate(0, 1, 0).Format("2006-01-02")}}}
	masterCall(t, NewEntryController(db).Purchase, purchase, 201)
	supplier.Type = "SUBCONTRACTOR"
	masterCall(t, c.UpdateSupplier, supplier, 409)
	supplier.Type, supplier.Phone = "RAW_MATERIAL", "555-0101"
	masterCall(t, c.UpdateSupplier, supplier, 200)
	customer := PartnerDto{Code: "MDM-" + run, Name: "Yeni market", Type: "MARKET", ContactPerson: "Eski kişi", Phone: "111", Email: "market@example.test", Address: "Adres"}
	masterCall(t, c.CreateCustomer, customer, 201)
	lot := "MDS-" + run
	shipmentLot(t, db, lot, 3, -1, 30)
	ship := ShipmentRequest{ShipmentNo: "MD-SVK-" + run, CustomerCode: customer.Code, FromWarehouse: "ANA-DEPO", ShipDate: today, Lines: []ShipmentLineRequest{{LotNo: lot, Qty: 3}}}
	if status, b := shipAs(t, NewShipmentController(db), ship); status != 201 {
		t.Fatalf("shipment: %d %s", status, b)
	}
	customer.ContactPerson, customer.Phone = "Yeni kişi", "555-0202"
	masterCall(t, c.UpdateCustomer, customer, 200)
	var phone, contact string
	if err := db.QueryRow(`SELECT c.phone,c.contact_person FROM shipment s JOIN customer c ON c.id=s.customer_id WHERE s.shipment_no=?`, ship.ShipmentNo).Scan(&phone, &contact); err != nil {
		t.Fatal(err)
	}
	if phone != customer.Phone || contact != customer.ContactPerson {
		t.Fatal("shipment does not resolve updated recall contact")
	}
	for _, h := range []http.HandlerFunc{c.Suppliers, c.Customers} {
		if status, b := call(t, h, "GET", "/", nil); status != 200 || !bytes.Contains(b, []byte("555-0")) {
			t.Fatalf("partners: %d %s", status, b)
		}
	}
	// A customer return works after every pack has left stock; duplicate quantities cannot inflate it.
	ret := ReturnRequest{LotNo: lot, Warehouse: "KARANTINA", CustomerCode: customer.Code, Qty: 2, Note: "Fiziksel iade"}
	masterCall(t, NewMovementsController(db).Return, ret, 201)
	masterCall(t, NewMovementsController(db).Return, ret, 409)
	var stock float64
	if err := db.QueryRow(`SELECT SUM(sb.qty) FROM stock_balance sb JOIN lot l ON l.id=sb.lot_id WHERE l.lot_no=?`, lot).Scan(&stock); err != nil || stock != 2 {
		t.Fatalf("return stock: %v %v", stock, err)
	}
}

func TestIntegrationMasterDataRecipeKeysAndRenamedProducts(t *testing.T) {
	db := testDB(t)
	defer db.Close()
	c := NewMasterDataController(db)
	run := runSuffix()
	cut := ProductCardDto{SKU: "MDC-" + run, Name: "Özel parça", ProductType: "CUT", Uom: "KG", SalePrice: masterNumber(20), IsActive: true}
	by := ProductCardDto{SKU: "MDB-" + run, Name: "Özel yan ürün", ProductType: "BY_PRODUCT", Uom: "KG", SalePrice: masterNumber(5), IsActive: true}
	masterCall(t, c.CreateProduct, cut, 201)
	masterCall(t, c.CreateProduct, by, 201)
	recipe := RecipeDto{Code: "MDR-" + run, Name: "Özel reçete", Species: "KUZU", DefaultAllocation: "QUANTITY", Lines: []RecipeLineDto{
		{SKU: cut.SKU, OutputKind: "CUT", ExpectedYieldPct: 70, ValuePricePerKg: masterNumber(20)},
		{SKU: by.SKU, OutputKind: "BY_PRODUCT", ExpectedYieldPct: 20, ValuePricePerKg: masterNumber(5)},
		{SKU: "KZ-FIRE", OutputKind: "WASTE", ExpectedYieldPct: 10}}}
	bad := recipe
	bad.Lines = append([]RecipeLineDto(nil), recipe.Lines...)
	bad.Lines[0].ExpectedYieldPct = 1
	masterCall(t, c.CreateRecipe, bad, 422)
	bad = recipe
	bad.Lines = append([]RecipeLineDto(nil), recipe.Lines...)
	bad.Lines[1].SKU = cut.SKU // duplicate / wrong type
	masterCall(t, c.CreateRecipe, bad, 422)
	bad.Lines[1].SKU = "NO-SUCH-PRODUCT"
	masterCall(t, c.CreateRecipe, bad, 422)
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM butchery_recipe WHERE code=?`, recipe.Code).Scan(&n); err != nil || n != 0 {
		t.Fatalf("invalid recipe leaked: %d %v", n, err)
	}
	masterCall(t, c.CreateRecipe, recipe, 201)
	masterCall(t, c.CreateRecipe, recipe, 409)
	// A recipe is already a commitment to a product's unit/type, even before a lot exists.
	changed := cut
	changed.Uom = "ADET"
	masterCall(t, c.UpdateProduct, changed, 409)
	today := time.Now().UTC().Format("2006-01-02")
	for _, method := range []string{"QUANTITY", "VALUE"} {
		t.Run(method, func(t *testing.T) {
			id := run + "-" + method[:1]
			carcass := "MDK-" + id
			masterCall(t, NewEntryController(db).Purchase, PurchaseRequest{ReceiptNo: "MDK-A-" + id, SupplierCode: "TED-HAY-01", ReceiptDate: today, Lines: []PurchaseLineRequest{{SKU: "KZ-KARKAS", LotNo: carcass, Qty: 10, UnitPrice: 11.5, ExpiryDate: time.Now().UTC().AddDate(0, 0, 7).Format("2006-01-02")}}}, 201)
			req := ButcheryOperationRequest{OpNo: "MD-OP-" + id, InputLotNo: carcass, InputWeightKg: 10, RecipeCode: recipe.Code, AllocationMethod: method, OpDate: today, Outputs: []ButcheryOutputRequest{
				{SKU: cut.SKU, Kind: "CUT", WeightKg: 7, LotNo: "MD-OUT-" + id},
				{SKU: by.SKU, Kind: "BY_PRODUCT", WeightKg: 2, LotNo: "MD-BY-" + id},
				{SKU: "KZ-FIRE", Kind: "WASTE", WeightKg: 1}}}
			if method == "QUANTITY" {
				req.AllocationMethod = ""
			} // use recipe's predefined key
			var result ButcheryOperationResultDto
			if err := json.Unmarshal(masterCall(t, NewEntryController(db).ButcheryOperation, req, 201), &result); err != nil {
				t.Fatal(err)
			}
			if result.AllocationMethod != method || result.TotalCost != 115 {
				t.Fatalf("posted %+v", result)
			}
			before := map[string][]byte{}
			for _, key := range []string{"QUANTITY", "VALUE"} {
				code, b := call(t, NewButcheryController(db).Allocation, "GET", "/butchery/allocation?op_no="+req.OpNo+"&method="+key, nil)
				if code != 200 {
					t.Fatalf("preview: %d %s", code, b)
				}
				before[key] = b
			}
			cut.Name, cut.SalePrice = "Ürün adı değişti", masterNumber(200)
			masterCall(t, c.UpdateProduct, cut, 200)
			for _, key := range []string{"QUANTITY", "VALUE"} {
				var old, after AllocationPreviewDto
				if err := json.Unmarshal(before[key], &old); err != nil {
					t.Fatal(err)
				}
				code, b := call(t, NewButcheryController(db).Allocation, "GET", "/butchery/allocation?op_no="+req.OpNo+"&method="+key, nil)
				if code != 200 || json.Unmarshal(b, &after) != nil || !after.SumsExact || after.Allocated != 115 {
					t.Fatalf("allocation: %d %s", code, b)
				}
				for i, l := range old.Lines {
					if l.AllocatedCost != after.Lines[i].AllocatedCost {
						t.Fatalf("%s changed with product name/price: %s -> %s", key, before[key], b)
					}
				}
			}
			if err := db.QueryRow(`SELECT COUNT(*) FROM lot_link ll JOIN lot l ON l.id=ll.parent_lot_id WHERE l.lot_no=? AND ll.qty_consumed>0 AND ll.qty_produced>0`, carcass).Scan(&n); err != nil || n != 2 {
				t.Fatalf("missing by-product/cut trace links: %d %v", n, err)
			}
		})
	}
	code, b := call(t, c.Recipes, "GET", "/butchery/recipes", nil)
	var list []RecipeDto
	if code != 200 || json.Unmarshal(b, &list) != nil {
		t.Fatalf("recipes: %d %s", code, b)
	}
	found := false
	for _, r := range list {
		if r.Code == recipe.Code {
			found = r.Operations == 2 && len(r.Lines) == 3
		}
	}
	if !found {
		t.Fatal("recipe usage/lines not listed")
	}
}

func TestMasterDataWritesRequireSupervisor(t *testing.T) {
	c := NewMasterDataController(nil) // an anonymous write must never reach the database
	m := NewSessionManager()
	for _, h := range []http.HandlerFunc{c.CreateProduct, c.UpdateProduct, c.CreateUnit, c.CreateSupplier, c.UpdateSupplier, c.CreateCustomer, c.UpdateCustomer, c.CreateRecipe} {
		code, b := call(t, m.RequireOperator(h), "POST", "/", map[string]string{})
		if code != 401 || errorCode(t, b) != "AUTH_REQUIRED" {
			t.Fatalf("anonymous write: %d %s", code, b)
		}
	}
}
