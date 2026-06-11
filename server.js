const express = require("express");
const fs = require("fs");
const cors = require("cors");

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());
app.use(express.static(__dirname));

const DB_PRODUCTS = "./data.json";
const DB_STOCK = "./stock.json";

// ---------- HELPERS ----------
const read = (file) => JSON.parse(fs.readFileSync(file, "utf-8"));
const write = (file, data) =>
  fs.writeFileSync(file, JSON.stringify(data, null, 2));

// ---------- INIT SAFETY ----------
if (!fs.existsSync(DB_PRODUCTS)) write(DB_PRODUCTS, []);
if (!fs.existsSync(DB_STOCK)) write(DB_STOCK, []);

// ---------- PRODUCTS ----------
app.get("/api/products", (req, res) => {
  res.json(read(DB_PRODUCTS));
});

app.post("/api/products", (req, res) => {
  const products = read(DB_PRODUCTS);

  const newItem = {
    id: Date.now(),
    name: req.body.name,
    price: Number(req.body.price || 0),
    stock: Number(req.body.stock || 0),
    minStock: Number(req.body.minStock || 5),
    category: req.body.category || "General"
  };

  products.push(newItem);
  write(DB_PRODUCTS, products);

  res.json(newItem);
});

app.put("/api/products/:id", (req, res) => {
  let products = read(DB_PRODUCTS);

  products = products.map(p =>
    p.id == req.params.id ? { ...p, ...req.body } : p
  );

  write(DB_PRODUCTS, products);
  res.json({ success: true });
});

app.delete("/api/products/:id", (req, res) => {
  let products = read(DB_PRODUCTS);
  products = products.filter(p => p.id != req.params.id);

  write(DB_PRODUCTS, products);
  res.json({ success: true });
});

// ---------- POS / SALE ----------
app.post("/api/sale", (req, res) => {
  let products = read(DB_PRODUCTS);
  let stockLog = read(DB_STOCK);

  const cart = req.body.items;

  cart.forEach(item => {
    const product = products.find(p => p.id === item.id);

    if (product) {
      product.stock -= item.qty;

      stockLog.push({
        id: Date.now(),
        productId: product.id,
        name: product.name,
        qty: -item.qty,
        type: "SALE",
        date: new Date().toISOString()
      });
    }
  });

  write(DB_PRODUCTS, products);
  write(DB_STOCK, stockLog);

  res.json({ success: true });
});

// ---------- DASHBOARD ----------
app.get("/api/dashboard", (req, res) => {
  const products = read(DB_PRODUCTS);
  const stockLog = read(DB_STOCK);

  const totalProducts = products.length;
  const totalStock = products.reduce((a, b) => a + b.stock, 0);
  const lowStock = products.filter(p => p.stock <= p.minStock);

  const salesCount = stockLog.filter(s => s.type === "SALE").length;

  res.json({
    totalProducts,
    totalStock,
    lowStock,
    salesCount
  });
});

// ---------- START ----------
app.listen(PORT, () => {
  console.log("Server running on port " + PORT);
});
