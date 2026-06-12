// STORAGE KEYS
const STORAGE_PRODUCTS = 'inventory_products';
const STORAGE_HISTORY = 'inventory_history';
const STORAGE_SETTINGS = 'inventory_settings';

// Global State
let products = [];
let stockHistory = [];
let settings = { currency: '$', defaultMinStock: 5 };
let currentProductPage = 1;
let currentHistoryPage = 1;
const ITEMS_PER_PAGE = 8;

// Helper: Save to localStorage
function persistData() {
    localStorage.setItem(STORAGE_PRODUCTS, JSON.stringify(products));
    localStorage.setItem(STORAGE_HISTORY, JSON.stringify(stockHistory));
    localStorage.setItem(STORAGE_SETTINGS, JSON.stringify(settings));
    document.getElementById('globalLastUpdated').innerText = new Date().toLocaleString();
}

// Load initial data with sample products
function loadInitialData() {
    const storedProducts = localStorage.getItem(STORAGE_PRODUCTS);
    if (storedProducts) {
        products = JSON.parse(storedProducts);
    } else {
        // Sample data matching the design
        products = [
            { id: 1, vendor: 'TechPro Solutions', name: 'Wireless Mouse', unit: 'pcs', rate: 25.00, stock: 45, minStock: 10, lastUpdated: new Date('2025-05-25T10:30:00').toISOString() },
            { id: 2, vendor: 'LogiTech', name: 'Mechanical Keyboard', unit: 'pcs', rate: 85.00, stock: 7, minStock: 10, lastUpdated: new Date('2025-05-25T09:15:00').toISOString() },
            { id: 3, vendor: 'CableWorld', name: 'USB Type-C Cable', unit: 'pcs', rate: 12.00, stock: 120, minStock: 20, lastUpdated: new Date('2025-05-25T08:50:00').toISOString() },
            { id: 4, vendor: 'DisplayTech', name: 'Monitor 24 inch', unit: 'pcs', rate: 199.99, stock: 5, minStock: 5, lastUpdated: new Date('2025-05-25T10:10:00').toISOString() },
            { id: 5, vendor: 'AccessoryHub', name: 'HDMI Cable', unit: 'pcs', rate: 6.50, stock: 60, minStock: 15, lastUpdated: new Date('2025-05-25T09:05:00').toISOString() },
            { id: 6, vendor: 'VisionTech', name: 'Webcam 1080p', unit: 'pcs', rate: 45.00, stock: 3, minStock: 5, lastUpdated: new Date('2025-05-25T08:20:00').toISOString() }
        ];
        persistData();
    }
    
    const storedHistory = localStorage.getItem(STORAGE_HISTORY);
    if (storedHistory) {
        stockHistory = JSON.parse(storedHistory);
    } else {
        stockHistory = [];
        products.forEach(p => {
            stockHistory.push({
                id: Date.now() + p.id,
                date: p.lastUpdated,
                productId: p.id,
                productName: p.name,
                type: 'INITIAL',
                quantity: p.stock,
                previousStock: 0,
                newStock: p.stock,
                userNote: 'System initialization'
            });
        });
        persistData();
    }
    
    const storedSettings = localStorage.getItem(STORAGE_SETTINGS);
    if (storedSettings) {
        settings = JSON.parse(storedSettings);
    }
    applySettingsUI();
    updateDateTime();
    setInterval(updateDateTime, 1000);
}

// Apply settings to UI
function applySettingsUI() {
    document.getElementById('currencySelect').value = settings.currency;
    document.getElementById('defaultMinStock').value = settings.defaultMinStock;
    refreshAllUI();
}

// Update date/time
function updateDateTime() {
    const now = new Date();
    document.getElementById('currentDateTime').innerHTML = now.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' }) + ' | ' + now.toLocaleTimeString();
}

// Refresh everything
function refreshAllUI() {
    updateDashboardStats();
    renderProductsTable();
    renderStockSelectors();
    renderHistoryTable();
    renderLowStockTable();
    renderRecentActivity();
    populateVendorFilter();
}

