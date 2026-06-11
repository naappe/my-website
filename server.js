const express = require('express');
const app = express();
const fs = require('fs');

app.use(express.static('.'));
app.use(express.json());

// Stock data file
const STOCK_FILE = 'stock.json';

// Load products
let products = [];

function loadStock() {
  try {
    if (fs.existsSync(STOCK_FILE)) {
      const data = fs.readFileSync(STOCK_FILE, 'utf8');
      products = JSON.parse(data);
    } else {
      products = [];
    }
  } catch(e) {
    products = [];
  }
}

function saveStock() {
  fs.writeFileSync(STOCK_FILE, JSON.stringify(products, null, 2));
}

loadStock();

// Get all products
app.get('/api/stock', (req, res) => {
  res.json(products);
});

// Add product
app.post('/api/stock/add', (req, res) => {
  const { name, stock, minStock } = req.body;
  
  if (!name) {
    return res.json({ success: false, error: 'Product name required' });
  }
  
  // Check if exists
  if (products.find(p => p.name.toLowerCase() === name.toLowerCase())) {
    return res.json({ success: false, error: 'Product already exists' });
  }
  
  products.push({
    id: Date.now(),
    name: name,
    stock: stock || 0,
    minStock: minStock || 5,
    history: []
  });
  
  saveStock();
  res.json({ success: true, products: products });
});

// Stock IN / OUT
app.post('/api/stock/update', (req, res) => {
  const { id, type, quantity, note } = req.body;
  
  const product = products.find(p => p.id === id);
  if (!product) {
    return res.json({ success: false, error: 'Product not found' });
  }
  
  if (type === 'out' && product.stock < quantity) {
    return res.json({ success: false, error: `Not enough stock! Only ${product.stock} available` });
  }
  
  const oldStock = product.stock;
  
  if (type === 'in') {
    product.stock += quantity;
  } else {
    product.stock -= quantity;
  }
  
  // Add to history
  product.history.unshift({
    date: new Date().toLocaleString(),
    type: type,
    quantity: quantity,
    oldStock: oldStock,
    newStock: product.stock,
    note: note || ''
  });
  
  // Keep only last 20 records
  if (product.history.length > 20) product.history.pop();
  
  saveStock();
  res.json({ success: true, product: product });
});

// Delete product
app.post('/api/stock/delete', (req, res) => {
  const { id } = req.body;
  products = products.filter(p => p.id !== id);
  saveStock();
  res.json({ success: true });
});

// Simple login (hardcoded)
app.post('/api/login', (req, res) => {
  const { password } = req.body;
  if (password === 'admin123') {
    res.json({ success: true });
  } else {
    res.json({ success: false });
  }
});

const port = 3000;
app.listen(port, () => {
  console.log(`Server running on port ${port}`);
  console.log(`Products loaded: ${products.length}`);
});
