<?php
// api.php - All endpoints in one file for testing

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

// Database config
define('DB_HOST', 'localhost');
define('DB_NAME', 'campaign_test');
define('DB_USER', 'root');
define('DB_PASS', '');

function getDB() {
    try {
        $pdo = new PDO(
            "mysql:host=" . DB_HOST . ";dbname=" . DB_NAME . ";charset=utf8",
            DB_USER,
            DB_PASS,
            [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
        );
        return $pdo;
    } catch(PDOException $e) {
        http_response_code(500);
        echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
        exit;
    }
}

function getJsonInput() {
    return json_decode(file_get_contents('php://input'), true);
}

function sendResponse($data, $status = 200) {
    http_response_code($status);
    echo json_encode($data);
    exit;
}

// ===== ROUTE: GET /api.php?action=get_voters =====
if (isset($_GET['action']) && $_GET['action'] === 'get_voters') {
    $pdo = getDB();
    $stmt = $pdo->query("SELECT * FROM voters ORDER BY id DESC LIMIT 100");
    $voters = $stmt->fetchAll(PDO::FETCH_ASSOC);
    sendResponse(['success' => true, 'count' => count($voters), 'voters' => $voters]);
}

// ===== ROUTE: GET /api.php?action=get_logs =====
if (isset($_GET['action']) && $_GET['action'] === 'get_logs') {
    $pdo = getDB();
    $voterId = isset($_GET['voter_id']) ? intval($_GET['voter_id']) : null;
    
    $sql = "SELECT l.*, v.name as voter_name FROM visit_logs l 
            LEFT JOIN voters v ON l.voter_id = v.id";
    $params = [];
    
    if ($voterId) {
        $sql .= " WHERE l.voter_id = :voter_id";
        $params[':voter_id'] = $voterId;
    }
    
    $sql .= " ORDER BY l.timestamp DESC LIMIT 100";
    $stmt = $pdo->prepare($sql);
    $stmt->execute($params);
    $logs = $stmt->fetchAll(PDO::FETCH_ASSOC);
    sendResponse(['success' => true, 'count' => count($logs), 'logs' => $logs]);
}

// ===== ROUTE: POST /api.php (Import Voters) =====
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $input = getJsonInput();
    
    // Check if it's a CSV import (has 'voters' key)
    if (isset($input['voters']) && is_array($input['voters'])) {
        $pdo = getDB();
        $inserted = 0;
        $errors = [];
        
        foreach ($input['voters'] as $voter) {
            try {
                $stmt = $pdo->prepare("
                    INSERT INTO voters (
                        name, national_id, address, phone, party, 
                        photo, status, notes, support_level, revisit_priority
                    ) VALUES (
                        :name, :national_id, :address, :phone, :party,
                        :photo, :status, :notes, :support_level, :revisit_priority
                    )
                ");
                
                $stmt->execute([
                    ':name' => $voter['name'] ?? '',
                    ':national_id' => $voter['national_id'] ?? '',
                    ':address' => $voter['address'] ?? '',
                    ':phone' => $voter['phone'] ?? '',
                    ':party' => $voter['party'] ?? '',
                    ':photo' => $voter['photo'] ?? '',
                    ':status' => $voter['status'] ?? 'not_contacted',
                    ':notes' => $voter['notes'] ?? '',
                    ':support_level' => $voter['supportLevel'] ?? 0,
                    ':revisit_priority' => $voter['revisitPriority'] ?? 0
                ]);
                $inserted++;
            } catch (PDOException $e) {
                $errors[] = $e->getMessage();
            }
        }
        
        sendResponse([
            'success' => true,
            'inserted' => $inserted,
            'total' => count($input['voters']),
            'errors' => $errors
        ]);
    }
    
    // Check if it's a log entry (has 'voter_id' and 'outcome')
    if (isset($input['voter_id']) && isset($input['outcome'])) {
        $pdo = getDB();
        
        // Insert log
        $stmt = $pdo->prepare("
            INSERT INTO visit_logs (voter_id, canvasser, outcome, notes, support_level, revisit_priority)
            VALUES (:voter_id, :canvasser, :outcome, :notes, :support_level, :revisit_priority)
        ");
        
        $stmt->execute([
            ':voter_id' => $input['voter_id'],
            ':canvasser' => $input['canvasser'] ?? 'Me',
            ':outcome' => $input['outcome'],
            ':notes' => $input['notes'] ?? '',
            ':support_level' => $input['support_level'] ?? 0,
            ':revisit_priority' => $input['revisit_priority'] ?? 0
        ]);
        
        $logId = $pdo->lastInsertId();
        
        // Update voter status
        $stmt2 = $pdo->prepare("
            UPDATE voters SET status = :status, last_contacted_at = NOW(), updated_at = NOW()
            WHERE id = :id
        ");
        $stmt2->execute([
            ':status' => $input['outcome'],
            ':id' => $input['voter_id']
        ]);
        
        sendResponse([
            'success' => true,
            'log_id' => $logId,
            'message' => 'Log saved successfully'
        ]);
    }
    
    sendResponse(['error' => 'Invalid request'], 400);
}

// ===== ROUTE: PUT /api.php (Update Voter) =====
if ($_SERVER['REQUEST_METHOD'] === 'PUT') {
    parse_str(file_get_contents('php://input'), $putData);
    $input = array_merge($putData, getJsonInput() ?: []);
    
    if (!isset($input['id'])) {
        sendResponse(['error' => 'Voter ID required'], 400);
    }
    
    $pdo = getDB();
    $fields = [];
    $params = [':id' => $input['id']];
    
    $allowedFields = ['status', 'notes', 'support_level', 'revisit_priority', 'assigned_to', 'photo'];
    
    foreach ($allowedFields as $field) {
        if (isset($input[$field])) {
            $fields[] = "$field = :$field";
            $params[":$field"] = $input[$field];
        }
    }
    
    if (empty($fields)) {
        sendResponse(['error' => 'No fields to update'], 400);
    }
    
    if (isset($input['status'])) {
        $fields[] = "last_contacted_at = NOW()";
    }
    
    $sql = "UPDATE voters SET " . implode(', ', $fields) . ", updated_at = NOW() WHERE id = :id";
    $stmt = $pdo->prepare($sql);
    $stmt->execute($params);
    
    sendResponse(['success' => true, 'message' => 'Voter updated successfully']);
}

// ===== ROUTE: GET /api.php?action=stats =====
if (isset($_GET['action']) && $_GET['action'] === 'stats') {
    $pdo = getDB();
    
    $total = $pdo->query("SELECT COUNT(*) FROM voters")->fetchColumn();
    $knocked = $pdo->query("SELECT COUNT(*) FROM visit_logs")->fetchColumn();
    $supporters = $pdo->query("SELECT COUNT(*) FROM visit_logs WHERE outcome = 'supporter'")->fetchColumn();
    $notHome = $pdo->query("SELECT COUNT(*) FROM visit_logs WHERE outcome = 'not_home'")->fetchColumn();
    
    sendResponse([
        'success' => true,
        'stats' => [
            'total_voters' => (int)$total,
            'knocked' => (int)$knocked,
            'supporters' => (int)$supporters,
            'not_home' => (int)$notHome
        ]
    ]);
}

// Default response
sendResponse(['error' => 'Unknown action'], 404);
?>