// Dashboard calculations
function updateDashboardStats() {
    const totalProducts = products.length;
    const totalUnits = products.reduce((sum, p) => sum + p.stock, 0);
    const lowCount = products.filter(p => p.stock <= p.minStock).length;
    const totalValue = products.reduce((sum, p) => sum + (p.stock * p.rate), 0);
    document.getElementById('statTotalProducts').innerText = totalProducts;
    document.getElementById('statTotalUnits').innerText = totalUnits.toLocaleString();
    document.getElementById('statLowStock').innerText = lowCount;
    document.getElementById('statInventoryValue').innerHTML = `${settings.currency}${totalValue.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
    document.getElementById('lowStockCountBadge').innerText = lowCount;
}

// Add stock movement to history
function addHistory(productId, productName, type, quantity, previousStock, newStock, note) {
    const entry = {
        id: Date.now(),
        date: new Date().toISOString(),
        productId, productName, type, quantity, previousStock, newStock, userNote: note || ''
    };
    stockHistory.unshift(entry);
    if (stockHistory.length > 500) stockHistory.pop();
    persistData();
}

// PRODUCT CRUD
function openProductModal(editId = null) {
    document.getElementById('productModal').style.display = 'flex';
    if (editId) {
        const prod = products.find(p => p.id === editId);
        if (prod) {
            document.getElementById('modalTitle').innerText = 'Edit Product';
            document.getElementById('editProductId').value = prod.id;
            document.getElementById('modalVendor').value = prod.vendor;
            document.getElementById('modalName').value = prod.name;
            document.getElementById('modalUnit').value = prod.unit;
            document.getElementById('modalRate').value = prod.rate;
            document.getElementById('modalStock').value = prod.stock;
            document.getElementById('modalMinStock').value = prod.minStock;
            return;
        }
    }
    document.getElementById('modalTitle').innerText = 'Add Product';
    document.getElementById('editProductId').value = '';
    document.getElementById('modalVendor').value = '';
    document.getElementById('modalName').value = '';
    document.getElementById('modalUnit').value = 'pcs';
    document.getElementById('modalRate').value = '0';
    document.getElementById('modalStock').value = '0';
    document.getElementById('modalMinStock').value = settings.defaultMinStock;
}

function closeModal() {
    document.getElementById('productModal').style.display = 'none';
}

function saveProductFromModal() {
    const id = document.getElementById('editProductId').value;
    const vendor = document.getElementById('modalVendor').value.trim();
    const name = document.getElementById('modalName').value.trim();
    const unit = document.getElementById('modalUnit').value.trim();
    const rate = parseFloat(document.getElementById('modalRate').value);
    const stock = parseInt(document.getElementById('modalStock').value);
    const minStock = parseInt(document.getElementById('modalMinStock').value);
    
    if (!vendor || !name) { 
        showMsg('importExportMsg', 'Vendor and Product Name are required', 'error');
        setTimeout(() => document.getElementById('importExportMsg').style.display = 'none', 2000);
        return; 
    }
    if (isNaN(rate)) { 
        alert('Rate must be a number'); 
        return; 
    }
    
    if (id) {
        const index = products.findIndex(p => p.id == id);
        if (index !== -1) {
            const oldStock = products[index].stock;
            products[index] = { ...products[index], vendor, name, unit, rate, stock, minStock, lastUpdated: new Date().toISOString() };
            if (oldStock !== stock) {
                addHistory(products[index].id, products[index].name, 'ADJUSTMENT', Math.abs(stock - oldStock), oldStock, stock, 'Manual edit adjustment');
            }
            persistData();
            refreshAllUI();
            closeModal();
        }
    } else {
        const newId = Date.now();
        const newProduct = { id: newId, vendor, name, unit, rate, stock, minStock, lastUpdated: new Date().toISOString() };
        products.push(newProduct);
        addHistory(newId, name, 'INITIAL', stock, 0, stock, 'Product created');
        persistData();
        refreshAllUI();
        closeModal();
    }
}

function deleteProduct(productId) {
    if (confirm('⚠️ Permanently delete this product? This action cannot be undone.')) {
        products = products.filter(p => p.id !== productId);
        persistData();
        refreshAllUI();
        showMsg('productMessage', 'Product deleted successfully', 'success');
        setTimeout(() => document.getElementById('productMessage')?.style.setProperty('display', 'none'), 2000);
    }
}

// STOCK OPERATIONS
function stockIn() {
    const productId = parseInt(document.getElementById('stockInProduct').value);
    const qty = parseInt(document.getElementById('stockInQty').value);
    const note = document.getElementById('stockInNote').value;
    const date = document.getElementById('stockInDate').value;
    
    if (!productId || !qty || qty <= 0) { 
        showMsg('stockInMsg', 'Select product and enter valid quantity', 'error'); 
        setTimeout(() => document.getElementById('stockInMsg').style.display = 'none', 2000);
        return; 
    }
    
    const prod = products.find(p => p.id === productId);
    if (prod) {
        const oldStock = prod.stock;
        prod.stock += qty;
        prod.lastUpdated = new Date().toISOString();
        addHistory(prod.id, prod.name, 'IN', qty, oldStock, prod.stock, `${note}${date ? ' | ' + new Date(date).toLocaleDateString() : ''}`);
        persistData();
        refreshAllUI();
        showMsg('stockInMsg', `✅ Added ${qty} ${prod.unit} to ${prod.name}`, 'success');
        document.getElementById('stockInQty').value = '';
        document.getElementById('stockInNote').value = '';
        document.getElementById('stockInDate').value = '';
        setTimeout(() => document.getElementById('stockInMsg').style.display = 'none', 2000);
    }
}

function stockOut() {
    const productId = parseInt(document.getElementById('stockOutProduct').value);
    const qty = parseInt(document.getElementById('stockOutQty').value);
    const note = document.getElementById('stockOutNote').value;
    const date = document.getElementById('stockOutDate').value;
    
    if (!productId || !qty || qty <= 0) { 
        showMsg('stockOutMsg', 'Select product and enter valid quantity', 'error'); 
        setTimeout(() => document.getElementById('stockOutMsg').style.display = 'none', 2000);
        return; 
    }
    
    const prod = products.find(p => p.id === productId);
    if (prod && prod.stock >= qty) {
        const oldStock = prod.stock;
        prod.stock -= qty;
        prod.lastUpdated = new Date().toISOString();
        addHistory(prod.id, prod.name, 'OUT', qty, oldStock, prod.stock, `${note}${date ? ' | ' + new Date(date).toLocaleDateString() : ''}`);
        persistData();
        refreshAllUI();
        showMsg('stockOutMsg', `✅ Removed ${qty} ${prod.unit} from ${prod.name}`, 'success');
        document.getElementById('stockOutQty').value = '';
        document.getElementById('stockOutNote').value = '';
        document.getElementById('stockOutDate').value = '';
        setTimeout(() => document.getElementById('stockOutMsg').style.display = 'none', 2000);
    } else {
        showMsg('stockOutMsg', `❌ Insufficient stock! Available: ${prod?.stock || 0}`, 'error');
        setTimeout(() => document.getElementById('stockOutMsg').style.display = 'none', 2000);
    }
}

function stockAdjust() {
    const productId = parseInt(document.getElementById('adjustProduct').value);
    const newQty = parseInt(document.getElementById('adjustNewQty').value);
    const reason = document.getElementById('adjustReason').value;
    const date = document.getElementById('adjustDate').value;
    
    if (!productId || isNaN(newQty) || newQty < 0) { 
        showMsg('adjustMsg', 'Select product and enter valid quantity', 'error'); 
        setTimeout(() => document.getElementById('adjustMsg').style.display = 'none', 2000);
        return; 
    }
    
    const prod = products.find(p => p.id === productId);
    if (prod) {
        const oldStock = prod.stock;
        prod.stock = newQty;
        prod.lastUpdated = new Date().toISOString();
        addHistory(prod.id, prod.name, 'ADJUSTMENT', Math.abs(newQty - oldStock), oldStock, newQty, `${reason}${date ? ' | ' + new Date(date).toLocaleDateString() : ''}`);
        persistData();
        refreshAllUI();
        showMsg('adjustMsg', `✅ Adjusted ${prod.name} stock to ${newQty} ${prod.unit}`, 'success');
        document.getElementById('adjustNewQty').value = '';
        document.getElementById('adjustReason').value = '';
        document.getElementById('adjustDate').value = '';
        setTimeout(() => document.getElementById('adjustMsg').style.display = 'none', 2000);
    }
}

// RENDER PRODUCTS TABLE with Pagination, Search, Filter, Sort
function renderProductsTable() {
    let filtered = [...products];
    const searchTerm = document.getElementById('productSearch')?.value.toLowerCase() || '';
    const vendorFilter = document.getElementById('productFilterVendor')?.value || '';
    
    if (searchTerm) {
        filtered = filtered.filter(p => p.name.toLowerCase().includes(searchTerm) || p.vendor.toLowerCase().includes(searchTerm));
    }
    if (vendorFilter) {
        filtered = filtered.filter(p => p.vendor === vendorFilter);
    }
    
    const sortBy = document.getElementById('productSort')?.value || 'name_asc';
    if (sortBy === 'name_asc') filtered.sort((a,b) => a.name.localeCompare(b.name));
    if (sortBy === 'name_desc') filtered.sort((a,b) => b.name.localeCompare(a.name));
    if (sortBy === 'stock_asc') filtered.sort((a,b) => a.stock - b.stock);
    if (sortBy === 'stock_desc') filtered.sort((a,b) => b.stock - a.stock);
    
    const totalPages = Math.ceil(filtered.length / ITEMS_PER_PAGE);
    if (currentProductPage > totalPages) currentProductPage = 1;
    const start = (currentProductPage - 1) * ITEMS_PER_PAGE;
    const paginated = filtered.slice(start, start + ITEMS_PER_PAGE);
    
    const tbody = document.getElementById('productsTableBody');
    if (!tbody) return;
    
    if (paginated.length === 0) {
        tbody.innerHTML = '<tr><td colspan="9" style="text-align:center;">No products found</td></tr>';
        return;
    }
    
    tbody.innerHTML = paginated.map(p => `
        <tr class="${p.stock <= p.minStock ? 'low-stock-row' : ''}">
            <td>${p.id}</td>
            <td>${escapeHtml(p.vendor)}</td>
            <td><strong>${escapeHtml(p.name)}</strong></td>
            <td>${escapeHtml(p.unit)}</td>
            <td>${settings.currency}${p.rate.toFixed(2)}</td>
            <td><span class="${p.stock <= p.minStock ? 'stock-low' : 'stock-normal'}">${p.stock}</span></td>
            <td>${p.minStock}</td>
            <td>${new Date(p.lastUpdated).toLocaleDateString()}</td>
            <td>
                <button class="btn btn-primary" style="padding: 4px 12px; font-size: 12px;" onclick="editProduct(${p.id})">✏️ Edit</button>
                <button class="btn btn-danger" style="padding: 4px 12px; font-size: 12px; margin-left: 5px;" onclick="deleteProduct(${p.id})">🗑️ Delete</button>
            </td>
        </tr>
    `).join('');
    
    renderPagination('productsPagination', totalPages, currentProductPage, (page) => {
        currentProductPage = page;
        renderProductsTable();
    });
}

function editProduct(id) {
    openProductModal(id);
}

function populateVendorFilter() {
    const vendors = [...new Set(products.map(p => p.vendor))];
    const filterSelect = document.getElementById('productFilterVendor');
    if (filterSelect) {
        filterSelect.innerHTML = '<option value="">All Vendors</option>' + vendors.map(v => `<option value="${escapeHtml(v)}">${escapeHtml(v)}</option>`).join('');
    }
}

// RENDER HISTORY TABLE
function renderHistoryTable() {
    let filtered = [...stockHistory];
    const searchTerm = document.getElementById('historySearch')?.value.toLowerCase() || '';
    const typeFilter = document.getElementById('historyTypeFilter')?.value || 'all';
    
    if (searchTerm) {
        filtered = filtered.filter(h => h.productName.toLowerCase().includes(searchTerm) || h.userNote.toLowerCase().includes(searchTerm));
    }
    if (typeFilter !== 'all') {
        filtered = filtered.filter(h => h.type === typeFilter);
    }
    
    const totalPages = Math.ceil(filtered.length / ITEMS_PER_PAGE);
    if (currentHistoryPage > totalPages) currentHistoryPage = 1;
    const start = (currentHistoryPage - 1) * ITEMS_PER_PAGE;
    const paginated = filtered.slice(start, start + ITEMS_PER_PAGE);
    
    const tbody = document.getElementById('historyTableBody');
    if (!tbody) return;
    
    if (paginated.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;">No history records</td></tr>';
        return;
    }
    
    tbody.innerHTML = paginated.map(h => `
        <tr>
            <td>${new Date(h.date).toLocaleString()}</td>
            <td><strong>${escapeHtml(h.productName)}</strong></td>
            <td><span class="badge-${h.type.toLowerCase()}">${h.type}</span></td>
            <td>${h.quantity}</td>
            <td>${h.previousStock}</td>
            <td>${h.newStock}</td>
            <td>${escapeHtml(h.userNote) || '-'}</td>
        </tr>
    `).join('');
    
    renderPagination('historyPagination', totalPages, currentHistoryPage, (page) => {
        currentHistoryPage = page;
        renderHistoryTable();
    });
}

// RENDER LOW STOCK TABLE
function renderLowStockTable() {
    const lowStockItems = products.filter(p => p.stock <= p.minStock);
    const tbody = document.getElementById('lowStockTableBody');
    if (!tbody) return;
    
    if (lowStockItems.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;">✨ No low stock items. All inventory levels are healthy!</td></tr>';
        return;
    }
    
    tbody.innerHTML = lowStockItems.map(p => `
        <tr class="low-stock-row">
            <td><strong>${escapeHtml(p.name)}</strong></td>
            <td>${escapeHtml(p.vendor)}</td>
            <td style="color: #dc2626; font-weight: bold;">${p.stock}</td>
            <td>${p.minStock}</td>
            <td><span class="stock-low">⚠️ Critical</span></td>
            <td><button class="btn btn-success" style="padding: 4px 12px;" onclick="quickRestock(${p.id})">➕ Restock</button></td>
        </tr>
    `).join('');
}

function quickRestock(productId) {
    const qty = prompt('Enter quantity to add:');
    if (qty && parseInt(qty) > 0) {
        const prod = products.find(p => p.id === productId);
        if (prod) {
            const oldStock = prod.stock;
            prod.stock += parseInt(qty);
            prod.lastUpdated = new Date().toISOString();
            addHistory(prod.id, prod.name, 'IN', parseInt(qty), oldStock, prod.stock, 'Quick restock from low stock warning');
            persistData();
            refreshAllUI();
            showMsg('importExportMsg', `✅ Restocked ${qty} ${prod.unit}`, 'success');
            setTimeout(() => document.getElementById('importExportMsg').style.display = 'none', 2000);
        }
    }
}

// RENDER RECENT ACTIVITY on Dashboard
function renderRecentActivity() {
    const recent = [...stockHistory].slice(0, 5);
    const tbody = document.querySelector('#recentActivityTable tbody');
    if (!tbody) return;
    
    if (recent.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;">No recent activity</td></tr>';
        return;
    }
    
    tbody.innerHTML = recent.map(h => `
        <tr>
            <td>${new Date(h.date).toLocaleString()}</td>
            <td>${escapeHtml(h.productName)}</td>
            <td><span class="badge-${h.type.toLowerCase()}">${h.type}</span></td>
            <td>${h.quantity}</td>
        </tr>
    `).join('');
}

// RENDER STOCK SELECTORS
function renderStockSelectors() {
    const selects = ['stockInProduct', 'stockOutProduct', 'adjustProduct'];
    selects.forEach(id => {
        const select = document.getElementById(id);
        if (select) {
            select.innerHTML = '<option value="">-- Select Product --</option>' + 
                products.map(p => `<option value="${p.id}">${escapeHtml(p.name)} (${p.vendor}) - Stock: ${p.stock} ${p.unit}</option>`).join('');
        }
    });
    
    // Set default dates to today
    const today = new Date().toISOString().split('T')[0];
    ['stockInDate', 'stockOutDate', 'adjustDate'].forEach(id => {
        const el = document.getElementById(id);
        if (el && !el.value) el.value = today;
    });
}

// CSV IMPORT/EXPORT
function exportFullInventory() {
    const headers = ['ID', 'Vendor', 'Product Name', 'Unit', 'Rate', 'Current Stock', 'Minimum Stock', 'Last Updated'];
    const csvRows = [headers];
    products.forEach(p => {
        csvRows.push([p.id, p.vendor, p.name, p.unit, p.rate, p.stock, p.minStock, p.lastUpdated]);
    });
    downloadCSV(csvRows, `inventory_export_${new Date().toISOString().split('T')[0]}.csv`);
    showMsg('importExportMsg', '✅ Inventory exported successfully!', 'success');
    setTimeout(() => document.getElementById('importExportMsg').style.display = 'none', 2000);
}

function exportHistoryCSV() {
    const headers = ['Date', 'Product', 'Type', 'Quantity', 'Previous Stock', 'New Stock', 'User Note'];
    const csvRows = [headers];
    stockHistory.forEach(h => {
        csvRows.push([h.date, h.productName, h.type, h.quantity, h.previousStock, h.newStock, h.userNote]);
    });
    downloadCSV(csvRows, `stock_history_${new Date().toISOString().split('T')[0]}.csv`);
    showMsg('importExportMsg', '✅ History exported successfully!', 'success');
    setTimeout(() => document.getElementById('importExportMsg').style.display = 'none', 2000);
}

function downloadCSV(rows, filename) {
    const csvContent = rows.map(row => row.map(cell => `"${String(cell).replace(/"/g, '""')}"`).join(',')).join('\n');
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    const url = URL.createObjectURL(blob);
    link.setAttribute('href', url);
    link.setAttribute('download', filename);
    link.style.visibility = 'hidden';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
}

