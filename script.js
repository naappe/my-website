// STORAGE KEYS
const STORAGE_PRODUCTS = 'inventory_products';
const STORAGE_HISTORY = 'inventory_history';

// Global state
let products = [];
let stockHistory = [];
let currentPage = 1;
let itemsPerPage = 8;
let currentEditId = null;
let currentCurrency = '$';

// Load initial data
function loadData() {
    const storedProducts = localStorage.getItem(STORAGE_PRODUCTS);
    if (storedProducts) {
        products = JSON.parse(storedProducts);
    } else {
        // Sample data
        products = [
            { id: 1, vendor: 'TechPro', name: 'Wireless Mouse', unit: 'pcs', rate: 25.00, stock: 45, minStock: 10, lastUpdated: new Date().toISOString() },
            { id: 2, vendor: 'LogiTech', name: 'Keyboard', unit: 'pcs', rate: 35.00, stock: 7, minStock: 10, lastUpdated: new Date().toISOString() },
            { id: 3, vendor: 'CableWorld', name: 'USB Type-C Cable', unit: 'pcs', rate: 8.00, stock: 120, minStock: 20, lastUpdated: new Date().toISOString() },
            { id: 4, vendor: 'DisplayTech', name: 'Monitor 24 inch', unit: 'pcs', rate: 120.00, stock: 5, minStock: 5, lastUpdated: new Date().toISOString() },
            { id: 5, vendor: 'AccessoryHub', name: 'HDMI Cable', unit: 'pcs', rate: 6.00, stock: 60, minStock: 15, lastUpdated: new Date().toISOString() },
            { id: 6, vendor: 'VisionTech', name: 'Webcam', unit: 'pcs', rate: 45.00, stock: 3, minStock: 5, lastUpdated: new Date().toISOString() }
        ];
        saveData();
    }

    const storedHistory = localStorage.getItem(STORAGE_HISTORY);
    if (storedHistory) {
        stockHistory = JSON.parse(storedHistory);
    } else {
        stockHistory = [];
        products.forEach(p => {
            addToHistory(p.id, p.name, 'INITIAL', p.stock, 0, p.stock, 'Initial stock');
        });
        saveData();
    }

    renderAll();
}

function saveData() {
    localStorage.setItem(STORAGE_PRODUCTS, JSON.stringify(products));
    localStorage.setItem(STORAGE_HISTORY, JSON.stringify(stockHistory));
}

function addToHistory(productId, productName, type, quantity, oldStock, newStock, note) {
    stockHistory.unshift({
        id: Date.now(),
        date: new Date().toISOString(),
        productId,
        productName,
        type,
        quantity,
        previousStock: oldStock,
        newStock,
        userNote: note
    });
    saveData();
}

function renderAll() {
    updateStats();
    renderProductsTable();
    renderHistoryTable();
    renderLowStockTable();
    updateSelectors();
}

function updateStats() {
    document.getElementById('totalProducts').innerText = products.length;
    const totalStock = products.reduce((sum, p) => sum + p.stock, 0);
    document.getElementById('totalStock').innerText = totalStock;
    const lowCount = products.filter(p => p.stock <= p.minStock).length;
    document.getElementById('lowStockCount').innerText = lowCount;
    const totalValue = products.reduce((sum, p) => sum + (p.stock * p.rate), 0);
    document.getElementById('totalValue').innerHTML = `${currentCurrency}${totalValue.toFixed(2)}`;
}

