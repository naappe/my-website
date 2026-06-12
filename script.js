// =========================
// WHITE SAFFRON INVENTORY - COMPLETE SYSTEM
// =========================

let products = [];
let stockData = {};
let historyData = [];
let currentPage = 1;
let itemsPerPage = 10;

// Default sample products
const defaultProducts = [
    { 
        id: 1, 
        sku: "WM-001", 
        name: "Wireless Mouse", 
        category: "Electronics", 
        unit: "pcs", 
        rate: 25.00, 
        minStock: 10 
    },
    { 
        id: 2, 
        sku: "KB-002", 
        name: "Mechanical Keyboard", 
        category: "Electronics", 
        unit: "pcs", 
        rate: 85.00, 
        minStock: 10 
    },
    { 
        id: 3, 
        sku: "UC-003", 
        name: "USB Type-C Cable", 
        category: "Accessories", 
        unit: "pcs", 
        rate: 12.00, 
        minStock: 20 
    },
    { 
        id: 4, 
        sku: "MN-004", 
        name: "Monitor 24 inch", 
        category: "Electronics", 
        unit: "pcs", 
        rate: 199.99, 
        minStock: 5 
    },
    { 
        id: 5, 
        sku: "HD-005", 
        name: "HDMI Cable", 
        category: "Accessories", 
        unit: "pcs", 
        rate: 6.50, 
        minStock: 15 
    },
    { 
        id: 6, 
        sku: "WC-006", 
        name: "Webcam 1080p", 
        category: "Electronics", 
        unit: "pcs", 
        rate: 45.00, 
        minStock: 5 
    }
];

// Load data from localStorage
function loadData() {
    const storedProducts = localStorage.getItem("inventory_products");
    if (storedProducts) {
        products = JSON.parse(storedProducts);
    } else {
        products = [...defaultProducts];
    }

    const storedStock = localStorage.getItem("inventory_stock");
    if (storedStock) {
        stockData = JSON.parse(storedStock);
    } else {
        stockData = {};
        products.forEach(p => {
            stockData[p.sku] = { 
                qty: Math.floor(Math.random() * 50) + 5, 
                minStock: p.minStock, 
                updated: new Date().toLocaleString() 
            };
        });
    }

    const storedHistory = localStorage.getItem("inventory_history");
    if (storedHistory) {
        historyData = JSON.parse(storedHistory);
    } else {
        historyData = [];
        products.forEach(p => {
            addToHistory(p.sku, p.name, "INITIAL", stockData[p.sku]?.qty || 0, 0, stockData[p.sku]?.qty || 0, "Initial stock setup");
        });
    }

    updateDateTime();
    renderProducts();
    updateDashboard();
    populateDropdowns();
    renderHistory();
    updateLastUpdated();

    setInterval(updateDateTime, 1000);
}

function saveAll() {
    localStorage.setItem("inventory_products", JSON.stringify(products));
    localStorage.setItem("inventory_stock", JSON.stringify(stockData));
    localStorage.setItem("inventory_history", JSON.stringify(historyData));
    updateLastUpdated();
}

function updateLastUpdated() {
    document.getElementById("lastUpdated").innerText = "Last Updated: " + new Date().toLocaleString();
}

function updateDateTime() {
    const now = new Date();
    document.getElementById("currentDate").innerHTML = now.toLocaleDateString() + " | " + now.toLocaleTimeString();
}

function showToast(message, type = "success") {
    const toast = document.getElementById("toast");
    toast.textContent = message;
    toast.style.backgroundColor = type === "success" ? "#16a34a" : "#dc2626";
    toast.className = "toast show";
    setTimeout(() => {
        toast.className = "toast";
    }, 3000);
}

function showMessage(elementId, message, type) {
    const el = document.getElementById(elementId);
    if (el) {
        el.innerHTML = message;
        el.className = `toast-message ${type}`;
        el.style.display = "block";
        setTimeout(() => {
            el.style.display = "none";
        }, 3000);
    }
}

function showSection(sectionId) {
    document.querySelectorAll(".section").forEach(sec => sec.classList.remove("active-section"));
    document.getElementById(sectionId).classList.add("active-section");
    
    document.querySelectorAll(".menu li").forEach(li => li.classList.remove("active"));
    event.currentTarget.classList.add("active");
}