function importCSVFile() {
    const fileInput = document.getElementById('csvImportFile');
    const file = fileInput.files[0];
    if (!file) {
        showMsg('importExportMsg', 'Please select a CSV file', 'error');
        setTimeout(() => document.getElementById('importExportMsg').style.display = 'none', 2000);
        return;
    }
    
    const reader = new FileReader();
    reader.onload = function(e) {
        const text = e.target.result;
        const rows = text.split('\n').map(row => row.split(',').map(cell => cell.replace(/^"|"$/g, '').trim()));
        if (rows.length < 2) {
            showMsg('importExportMsg', 'Invalid CSV format', 'error');
            return;
        }
        
        let imported = 0;
        for (let i = 1; i < rows.length; i++) {
            const cols = rows[i];
            if (cols.length >= 4 && cols[1] && cols[0]) {
                const vendor = cols[0];
                const name = cols[1];
                const unit = cols[2] || 'pcs';
                const rate = parseFloat(cols[3]) || 0;
                
                if (!products.find(p => p.name === name && p.vendor === vendor)) {
                    products.push({
                        id: Date.now() + i,
                        vendor, name, unit, rate,
                        stock: 0,
                        minStock: settings.defaultMinStock,
                        lastUpdated: new Date().toISOString()
                    });
                    imported++;
                }
            }
        }
        persistData();
        refreshAllUI();
        showMsg('importExportMsg', `✅ Imported ${imported} new products successfully!`, 'success');
        fileInput.value = '';
        setTimeout(() => document.getElementById('importExportMsg').style.display = 'none', 3000);
    };
    reader.readAsText(file);
}