function renderProductsTable() {
    const searchTerm = document.getElementById('searchInput')?.value.toLowerCase() || '';
    let filtered = products.filter(p => 
        p.name.toLowerCase().includes(searchTerm) || 
        p.vendor.toLowerCase().includes(searchTerm)
    );
    
    const start = (currentPage - 1) * itemsPerPage;
    const paginated = filtered.slice(start, start + itemsPerPage);
    const totalPages = Math.ceil(filtered.length / itemsPerPage);
    
    const tbody = document.getElementById('productsTableBody');
    if (paginated.length === 0) {
        tbody.innerHTML = '<tr><td colspan="9" style="text-align:center;">No products found</td></tr>';
        document.getElementById('entriesInfo').innerHTML = 'Showing 0 of 0 entries';
        return;
    }
    
    tbody.innerHTML = paginated.map(p => `
        <tr ${p.stock <= p.minStock ? 'class="low-stock-row"' : ''}>
            <td>${p.id}</td>
            <td>${escapeHtml(p.vendor)}</td>
            <td><strong>${escapeHtml(p.name)}</strong></td>
            <td>${escapeHtml(p.unit)}</td>
            <td>${currentCurrency}${p.rate.toFixed(2)}</td>
            <td>${p.stock}</td>
            <td>${p.minStock}</td>
            <td>${new Date(p.lastUpdated).toLocaleDateString()}</td>
            <td>
                <button class="btn btn-blue" style="padding:4px 12px;font-size:12px;" onclick="editProduct(${p.id})">Edit</button>
                <button class="btn btn-red" style="padding:4px 12px;font-size:12px;margin-left:5px;" onclick="deleteProduct(${p.id})">Delete</button>
            </td>
        </tr>
    `).join('');
    
    document.getElementById('entriesInfo').innerHTML = `Showing ${start + 1} to ${Math.min(start + itemsPerPage, filtered.length)} of ${filtered.length} entries`;
    renderPagination(totalPages);
}

function renderPagination(totalPages) {
    const container = document.getElementById('pagination');
    if (!container) return;
    if (totalPages <= 1) {
        container.innerHTML = '';
        return;
    }
    
    let html = '';
    for (let i = 1; i <= Math.min(totalPages, 5); i++) {
        html += `<button class="${i === currentPage ? 'active' : ''}" onclick="goToPage(${i})">${i}</button>`;
    }
    container.innerHTML = html;
}

function goToPage(page) {
    currentPage = page;
    renderProductsTable();
}

function editProduct(id) {
    const product = products.find(p => p.id === id);
    if (product) {
        currentEditId = id;
        document.getElementById('modalTitle').innerText = 'Edit Product';
        document.getElementById('editProductId').value = id;
        document.getElementById('modalVendor').value = product.vendor;
        document.getElementById('modalName').value = product.name;
        document.getElementById('modalUnit').value = product.unit;
        document.getElementById('modalRate').value = product.rate;
        document.getElementById('modalStock').value = product.stock;
        document.getElementById('modalMinStock').value = product.minStock;
        document.getElementById('productModal').style.display = 'flex';
    }
}

function deleteProduct(id) {
    if (confirm('Are you sure you want to delete this product?')) {
        const product = products.find(p => p.id === id);
        products = products.filter(p => p.id !== id);
        addToHistory(id, product.name, 'DELETE', product.stock, product.stock, 0, 'Product deleted');
        saveData();
        renderAll();
    }
}

function saveProduct() {
    const vendor = document.getElementById('modalVendor').value.trim();
    const name = document.getElementById('modalName').value.trim();
    const unit = document.getElementById('modalUnit').value.trim();
    const rate = parseFloat(document.getElementById('modalRate').value);
    const stock = parseInt(document.getElementById('modalStock').value);
    const minStock = parseInt(document.getElementById('modalMinStock').value);
    const editId = document.getElementById('editProductId').value;
    
    if (!vendor || !name) {
        alert('Vendor and Product Name are required');
        return;
    }
    
    if (editId) {
        const index = products.findIndex(p => p.id == editId);
        if (index !== -1) {
            const oldStock = products[index].stock;
            products[index] = { ...products[index], vendor, name, unit, rate, stock, minStock, lastUpdated: new Date().toISOString() };
            if (oldStock !== stock) {
                addToHistory(products[index].id, name, 'ADJUSTMENT', Math.abs(stock - oldStock), oldStock, stock, 'Manual adjustment');
            }
        }
    } else {
        const newId = Date.now();
        products.push({
            id: newId, vendor, name, unit, rate, stock, minStock,
            lastUpdated: new Date().toISOString()
        });
        addToHistory(newId, name, 'INITIAL', stock, 0, stock, 'Product created');
    }
    
    saveData();
    closeModal();
    renderAll();
}

