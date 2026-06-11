const express = require('express');
const app = express();
const fs = require('fs');
const path = require('path');

app.use(express.static('.'));
app.use(express.json());

// ========== SERVE CSV FILE (Built-in, no external file needed) ==========
app.get('/data.csv', (req, res) => {
    res.setHeader('Content-Type', 'text/csv');
    res.setHeader('Content-Disposition', 'inline');
    res.send(`Product Name,Stock Quantity,Minimum Alert
Apple,50,5
Banana,30,3
Orange,25,5
Mango,15,2
Grapes,40,4
Watermelon,10,5
Pineapple,8,3
Strawberry,60,10
Blueberry,20,4
Raspberry,12,3
Kiwi,45,5
Papaya,22,4
Guava,35,3
Lychee,18,2
Dragon Fruit,12,3`);
});

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
const STOCK_FILE = 'stock.json';

let products = [];

function loadStock() {
  try {
    if (fs.existsSync(STOCK_FILE)) {
      const data = fs.readFileSync(STOCK_FILE, 'utf8');
      products = JSON.parse(data);
      if (!Array.isArray(products)) products = [];
    } else {
      products = [];
      saveStock();
    }
  } catch(e) {
    console.log('No existing stock data, creating new');
    products = [];
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

// ========== DESIGN API ==========
const DESIGN_FILE = 'design-settings.json';

let designSettings = {
  primaryColor: "#3498db",
  backgroundColor: "#f4f4f4",
  textColor: "#333333",
  headingColor: "#2c3e50",
  layoutStyle: "modern",
  showNavbar: true,
  showFooter: true,
  showHeroSection: true,
  showCardsSection: true,
  card1Title: "Stock Management",
  card1Text: "Track inventory, manage stock in/out, and get low stock alerts.",
  card2Title: "Admin Panel",
  card2Text: "Change website content, update messages, and manage settings.",
  card3Title: "Design Editor",
  card3Text: "Change colors, layout, and customize your website design.",
  customCSS: "",
  customHeader: "",
  customFooter: ""
};

function loadDesign() {
  try {
    if (fs.existsSync(DESIGN_FILE)) {
      const data = fs.readFileSync(DESIGN_FILE, 'utf8');
      const saved = JSON.parse(data);
      Object.assign(designSettings, saved);
    }
  } catch(e) {
    console.log('No design settings, using defaults');
  }
}

function saveDesign() {
  fs.writeFileSync(DESIGN_FILE, JSON.stringify(designSettings, null, 2));
}

loadDesign();

app.get('/api/design/get', (req, res) => {
  res.json({ success: true, settings: designSettings });
});

app.post('/api/design/save', (req, res) => {
  const { password, settings } = req.body;
  if (password === 'admin123') {
    Object.assign(designSettings, settings);
    saveDesign();
    res.json({ success: true });
  } else {
    res.json({ success: false });
  }
});

// ========== HELPER ENDPOINT ==========
app.get('/api/status', (req, res) => {
  res.json({
    status: 'running',
    products: products.length,
    timestamp: new Date().toISOString()
  });
});

// ========== START SERVER ==========
const port = 3000;
app.listen(port, () => {
  console.log(`✅ Server running on port ${port}`);
  console.log(`📦 Stock file: ${STOCK_FILE}`);
  console.log(`📊 Products loaded: ${products.length}`);
  console.log(`🎨 Design file: ${DESIGN_FILE}`);
  console.log(`📁 CSV available at: /data.csv`);
});