function downloadSampleCSV() {
    const sample = [
        ['Vendor', 'Name', 'Unit', 'Rate'],
        ['TechPro', 'Wireless Mouse', 'pcs', '25.00'],
        ['LogiTech', 'Mechanical Keyboard', 'pcs', '85.00'],
        ['CableWorld', 'USB-C Cable', 'pcs', '12.00']
    ];
    downloadCSV(sample, 'sample_inventory_template.csv');
}

// SETTINGS
function saveSettings() {
    settings.currency = document.getElementById('currencySelect').value;
    settings.defaultMinStock = parseInt(document.getElementById('defaultMinStock').value);
    localStorage.setItem(STORAGE_SETTINGS, JSON.stringify(settings));
    refreshAllUI();
    showMsg('settingsMsg', '✅ Settings saved successfully!', 'success');
    setTimeout(() => document.getElementById('settingsMsg').style.display = 'none', 2000);
}

function resetAllData() {
    if (confirm('⚠️ DANGER: This will delete ALL products and history. This action cannot be undone! Are you sure?')) {
        localStorage.clear();
        location.reload();
    }
}

// Helper Functions
function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/[&<>]/g, function(m) {
        if (m === '&') return '&amp;';
        if (m === '<') return '&lt;';
        if (m === '>') return '&gt;';
        return m;
    });
}

