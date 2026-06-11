const express = require('express');
const app = express();
const fs = require('fs');

app.use(express.static('.'));
app.use(express.json());

// ========== EXISTING CODE (keep this) ==========
app.get('/api/message', (req, res) => {
  res.json({ message: "Hello from backend!" });
});

app.post('/api/admin/login', (req, res) => {
  const { password } = req.body;
  if (password === 'admin123') {
    res.json({ success: true });
  } else {
    res.json({ success: false });
  }
});

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

// ========== NEW STOCK API (add this) ==========

// File to save stock data
const STOCK_FILE = 'stock-data.json';

// Load stock data from file
let stockProducts = [];

function loadStockData() {
  try {
    if (fs.existsSync(STOCK_FILE)) {
      const data = fs.readFileSync(STOCK_FILE, 'utf8');
      stockProducts = JSON.parse(data);
    }
  } catch(e) {
    console.log('No existing stock data');
  }
}

function saveStockData() {
  fs.writeFileSync(STOCK_FILE, JSON.stringify(stockProducts, null, 2));
}

loadStockData();

// Get stock data
app.get('/api/stock/get', (req, res) => {
  res.json({ success: true, products: stockProducts });
});

// Save stock data
app.post('/api/stock/save', (req, res) => {
  stockProducts = req.body.products;
  saveStockData();
  res.json({ success: true });
});

// ========== END STOCK API ==========

// ========== DESIGN SETTINGS (add this) ==========

// File to save design settings
const DESIGN_FILE = 'design-settings.json';

// Default design settings
let designSettings = {
  // Colors
  primaryColor: "#3498db",
  backgroundColor: "#f4f4f4",
  textColor: "#333333",
  headingColor: "#2c3e50",
  
  // Layout
  showNavbar: true,
  showFooter: true,
  layoutStyle: "modern", // modern, classic, dark
  
  // Custom HTML (admin can add anything)
  customHeader: "",
  customFooter: "",
  customCSS: "",
  
  // Homepage sections
  showHeroSection: true,
  showCardsSection: true,
  
  // Custom card titles and content
  card1Title: "Stock Management",
  card1Text: "Track inventory, manage stock in/out, and get low stock alerts.",
  card2Title: "Admin Panel", 
  card2Text: "Change website content, update messages, and manage settings.",
  card3Title: "Analytics",
  card3Text: "View reports and insights about your inventory and activity."
};

// Load design settings
function loadDesignSettings() {
  try {
    if (fs.existsSync(DESIGN_FILE)) {
      const data = fs.readFileSync(DESIGN_FILE, 'utf8');
      const saved = JSON.parse(data);
      Object.assign(designSettings, saved);
    }
  } catch(e) {
    console.log('No existing design settings');
  }
}

function saveDesignSettings() {
  fs.writeFileSync(DESIGN_FILE, JSON.stringify(designSettings, null, 2));
}

loadDesignSettings();

// Get design settings
app.get('/api/design/get', (req, res) => {
  res.json({ success: true, settings: designSettings });
});

// Save design settings (admin only)
app.post('/api/design/save', (req, res) => {
  const { password, settings } = req.body;
  if (password === 'admin123') {
    Object.assign(designSettings, settings);
    saveDesignSettings();
    res.json({ success: true });
  } else {
    res.json({ success: false });
  }
});

// ========== END DESIGN API ==========

const port = 3000;
app.listen(port, () => {
  console.log(`Server running on port ${port}`);
});
