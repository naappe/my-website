const express = require('express');
const app = express();

app.use(express.static('.'));

app.get('/api/message', (req, res) => {
  res.json({ message: "Hello from backend!" });
});

const port = 3000;
app.listen(port, () => {
  console.log(`Server running on port ${port}`);
});