function showMsg(elementId, message, type) {
    const el = document.getElementById(elementId);
    if (el) {
        el.innerHTML = message;
        el.className = `form-message ${type}`;
        el.style.display = 'block';
        setTimeout(() => {
            el.style.display = 'none';
        }, 3000);
    }
}

function renderPagination(containerId, totalPages, currentPage, onPageChange) {
    const container = document.getElementById(containerId);
    if (!container) return;
    if (totalPages <= 1) {
        container.innerHTML = '';
        return;
    }
    
    let html = '';
    for (let i = 1; i <= Math.min(totalPages, 5); i++) {
        html += `<button class="${i === currentPage ? 'active' : ''}" onclick="window.changePage(${i}, '${containerId}')">${i}</button>`;
    }
    container.innerHTML = html;
    window.changePage = function(page, id) {
        if (id === 'productsPagination') {
            currentProductPage = page;
            renderProductsTable();
        } else if (id === 'historyPagination') {
            currentHistoryPage = page;
            renderHistoryTable();
        }
    };
}

// Navigation
function initNavigation() {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', () => {
            const page = item.dataset.nav;
            document.querySelectorAll('.nav-item').forEach(nav => nav.classList.remove('active'));
            item.classList.add('active');
            document.querySelectorAll('.section').forEach(section => section.classList.remove('active-section'));
            document.getElementById(`${page}-section`).classList.add('active-section');
        });
    });
}

