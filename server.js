const express = require('express');
const app = express();
const fs = require('fs');

app.use(express.static('.'));
app.use(express.json());

// ========== CONTENT API ==========
let siteContent = {
  title: "Stock Management System",
  message: "Welcome to your inventory manager"
};

app.get('/api/content', (req, res) => {
  res.json(siteContent);
});

app.post('/api/admin/update', (req, res) => {
  const { password, title, message } = req.body;
  if (password === 'admin123') {
    if (title) siteContent.title = title;
    if (message) siteContent.message = message;
    res.json({ success: true, content: siteContent });
  } else {
    res.json({ success: false });
  }
});

// ========== LOGIN API ==========
app.post('/api/admin/login', (req, res) => {
  const { password } = req.body;
  if (password === 'admin123') {
    res.json({ success: true });
  } else {
    res.json({ success: false });
  }
});

// ========== STOCK API ==========
const STOCK_FILE = 'stock-data.json';

let products = [];

function loadStock() {
  try {
    if (fs.existsSync(STOCK_FILE)) {
      const data = fs.readFileSync(STOCK_FILE, 'utf8');
      products = JSON.parse(data);
    }
  } catch(e) {
    console.log('No existing stock data');
  }
}

function saveStock() {
  fs.writeFileSync(STOCK_FILE, JSON.stringify(products, null, 2));
}

loadStock();

app.get('/api/stock/get', (req, res) => {
  res.json({ success: true, products: products });
});

app.post('/api/stock/save', (req, res) => {
  products = req.body.products;
  saveStock();
  res.json({ success: true });
});

// ========== START SERVER ==========
const port = 3000;
app.listen(port, () => {
  console.log(`Server running on port ${port}`);
});
