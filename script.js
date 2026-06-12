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
