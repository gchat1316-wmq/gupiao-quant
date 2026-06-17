mysqldump: [Warning] Using a password on the command line interface can be insecure.
Warning: A partial dump from a server that has GTIDs will by default include the GTIDs of all transactions, even those that changed suppressed parts of the database. If you don't want to restore GTIDs, pass --set-gtid-purged=OFF. To make a complete dump, pass --all-databases --triggers --routines --events. 
Warning: A dump from a server that has GTIDs enabled will by default include the GTIDs of all transactions, even those that were executed during its extraction and might not be represented in the dumped data. This might result in an inconsistent data dump. 
In order to ensure a consistent backup of the database, pass --single-transaction or --lock-all-tables or --source-data. 
-- MySQL dump 10.13  Distrib 9.2.0, for macos15.2 (arm64)
--
-- Host: 43.140.208.165    Database: wucai_trade
-- ------------------------------------------------------
-- Server version	9.7.0

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- GTID state at the beginning of the backup 
--

SET @@GLOBAL.GTID_PURGED=/*!80000 '+'*/ '5759e58d-5830-11f1-9830-5254001dd9f3:1-27046';

--
-- Dumping data for table `study_course`
--
-- WHERE:  id IN (1,6,103,104,105)

LOCK TABLES `study_course` WRITE;
/*!40000 ALTER TABLE `study_course` DISABLE KEYS */;
INSERT INTO `study_course` (`id`, `title`, `summary`, `cover_text`, `cover_color`, `owner`, `source_type`, `visibility`, `status`, `progress`, `category_id`, `learn_status`, `mastered_cnt`, `total_cnt`, `learner_cnt`, `book_cover_url`, `recommend_images`, `created_at`) VALUES (1,'\"学习搭子\"使用宝典:专治学习困境的AIT具指南','清官\"学习搭子\"是一款让你高效自学的工具,基于你上传的学习资料,智能生成知识图谱、AI详解、闪卡与测验,帮你把零散信息变成完整知识体系。','📘','#e8f5e9','智谱清言创建','upload','private','ready',100,NULL,'learning',1,29,29100,NULL,NULL,'2026-05-26 11:18:30'),(6,'大型语言模型初级认证知识图谱',NULL,'🧠','#e0f7fa','智谱清言创建','upload','private','ready',100,NULL,'pending',0,68,117000,NULL,NULL,'2026-05-26 11:18:30'),(103,'食品安全知识体系指南','本文档系统性地阐述了食品安全的知识框架,从基本概念、常见风险到日常处理方法,内容全面。','🍱','#fff9c4','智谱清言创建','upload','public','ready',100,5,'pending',0,0,1713,NULL,NULL,'2026-05-26 11:18:30'),(104,'户外应急救援核心技能','本文档介绍了户外应急救援所需的核心技能与装备指南,涵盖紧急情况下的救助方法、内容全面。','⛑️','#e8f5e9','智谱清言创建','upload','public','ready',100,4,'pending',0,0,680,NULL,NULL,'2026-05-26 11:18:30'),(105,'火灾自救与应急处置','火灾自救是一套关于火灾自救与应急处置的全面指南,内容覆盖了火灾的科学。','🔥','#ffebee','智谱清言创建','upload','public','ready',100,4,'pending',0,0,315,NULL,NULL,'2026-05-26 11:18:30');
/*!40000 ALTER TABLE `study_course` ENABLE KEYS */;
UNLOCK TABLES;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-06-17 21:26:29
