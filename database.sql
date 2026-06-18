-- Create database
CREATE DATABASE IF NOT EXISTS campaign_test;
USE campaign_test;

-- Voters table
CREATE TABLE IF NOT EXISTS voters (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    national_id VARCHAR(50),
    address VARCHAR(255),
    phone VARCHAR(20),
    party VARCHAR(50),
    photo LONGTEXT,
    status VARCHAR(50) DEFAULT 'not_contacted',
    notes TEXT,
    support_level INT DEFAULT 0,
    revisit_priority INT DEFAULT 0,
    assigned_to VARCHAR(100),
    last_contacted_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Visit logs table
CREATE TABLE IF NOT EXISTS visit_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    voter_id INT NOT NULL,
    canvasser VARCHAR(100) DEFAULT 'Me',
    outcome VARCHAR(50) NOT NULL,
    notes TEXT,
    support_level INT DEFAULT 0,
    revisit_priority INT DEFAULT 0,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (voter_id) REFERENCES voters(id) ON DELETE CASCADE
);