// Event Listeners
function bindEvents() {
    document.getElementById('openAddProductBtn')?.addEventListener('click', () => openProductModal());
    document.getElementById('modalSaveBtn')?.addEventListener('click', saveProductFromModal);
    document.querySelector('.close-modal')?.addEventListener('click', closeModal);
    document.getElementById('executeStockInBtn')?.addEventListener('click', stockIn);
    document.getElementById('executeStockOutBtn')?.addEventListener('click', stockOut);
    document.getElementById('executeAdjustBtn')?.addEventListener('click', stockAdjust);
    document.getElementById('exportProductsBtn')?.addEventListener('click', exportFullInventory);
    document.getElementById('fullExportBtn')?.addEventListener('click', exportFullInventory);
    document.getElementById('historyExportBtn')?.addEventListener('click', exportHistoryCSV);
    document.getElementById('doImportBtn')?.addEventListener('click', importCSVFile);
    document.getElementById('downloadSampleCsv')?.addEventListener('click', (e) => { e.preventDefault(); downloadSampleCSV(); });
    document.getElementById('saveSettingsBtn')?.addEventListener('click', saveSettings);
    document.getElementById('resetDataBtn')?.addEventListener('click', resetAllData);
    document.getElementById('productSearch')?.addEventListener('keyup', () => { currentProductPage = 1; renderProductsTable(); });
    document.getElementById('productFilterVendor')?.addEventListener('change', () => { currentProductPage = 1; renderProductsTable(); });
    document.getElementById('productSort')?.addEventListener('change', () => { currentProductPage = 1; renderProductsTable(); });
    document.getElementById('historySearch')?.addEventListener('keyup', () => { currentHistoryPage = 1; renderHistoryTable(); });
    document.getElementById('historyTypeFilter')?.addEventListener('change', () => { currentHistoryPage = 1; renderHistoryTable(); });
    
    window.onclick = function(event) {
        const modal = document.getElementById('productModal');
        if (event.target === modal) closeModal();
    };
}

// Initialize App
document.addEventListener('DOMContentLoaded', () => {
    loadInitialData();
    initNavigation();
    bindEvents();
    refreshAllUI();
});
