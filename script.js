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

// Load initial data
function loadInitialData() {
  const storedProducts = localStorage.getItem(STORAGE_PRODUCTS);
  if (storedProducts) {
    products = JSON.parse(storedProducts);
  } else {
    // Sample data
    products = [
      { id: 1001, vendor: 'TechPro', name: 'Wireless Mouse', unit: 'pcs', rate: 25, stock: 45, minStock: 10, lastUpdated: new Date().toISOString() },
      { id: 1002, vendor: 'LogiTech', name: 'Mechanical Keyboard', unit: 'pcs', rate: 85, stock: 7, minStock: 10, lastUpdated: new Date().toISOString() },
      { id: 1003, vendor: 'CableWorld', name: 'USB-C Cable 2m', unit: 'pcs', rate: 12, stock: 120, minStock: 20, lastUpdated: new Date().toISOString() }
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
        date: new Date().toISOString(),
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
  document.getElementById('statInventoryValue').innerHTML = `${settings.currency}0.00`;
  refreshAllUI();
}

// Update date/time
function updateDateTime() {
  const now = new Date();
  document.getElementById('currentDateTime').innerHTML = now.toLocaleDateString() + ' ' + now.toLocaleTimeString();
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
  document.getElementById('statTotalUnits').innerText = totalUnits;
  document.getElementById('statLowStock').innerText = lowCount;
  document.getElementById('statInventoryValue').innerHTML = `${settings.currency}${totalValue.toFixed(2)}`;
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

function saveProductFromModal() {
  const id = document.getElementById('editProductId').value;
  const vendor = document.getElementById('modalVendor').value.trim();
  const name = document.getElementById('modalName').value.trim();
  const unit = document.getElementById('modalUnit').value.trim();
  const rate = parseFloat(document.getElementById('modalRate').value);
  const stock = parseInt(document.getElementById('modalStock').value);
  const minStock = parseInt(document.getElementById('modalMinStock').value);
  
  if (!vendor || !name) { alert('Vendor and Product Name are required'); return; }
  if (isNaN(rate)) { alert('Rate must be a number'); return; }
  
  if (id) {
    const index = products.findIndex(p => p.id == id);
    if (index !== -1) {
      const oldStock = products[index].stock;
      products[index] = { ...products[index], vendor, name, unit, rate, stock, minStock, lastUpdated: new Date().toISOString() };
      if (oldStock !== stock) {
        addHistory(products[index].id, products[index].name, 'ADJUSTMENT', Math.abs(stock - oldStock), oldStock, stock, 'Manual edit adjustment');
      } else {
        addHistory(products[index].id, products[index].name, 'EDIT', 0, oldStock, stock, 'Product details updated');
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
  if (confirm('Permanently delete this product?')) {
    products = products.filter(p => p.id !== productId);
    persistData();
    refreshAllUI();
  }
}

// STOCK OPERATIONS
function stockIn() {
  const productId = parseInt(document.getElementById('stockInProduct').value);
  const qty = parseInt(document.getElementById('stockInQty').value);
  const note = document.getElementById('stockInNote').value;
  const date = document.getElementById('stockInDate').value;
  if (!productId || !qty || qty <= 0) { showMsg('stockInMsg', 'Select product and valid quantity', 'error'); return; }
  const prod = products.find(p => p.id === productId);
  if (prod) {
    const oldStock = prod.stock;
    prod.stock += qty;
    prod.lastUpdated = new Date().toISOString();
    addHistory(prod.id, prod.name, 'IN', qty, oldStock, prod.stock, `${note} | ${date}`);
    persistData();
    refreshAllUI();
    showMsg('stockInMsg', `Added ${qty} ${prod.unit} to ${prod.name}`, 'success');
    document.getElementById('stockInQty').value = '';
    document.getElementById('stockInNote').value = '';
  }
}

function stockOut() {
  const productId = parseInt(document.getElementById('stockOutProduct').value);
  const qty = parseInt(document.getElementById('stockOutQty').value);
  const note = document.getElementById('stockOutNote').value;
  const date = document.getElementById('stockOutDate').value;
  if (!productId || !qty || qty <= 0) { showMsg('stockOutMsg', 'Select product and valid quantity', 'error'); return; }
  const prod = products.find(p => p.id === productId);
  if (prod && prod.stock >= qty) {
    const oldStock = prod.stock;
    prod.stock -= qty;
    prod.lastUpdated = new Date().toISOString();
    addHistory(prod.id, prod.name, 'OUT', qty, oldStock, prod.stock, `${note} | ${date}`);
    persistData();
    refreshAllUI();
    showMsg('stockOutMsg', `Removed ${qty} ${prod.unit} from ${prod.name}`, 'success');
    document.getElementById('stockOutQty').value = '';
    document.getElementById('stockOutNote').value = '';
  } else {
    showMsg('stockOutMsg', `Insufficient stock! Available: ${prod?.stock || 0}`, 'error');
  }
}

function stockAdjust() {
  const productId = parseInt(document.getElementById('adjustProduct').value);
  const newQty = parseInt(document.getElementById('adjustNewQty').value);
  const reason = document.getElementById('adjustReason').value;
  const date = document.getElementById('adjustDate').value;
  if (!productId || isNaN(newQty) || newQty < 0) { showMsg('adjustMsg', 'Select product and valid quantity', 'error'); return; }
  const prod = products.find(p => p.id === productId);
  if (prod) {
    const oldStock =
