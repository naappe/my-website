const express = require("express");
const fs = require("fs");
const cors = require("cors");
const bodyParser = require("body-parser");

const app = express();
const PORT = 3000;

app.use(cors());
app.use(bodyParser.json());
app.use(express.static(__dirname));

const DATA_FILE = "./data.json";
const STOCK_FILE = "./stock.json";

// Helpers
const read = (file) => JSON.parse(fs.readFileSync(file));
const write = (file, data) =>
  fs.writeFileSync(file, JSON.stringify(data, null, 2));

// ---------------- PRODUCTS ----------------
app.get("/api/products", (req, res) => {
  res.json(read(DATA_FILE));
});

app.post("/api/products", (req, res) => {
  const products = read(DATA_FILE);
  const newProduct = req.body;

  newProduct.id = Date.now();
  newProduct.stock = Number(newProduct.stock || 0);

  products.push(newProduct);
  write(DATA_FILE, products);

  res.json(newProduct);
});

app.put("/api/products/:id", (req, res) => {
  let products = read(DATA_FILE);
  const id = Number(req.params.id);

  products = products.map((p) =>
    p.id === id ? { ...p, ...req.body } : p
  );

  write(DATA_FILE, products);
  res.json({ success: true });
});

app.delete("/api/products/:id", (req, res) => {
  let products = read(DATA_FILE);
  const id = Number(req.params.id);

  products = products.filter((p) => p.id !== id);
  write(DATA_FILE, products);

  res.json({ success: true });
});

// ---------------- SALES / STOCK ----------------
app.post("/api/sale", (req, res) => {
  const { items } = req.body;

  let products = read(DATA_FILE);
  let stock = read(STOCK_FILE);

  items.forEach((item) => {
    const product = products.find((p) => p.id === item.id);

    if (product) {
      product.stock -= item.qty;

      stock.push({
        date: new Date().toISOString(),
        productId: item.id,
        name: product.name,
        qty: -item.qty,
        type: "SALE"
      });
    }
  });

  write(DATA_FILE, products);
  write(STOCK_FILE, stock);

  res.json({ success: true });
});

// ---------------- DASHBOARD ----------------
app.get("/api/dashboard", (req, res) => {
  const products = read(DATA_FILE);
  const stock = read(STOCK_FILE);

  const totalProducts = products.length;
  const totalStock = products.reduce((a, b) => a + (b.stock || 0), 0);
  const lowStock = products.filter(p => p.stock <= (p.minStock || 5));

  res.json({
    totalProducts,
    totalStock,
    lowStockCount: lowStock.length,
    stock,
    lowStock
  });
});

app.listen(PORT, () => {
  console.log(`Server running on http://localhost:${PORT}`);
});