function renderProducts() {
    const tbody = document.getElementById("productTable");
    const searchTerm = document.getElementById("searchInput").value.toLowerCase();
    
    let filteredProducts = products;
    if (searchTerm) {
        filteredProducts = products.filter(p => 
            p.name.toLowerCase().includes(searchTerm) || 
            p.sku.toLowerCase().includes(searchTerm) ||
            (p.category && p.category.toLowerCase().includes(searchTerm))
        );
    }
    
    const start = (currentPage - 1) * itemsPerPage;
    const paginated = filteredProducts.slice(start, start + itemsPerPage);
    const totalPages = Math.ceil(filteredProducts.length / itemsPerPage);
    
    tbody.innerHTML = "";
    
    if (paginated.length === 0) {
        tbody.innerHTML = '<tr><td colspan="10" style="text-align:center;">No products found</td></tr>';
        return;
    }
    
    paginated.forEach(product => {
        const stock = stockData[product.sku]?.qty || 0;
        const min = stockData[product.sku]?.minStock || product.minStock || 10;
        const value = stock * product.rate;
        
        let status = "OK";
        let statusClass = "status-ok";
        if (stock <= 0) {
            status = "OUT";
            statusClass = "status-out";
        } else if (stock <= min) {
            status = "LOW";
            statusClass = "status-low";
        }
        
        tbody.innerHTML += `
            <tr>
                <td>${product.id}</td>
                <td><strong>${escapeHtml(product.name)}</strong></td>
                <td>${escapeHtml(product.sku)}</td>
                <td>${escapeHtml(product.category) || "-"}</td>
                <td><span class="${statusClass}">${stock}</span></td>
                <td>$${product.rate.toFixed(2)}</td>
                <td>${min}</td>
                <td>$${value.toFixed(2)}</td>
                <td>${stockData[product.sku]?.updated || "-"}</td>
                <td>
                    <button class="edit-btn" onclick="editProduct(${product.id})">
                        <i class="fa fa-pen"></i> Edit
                    </button>
                    <button class="delete-btn" onclick="deleteProduct(${product.id})">
                        <i class="fa fa-trash"></i> Delete
                    </button>
                 </td>
             </tr>
        `;
    });
    
    renderPagination(totalPages);
}

function renderPagination(totalPages) {
    const container = document.getElementById("pagination");
    if (!container) return;
    
    let html = `<button onclick="changePage(-1)">‹</button>`;
    for (let i = 1; i <= Math.min(totalPages, 5); i++) {
        html += `<button class="${i === currentPage ? 'active' : ''}" onclick="goToPage(${i})">${i}</button>`;
    }
    html += `<button onclick="changePage(1)">›</button>`;
    container.innerHTML = html;
}

function changePage(delta) {
    const totalProducts = products.length;
    const totalPages = Math.ceil(totalProducts / itemsPerPage);
    const newPage = currentPage + delta;
    if (newPage >= 1 && newPage <= totalPages) {
        currentPage = newPage;
        renderProducts();
    }
}

function goToPage(page) {
    currentPage = page;
    renderProducts();
}

function openAddProductModal() {
    document.getElementById("modalTitle").innerText = "Add Product";
    document.getElementById("editProductId").value = "";
    document.getElementById("productSKU").value = "";
    document.getElementById("productName").value = "";
    document.getElementById("productCategory").value = "";
    document.getElementById("unit").value = "pcs";
    document.getElementById("rate").value = "";
    document.getElementById("modalMinStock").value = "10";
    document.getElementById("modalStock").value = "0";
    document.getElementById("productModal").style.display = "block";
}

function editProduct(id) {
    const product = products.find(p => p.id === id);
    if (product) {
        document.getElementById("modalTitle").innerText = "Edit Product";
        document.getElementById("editProductId").value = product.id;
        document.getElementById("productSKU").value = product.sku;
        document.getElementById("productName").value = product.name;
        document.getElementById("productCategory").value = product.category || "";
        document.getElementById("unit").value = product.unit;
        document.getElementById("rate").value = product.rate;
        document.getElementById("modalMinStock").value = stockData[product.sku]?.minStock || product.minStock || 10;
        document.getElementById("modalStock").value = stockData[product.sku]?.qty || 0;
        document.getElementById("productModal").style.display = "block";
    }
}

function editSelectedProduct() {
    alert("Please click the Edit button on any product row to edit.");
}

function deleteProduct(id) {
    if (confirm("Are you sure you want to delete this product?")) {
        const product = products.find(p => p.id === id);
        if (product) {
            delete stockData[product.sku];
            products = products.filter(p => p.id !== id);
            addToHistory(product.sku, product.name, "DELETE", 0, stockData[product.sku]?.qty || 0, 0, "Product deleted");
            saveAll();
            renderProducts();
            updateDashboard();
            populateDropdowns();
            showToast("Product deleted successfully!", "success");
        }
    }
}

