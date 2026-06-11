const express = require('express');
const app = express();
const fs = require('fs');

app.use(express.static('.'));
app.use(express.json());

// ========== CONTENT API ==========
let siteContent = {
  title: "My Website",
  message: "Hello from backend!"
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
    }
  } catch(e) {
    console.log('No existing stock data');
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
    console.log('No design settings');
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

// ========== START SERVER ==========
const port = 3000;
app.listen(port, () => {
  console.log(`Server running on port ${port}`);
});
