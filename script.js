// =========================
// WHITE SAFFRON INVENTORY - COMPLETE SYSTEM
// =========================

let products = [];
let stockData = {};
let historyData = [];
let selectedProductIndex = null;

// Default sample products
const defaultProducts = [
    { vendor: "TechPro", name: "Wireless Mouse", unit: "pcs", rate: 25.00, minStock: 10 },
    { vendor: "LogiTech", name: "Keyboard", unit: "pcs", rate: 35.00, minStock: 10 },
    { vendor: "CableWorld", name: "USB Type-C Cable", unit: "pcs", rate: 8.00, minStock: 20 },
    { vendor: "DisplayTech", name: "Monitor 24 inch", unit: "pcs", rate: 120.00, minStock: 5 },
    { vendor: "AccessoryHub", name: "HDMI Cable", unit: "pcs", rate: 6.00, minStock: 15 },
    { vendor: "VisionTech", name: "Webcam", unit: "pcs", rate: 45.00, minStock: 5 }
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
            stockData[p.name] = { 
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
            p.vendor.toLowerCase().includes(searchTerm)
        );
    }
    
    tbody.innerHTML = "";
    
    if (filteredProducts.length === 0) {
        tbody.innerHTML = '<tr><td colspan="9" style="text-align:center;">No products found</td></tr>';
        return;
    }
    
    filteredProducts.forEach((product, idx) => {
        const originalIndex = products.findIndex(p => p.name === product.name && p.vendor === product.vendor);
        const stock = stockData[product.name]?.qty || 0;
        const min = stockData[product.name]?.minStock || product.minStock || 5;
        
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
                <td><input type="radio" name="productSelect" class="selected-checkbox" onclick="selectProduct(${originalIndex})"></td>
                <td>${escapeHtml(product.vendor)}</td>
                <td><strong>${escapeHtml(product.name)}</strong></td>
                <td>${escapeHtml(product.unit)}</td>
                <td>$${product.rate.toFixed(2)}</td>
                <td>${stock}</td>
                <td>${min}</td>
                <td><span class="${statusClass}">${status}</span></td>
                <td>${stockData[product.name]?.updated || "-"}</td>
            </tr>
        `;
    });
}

function selectProduct(index) {
    selectedProductIndex = index;
}

function openAddProductModal() {
    document.getElementById("modalTitle").innerText = "Add Product";
    document.getElementById("editProductIndex").value = "";
    document.getElementById("vendor").value = "";
    document.getElementById("productName").value = "";
    document.getElementById("unit").value = "pcs";
    document.getElementById("rate").value = "";
    document.getElementById("modalMinStock").value = "5";
    document.getElementById("productModal").style.display = "block";
}

function editSelectedProduct() {
    if (selectedProductIndex === null || selectedProductIndex === undefined) {
        showToast("Please select a product first", "error");
        return;
    }
    const product = products[selectedProductIndex];
    document.getElementById("modalTitle").innerText = "Edit Product";
    document.getElementById("editProductIndex").value = selectedProductIndex;
    document.getElementById("vendor").value = product.vendor;
    document.getElementById("productName").value = product.name;
    document.getElementById("unit").value = product.unit;
    document.getElementById("rate").value = product.rate;
    document.getElementById("modalMinStock").value = stockData[product.name]?.minStock || product.minStock || 5;
    document.getElementById("productModal").style.display = "block";
}

function deleteSelectedProduct() {
    if (selectedProductIndex === null || selectedProductIndex === undefined) {
        showToast("Please select a product first", "error");
        return;
    }
    if (confirm("Are you sure you want to delete this product?")) {
        const product = products[selectedProductIndex];
        delete stockData[product.name];
        products.splice(selectedProductIndex, 1);
        selectedProductIndex = null;
        saveAll();
        renderProducts();
        updateDashboard();
        populateDropdowns();
        showToast("Product deleted successfully!", "success");
    }
}

function saveProduct() {
    const vendor = document.getElementById("vendor").value.trim();
    const name = document.getElementById("productName").value.trim();
    const unit = document.getElementById("unit").value.trim();
    const rate = parseFloat(document.getElementById("rate").value);
    const minStock = parseInt(document.getElementById("modalMinStock").value);
    const editIndex = document.getElementById("editProductIndex").value;
    
    if (!vendor || !name || isNaN(rate)) {
        showToast("Please fill all required fields", "error");
        return;
    }
    
    if (editIndex !== "") {
        // Edit existing product
        const oldProduct = products[editIndex];
        const oldName = oldProduct.name;
        products[editIndex] = { vendor, name, unit, rate, minStock };
        if (oldName !== name) {
            stockData[name] = stockData[oldName];
            delete stockData[oldName];
        }
        if (stockData[name]) {
            stockData[name].minStock = minStock;
        }
        addToHistory(name, "EDIT", 0, 0, 0, `Product edited: ${oldName} → ${name}`);
        showToast("Product updated successfully!", "success");
    } else {
        // Add new product
        if (products.find(p => p.name === name && p.vendor === vendor)) {
            showToast("Product already exists!", "error");
            return;
        }
        products.push({ vendor, name, unit, rate, minStock });
        if (!stockData[name]) {
            stockData[name] = { qty: 0, minStock: minStock, updated: new Date().toLocaleString() };
        }
        addToHistory(name, "ADD", 0, 0, 0, "New product created");
        showToast("Product added successfully!", "success");
    }
    
    saveAll();
    renderProducts();
    updateDashboard();
    populateDropdowns();
    closeModal();
}

function stockIn() {
    const productName = document.getElementById("stockInProduct").value;
    const qty = parseInt(document.getElementById("stockInQty").value);
    const note = document.getElementById("stockInNote").value;
    
    if (!productName || !qty || qty <= 0) {
        showMessage("stockInMsg", "Please select product and valid quantity", "error");
        return;
    }
    
    if (!stockData[productName]) {
        stockData[productName] = { qty: 0, minStock: 5 };
    }
    
    const oldQty = stockData[productName].qty;
    stockData[productName].qty += qty;
    stockData[productName].updated = new Date().toLocaleString();
    
    addToHistory(productName, "IN", qty, oldQty, stockData[productName].qty, note);
    saveAll();
    renderProducts();
    updateDashboard();
    
    document.getElementById("stockInQty").value = "";
    document.getElementById("stockInNote").value = "";
    showMessage("stockInMsg", `✅ Added ${qty} units to ${productName}`, "success");
    showToast(`Added ${qty} units to ${productName}`, "success");
}

function stockOut() {
    const productName = document.getElementById("stockOutProduct").value;
    const qty = parseInt(document.getElementById("stockOutQty").value);
    const note = document.getElementById("stockOutNote").value;
    
    if (!productName || !qty || qty <= 0) {
        showMessage("stockOutMsg", "Please select product and valid quantity", "error");
        return;
    }
    
    if (!stockData[productName]) {
        stockData[productName] = { qty: 0, minStock: 5 };
    }
    
    if (stockData[productName].qty < qty) {
        showMessage("stockOutMsg", `Insufficient stock! Only ${stockData[productName].qty} available`, "error");
        return;
    }
    
    const oldQty = stockData[productName].qty;
    stockData[productName].qty -= qty;
    stockData[productName].updated = new Date().toLocaleString();
    
    addToHistory(productName, "OUT", qty, oldQty, stockData[productName].qty, note);
    saveAll();
    renderProducts();
    updateDashboard();
    
    document.getElementById("stockOutQty").value = "";
    document.getElementById("stockOutNote").value = "";
    showMessage("stockOutMsg", `✅ Removed ${qty} units from ${productName}`, "success");
    showToast(`Removed ${qty} units from ${productName}`, "success");
}

function adjustStock() {
    const productName = document.getElementById("adjustProduct").value;
    const newQty = parseInt(document.getElementById("adjustQty").value);
    const reason = document.getElementById("adjustReason").value;
    
    if (!productName || isNaN(newQty) || newQty < 0) {
        showMessage("adjustMsg", "Please select product and valid quantity", "error");
        return;
    }
    
    if (!stockData[productName]) {
        stockData[productName] = { qty: 0, minStock: 5 };
    }
    
    const oldQty = stockData[productName].qty;
    stockData[productName].qty = newQty;
    stockData[productName].updated = new Date().toLocaleString();
    
    addToHistory(productName, "ADJUST", Math.abs(newQty - oldQty), oldQty, newQty, reason);
    saveAll();
    renderProducts();
    updateDashboard();
    
    document.getElementById("adjustQty").value = "";
    document.getElementById("adjustReason").value = "";
    showMessage("adjustMsg", `✅ Adjusted ${productName} stock to ${