function deleteSelectedProduct() {
    alert("Please click the Delete button on any product row to delete.");
}

function saveProduct() {
    const id = document.getElementById("editProductId").value;
    const sku = document.getElementById("productSKU").value.trim();
    const name = document.getElementById("productName").value.trim();
    const category = document.getElementById("productCategory").value.trim();
    const unit = document.getElementById("unit").value.trim();
    const rate = parseFloat(document.getElementById("rate").value);
    const minStock = parseInt(document.getElementById("modalMinStock").value);
    const initialStock = parseInt(document.getElementById("modalStock").value);
    
    if (!sku || !name || isNaN(rate)) {
        showToast("Please fill all required fields (SKU, Name, Rate)", "error");
        return;
    }
    
    if (id) {
        // Edit existing product
        const index = products.findIndex(p => p.id == id);
        if (index !== -1) {
            const oldSku = products[index].sku;
            products[index] = { ...products[index], sku, name, category, unit, rate, minStock };
            if (oldSku !== sku) {
                stockData[sku] = stockData[oldSku];
                delete stockData[oldSku];
            }
            if (stockData[sku]) {
                stockData[sku].minStock = minStock;
            }
            addToHistory(sku, name, "EDIT", 0, 0, 0, `Product edited`);
            showToast("Product updated successfully!", "success");
        }
    } else {
        // Add new product
        if (products.find(p => p.sku === sku)) {
            showToast("SKU already exists!", "error");
            return;
        }
        const newId = Date.now();
        products.push({ id: newId, sku, name, category: category || "Uncategorized", unit, rate, minStock });
        if (!stockData[sku]) {
            stockData[sku] = { qty: initialStock, minStock: minStock, updated: new Date().toLocaleString() };
        }
        addToHistory(sku, name, "ADD", initialStock, 0, initialStock, "New product created");
        showToast("Product added successfully!", "success");
    }
    
    saveAll();
    renderProducts();
    updateDashboard();
    populateDropdowns();
    closeModal();
}

function stockIn() {
    const productSku = document.getElementById("stockInProduct").value;
    const qty = parseInt(document.getElementById("stockInQty").value);
    const note = document.getElementById("stockInNote").value;
    const date = document.getElementById("stockInDate").value;
    
    if (!productSku || !qty || qty <= 0) {
        showMessage("stockInMsg", "Please select product and valid quantity", "error");
        return;
    }
    
    const product = products.find(p => p.sku === productSku);
    if (!stockData[productSku]) {
        stockData[productSku] = { qty: 0, minStock: 10 };
    }
    
    const oldQty = stockData[productSku].qty;
    stockData[productSku].qty += qty;
    stockData[productSku].updated = new Date().toLocaleString();
    
    addToHistory(productSku, product?.name || productSku, "IN", qty, oldQty, stockData[productSku].qty, `${note} | Date: ${date || new Date().toLocaleDateString()}`);
    saveAll();
    renderProducts();
    updateDashboard();
    
    document.getElementById("stockInQty").value = "";
    document.getElementById("stockInNote").value = "";
    document.getElementById("stockInDate").value = "";
    showMessage("stockInMsg", `✅ Added ${qty} units to ${product?.name || productSku}`, "success");
    showToast(`Added ${qty} units`, "success");
}

function stockOut() {
    const productSku = document.getElementById("stockOutProduct").value;
    const qty = parseInt(document.getElementById("stockOutQty").value);
    const note = document.getElementById("stockOutNote").value;
    const date = document.getElementById("stockOutDate").value;
    
    if (!productSku || !qty || qty <= 0) {
        showMessage("stockOutMsg", "Please select product and valid quantity", "error");
        return;
    }
    
    const product = products.find(p => p.sku === productSku);
    if (!stockData[productSku]) {
        stockData[productSku] = { qty: 0, minStock: 10 };
    }
    
    if (stockData[productSku].qty < qty) {
        showMessage("stockOutMsg", `Insufficient stock! Only ${stockData[productSku].qty} available`, "error");
        return;
    }
    
    const oldQty = stockData[productSku].qty;
    stockData[productSku].qty -= qty;
    stockData[productSku].updated = new Date().toLocaleString();
    
    addToHistory(productSku, product?.name || productSku, "OUT", qty, oldQty, stockData[productSku].qty, `${note} | Date: ${date || new Date().toLocaleDateString()}`);
    saveAll();
    renderProducts();
    updateDashboard();
    
    document.getElementById("stockOutQty").value = "";
    document.getElementById("stockOutNote").value = "";
    document.getElementById("stockOutDate").value = "";
    showMessage("stockOutMsg", `✅ Removed ${qty} units from ${product?.name || productSku}`, "success");
    showToast(`Removed ${qty} units`, "success");
}