function processStockIn() {
    const productId = parseInt(document.getElementById('stockInProduct').value);
    const qty = parseInt(document.getElementById('stockInQty').value);
    const note = document.getElementById('stockInNote').value;
    
    if (!productId || !qty || qty <= 0) {
        showMessage('stockInMsg', 'Select product and valid quantity', 'error');
        return;
    }
    
    const product = products.find(p => p.id === productId);
    if (product) {
        const oldStock = product.stock;
        product.stock += qty;
        product.lastUpdated = new Date().toISOString();
        addToHistory(product.id, product.name, 'IN', qty, oldStock, product.stock, note);
        saveData();
        renderAll();
        showMessage('stockInMsg', `Added ${qty} ${product.unit} to ${product.name}`, 'success');
        document.getElementById('stockInQty').value = '';
        document.getElementById('stockInNote').value = '';
    }
}

function processStockOut() {
    const productId = parseInt(document.getElementById('stockOutProduct').value);
    const qty = parseInt(document.getElementById('stockOutQty').value);
    const note = document.getElementById('stockOutNote').value;
    
    if (!productId || !qty || qty <= 0) {
        showMessage('stockOutMsg', 'Select product and valid quantity', 'error');
        return;
    }
    
    const product = products.find(p => p.id === productId);
    if (product && product.stock >= qty) {
        const oldStock = product.stock;
        product.stock -= qty;
        product.lastUpdated = new Date().toISOString();
        addToHistory(product.id, product.name, 'OUT', qty, oldStock, product.stock, note);
        saveData();
        renderAll();
        showMessage('stockOutMsg', `Removed ${qty} ${product.unit} from ${product.name}`, 'success');
        document.getElementById('stockOutQty').value = '';
        document.getElementById('stockOutNote').value = '';
    } else {
        showMessage('stockOutMsg', `Insufficient stock! Available: ${product?.stock || 0}`, 'error');
    }
}

function processAdjustment() {
    const productId = parseInt(document.getElementById('adjustProduct').value);
    const newQty = parseInt(document.getElementById('adjustNewQty').value);
    const reason = document.getElementById('adjustReason').value;
    
    if (!productId || isNaN(newQty) || newQty < 0) {
        showMessage('adjustMsg', 'Select product and valid quantity', 'error');
        return;
    }
    
    const product = products.find(p => p.id === productId);
    if (product) {
        const oldStock = product.stock;
        product.stock = newQty;
        product.lastUpdated = new Date().toISOString();
        addToHistory(product.id, product.name, 'ADJUSTMENT', Math.abs(newQty - oldStock), oldStock, newQty, reason);
        saveData();
        renderAll();
        showMessage('adjustMsg', `Adjusted ${product.name} stock to ${newQty}`, 'success');
        document.getElementById('adjustNewQty').value = '';
        document.getElementById('adjustReason').value = '';
    }
}

function renderHistoryTable() {
    const tbody = document.getElementById('historyTableBody');
    if (stockHistory.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;">No history available</td></tr>';
        return;
    }
    
    tbody.innerHTML = stockHistory.slice(0, 50).map(h => `
        <tr>
            <td>${new Date(h.date).toLocaleString()}</td>
            <td><strong>${escapeHtml(h.productName)}</strong></td>
            <td><span style="color:${h.type === 'IN' ? '#16a34a' : (h.type === 'OUT' ? '#dc2626' : '#7c3aed')}">${h.type}</span></td>
            <td>${h.quantity}</td>
            <td>${h.previousStock}</td>
            <td>${h.newStock}</td>
            <td>${escapeHtml(h.userNote) || '-'}</td>
        </tr>
    `).join('');
}

