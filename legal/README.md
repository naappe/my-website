# LawLens research preview

Static GitHub Pages site at `/my-website/legal/`. No build or credentials required.

Search English and Dhivehi source records; filter documents; browse original PDFs; inspect review status and copy references. Results paginate in batches of 20 without discarding matches. Content is rendered as text, never HTML.

Corpus snapshot: database version 4, 19 October 2026. Seven source documents, 650 original PDF pages preserved in the source collection, 2,477 website research records. The six included PDFs total 616 pages. The 34-page criminal-procedure rough draft is metadata-only and never searchable. Comparison edition is opt-in. No source has completed full legal verification. Website pages do not provide case judgments or generated legal opinions.

`data.json` is an exported research view of the version 4 SQLite collection. The original English Constitution uses article records; the civil law uses native portal clauses; other sources use page records. Source SHA-256 values identify preserved PDFs. Search is literal and requires all query words within the same record; it does not translate or infer semantic relevance. Current-law applicability, amendments and text accuracy require separate review.

Run checks: `node --test legal/search.test.mjs` from repository root, and `node --check legal/app.mjs`. Existing website routes remain untouched.
