# Lost & Found System

A web-based Lost and Found management system built with Java and vanilla JavaScript.

**Live Demo:** https://lost-found-system-053f.onrender.com

## Features

- Report found items with name, category, location, date, and description
- Search for lost items by name
- Claim items with description and date verification
- Items sorted by date using Insertion Sort
- Items looked up by ID using Binary Search
- Data persisted to `data.json` — survives server restarts

## Project Structure

```
├── WebServer.java       # Java HTTP server + business logic
├── web/
│   ├── index.html
│   ├── css/style.css
│   └── js/app.js
└── data.json            # Auto-generated on first item added
```

## Requirements

- Java 11 or higher

## Running the App

```bash
# Compile
javac WebServer.java

# Run
java WebServer
```

Then open http://localhost:8080 in your browser.

## Usage

**Finder** — Add items that were found. Each item gets an auto-assigned ID.

**Claimant** — Search for an item by name, then claim it by providing:
- The item ID
- At least 2 words matching the item's description
- The exact date the item was found
