const express = require('express');
const app = express();

app.use(express.static('.'));
app.use(express.json()); // For reading form data

// Your API endpoint (for frontend)
app.get('/api/message', (req, res) => {
  res.json({ message: "Hello from backend!" });
});

// Admin login check
app.post('/api/admin/login', (req, res) => {
  const { password } = req.body;
  // Change 'admin123' to your own password
  if (password === 'admin123') {
    res.json({ success: true });
  } else {
    res.json({ success: false });
  }
});

// Get content (for frontend and admin)
let siteContent = {
  title: "My Website",
  message: "Hello from backend!"
};

app.get('/api/content', (req, res) => {
  res.json(siteContent);
});

// Update content (admin only - simple key check)
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

const port = 3000;
app.listen(port, () => {
  console.log(`Server running on port ${port}`);
});