function renderLowStockTable() {
    const lowStock = products.filter(p => p.stock <= p.minStock);
    const tbody = document.getElementById('lowStockTableBody');
    
    if (lowStock.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;">No low stock items</td></tr>';
        return;
    }
    
    tbody.innerHTML = lowStock.map(p => `
        <tr class="low-stock-row">
            <td><strong>${escapeHtml(p.name)}</strong></td>
            <td>${escapeHtml(p.vendor)}</td>
            <td style="color:#dc2626;font-weight:bold;">${p.stock}</td>
            <td>${p.minStock}</td>
            <td>⚠️ Critical</td>
            <td><button class="btn btn-green" style="padding:4px 12px;" onclick="quickRestock(${p.id})">Restock</button></td>
        </table>
    `).join('');
}

function quickRestock(id) {
    const qty = prompt('Enter quantity to add:');
    if (qty && parseInt(qty) > 0) {
        const product = products.find(p => p.id === id);
        if (product) {
            const oldStock = product.stock;
            product.stock += parseInt(qty);
            product.lastUpdated = new Date().toISOString();
            addToHistory(product.id, product.name, 'IN', parseInt(qty), oldStock, product.stock, 'Quick restock');
            saveData();
            renderAll();
        }
    }
}

function updateSelectors() {
    const selects = ['stockInProduct', 'stockOutProduct', 'adjustProduct'];
    selects.forEach(id => {
        const select = document.getElementById(id);
        if (select) {
            select.innerHTML = '<option value="">-- Select Product --</option>' + 
                products.map(p => `<option value="${p.id}">${p.name} (${p.vendor}) - Stock: ${p.stock}</option>`).join('');
        }
    });
    
    const today = new Date().toISOString().split('T')[0];
    ['stockInDate', 'stockOutDate', 'adjustDate'].forEach(id => {
        const el = document.getElementById(id);
        if (el && !el.value) el.value = today;
    });
}

function exportInventory() {
    const headers = ['ID', 'Vendor', 'Product Name', 'Unit', 'Rate', 'Stock', 'Min Stock', 'Last Updated'];
    const rows = [headers];
    products.forEach(p => {
        rows.push([p.id, p.vendor, p.name, p.unit, p.rate, p.stock, p.minStock, p.lastUpdated]);
    });
    downloadCSV(rows, `inventory_${new Date().toISOString().split('T')[0]}.csv`);
    showMessage('importExportMsg', 'Inventory exported!', 'success');
}

function exportHistory() {
    const headers = ['Date', 'Product', 'Type', 'Quantity', 'Previous Stock', 'New Stock', 'Note'];
    const rows = [headers];
    stockHistory.forEach(h => {
        rows.push([h.date, h.productName, h.type, h.quantity, h.previousStock, h.newStock, h.userNote]);
    });
    downloadCSV(rows, `history_${new Date().toISOString().split('T')[0]}.csv`);
    showMessage('importExportMsg', 'History exported!', 'success');
}

function importCSV() {
    const file = document.getElementById('csvFileInput').files[0];
    if (!file) {
        showMessage('importExportMsg', 'Select a CSV file', 'error');
        return;
    }
    
    const reader = new FileReader();
    reader.onload = function(e) {
        const text = e.target.result;
        const rows = text.split('\n').map(row => row.split(',').map(cell => cell.replace(/^"|"$/g, '')));
        let imported = 0;
        
        for (let i = 1; i < rows.length; i++) {
            const cols = rows[i];
            if (cols.length >= 4 && cols[1]) {
                if (!products.find(p => p.name === cols[1] && p.vendor === cols[0])) {
                    products.push({
                        id: Date.now() + i,
                        vendor: cols[0],
                        name: cols[1],
                        unit: cols[2] || 'pcs',
                        rate: parseFloat(cols[3]) || 0,
                        stock: 0,
                        minStock: 5,
                        lastUpdated: new Date().toISOString()
                    });
                    imported++;
                }
            }
        }
        saveData();
        renderAll();
        showMessage('importExportMsg', `Imported ${imported} products!`, 'success');
    };
    reader.readAsText(file);
}

