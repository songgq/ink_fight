CREATE TABLE IF NOT EXISTS game_account (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_game_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='游戏账号';

CREATE TABLE IF NOT EXISTS game_session (
  token VARCHAR(64) NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  player_id BIGINT NOT NULL,
  expire_time DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_game_session_player (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='游戏登录会话';

CREATE TABLE IF NOT EXISTS game_player (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  level INT NOT NULL DEFAULT 1,
  exp INT NOT NULL DEFAULT 0,
  gold INT NOT NULL DEFAULT 0,
  journey_unlocked INT NOT NULL DEFAULT 1,
  journey_max_stage INT NOT NULL DEFAULT 0,
  ladder_score INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_game_player_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='游戏玩家';

CREATE TABLE IF NOT EXISTS game_card_config (
  code VARCHAR(64) NOT NULL PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  type VARCHAR(32) NOT NULL,
  asset_key VARCHAR(64) NOT NULL,
  growable TINYINT NOT NULL,
  max_level INT NOT NULL DEFAULT 11,
  stack_limit INT NOT NULL DEFAULT 9,
  sell_gold INT NOT NULL DEFAULT 1,
  hp INT NOT NULL DEFAULT 0,
  attack INT NOT NULL DEFAULT 0,
  range_value INT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡牌配置';

CREATE TABLE IF NOT EXISTS game_player_card (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  player_id BIGINT NOT NULL,
  card_code VARCHAR(64) NOT NULL,
  level INT NOT NULL DEFAULT 1,
  count INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_game_player_card (player_id, card_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家卡牌';

CREATE TABLE IF NOT EXISTS game_card_upgrade_config (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  level INT NOT NULL,
  need_count INT NOT NULL,
  need_gold INT NOT NULL,
  gain_exp INT NOT NULL,
  UNIQUE KEY uk_game_card_upgrade_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡牌升级配置';

CREATE TABLE IF NOT EXISTS game_pack_config (
  code VARCHAR(32) NOT NULL PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  initial_price INT NOT NULL,
  price_increase INT NOT NULL,
  draw_count INT NOT NULL,
  required_empty_slots INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='局内卡包配置';

CREATE TABLE IF NOT EXISTS game_pack_pool_config (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  pack_code VARCHAR(32) NOT NULL,
  card_code VARCHAR(64) NOT NULL,
  weight INT NOT NULL DEFAULT 1,
  UNIQUE KEY uk_game_pack_pool (pack_code, card_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='局内卡包卡池';

CREATE TABLE IF NOT EXISTS game_journey_stage (
  stage_no INT NOT NULL PRIMARY KEY,
  chapter_no INT NOT NULL,
  name VARCHAR(64) NOT NULL,
  reward_gold INT NOT NULL,
  enemy_base_hp INT NOT NULL,
  config_json JSON NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='征途关卡';

CREATE TABLE IF NOT EXISTS game_battle (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  battle_no VARCHAR(64) NOT NULL,
  player_id BIGINT NOT NULL,
  mode VARCHAR(32) NOT NULL,
  stage_no INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  winner VARCHAR(32) NULL,
  star INT NOT NULL DEFAULT 0,
  seed BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_game_battle_no (battle_no),
  KEY idx_game_battle_player (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对局';

CREATE TABLE IF NOT EXISTS game_battle_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  battle_id BIGINT NOT NULL,
  state_json JSON NOT NULL,
  command_log JSON NULL,
  event_log JSON NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_game_battle_snapshot (battle_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对局快照';

INSERT INTO game_card_config(code, name, type, asset_key, growable, max_level, stack_limit, sell_gold, hp, attack, range_value, sort_order) VALUES
('villager','村民','unit','villager',1,11,9,1,30,5,1,10),
('worker','工人','unit','worker',1,11,9,1,40,7,1,20),
('swordsman','剑士','unit','swordsman',1,11,9,1,60,12,1,30),
('archer','弓箭手','unit','archer',1,11,9,1,40,14,3,40),
('mage','法师','unit','mage',1,11,9,1,35,10,3,50),
('shieldman','战士','unit','shieldman',1,11,9,1,100,8,1,60),
('spearman','长枪手','unit','spearman',1,11,9,1,55,10,2,70),
('maternityRoom','产房','building','maternityRoom',1,11,9,1,35,0,0,110),
('nursery','育婴室','building','nursery',1,11,9,1,35,0,0,120),
('trainingCamp','训练营','building','trainingCamp',1,11,9,1,42,0,0,130),
('belt','传送带','building','belt',1,11,9,1,24,0,0,140),
('toolWorkshop','镐子工坊','building','toolWorkshop',1,11,9,1,36,0,0,150),
('swordWorkshop','长剑工坊','building','swordWorkshop',1,11,9,1,36,0,0,160),
('bowWorkshop','弓箭工坊','building','bowWorkshop',1,11,9,1,36,0,0,170),
('staffWorkshop','法杖工坊','building','staffWorkshop',1,11,9,1,36,0,0,180),
('shieldWorkshop','护盾工坊','building','shieldWorkshop',1,11,9,1,36,0,0,190),
('spearWorkshop','长枪工坊','building','spearWorkshop',1,11,9,1,36,0,0,200),
('ironMine','铁矿','resource','ironMine',1,11,9,1,80,0,0,210),
('goldMine','金矿','resource','goldMine',1,11,9,1,80,0,0,220),
('forest','果树','resource','forest',1,11,9,1,80,0,0,230),
('baby','婴儿','material','baby',0,1,99,1,1,0,0,310),
('ore','矿石','material','pickaxe',0,1,99,1,1,0,0,320),
('pickaxe','镐子','equipment','pickaxe',0,1,99,1,1,0,0,330),
('sword','长剑','equipment','sword',0,1,99,1,1,0,0,340),
('bow','弓箭','equipment','bow',0,1,99,1,1,0,0,350),
('staff','法杖','equipment','staff',0,1,99,1,1,0,0,360),
('shield','护盾','equipment','shield',0,1,99,1,1,0,0,370),
('spear','长枪','equipment','spear',0,1,99,1,1,0,0,380)
ON DUPLICATE KEY UPDATE name=VALUES(name), type=VALUES(type), asset_key=VALUES(asset_key), growable=VALUES(growable), max_level=VALUES(max_level), stack_limit=VALUES(stack_limit), sell_gold=VALUES(sell_gold), hp=VALUES(hp), attack=VALUES(attack), range_value=VALUES(range_value), sort_order=VALUES(sort_order);

INSERT INTO game_card_upgrade_config(level, need_count, need_gold, gain_exp) VALUES
(1,2,20,5),(2,4,50,8),(3,10,120,12),(4,20,300,18),(5,50,700,25),(6,100,1500,35),(7,200,3000,50),(8,400,6000,70),(9,800,12000,100),(10,1000,20000,150)
ON DUPLICATE KEY UPDATE need_count=VALUES(need_count), need_gold=VALUES(need_gold), gain_exp=VALUES(gain_exp);

INSERT INTO game_pack_config(code, name, initial_price, price_increase, draw_count, required_empty_slots) VALUES
('building','建筑卡包',3,1,2,2),
('resource','资源卡包',3,1,4,4)
ON DUPLICATE KEY UPDATE name=VALUES(name), initial_price=VALUES(initial_price), price_increase=VALUES(price_increase), draw_count=VALUES(draw_count), required_empty_slots=VALUES(required_empty_slots);

INSERT INTO game_pack_pool_config(pack_code, card_code, weight) VALUES
('building','maternityRoom',1),('building','nursery',1),('building','trainingCamp',1),('building','belt',1),
('building','toolWorkshop',1),('building','swordWorkshop',1),('building','bowWorkshop',1),('building','staffWorkshop',1),('building','shieldWorkshop',1),('building','spearWorkshop',1),
('building','ironMine',1),('building','goldMine',1),('building','forest',1),
('resource','villager',4),('resource','worker',2),('resource','baby',2),('resource','ore',3),
('resource','pickaxe',1),('resource','sword',1),('resource','bow',1),('resource','staff',1),('resource','shield',1),('resource','spear',1),
('resource','swordsman',2),('resource','archer',2),('resource','mage',1),('resource','shieldman',1),('resource','spearman',2)
ON DUPLICATE KEY UPDATE weight=VALUES(weight);

INSERT INTO game_journey_stage(stage_no, chapter_no, name, reward_gold, enemy_base_hp, config_json) VALUES
(1,1,'第1关 草原试炼',50,1000, JSON_OBJECT('enemySpawns', JSON_ARRAY(JSON_OBJECT('time', 20, 'lane', 1, 'cardCode', 'swordsman')), 'tutorial', JSON_ARRAY('采集资源','购买卡包','摆放建筑','备战战斗')))
ON DUPLICATE KEY UPDATE chapter_no=VALUES(chapter_no), name=VALUES(name), reward_gold=VALUES(reward_gold), enemy_base_hp=VALUES(enemy_base_hp), config_json=VALUES(config_json);
