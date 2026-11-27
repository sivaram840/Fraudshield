-- Run once as a MySQL administrator (e.g. root):
--   mysql -u root -p < database/setup.sql
-- Creates the database and a dedicated application user. The password below is a
-- local development value; change it here and in DB_PASSWORD for anything shared.
CREATE DATABASE IF NOT EXISTS fraudshield CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'fraudshield'@'localhost' IDENTIFIED BY 'fraudshield_dev';
GRANT ALL PRIVILEGES ON fraudshield.* TO 'fraudshield'@'localhost';
FLUSH PRIVILEGES;