function downloadCSV(rows, filename) {
    const csv = rows.map(row => row.map(cell => `"${cell}"`).join(',')).join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = filename;
    link.click();
    URL.revokeObjectURL(link.href);
}

function showMessage(elementId, message, type) {
    const el = document.getElementById(elementId);
    if (el) {
        el.innerHTML = message;
        el.className = `message ${type}`;
        setTimeout(() => {
            el.style.display = 'none';
        }, 3000);
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/[&<>]/g, function(m) {
        if (m === '&') return '&amp;';
        if (m === '<') return '&lt;';
        if (m === '>') return '&gt;';
        return m;
    });
}

function closeModal() {
    document.getElementById('productModal').style.display = 'none';
    document.getElementById('editProductId').value = '';
    currentEditId = null;
}

// Navigation
function initNavigation() {
    const navLinks = document.querySelectorAll('.nav-link');
    const pages = document.querySelectorAll('.page');
    
    navLinks.forEach(link => {
        link.addEventListener('click', (e) => {
            e.preventDefault();
            const pageId = link.dataset.page;
            
            navLinks.forEach(l => l.classList.remove('active'));
            link.classList.add('active');
            
            pages.forEach(page => page.classList.remove('active'));
            document.getElementById(`${pageId}-page`).classList.add('active');
        });
    });
}

// Event listeners
function bindEvents() {
    document.getElementById('addProductBtn')?.addEventListener('click', () => {
        currentEditId = null;
        document.getElementById('modalTitle').innerText = 'Add Product';
        document.getElementById('editProductId').value = '';
        document.getElementById('modalVendor').value = '';
        document.getElementById('modalName').value = '';
        document.getElementById('modalUnit').value = 'pcs';
        document.getElementById('modalRate').value = '0';
        document.getElementById('modalStock').value = '0';
        document.getElementById('modalMinStock').value = '5';
        document.getElementById('productModal').style.display = 'flex';
    });
    
    document.getElementById('editProductBtn')?.addEventListener('click', () => {
        alert('Select a product from the table and click Edit');
    });
    
    document.getElementById('deleteProductBtn')?.addEventListener('click', () => {
        alert('Select a product from the table and click Delete');
    });
    
    document.getElementById('stockInTopBtn')?.addEventListener('click', () => {
        document.querySelector('[data-page="stock-in"]').click();
    });
    
    document.getElementById('stockOutTopBtn')?.addEventListener('click', () => {
        document.querySelector('[data-page="stock-out"]').click();
    });
    
    document.getElementById('adjustmentTopBtn')?.addEventListener('click', () => {
        document.querySelector('[data-page="adjustment"]').click();
    });
    
    document.getElementById('modalSaveBtn')?.addEventListener('click', saveProduct);
    document.querySelector('.close')?.addEventListener('click', closeModal);
    document.getElementById('processStockInBtn')?.addEventListener('click', processStockIn);
    document.getElementById('processStockOutBtn')?.addEventListener('click', processStockOut);
    document.getElementById('processAdjustBtn')?.addEventListener('click', processAdjustment);
    document.getElementById('csvImportBtn')?.addEventListener('click', () => document.getElementById('import-page').style.display !== 'none' ? null : document.querySelector('[data-page="import"]').click());
    document.getElementById('csvExportBtn')?.addEventListener('click', exportInventory);
    document.getElementById('importCsvBtn')?.addEventListener('click', importCSV);
    document.getElementById('exportInventoryBtn')?.addEventListener('click', exportInventory);
    document.getElementById('exportHistoryBtn')?.addEventListener('click', exportHistory);
    document.getElementById('searchInput')?.addEventListener('keyup', () => {
        currentPage = 1;
        renderProductsTable();
    });
    
    window.onclick = function(event) {
        const modal = document.getElementById('productModal');
        if (event.target === modal) closeModal();
    };
}

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    loadData();
    initNavigation();
    bindEvents();
});