function adjustStock() {
    const productSku = document.getElementById("adjustProduct").value;
    const newQty = parseInt(document.getElementById("adjustQty").value);
    const reason = document.getElementById("adjustReason").value;
    const date = document.getElementById("adjustDate").value;
    
    if (!productSku || isNaN(newQty) || newQty < 0) {
        showMessage("adjustMsg", "Please select product and valid quantity", "error");
        return;
    }
    
    const product = products.find(p => p.sku === productSku);
    if (!stockData[productSku]) {
        stockData[productSku] = { qty: 0, minStock: 10 };
    }
    
    const oldQty = stockData[productSku].qty;
    stockData[productSku].qty = newQty;
    stockData[productSku].updated = new Date().toLocaleString();
    
    addToHistory(productSku, product?.name || productSku, "ADJUST", Math.abs(newQty - oldQty), oldQty, newQty, `${reason} | Date: ${date || new Date().toLocaleDateString()}`);
    saveAll();
    renderProducts();
    updateDashboard();
    
    document.getElementById("adjustQty").value = "";
    document.getElementById("adjustReason").value = "";
    document.getElementById("adjustDate").value = "";
    showMessage("adjustMsg", `✅ Adjusted ${product?.name || productSku} stock to ${newQty} units`, "success");
    showToast(`Stock adjusted to ${newQty} units`, "success");
}

function addToHistory(sku, productName, type, qty, previous, newStock, note) {
    historyData.unshift({
        id: Date.now(),
        date: new Date().toLocaleString(),
        sku: sku,
        productName: productName,
        type: type,
        quantity: qty,
        previousStock: previous,
        newStock: newStock,
        userNote: note || ""
    });
    if (historyData.length > 500) historyData.pop();
}

function renderHistory() {
    const tbody = document.getElementById("historyTable");
    tbody.innerHTML = "";
    
    if (historyData.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" style="text-align:center;">No history available</td></tr>';
        return;
    }
    
    historyData.slice(0, 100).forEach(item => {
        let typeClass = "";
        if (item.type === "IN") typeClass = "status-ok";
        else if (item.type === "OUT") typeClass = "status-out";
        else typeClass = "status-low";
        
        tbody.innerHTML += `
            <tr>
                <td>${item.date}</td>
                <td><strong>${escapeHtml(item.productName)}</strong></td>
                <td>${escapeHtml(item.sku)}</td>
                <td><span class="${typeClass}">${item.type}</span></td>
                <td>${item.quantity}</td>
                <td>${item.previousStock}</td>
                <td>${item.newStock}</td>
                <td>${escapeHtml(item.userNote) || "-"}</td>
             </tr>
        `;
    });
}

function updateDashboard() {
    const totalProducts = products.length;
    let totalStock = 0;
    let lowStock = 0;
    let totalValue = 0;
    let lowStockItems = [];
    
    products.forEach(product => {
        const stock = stockData[product.sku]?.qty || 0;
        const min = stockData[product.sku]?.minStock || product.minStock || 10;
        totalStock += stock;
        totalValue += stock * product.rate;
        if (stock <= min) {
            lowStock++;
            lowStockItems.push(product.name);
        }
    });
    
    document.getElementById("totalProducts").innerText = totalProducts;
    document.getElementById("totalStock").innerText = totalStock;
    document.getElementById("lowStock").innerText = lowStock;
    document.getElementById("inventoryValue").innerText = "$" + totalValue.toFixed(2);
    
    const warningBox = document.getElementById("lowStockWarning");
    if (lowStock > 0) {
        warningBox.innerHTML = `⚠️ Warning: ${lowStock} product(s) are running low on stock! Please restock soon: ${lowStockItems.slice(0, 5).join(", ")}${lowStockItems.length > 5 ? "..." : ""}`;
        warningBox.style.background = "#fef3c7";
        warningBox.style.color = "#92400e";
    } else {
        warningBox.innerHTML = "✅ No low stock items. All inventory levels are healthy!";
        warningBox.style.background = "#dcfce7";
        warningBox.style.color = "#166534";
    }
}

function populateDropdowns() {
    const stockIn = document.getElementById("stockInProduct");
    const stockOut = document.getElementById("stockOutProduct");
    const adjust = document.getElementById("adjustProduct");
    
    stockIn.innerHTML = "";
    stockOut.innerHTML = "";
    adjust.innerHTML = "";
    
    products.forEach(product => {
        const option = `<option value="${escapeHtml(product.sku)}">${escapeHtml(product.name)} (${escapeHtml(product.sku)}) - Stock: ${stockData[product.sku]?.qty || 0}</option>`;
        stockIn.innerHTML += option;
        stockOut.innerHTML += option;
        adjust.innerHTML += option;
    });
    
    // Set default dates
    const today = new Date().toISOString().split('T')[0];
    ["stockInDate", "stockOutDate", "adjustDate"].forEach(id => {
        const el = document.getElementById(id);
        if (el && !el.value) el.value = today;
    });
}

function searchProducts() {
    currentPage = 1;
    renderProducts();
}

function importCSV() {
    const fileInput = document.getElementById("csvFileInput");
    const file = fileInput.files[0];
    
    if (!file) {
        showToast("Please select a CSV file", "error");
        return;
    }
    
    const reader = new FileReader();
    reader.onload = function(e) {
        const text = e.target.result;
        const rows = text.split("\n");
        let imported = 0;
        
        for (let i = 1; i < rows.length; i++) {
            const cols = rows[i].split(",");
            if (cols.length >= 4 && cols[1]) {
                const sku = cols[0].trim();
                const name = cols[1].trim();
                const category = cols[2].trim() || "Uncategorized";
                const rate = parseFloat(cols[3]) || 0;
                
                if (!products.find(p => p.sku === sku)) {
                    products.push({
                        id: Date.now() + i,
                        sku: sku,
                        name: name,
                        category: category,
                        unit: "pcs",
                        rate: rate,
                        minStock: 10
                    });
                    if (!stockData[sku]) {
                        stockData[sku] = { qty: 0, minStock: 10, updated: new Date().toLocaleString() };
                    }
                    imported++;
                }
            }
        }
        
        saveAll();
        renderProducts();
        updateDashboard();
        populateDropdowns();
        showToast(`Imported ${imported} products successfully!`, "success");
        fileInput.value = "";
    };
    reader.readAsText(file);
}

function exportCSV() {
    const headers = ["SKU", "Product Name", "Category", "Unit Price", "Current Stock", "Min Stock", "Total Value", "Last Updated"];
    const rows = [headers];
    
    products.forEach(product => {
        const stock = stockData[product.sku]?.qty || 0;
        const min = stockData[product.sku]?.minStock || product.minStock || 10;
        const value = stock * product.rate;
        rows.push([
            product.sku,
            product.name,
            product.category || "Uncategorized",
            product.rate,
            stock,
            min,
            value.toFixed(2),
            stockData[product.sku]?.updated || "-"
        ]);
    });
    
    const csv = rows.map(row => row.map(cell => `"${cell}"`).join(",")).join("\n");
    const blob = new Blob([csv], { type: "text/csv" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `inventory_${new Date().toISOString().split("T")[0]}.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
    
    showToast("Inventory exported successfully!", "success");
}

function downloadSampleCSV() {
    const sample = [
        ["SKU", "Product Name", "Category", "Unit Price"],
        ["PRD-001", "Sample Product 1", "Electronics", "25.00"],
        ["PRD-002", "Sample Product 2", "Accessories", "12.50"],
        ["PRD-003", "Sample Product 3", "Tools", "45.00"]
    ];
    const csv = sample.map(row => row.join(",")).join("\n");
    const blob = new Blob([csv], { type: "text/csv" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "sample_inventory_template.csv";
    link.click();
    URL.revokeObjectURL(link.href);
}

function closeModal() {
    document.getElementById("productModal").style.display = "none";
}

function escapeHtml(str) {
    if (!str) return "";
    return str.replace(/[&<>]/g, function(m) {
        if (m === "&") return "&amp;";
        if (m === "<") return "&lt;";
        if (m === ">") return "&gt;";
        return m;
    });
}

// Initialize
loadData();

// Close modal when clicking outside
window.onclick = function(event) {
    const modal = document.getElementById("productModal");
    if (event.target === modal) closeModal();
};
