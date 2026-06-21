package cn.iocoder.yudao.module.game.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class GameAppService {

    // ── Grid dimensions ──
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 22;
    private static final int VISIBLE_ROW_START = 8;
    private static final int VISIBLE_ROWS = 14;

    // ── Enemy zone (Y00-Y07, rows 0-7) ──
    private static final int ENEMY_STORAGE_ROW = 0;      // Y00
    private static final int ENEMY_PLACE_START_ROW = 2;  // Y02
    private static final int ENEMY_PLACE_END_ROW = 7;    // Y07
    private static final int ENEMY_BENCH_ROW = 8;        // Y08

    // ── Battle area (Y09-Y11, rows 9-11) ──
    private static final int BATTLE_UPPER_ROW = 9;   // Y09
    private static final int BATTLE_MIDDLE_ROW = 10;  // Y10
    private static final int BATTLE_LOWER_ROW = 11;  // Y11
    private static final int[] BATTLE_ROWS = {BATTLE_UPPER_ROW, BATTLE_MIDDLE_ROW, BATTLE_LOWER_ROW};

    // Player battle columns: X2=后排, X3=中排, X4=前排
    private static final int PLAYER_BACK_COL = 1;   // X2
    private static final int PLAYER_MID_COL = 2;    // X3
    private static final int PLAYER_FRONT_COL = 3;  // X4
    private static final int[] PLAYER_BATTLE_COLS = {PLAYER_BACK_COL, PLAYER_MID_COL, PLAYER_FRONT_COL};

    // Wall column
    private static final int WALL_COL = 4;  // X5

    // Enemy battle columns: X6=前排, X7=中排, X8=后排
    private static final int ENEMY_FRONT_COL = 5;  // X6
    private static final int ENEMY_MID_COL = 6;    // X7
    private static final int ENEMY_BACK_COL = 7;   // X8
    private static final int[] ENEMY_BATTLE_COLS = {ENEMY_BACK_COL, ENEMY_MID_COL, ENEMY_FRONT_COL};

    // Base positions: X1=我方基地, X9=敌方基地
    private static final int PLAYER_BASE_COL = 0;
    private static final int PLAYER_BASE_ROW = BATTLE_MIDDLE_ROW;
    private static final int ENEMY_BASE_COL = 8;
    private static final int ENEMY_BASE_ROW = BATTLE_MIDDLE_ROW;

    // ── Player zone (Y12-Y21, rows 12-21) ──
    private static final int PLAYER_BENCH_ROW = 12;        // Y12
    private static final int PLAYER_PLACE_START_ROW = 13;  // Y13
    private static final int PLAYER_PLACE_END_ROW = 18;    // Y18
    private static final int PLAYER_RESOURCE_ROW = 19;     // Y19 fixed resource row
    private static final int PLAYER_STORAGE_ROW = 20;      // Y20 storage row
    private static final int PLAYER_PACK_ROW = 21;         // Y21 pack row
    private static final int STORAGE_SLOT_COUNT = 9;

    // ── Game clock constants ──
    private static final int MAIN_PHASE_DURATION = 1800;
    private static final int FINAL_PHASE_DURATION = 300;
    private static final int TOTAL_TICKS = MAIN_PHASE_DURATION + FINAL_PHASE_DURATION;
    private static final int ATTACK_BEAT_INTERVAL = 30;
    private static final int MAGE_ATTACK_INTERVAL = 40;
    private static final int WALL_DECAY_TICKS = 300;
    private static final int WALL_MAX_HP = 300;
    private static final int PLAYER_BASE_HP_LV1 = 1000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private PasswordEncoder passwordEncoder;
    @Resource
    private ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> register(Map<String, Object> req) {
        String username = required(req, "username");
        String password = required(req, "password");
        String nickname = StrUtil.blankToDefault(str(req.get("nickname")), username);
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM game_account WHERE username = ?", Integer.class, username);
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("账号已存在");
        }
        long accountId = insertAndReturnId(
                "INSERT INTO game_account(username,password_hash,nickname,status) VALUES (?,?,?,1)",
                username, passwordEncoder.encode(password), nickname);
        long playerId = insertAndReturnId(
                "INSERT INTO game_player(account_id,nickname,level,exp,gold,journey_unlocked,journey_max_stage,ladder_score) VALUES (?,?,?,?,?,?,?,?)",
                accountId, nickname, 1, 0, 500, 1, 0, 0);
        jdbcTemplate.update("""
                INSERT INTO game_player_card(player_id, card_code, level, count)
                SELECT ?, code, 1, 0 FROM game_card_config WHERE growable = 1
                ON DUPLICATE KEY UPDATE level = VALUES(level)
                """, playerId);
        return authResponse(accountId, playerId);
    }

    public Map<String, Object> login(Map<String, Object> req) {
        String username = required(req, "username");
        String password = required(req, "password");
        Map<String, Object> account = queryOne("SELECT * FROM game_account WHERE username = ? AND status = 1", username);
        if (account == null || !passwordEncoder.matches(password, str(account.get("password_hash")))) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        Map<String, Object> player = queryOne("SELECT * FROM game_player WHERE account_id = ?", account.get("id"));
        if (player == null) {
            throw new IllegalStateException("玩家数据不存在");
        }
        return authResponse(num(account.get("id")), num(player.get("id")));
    }

    public Map<String, Object> me(String authorization) {
        return Map.of("player", playerSummary(currentPlayerId(authorization)));
    }

    public Map<String, Object> cards(String authorization) {
        long playerId = currentPlayerId(authorization);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.code, c.name, c.type, c.asset_key AS assetKey, c.hp, c.attack, c.range_value AS rangeValue,
                       pc.level, pc.count, u.need_count AS needCount, u.need_gold AS needGold,
                       CASE WHEN pc.level < c.max_level AND pc.count >= COALESCE(u.need_count, 999999)
                              AND p.gold >= COALESCE(u.need_gold, 999999) THEN 1 ELSE 0 END AS upgradeable
                FROM game_card_config c
                JOIN game_player_card pc ON pc.card_code = c.code AND pc.player_id = ?
                JOIN game_player p ON p.id = pc.player_id
                LEFT JOIN game_card_upgrade_config u ON u.level = pc.level
                WHERE c.growable = 1
                ORDER BY c.sort_order ASC
                """, playerId);
        return Map.of("player", playerSummary(playerId), "cards", rows);
    }

    @Transactional
    public Map<String, Object> upgrade(String authorization, String cardCode) {
        long playerId = currentPlayerId(authorization);
        Map<String, Object> card = queryOne("""
                SELECT pc.*, c.max_level, u.need_count, u.need_gold, u.gain_exp, p.gold
                FROM game_player_card pc
                JOIN game_card_config c ON c.code = pc.card_code
                JOIN game_player p ON p.id = pc.player_id
                LEFT JOIN game_card_upgrade_config u ON u.level = pc.level
                WHERE pc.player_id = ? AND pc.card_code = ?
                """, playerId, cardCode);
        if (card == null) throw new IllegalArgumentException("卡牌不存在");
        if (num(card.get("level")) >= num(card.get("max_level"))) throw new IllegalArgumentException("卡牌已满级");
        int needCount = (int) num(card.get("need_count"));
        int needGold = (int) num(card.get("need_gold"));
        if (num(card.get("count")) < needCount) throw new IllegalArgumentException("卡牌数量不足");
        if (num(card.get("gold")) < needGold) throw new IllegalArgumentException("金币不足");
        jdbcTemplate.update("UPDATE game_player_card SET level = level + 1, count = count - ? WHERE player_id = ? AND card_code = ?",
                needCount, playerId, cardCode);
        jdbcTemplate.update("UPDATE game_player SET gold = gold - ?, exp = exp + ? WHERE id = ?",
                needGold, num(card.get("gain_exp")), playerId);
        return cards(authorization);
    }

    public Map<String, Object> journey(String authorization) {
        long playerId = currentPlayerId(authorization);
        Map<String, Object> player = queryOne("SELECT journey_unlocked, journey_max_stage FROM game_player WHERE id = ?", playerId);
        int unlocked = (int) num(player.get("journey_unlocked"));
        List<Map<String, Object>> stages = jdbcTemplate.queryForList("""
                SELECT stage_no AS stageNo, chapter_no AS chapterNo, name, reward_gold AS rewardGold
                FROM game_journey_stage ORDER BY stage_no ASC
                """);
        for (Map<String, Object> stage : stages) {
            int stageNo = (int) num(stage.get("stageNo"));
            stage.put("unlocked", stageNo <= unlocked);
            stage.put("stars", stageNo <= num(player.get("journey_max_stage")) ? 3 : 0);
        }
        return Map.of("player", playerSummary(playerId), "stages", stages);
    }

    @Transactional
    public Map<String, Object> startJourney(String authorization, Integer stageNo) {
        long playerId = currentPlayerId(authorization);
        Map<String, Object> player = queryOne("SELECT journey_unlocked FROM game_player WHERE id = ?", playerId);
        if (stageNo > num(player.get("journey_unlocked"))) throw new IllegalArgumentException("关卡未解锁");
        Map<String, Object> stage = queryOne("SELECT * FROM game_journey_stage WHERE stage_no = ?", stageNo);
        if (stage == null) throw new IllegalArgumentException("关卡不存在");
        String battleNo = "B" + System.currentTimeMillis() + random().substring(0, 6);
        long seed = System.currentTimeMillis();
        long battleId = insertAndReturnId(
                "INSERT INTO game_battle(battle_no,player_id,mode,stage_no,status,seed) VALUES (?,?,?,?,?,?)",
                battleNo, playerId, "journey", stageNo, "running", seed);
        Map<String, Object> state = initialBattleState(playerId, battleNo, stageNo, (int) num(stage.get("enemy_base_hp")), seed);
        saveSnapshot(battleId, state, List.of(), List.of(Map.of("text", "征途第" + stageNo + "关开始")));
        return battleResponse(battleNo, state, List.of("征途第" + stageNo + "关开始"));
    }

    public Map<String, Object> battle(String authorization, String battleNo) {
        currentPlayerId(authorization);
        BattleSnapshot snapshot = loadSnapshot(battleNo);
        return battleResponse(battleNo, snapshot.state, snapshot.eventTexts());
    }

    @Transactional
    public Map<String, Object> command(String authorization, String battleNo, Map<String, Object> req) {
        currentPlayerId(authorization);
        BattleSnapshot snapshot = loadSnapshot(battleNo);
        List<Map<String, Object>> events = new ArrayList<>(snapshot.events);
        applyCommand(snapshot.state, req, events);
        saveSnapshot(snapshot.battleId, snapshot.state, append(snapshot.commands, req), events);
        return battleResponse(battleNo, snapshot.state, eventTexts(events));
    }

    @Transactional
    public Map<String, Object> tick(String authorization, String battleNo) {
        currentPlayerId(authorization);
        BattleSnapshot snapshot = loadSnapshot(battleNo);
        List<Map<String, Object>> events = new ArrayList<>(snapshot.events);
        tickState(snapshot.state, events);
        saveSnapshot(snapshot.battleId, snapshot.state, snapshot.commands, events);
        return battleResponse(battleNo, snapshot.state, eventTexts(events));
    }

    @Transactional
    public Map<String, Object> settle(String authorization, String battleNo) {
        long playerId = currentPlayerId(authorization);
        BattleSnapshot snapshot = loadSnapshot(battleNo);
        String winner = str(snapshot.state.get("winner"));
        if (StrUtil.isBlank(winner)) {
            winner = num(snapshot.state.get("enemyBaseHp")) <= num(snapshot.state.get("playerBaseHp")) ? "player" : "enemy";
        }
        int stars = "player".equals(winner) ? 3 : 0;
        if ("player".equals(winner)) {
            jdbcTemplate.update("UPDATE game_player SET gold = gold + 50, journey_max_stage = GREATEST(journey_max_stage, ?), journey_unlocked = GREATEST(journey_unlocked, ?) WHERE id = ?",
                    num(snapshot.state.get("stageNo")), num(snapshot.state.get("stageNo")) + 1, playerId);
        }
        jdbcTemplate.update("UPDATE game_battle SET status = 'finished', winner = ?, star = ? WHERE id = ?", winner, stars, snapshot.battleId);
        snapshot.state.put("winner", winner);
        saveSnapshot(snapshot.battleId, snapshot.state, snapshot.commands, append(snapshot.events, Map.of("text", "player".equals(winner) ? "胜利结算" : "失败结算")));
        return Map.of("winner", winner, "stars", stars, "rewardGold", "player".equals(winner) ? 50 : 0, "player", playerSummary(playerId));
    }

    @Transactional
    public Map<String, Object> surrender(String authorization, String battleNo) {
        long playerId = currentPlayerId(authorization);
        BattleSnapshot snapshot = loadSnapshot(battleNo);
        snapshot.state.put("winner", "enemy");
        snapshot.state.put("gamePhase", "settled");
        jdbcTemplate.update("UPDATE game_battle SET status = 'finished', winner = 'enemy', star = 0 WHERE id = ?", snapshot.battleId);
        saveSnapshot(snapshot.battleId, snapshot.state, snapshot.commands, append(snapshot.events, Map.of("text", "我方投降，失败结算")));
        return Map.of("winner", "enemy", "stars", 0, "rewardGold", 0, "player", playerSummary(playerId));
    }

    private Map<String, Object> authResponse(long accountId, long playerId) {
        String token = random() + random();
        jdbcTemplate.update("INSERT INTO game_session(token, account_id, player_id, expire_time) VALUES (?,?,?,?)",
                token, accountId, playerId, LocalDateTime.now().plusDays(30));
        return Map.of("token", token, "player", playerSummary(playerId));
    }

    private Map<String, Object> playerSummary(long playerId) {
        return queryOne("""
                SELECT id, nickname, level, exp, gold, journey_unlocked AS journeyUnlocked,
                       journey_max_stage AS journeyMaxStage, ladder_score AS ladderScore
                FROM game_player WHERE id = ?
                """, playerId);
    }

    private long currentPlayerId(String authorization) {
        String token = StrUtil.removePrefix(StrUtil.blankToDefault(authorization, ""), "Bearer ").trim();
        if (StrUtil.isBlank(token)) throw new IllegalArgumentException("未登录");
        Map<String, Object> session = queryOne("SELECT * FROM game_session WHERE token = ? AND expire_time > NOW()", token);
        if (session == null) throw new IllegalArgumentException("登录已失效");
        return num(session.get("player_id"));
    }

    private Map<String, Object> initialBattleState(long playerId, String battleNo, int stageNo, int enemyBaseHp, long seed) {
        List<Map<String, Object>> configs = jdbcTemplate.queryForList("SELECT * FROM game_card_config");
        Map<String, Map<String, Object>> configMap = new LinkedHashMap<>();
        for (Map<String, Object> config : configs) configMap.put(str(config.get("code")), config);
        List<List<Map<String, Object>>> grid = new ArrayList<>();
        for (int row = 0; row < GRID_ROWS; row++) {
            List<Map<String, Object>> line = new ArrayList<>();
            for (int col = 0; col < GRID_COLS; col++) line.add(new LinkedHashMap<>(Map.of("col", col, "row", row)));
            grid.add(line);
        }
        int playerBaseHp = PLAYER_BASE_HP_LV1;
        // Player base at (0, 10)
        place(grid, entity("playerBase", "playerBase", "我方基地", "base", "player", "staffWorkshop", 1, playerBaseHp, 0), PLAYER_BASE_COL, PLAYER_BASE_ROW);
        // Walls at (4, 9), (4, 10), (4, 11) - upper, middle, lower lanes
        place(grid, entity("wallUpper", "playerWall", "上路城墙", "wall", "player", "shield", 1, WALL_MAX_HP, 0), WALL_COL, BATTLE_UPPER_ROW);
        place(grid, entity("wallMid", "playerWall", "中路城墙", "wall", "player", "shield", 1, WALL_MAX_HP, 0), WALL_COL, BATTLE_MIDDLE_ROW);
        place(grid, entity("wallLower", "playerWall", "下路城墙", "wall", "player", "shield", 1, WALL_MAX_HP, 0), WALL_COL, BATTLE_LOWER_ROW);
        // Enemy base at (8, 10)
        place(grid, entity("enemyBase", "enemyBase", "敌方基地", "base", "enemy", "hammerWorkshop", 1, enemyBaseHp, 0), ENEMY_BASE_COL, ENEMY_BASE_ROW);
        // Fixed resources at Y19.
        place(grid, resourceEntity(configMap, "ironMine"), 1, PLAYER_RESOURCE_ROW);
        place(grid, resourceEntity(configMap, "goldMine"), 4, PLAYER_RESOURCE_ROW);
        place(grid, resourceEntity(configMap, "forest"), 7, PLAYER_RESOURCE_ROW);

        List<Map<String, Object>> hand = new ArrayList<>();
        addStack(hand, "villager", 4, configMap);
        addStack(hand, "maternityRoom", 1, configMap);
        addStack(hand, "nursery", 1, configMap);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("battleNo", battleNo);
        state.put("stageNo", stageNo);
        state.put("stageConfig", stageConfig(stageNo));
        state.put("spawnedEnemySpawns", new ArrayList<Integer>());
        state.put("seed", seed);
        state.put("tick", 0);
        state.put("gamePhase", "main");
        state.put("visibleRows", VISIBLE_ROWS);
        state.put("fullRows", GRID_ROWS);
        state.put("resources", new LinkedHashMap<>(Map.of("gold", 1250, "food", 860, "ore", 3)));
        state.put("hand", hand);
        state.put("grid", grid);
        state.put("packPurchases", new LinkedHashMap<>(Map.of("building", 0, "resource", 0)));
        syncPackCosts(state);
        state.put("enemyBaseHp", enemyBaseHp);
        state.put("enemyBaseMaxHp", enemyBaseHp);
        state.put("playerBaseHp", playerBaseHp);
        state.put("playerBaseMaxHp", playerBaseHp);
        state.put("playerWallHp", WALL_MAX_HP * 3);
        state.put("wallDecayTimers", new int[]{WALL_DECAY_TICKS, WALL_DECAY_TICKS, WALL_DECAY_TICKS});
        state.put("winner", "");
        return state;
    }

    @SuppressWarnings("unchecked")
    private void applyCommand(Map<String, Object> state, Map<String, Object> command, List<Map<String, Object>> events) {
        String type = str(command.get("type"));
        if ("buy-pack".equals(type)) {
            buyPack(state, str(command.get("packType")), events);
        } else if ("place-card".equals(type)) {
            placeCard(state, command, events);
        } else if ("sell-card".equals(type)) {
            sellCard(state, str(command.get("cardId")), events);
        } else if ("sell-entity".equals(type)) {
            sellEntity(state, command, events);
        } else if ("split-card".equals(type)) {
            splitCard(state, str(command.get("cardId")), events);
        } else if ("move-entity".equals(type)) {
            moveEntity(state, command, events);
        } else if ("set-port".equals(type)) {
            setPort(state, command, events);
        }
    }

    @SuppressWarnings("unchecked")
    private void buyPack(Map<String, Object> state, String packType, List<Map<String, Object>> events) {
        if (!"building".equals(packType) && !"resource".equals(packType)) throw new IllegalArgumentException("卡包类型不存在");
        Map<String, Object> resources = (Map<String, Object>) state.get("resources");
        List<Map<String, Object>> hand = (List<Map<String, Object>>) state.get("hand");
        Map<String, Object> pack = packConfig(packType);
        int drawCount = (int) num(pack.get("draw_count"));
        int requiredEmptySlots = (int) num(pack.get("required_empty_slots"));
        int cost = packCost(state, packType, pack);
        if (num(resources.get("gold")) < cost) throw new IllegalArgumentException("金币不足");
        if (STORAGE_SLOT_COUNT - hand.size() < requiredEmptySlots) throw new IllegalArgumentException("存卡区空位不足");
        resources.put("gold", num(resources.get("gold")) - cost);
        Map<String, Map<String, Object>> configMap = configMap();
        List<String> gained = new ArrayList<>();
        Random rng = packRandom(state, packType);
        for (int i = 0; i < drawCount; i++) {
            String cardCode = drawFromPackPool(packType, rng);
            addStack(hand, cardCode, 1, configMap);
            gained.add(str(configMap.get(cardCode).get("name")));
        }
        incrementPackPurchase(state, packType);
        syncPackCosts(state);
        events.add(Map.of("text", pack.get("name") + "获得 " + String.join("、", gained)));
    }

    @SuppressWarnings("unchecked")
    private void placeCard(Map<String, Object> state, Map<String, Object> command, List<Map<String, Object>> events) {
        String cardId = str(command.get("cardId"));
        int col = (int) num(command.get("col"));
        int row = (int) num(command.get("row"));
        if (row < PLAYER_BENCH_ROW || row > PLAYER_RESOURCE_ROW || col < 0 || col >= GRID_COLS) throw new IllegalArgumentException("只能放在我方可操作区域");
        List<Map<String, Object>> hand = (List<Map<String, Object>>) state.get("hand");
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Object> cell = grid.get(row).get(col);
        Map<String, Object> stack = hand.stream().filter(item -> cardId.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Map<String, Object>> configMap = configMap();
        Map<String, Object> def = configMap.get(str(stack.get("code")));
        if (def == null) throw new IllegalArgumentException("卡牌配置不存在");
        String cardCode = str(stack.get("code"));
        String type = str(def.get("type"));
        int placingCount = (int) num(stack.get("count"));
        Map<String, Object> targetEntity = (Map<String, Object>) cell.get("entity");
        if (targetEntity != null) {
            if ("resource".equals(str(targetEntity.get("kind"))) && isCollector(cardCode)) {
                attachCollector(targetEntity, cardCode, placingCount);
            } else if (canStack(targetEntity, cardCode)) {
                long total = num(targetEntity.get("count")) + placingCount;
                int limit = stackLimit(configMap, cardCode);
                if (total > limit) throw new IllegalArgumentException("堆叠上限为 " + limit);
                targetEntity.put("count", total);
                targetEntity.put("hp", num(targetEntity.get("hp")) + num(def.get("hp")) * placingCount);
                targetEntity.put("maxHp", num(targetEntity.get("maxHp")) + num(def.get("hp")) * placingCount);
            } else {
                throw new IllegalArgumentException("目标格已占用");
            }
        } else {
            if (row == PLAYER_RESOURCE_ROW) throw new IllegalArgumentException("固定资源区只能放入采集单位到资源点上");
            if (row == PLAYER_BENCH_ROW && !"unit".equals(type)) throw new IllegalArgumentException("备战区只能放入单位");
            Map<String, Object> placed = entity(random(), cardCode, str(def.get("name")), type, "player", str(def.get("asset_key")), placingCount, (int) num(def.get("hp")) * placingCount, (int) num(def.get("attack")));
            initPorts(placed, col, row);
            cell.put("entity", placed);
        }
        hand.remove(stack);
        events.add(Map.of("text", def.get("name") + " 已放置"));
    }

    @SuppressWarnings("unchecked")
    private void sellCard(Map<String, Object> state, String cardId, List<Map<String, Object>> events) {
        List<Map<String, Object>> hand = (List<Map<String, Object>>) state.get("hand");
        Map<String, Object> stack = hand.stream().filter(item -> cardId.equals(item.get("id"))).findFirst().orElseThrow();
        int count = (int) num(stack.get("count")) - 1;
        if (count <= 0) hand.remove(stack); else stack.put("count", count);
        Map<String, Object> resources = (Map<String, Object>) state.get("resources");
        resources.put("gold", num(resources.get("gold")) + 1);
        events.add(Map.of("text", "出售卡牌 +1 金币"));
    }

    @SuppressWarnings("unchecked")
    private void sellEntity(Map<String, Object> state, Map<String, Object> command, List<Map<String, Object>> events) {
        int col = (int) num(command.get("col"));
        int row = (int) num(command.get("row"));
        if (!isOperableCell(col, row) && row != PLAYER_RESOURCE_ROW) throw new IllegalArgumentException("只能出售己方可操作区域卡牌");
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Object> cell = grid.get(row).get(col);
        Map<String, Object> entity = castEntity(cell.get("entity"));
        if (entity == null) throw new IllegalArgumentException("没有可出售卡牌");
        if (!"player".equals(str(entity.get("owner"))) && !"resource".equals(str(entity.get("kind")))) throw new IllegalArgumentException("只能出售己方卡牌");
        String kind = str(entity.get("kind"));
        if ("base".equals(kind) || "wall".equals(kind)) throw new IllegalArgumentException("基地和城墙不可出售");
        if ("unit".equals(kind) && row >= BATTLE_UPPER_ROW && row <= BATTLE_LOWER_ROW) throw new IllegalArgumentException("战斗区单位不可出售");
        if ("resource".equals(kind)) {
            if (Boolean.TRUE.equals(entity.get("fixed"))) throw new IllegalArgumentException("固定资源点不可出售");
            Map<String, Object> collectors = castEntity(entity.get("collectors"));
            if (collectors != null && num(collectors.get("villager")) + num(collectors.get("worker")) > 0) {
                throw new IllegalArgumentException("资源点上有采集单位，不能出售");
            }
        }
        Map<String, Object> def = configMap().get(str(entity.get("code")));
        long gainGold = Math.max(1, num(def == null ? null : def.get("sell_gold")) * Math.max(1, num(entity.get("count"))));
        Map<String, Object> resources = (Map<String, Object>) state.get("resources");
        resources.put("gold", num(resources.get("gold")) + gainGold);
        cell.remove("entity");
        events.add(Map.of("text", "出售" + entity.get("label") + " +" + gainGold + " 金币"));
    }

    @SuppressWarnings("unchecked")
    private void splitCard(Map<String, Object> state, String cardId, List<Map<String, Object>> events) {
        List<Map<String, Object>> hand = (List<Map<String, Object>>) state.get("hand");
        Map<String, Object> stack = hand.stream().filter(item -> cardId.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> def = configMap().get(str(stack.get("code")));
        if (def != null && "building".equals(str(def.get("type")))) throw new IllegalArgumentException("建筑不能拆分");
        if (num(stack.get("count")) < 2) throw new IllegalArgumentException("至少 2 张同卡才可拆分");
        if (hand.size() >= STORAGE_SLOT_COUNT) throw new IllegalArgumentException("存卡区空位不足");
        stack.put("count", num(stack.get("count")) - 1);
        addNewStack(hand, str(stack.get("code")), 1, configMap());
        events.add(Map.of("text", "卡堆已拆分"));
    }

    @SuppressWarnings("unchecked")
    private void moveEntity(Map<String, Object> state, Map<String, Object> command, List<Map<String, Object>> events) {
        int fromCol = (int) num(command.get("fromCol"));
        int fromRow = (int) num(command.get("fromRow"));
        int toCol = (int) num(command.get("toCol"));
        int toRow = (int) num(command.get("toRow"));
        if (!isOperableCell(fromCol, fromRow) || !isOperableCell(toCol, toRow)) throw new IllegalArgumentException("只能移动己方生产区或备战区卡牌");
        if (fromCol == toCol && fromRow == toRow) {
            events.add(Map.of("text", "卡牌位置未变化"));
            return;
        }
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Object> from = grid.get(fromRow).get(fromCol);
        Map<String, Object> to = grid.get(toRow).get(toCol);
        Object entity = from.get("entity");
        if (entity == null) throw new IllegalArgumentException("没有可移动卡牌");
        Map<String, Object> entityMap = (Map<String, Object>) entity;
        if (!"player".equals(str(entityMap.get("owner")))) throw new IllegalArgumentException("只能移动己方卡牌");
        if ("wall".equals(str(entityMap.get("kind"))) || "base".equals(str(entityMap.get("kind")))) throw new IllegalArgumentException("固定建筑不可移动");
        if ("resource".equals(str(entityMap.get("kind")))) throw new IllegalArgumentException("资源点放置后不可移动");
        Map<String, Object> targetEntity = (Map<String, Object>) to.get("entity");
        if (targetEntity != null) {
            if ("resource".equals(str(targetEntity.get("kind"))) && isCollector(str(entityMap.get("code")))) {
                attachCollector(targetEntity, str(entityMap.get("code")), (int) num(entityMap.get("count")));
                events.add(Map.of("text", entityMap.get("label") + " 开始采集 " + targetEntity.get("label")));
            } else if (canStack(targetEntity, str(entityMap.get("code")))) {
                long total = num(targetEntity.get("count")) + num(entityMap.get("count"));
                int limit = stackLimit(configMap(), str(entityMap.get("code")));
                if (total > limit) throw new IllegalArgumentException("堆叠上限为 " + limit);
                targetEntity.put("count", total);
                targetEntity.put("hp", num(targetEntity.get("hp")) + num(entityMap.get("hp")));
                targetEntity.put("maxHp", num(targetEntity.get("maxHp")) + num(entityMap.get("maxHp")));
                events.add(Map.of("text", "同类卡牌已堆叠"));
            } else {
                throw new IllegalArgumentException("目标格已占用");
            }
        } else {
            if (toRow == PLAYER_RESOURCE_ROW) throw new IllegalArgumentException("固定资源区只能移动采集单位到资源点上");
            if (toRow == PLAYER_BENCH_ROW && !"unit".equals(str(entityMap.get("kind")))) throw new IllegalArgumentException("备战区只能放入单位");
            to.put("entity", entity);
            events.add(Map.of("text", "卡牌已移动"));
        }
        from.remove("entity");
    }

    @SuppressWarnings("unchecked")
    private void setPort(Map<String, Object> state, Map<String, Object> command, List<Map<String, Object>> events) {
        int col = (int) num(command.get("col"));
        int row = (int) num(command.get("row"));
        int targetCol = (int) num(command.get("targetCol"));
        int targetRow = (int) num(command.get("targetRow"));
        int index = (int) num(command.get("index"));
        String portKind = str(command.get("portKind"));
        if (!isOperableCell(col, row) || !isOperableCell(targetCol, targetRow)) throw new IllegalArgumentException("口位只能设置在我方可操作区域");
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Object> entity = (Map<String, Object>) grid.get(row).get(col).get("entity");
        if (entity == null || !"building".equals(str(entity.get("kind"))) || !"player".equals(str(entity.get("owner")))) {
            throw new IllegalArgumentException("请选择己方建筑设置口位");
        }
        initPorts(entity, col, row);
        boolean belt = "belt".equals(str(entity.get("code")));
        int range = belt ? 3 : 1;
        if (Math.max(Math.abs(targetCol - col), Math.abs(targetRow - row)) > range || (targetCol == col && targetRow == row)) {
            throw new IllegalArgumentException(belt ? "传送带口位范围为 3 格" : "建筑口位必须在周围 1 格");
        }
        if ("output".equals(portKind) && targetRow >= BATTLE_UPPER_ROW && targetRow <= BATTLE_LOWER_ROW) throw new IllegalArgumentException("不能直接产出到战斗区");
        Map<String, Object> ports = (Map<String, Object>) entity.get("ports");
        List<Map<String, Object>> list = (List<Map<String, Object>>) ports.get("input".equals(portKind) ? "inputs" : "outputs");
        if (index < 0 || index >= list.size()) throw new IllegalArgumentException("口位编号不存在");
        Map<String, Object> port = list.get(index);
        port.put("col", targetCol);
        port.put("row", targetRow);
        events.add(Map.of("text", entity.get("label") + ("input".equals(portKind) ? " 投料口" : " 产出口") + (index + 1) + " 已设置"));
    }

    @SuppressWarnings("unchecked")
    private void tickState(Map<String, Object> state, List<Map<String, Object>> events) {
        int tick = (int) num(state.get("tick")) + 1;
        state.put("tick", tick);
        Map<String, Object> resources = (Map<String, Object>) state.get("resources");
        // Phase transition
        String gamePhase = str(state.get("gamePhase"));
        if ("main".equals(gamePhase) && tick >= MAIN_PHASE_DURATION) {
            state.put("gamePhase", "final");
            events.add(Map.of("text", "进入最终阶段，禁止补充备战区"));
        }
        if ("final".equals(gamePhase) && tick >= TOTAL_TICKS) {
            state.put("gamePhase", "settled");
            if (StrUtil.isBlank(str(state.get("winner")))) {
                long playerHp = num(state.get("playerBaseHp"));
                long enemyHp = num(state.get("enemyBaseHp"));
                String winner = enemyHp <= 0 ? "player" : playerHp <= 0 ? "enemy" : playerHp > enemyHp ? "player" : enemyHp > playerHp ? "enemy" : "";
                state.put("winner", winner);
                events.add(Map.of("text", "player".equals(winner) ? "时间到，我方基地血量更高，胜利！" : "enemy".equals(winner) ? "时间到，敌方基地血量更高，失败..." : "平局！"));
            }
            return;
        }
        if ("settled".equals(gamePhase)) return;
        gatherResources(state, resources, tick, events);
        runBuildings(state, resources, tick, events);
        runBelts(state, tick, events);
        decayWalls(state, tick, events);
        runEnemySpawns(state, tick, events);
        autoFillBattleArea(state, tick, events);
        runCombat(state, tick, events);
    }

    @SuppressWarnings("unchecked")
    private void gatherResources(Map<String, Object> state, Map<String, Object> resources, int tick, List<Map<String, Object>> events) {
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        long gold = 0;
        long food = 0;
        long ore = 0;
        for (List<Map<String, Object>> row : grid) {
            for (Map<String, Object> cell : row) {
                Map<String, Object> entity = (Map<String, Object>) cell.get("entity");
                if (entity == null || !"resource".equals(str(entity.get("kind")))) continue;
                Map<String, Object> collectors = (Map<String, Object>) entity.get("collectors");
                if (collectors == null) continue;
                long villager = num(collectors.get("villager"));
                long worker = num(collectors.get("worker"));
                String code = str(entity.get("code"));
                long lastGatherTick = num(entity.get("lastGatherTick"));
                int interval = gatherIntervalTicks(code, worker > 0 ? "worker" : "villager");
                if (interval <= 0 || tick - lastGatherTick < interval) continue;
                long amount = gatherAmount(code, villager, worker, num(entity.get("level")));
                if (amount <= 0) continue;
                entity.put("lastGatherTick", tick);
                if ("goldMine".equals(code)) gold += amount;
                if ("forest".equals(code)) food += amount;
                if ("ironMine".equals(code)) ore += amount;
            }
        }
        if (gold + food + ore <= 0) return;
        resources.put("gold", num(resources.get("gold")) + gold);
        resources.put("food", num(resources.get("food")) + food);
        resources.put("ore", num(resources.get("ore")) + ore);
        events.add(Map.of("text", "采集 +" + gold + " 金币 +" + food + " 粮食 +" + ore + " 矿石"));
    }

    private int gatherIntervalTicks(String resourceCode, String collectorCode) {
        if ("ironMine".equals(resourceCode)) return "worker".equals(collectorCode) ? 40 : 60;
        if ("goldMine".equals(resourceCode) || "forest".equals(resourceCode)) return 50;
        return 0;
    }

    private long gatherAmount(String resourceCode, long villager, long worker, long level) {
        long base;
        if ("ironMine".equals(resourceCode)) {
            base = villager + worker;
        } else {
            base = villager * 5 + worker * 8;
        }
        double levelMultiplier = 1D + Math.max(0, level - 1) * 0.1D;
        return Math.max(1, (long) Math.floor(base * levelMultiplier));
    }

    @SuppressWarnings("unchecked")
    private void runBuildings(Map<String, Object> state, Map<String, Object> resources, int tick, List<Map<String, Object>> events) {
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Map<String, Object>> configMap = configMap();
        for (List<Map<String, Object>> row : grid) {
            for (Map<String, Object> cell : row) {
                Map<String, Object> entity = (Map<String, Object>) cell.get("entity");
                if (entity == null || !"building".equals(str(entity.get("kind"))) || !"player".equals(str(entity.get("owner")))) continue;
                String code = str(entity.get("code"));
                if ("belt".equals(code)) continue;
                initPorts(entity, (int) num(cell.get("col")), (int) num(cell.get("row")));
                int interval = buildingIntervalTicks(entity, code);
                if (interval <= 0) continue;
                entity.put("progress", Math.min(10, num(entity.get("progress")) + Math.max(1, 100 / interval)));
                if (tick % interval != 0) {
                    entity.put("status", "生产中");
                    continue;
                }
                if (produceByBuilding(grid, entity, code, resources, configMap, events)) {
                    entity.put("progress", 0);
                    entity.put("status", "生产中");
                } else {
                    entity.put("status", "缺少投料");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean produceByBuilding(List<List<Map<String, Object>>> grid, Map<String, Object> building, String code, Map<String, Object> resources,
                                      Map<String, Map<String, Object>> configMap, List<Map<String, Object>> events) {
        Map<String, Object> ports = (Map<String, Object>) building.get("ports");
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) ports.get("inputs");
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) ports.get("outputs");
        if ("maternityRoom".equals(code)) {
            if (countAt(grid, inputs.get(0), "villager") < 2 || num(resources.get("food")) < 1 || !canOutput(grid, outputs.get(0), "baby")) return false;
            resources.put("food", num(resources.get("food")) - 1);
            outputTo(grid, outputs.get(0), "baby", configMap);
            events.add(Map.of("text", "产房按投料口村民生产婴儿"));
            return true;
        }
        if ("nursery".equals(code)) {
            if (num(resources.get("food")) < 2 || !canOutput(grid, outputs.get(0), "villager")) return false;
            if (!consumeAt(grid, inputs.get(0), "baby", 1)) return false;
            resources.put("food", num(resources.get("food")) - 2);
            outputTo(grid, outputs.get(0), "villager", configMap);
            events.add(Map.of("text", "育婴室消耗婴儿和粮食，产出村民"));
            return true;
        }
        String equipment = workshopOutput(code);
        if (equipment != null) {
            if (!canOutput(grid, outputs.get(0), equipment)) return false;
            if (!consumeAt(grid, inputs.get(0), "ore", 1)) {
                if (num(resources.get("ore")) < 1) return false;
                resources.put("ore", num(resources.get("ore")) - 1);
            }
            outputTo(grid, outputs.get(0), equipment, configMap);
            events.add(Map.of("text", "工坊消耗矿石，产出" + configMap.get(equipment).get("name")));
            return true;
        }
        if ("trainingCamp".equals(code)) {
            for (int inputIndex = 1; inputIndex <= 3 && inputIndex < inputs.size(); inputIndex++) {
                Map<String, Object> equipmentEntity = entityAt(grid, inputs.get(inputIndex));
                if (equipmentEntity == null) continue;
                String equipmentCode = str(equipmentEntity.get("code"));
                String unitCode = trainingOutput(equipmentCode);
                int outputIndex = Math.min(inputIndex - 1, outputs.size() - 1);
                if (configMap.containsKey(unitCode) && canOutput(grid, outputs.get(outputIndex), unitCode) && consumeAt(grid, inputs.get(0), "villager", 1) && consumeAt(grid, inputs.get(inputIndex), equipmentCode, 1)) {
                    outputTo(grid, outputs.get(outputIndex), unitCode, configMap);
                    events.add(Map.of("text", "训练营消耗村民和" + configMap.get(equipmentCode).get("name") + "，产出" + configMap.get(unitCode).get("name")));
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void runBelts(Map<String, Object> state, int tick, List<Map<String, Object>> events) {
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Map<String, Object>> configMap = configMap();
        for (List<Map<String, Object>> row : grid) {
            for (Map<String, Object> cell : row) {
                Map<String, Object> belt = (Map<String, Object>) cell.get("entity");
                if (belt == null || !"belt".equals(str(belt.get("code"))) || !"player".equals(str(belt.get("owner")))) continue;
                int interval = Math.max(1, (int) Math.ceil(20D / Math.max(1, num(belt.get("count")))));
                if (tick % interval != 0) continue;
                initPorts(belt, (int) num(cell.get("col")), (int) num(cell.get("row")));
                Map<String, Object> ports = (Map<String, Object>) belt.get("ports");
                List<Map<String, Object>> inputs = (List<Map<String, Object>>) ports.get("inputs");
                List<Map<String, Object>> outputs = (List<Map<String, Object>>) ports.get("outputs");
                for (int i = 0; i < inputs.size(); i++) {
                    Map<String, Object> source = entityAt(grid, inputs.get(i));
                    if (source == null || !canBeltMove(source)) continue;
                    String code = str(source.get("code"));
                    Map<String, Object> output = outputs.get(i);
                    if ((int) num(output.get("row")) == PLAYER_BENCH_ROW && !"unit".equals(str(source.get("kind")))) continue;
                    if (!canOutput(grid, output, code)) continue;
                    consumeAt(grid, inputs.get(i), code, 1);
                    outputTo(grid, output, code, configMap);
                    events.add(Map.of("text", "传送带搬运 " + source.get("label")));
                    break;
                }
            }
        }
    }

    // ── Wall decay ──
    @SuppressWarnings("unchecked")
    private void decayWalls(Map<String, Object> state, int tick, List<Map<String, Object>> events) {
        List<Long> timers = wallDecayTimers(state.get("wallDecayTimers"));
        if (timers.isEmpty()) return;
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        String[] laneNames = {"上路", "中路", "下路"};
        for (int i = 0; i < BATTLE_ROWS.length; i++) {
            int row = BATTLE_ROWS[i];
            Map<String, Object> wall = (Map<String, Object>) grid.get(row).get(WALL_COL).get("entity");
            if (wall == null || num(wall.get("hp")) <= 0) continue;
            long remain = timers.get(i) - 1;
            timers.set(i, remain);
            if (remain <= 0) {
                wall.put("hp", 0);
                grid.get(row).get(WALL_COL).remove("entity");
                state.put("playerWallHp", Math.max(0, num(state.get("playerWallHp")) - WALL_MAX_HP));
                events.add(Map.of("text", laneNames[i] + "城墙自然消失"));
            }
        }
        state.put("wallDecayTimers", timers);
    }

    private List<Long> wallDecayTimers(Object value) {
        List<Long> timers = new ArrayList<>();
        if (value instanceof int[] array) {
            for (int item : array) timers.add((long) item);
        } else if (value instanceof List<?> list) {
            for (Object item : list) timers.add(num(item));
        }
        while (timers.size() < BATTLE_ROWS.length) timers.add((long) WALL_DECAY_TICKS);
        return timers;
    }

    @SuppressWarnings("unchecked")
    private void runEnemySpawns(Map<String, Object> state, int tick, List<Map<String, Object>> events) {
        Map<String, Object> stageConfig = castEntity(state.get("stageConfig"));
        if (stageConfig == null) return;
        Object rawSpawns = stageConfig.get("enemySpawns");
        if (!(rawSpawns instanceof List<?> spawns) || spawns.isEmpty()) return;
        List<Object> spawned = (List<Object>) state.computeIfAbsent("spawnedEnemySpawns", key -> new ArrayList<>());
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Map<String, Object>> configMap = configMap();
        for (int i = 0; i < spawns.size(); i++) {
            if (spawned.contains(i)) continue;
            if (!(spawns.get(i) instanceof Map<?, ?> rawSpawn)) continue;
            Map<String, Object> spawn = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawSpawn.entrySet()) spawn.put(str(entry.getKey()), entry.getValue());
            int triggerTick = (int) num(spawn.get("time")) * 10;
            if (tick < triggerTick) continue;
            String cardCode = str(spawn.get("cardCode"));
            Map<String, Object> def = configMap.get(cardCode);
            if (def == null || !"unit".equals(str(def.get("type")))) {
                spawned.add(i);
                events.add(Map.of("text", "敌方刷兵配置无效：" + cardCode));
                continue;
            }
            int laneIndex = Math.max(0, Math.min(BATTLE_ROWS.length - 1, (int) num(spawn.get("lane"))));
            int laneRow = BATTLE_ROWS[laneIndex];
            int range = def.get("range_value") == null ? 1 : (int) num(def.get("range_value"));
            int targetCol = findBattlePosition(grid, laneRow, range, "enemy", cardCode);
            if (targetCol < 0) continue;
            Map<String, Object> unit = unitEntity(configMap, cardCode, "enemy");
            Map<String, Object> target = castEntity(grid.get(laneRow).get(targetCol).get("entity"));
            if (target != null && canStack(target, cardCode)) {
                target.put("count", num(target.get("count")) + 1);
                target.put("hp", num(target.get("hp")) + num(unit.get("hp")));
                target.put("maxHp", num(target.get("maxHp")) + num(unit.get("maxHp")));
            } else {
                grid.get(laneRow).get(targetCol).put("entity", unit);
            }
            spawned.add(i);
            events.add(Map.of("text", "敌方" + def.get("name") + "进入战场"));
        }
    }

    // ── Auto-fill battle area from bench ──
    @SuppressWarnings("unchecked")
    private void autoFillBattleArea(Map<String, Object> state, int tick, List<Map<String, Object>> events) {
        if (!"main".equals(str(state.get("gamePhase")))) return;
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        List<Map<String, Object>> benchRow = grid.get(PLAYER_BENCH_ROW);
        Map<String, Map<String, Object>> configMap = configMap();
        for (int laneIdx = 0; laneIdx < BATTLE_ROWS.length; laneIdx++) {
            int laneRow = BATTLE_ROWS[laneIdx];
            for (int benchCol = 0; benchCol < GRID_COLS; benchCol++) {
                Map<String, Object> unitEntity = (Map<String, Object>) benchRow.get(benchCol).get("entity");
                if (unitEntity == null || !"player".equals(str(unitEntity.get("owner"))) || !"unit".equals(str(unitEntity.get("kind")))) continue;
                String code = str(unitEntity.get("code"));
                Map<String, Object> def = configMap.get(code);
                int range = def != null && def.get("range_value") != null ? (int) num(def.get("range_value")) : 1;
                int targetCol = findBattlePosition(grid, laneRow, range, "player", code);
                if (targetCol < 0) continue;
                Map<String, Object> targetEntity = castEntity(grid.get(laneRow).get(targetCol).get("entity"));
                if (targetEntity != null && canStack(targetEntity, code)) {
                    targetEntity.put("count", num(targetEntity.get("count")) + num(unitEntity.get("count")));
                    targetEntity.put("hp", num(targetEntity.get("hp")) + num(unitEntity.get("hp")));
                    targetEntity.put("maxHp", num(targetEntity.get("maxHp")) + num(unitEntity.get("maxHp")));
                } else {
                    grid.get(laneRow).get(targetCol).put("entity", unitEntity);
                }
                benchRow.get(benchCol).remove("entity");
                events.add(Map.of("text", unitEntity.get("label") + " 进入战斗区"));
                break; // one per lane per tick
            }
        }
    }

    private int findBattlePosition(List<List<Map<String, Object>>> grid, int laneRow, int range, String side, String code) {
        for (int col : battlePositionOrder(range, side)) {
            Map<String, Object> target = castEntity(grid.get(laneRow).get(col).get("entity"));
            if (target == null || canStack(target, code)) return col;
        }
        return -1;
    }

    private int[] battlePositionOrder(int range, String side) {
        int[] cols = "player".equals(side) ? PLAYER_BATTLE_COLS : ENEMY_BATTLE_COLS;
        if (range <= 1) return new int[]{cols[2], cols[1], cols[0]};
        if (range == 2) return new int[]{cols[1], cols[0], cols[2]};
        return new int[]{cols[0], cols[1], cols[2]};
    }

    // ── Combat engine ──
    @SuppressWarnings("unchecked")
    private void runCombat(Map<String, Object> state, int tick, List<Map<String, Object>> events) {
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Map<String, Object>> configMap = configMap();
        List<Map<String, Object>> damageQueue = new ArrayList<>();

        for (int laneIdx = 0; laneIdx < BATTLE_ROWS.length; laneIdx++) {
            int laneRow = BATTLE_ROWS[laneIdx];
            // Player units attack
            for (int col : PLAYER_BATTLE_COLS) {
                Map<String, Object> attacker = (Map<String, Object>) grid.get(laneRow).get(col).get("entity");
                if (attacker == null || !"player".equals(str(attacker.get("owner"))) || !"unit".equals(str(attacker.get("kind")))) continue;
                if (!canAttack(attacker, tick, configMap)) continue;
                Map<String, Object> target = findTarget(grid, laneRow, "enemy");
                if (target != null) {
                    int damage = (int) num(attacker.get("attack")) * (int) num(attacker.get("count"));
                    int targetCol = (int) num(target.get("_col"));
                    damageQueue.add(Map.of("row", laneRow, "col", targetCol, "damage", damage));
                    attacker.put("lastAttackTick", tick);
                    // Mage splash
                    if ("mage".equals(str(attacker.get("code")))) {
                        applyMageSplash(damageQueue, grid, laneRow, targetCol, damage);
                    }
                } else if (isLaneBroken(grid, laneRow)) {
                    damageQueue.add(Map.of("row", ENEMY_BASE_ROW, "col", ENEMY_BASE_COL, "damage",
                            (int) num(attacker.get("attack")) * (int) num(attacker.get("count"))));
                    attacker.put("lastAttackTick", tick);
                }
            }
            // Enemy units attack
            for (int col : ENEMY_BATTLE_COLS) {
                Map<String, Object> attacker = (Map<String, Object>) grid.get(laneRow).get(col).get("entity");
                if (attacker == null || !"enemy".equals(str(attacker.get("owner"))) || !"unit".equals(str(attacker.get("kind")))) continue;
                if (!canAttack(attacker, tick, configMap)) continue;
                Map<String, Object> target = findTarget(grid, laneRow, "player");
                if (target != null) {
                    int damage = (int) num(attacker.get("attack")) * (int) num(attacker.get("count"));
                    int targetCol = (int) num(target.get("_col"));
                    damageQueue.add(Map.of("row", laneRow, "col", targetCol, "damage", damage));
                    attacker.put("lastAttackTick", tick);
                } else if (isLaneBrokenEnemy(grid, laneRow)) {
                    damageQueue.add(Map.of("row", PLAYER_BASE_ROW, "col", PLAYER_BASE_COL, "damage",
                            (int) num(attacker.get("attack")) * (int) num(attacker.get("count"))));
                    attacker.put("lastAttackTick", tick);
                }
            }
        }

        // Simultaneous damage resolution
        for (Map<String, Object> dmg : damageQueue) {
            int row = (int) dmg.get("row");
            int col = (int) dmg.get("col");
            int damage = (int) dmg.get("damage");
            Map<String, Object> target = (Map<String, Object>) grid.get(row).get(col).get("entity");
            if (target == null) continue;
            long newHp = Math.max(0, num(target.get("hp")) - damage);
            target.put("hp", newHp);
            if (newHp <= 0) {
                grid.get(row).get(col).remove("entity");
                events.add(Map.of("text", target.get("label") + " 被击杀"));
            }
        }

        // Sync HP after combat
        long playerBaseHp = num(state.get("playerBaseHp"));
        long enemyBaseHp = num(state.get("enemyBaseHp"));
        for (Map<String, Object> dmg : damageQueue) {
            int row = (int) dmg.get("row");
            int col = (int) dmg.get("col");
            int damage = (int) dmg.get("damage");
            if (row == PLAYER_BASE_ROW && col == PLAYER_BASE_COL) playerBaseHp = Math.max(0, playerBaseHp - damage);
            if (row == ENEMY_BASE_ROW && col == ENEMY_BASE_COL) enemyBaseHp = Math.max(0, enemyBaseHp - damage);
        }
        state.put("playerBaseHp", playerBaseHp);
        state.put("enemyBaseHp", enemyBaseHp);

        // Check win/loss
        if (StrUtil.isBlank(str(state.get("winner")))) {
            if (enemyBaseHp <= 0) {
                state.put("winner", "player");
                events.add(Map.of("text", "胜利！敌方基地被摧毁"));
            } else if (playerBaseHp <= 0) {
                state.put("winner", "enemy");
                events.add(Map.of("text", "失败，我方基地被摧毁"));
            }
        }
    }

    private boolean canAttack(Map<String, Object> unit, int tick, Map<String, Map<String, Object>> configMap) {
        String code = str(unit.get("code"));
        int interval = "mage".equals(code) ? MAGE_ATTACK_INTERVAL : ATTACK_BEAT_INTERVAL;
        long lastAttack = unit.containsKey("lastAttackTick") ? num(unit.get("lastAttackTick")) : 0;
        return (tick - lastAttack) >= interval;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findTarget(List<List<Map<String, Object>>> grid, int laneRow, String targetOwner) {
        int[] cols = "player".equals(targetOwner) ? PLAYER_BATTLE_COLS : ENEMY_BATTLE_COLS;
        // Scan front-to-back (front blocks back)
        for (int col : cols) {
            Map<String, Object> entity = (Map<String, Object>) grid.get(laneRow).get(col).get("entity");
            if (entity != null && targetOwner.equals(str(entity.get("owner"))) && "unit".equals(str(entity.get("kind"))) && num(entity.get("hp")) > 0) {
                entity.put("_col", col); // tag for damage resolution
                return entity;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private boolean isLaneBroken(List<List<Map<String, Object>>> grid, int laneRow) {
        for (int col : ENEMY_BATTLE_COLS) {
            Map<String, Object> entity = (Map<String, Object>) grid.get(laneRow).get(col).get("entity");
            if (entity != null && "enemy".equals(str(entity.get("owner"))) && "unit".equals(str(entity.get("kind"))) && num(entity.get("hp")) > 0) return false;
        }
        Map<String, Object> wall = (Map<String, Object>) grid.get(laneRow).get(WALL_COL).get("entity");
        return wall == null || num(wall.get("hp")) <= 0;
    }

    @SuppressWarnings("unchecked")
    private boolean isLaneBrokenEnemy(List<List<Map<String, Object>>> grid, int laneRow) {
        for (int col : PLAYER_BATTLE_COLS) {
            Map<String, Object> entity = (Map<String, Object>) grid.get(laneRow).get(col).get("entity");
            if (entity != null && "player".equals(str(entity.get("owner"))) && "unit".equals(str(entity.get("kind"))) && num(entity.get("hp")) > 0) return false;
        }
        Map<String, Object> wall = (Map<String, Object>) grid.get(laneRow).get(WALL_COL).get("entity");
        return wall == null || num(wall.get("hp")) <= 0;
    }

    private void applyMageSplash(List<Map<String, Object>> damageQueue, List<List<Map<String, Object>>> grid, int laneRow, int targetCol, int baseDamage) {
        int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}; // up, down, left, right
        for (int[] offset : offsets) {
            int r = laneRow + offset[0];
            int c = targetCol + offset[1];
            if (r < 0 || r >= GRID_ROWS || c < 0 || c >= GRID_COLS) continue;
            Map<String, Object> entity = (Map<String, Object>) grid.get(r).get(c).get("entity");
            if (entity != null && "enemy".equals(str(entity.get("owner")))) {
                damageQueue.add(Map.of("row", r, "col", c, "damage", baseDamage / 2));
            }
        }
    }

    private int buildingIntervalTicks(Map<String, Object> building, String code) {
        int baseSeconds = switch (code) {
            case "toolWorkshop", "swordWorkshop", "bowWorkshop", "spearWorkshop" -> 8;
            case "staffWorkshop", "shieldWorkshop", "maternityRoom" -> 10;
            case "nursery", "trainingCamp" -> 8;
            default -> 0;
        };
        if (baseSeconds <= 0) return 0;
        double levelMultiplier = 1D - Math.max(0, num(building.get("level")) - 1) * 0.05D;
        long stackCount = Math.max(1, num(building.get("count")));
        return Math.max(1, (int) Math.ceil(baseSeconds * 10D * Math.max(0.2D, levelMultiplier) / stackCount));
    }

    private String workshopOutput(String code) {
        return switch (code) {
            case "toolWorkshop" -> "pickaxe";
            case "swordWorkshop" -> "sword";
            case "bowWorkshop" -> "bow";
            case "staffWorkshop" -> "staff";
            case "shieldWorkshop" -> "shield";
            case "spearWorkshop" -> "spear";
            default -> null;
        };
    }

    private String trainingOutput(String equipmentCode) {
        return switch (equipmentCode) {
            case "pickaxe" -> "worker";
            case "sword" -> "swordsman";
            case "bow" -> "archer";
            case "staff" -> "mage";
            case "shield" -> "shieldman";
            case "spear" -> "spearman";
            default -> "swordsman";
        };
    }


    private void saveSnapshot(long battleId, Map<String, Object> state, List<Map<String, Object>> commands, List<Map<String, Object>> events) {
        String stateJson = json(state);
        String commandJson = json(commands);
        String eventJson = json(events);
        jdbcTemplate.update("""
                INSERT INTO game_battle_snapshot(battle_id,state_json,command_log,event_log) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE state_json=VALUES(state_json), command_log=VALUES(command_log), event_log=VALUES(event_log), update_time=NOW()
                """, battleId, stateJson, commandJson, eventJson);
    }

    private BattleSnapshot loadSnapshot(String battleNo) {
        Map<String, Object> battle = queryOne("SELECT id FROM game_battle WHERE battle_no = ?", battleNo);
        if (battle == null) throw new IllegalArgumentException("对局不存在");
        Map<String, Object> row = queryOne("SELECT * FROM game_battle_snapshot WHERE battle_id = ?", battle.get("id"));
        if (row == null) throw new IllegalArgumentException("对局快照不存在");
        return new BattleSnapshot(num(battle.get("id")), readMap(str(row.get("state_json"))), readList(str(row.get("command_log"))), readList(str(row.get("event_log"))));
    }

    private Map<String, Object> battleResponse(String battleNo, Map<String, Object> state, List<String> events) {
        syncPackCosts(state);
        return Map.of("battleNo", battleNo, "state", state, "events", events);
    }

    private void place(List<List<Map<String, Object>>> grid, Map<String, Object> entity, int col, int row) {
        grid.get(row).get(col).put("entity", entity);
    }

    private boolean isOperableCell(int col, int row) {
        return col >= 0 && col < GRID_COLS && row >= PLAYER_BENCH_ROW && row <= PLAYER_RESOURCE_ROW;
    }

    @SuppressWarnings("unchecked")
    private void attachCollector(Map<String, Object> resource, String collectorCode, int count) {
        Map<String, Object> collectors = (Map<String, Object>) resource.computeIfAbsent("collectors", key -> new LinkedHashMap<String, Object>());
        String existing = num(collectors.get("villager")) > 0 ? "villager" : num(collectors.get("worker")) > 0 ? "worker" : "";
        if (StrUtil.isNotBlank(existing) && !existing.equals(collectorCode)) throw new IllegalArgumentException("同一资源点不能混放村民和工人");
        long total = num(collectors.get("villager")) + num(collectors.get("worker")) + count;
        if (total > 9) throw new IllegalArgumentException("同一资源点最多 9 个采集单位");
        collectors.put(collectorCode, num(collectors.get(collectorCode)) + count);
    }

    private boolean canStack(Map<String, Object> entity, String code) {
        if (!code.equals(str(entity.get("code")))) return false;
        String kind = str(entity.get("kind"));
        return ("unit".equals(kind) || "building".equals(kind) || "material".equals(kind) || "equipment".equals(kind) || "resource".equals(kind))
                && num(entity.get("count")) < stackLimit(configMap(), code);
    }

    private boolean isCollector(String code) {
        return "villager".equals(code) || "worker".equals(code);
    }

    private Map<String, Object> unitEntity(Map<String, Map<String, Object>> configMap, String code, String owner) {
        Map<String, Object> def = configMap.get(code);
        return entity(random(), code, str(def.get("name")), "unit", owner, str(def.get("asset_key")), 1, (int) num(def.get("hp")), (int) num(def.get("attack")));
    }

    private Map<String, Object> resourceEntity(Map<String, Map<String, Object>> configMap, String code) {
        Map<String, Object> def = configMap.get(code);
        Map<String, Object> entity = entity(random(), code, str(def.get("name")), "resource", "neutral", str(def.get("asset_key")), 1, (int) num(def.get("hp")), 0);
        entity.put("fixed", true);
        return entity;
    }

    private Map<String, Object> entity(String id, String code, String label, String kind, String owner, String asset, int count, int hp, int attack) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("code", code);
        map.put("label", label);
        map.put("kind", kind);
        map.put("owner", owner);
        map.put("asset", asset);
        map.put("count", count);
        map.put("hp", hp);
        map.put("maxHp", hp);
        map.put("attack", attack);
        map.put("progress", 0);
        map.put("status", "");
        map.put("level", 1);
        return map;
    }

    private void initPorts(Map<String, Object> entity, int col, int row) {
        if (entity.containsKey("ports") || !"building".equals(str(entity.get("kind")))) return;
        String code = str(entity.get("code"));
        int inputCount = "belt".equals(code) ? 5 : "trainingCamp".equals(code) ? 4 : 1;
        int outputCount = "belt".equals(code) ? 5 : "trainingCamp".equals(code) ? 3 : 1;
        List<Map<String, Object>> inputs = new ArrayList<>();
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (int i = 0; i < inputCount; i++) {
            inputs.add(port(Math.max(0, col - 1), Math.max(4, row - Math.min(i, 1)), inputName(code, i)));
        }
        for (int i = 0; i < outputCount; i++) {
            outputs.add(port(Math.min(GRID_COLS - 1, col + 1), Math.min(11, row + Math.min(i, 1)), outputName(code, i)));
        }
        Map<String, Object> ports = new LinkedHashMap<>();
        ports.put("inputs", inputs);
        ports.put("outputs", outputs);
        entity.put("ports", ports);
    }

    private Map<String, Object> port(int col, int row, String accepts) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("col", col);
        port.put("row", row);
        port.put("accepts", accepts);
        return port;
    }

    private String inputName(String code, int index) {
        if ("maternityRoom".equals(code)) return "villager";
        if ("nursery".equals(code)) return "baby";
        if ("trainingCamp".equals(code)) return index == 0 ? "villager" : "equipment";
        if ("belt".equals(code)) return "movable";
        return "ore";
    }

    private String outputName(String code, int index) {
        if ("maternityRoom".equals(code)) return "baby";
        if ("nursery".equals(code)) return "villager";
        if ("trainingCamp".equals(code)) return "unit";
        if ("belt".equals(code)) return "movable";
        return workshopOutput(code);
    }

    private Map<String, Object> entityAt(List<List<Map<String, Object>>> grid, Map<String, Object> port) {
        int col = (int) num(port.get("col"));
        int row = (int) num(port.get("row"));
        if (row < 0 || row >= grid.size() || col < 0 || col >= GRID_COLS) return null;
        return castEntity(grid.get(row).get(col).get("entity"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castEntity(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private long countAt(List<List<Map<String, Object>>> grid, Map<String, Object> port, String code) {
        Map<String, Object> entity = entityAt(grid, port);
        if (entity == null || !code.equals(str(entity.get("code")))) return 0;
        return num(entity.get("count"));
    }

    private boolean consumeAt(List<List<Map<String, Object>>> grid, Map<String, Object> port, String code, int count) {
        int col = (int) num(port.get("col"));
        int row = (int) num(port.get("row"));
        if (row < 0 || row >= grid.size() || col < 0 || col >= GRID_COLS) return false;
        Map<String, Object> cell = grid.get(row).get(col);
        Map<String, Object> entity = castEntity(cell.get("entity"));
        if (entity == null || !code.equals(str(entity.get("code"))) || num(entity.get("count")) < count) return false;
        long remain = num(entity.get("count")) - count;
        if (remain <= 0) cell.remove("entity"); else entity.put("count", remain);
        return true;
    }

    private boolean canOutput(List<List<Map<String, Object>>> grid, Map<String, Object> port, String code) {
        if (StrUtil.isBlank(code)) return false;
        int col = (int) num(port.get("col"));
        int row = (int) num(port.get("row"));
        if (!isOperableCell(col, row)) return false;
        if (row == PLAYER_RESOURCE_ROW) return false;
        Map<String, Object> target = castEntity(grid.get(row).get(col).get("entity"));
        return target == null || canStack(target, code);
    }

    private void outputTo(List<List<Map<String, Object>>> grid, Map<String, Object> port, String code, Map<String, Map<String, Object>> configMap) {
        int col = (int) num(port.get("col"));
        int row = (int) num(port.get("row"));
        Map<String, Object> cell = grid.get(row).get(col);
        Map<String, Object> target = castEntity(cell.get("entity"));
        Map<String, Object> def = configMap.get(code);
        if (target != null && canStack(target, code)) {
            if (num(target.get("count")) + 1 > stackLimit(configMap, code)) return;
            target.put("count", num(target.get("count")) + 1);
            target.put("hp", num(target.get("hp")) + num(def.get("hp")));
            target.put("maxHp", num(target.get("maxHp")) + num(def.get("hp")));
            return;
        }
        cell.put("entity", entity(random(), code, str(def.get("name")), str(def.get("type")), "player", str(def.get("asset_key")), 1, (int) num(def.get("hp")), (int) num(def.get("attack"))));
    }

    private boolean canBeltMove(Map<String, Object> entity) {
        String kind = str(entity.get("kind"));
        return "unit".equals(kind) || "material".equals(kind) || "equipment".equals(kind);
    }

    private void addStack(List<Map<String, Object>> hand, String code, int count, Map<String, Map<String, Object>> configMap) {
        for (Map<String, Object> stack : hand) {
            if (code.equals(stack.get("code")) && num(stack.get("count")) < stackLimit(configMap, code)) {
                stack.put("count", num(stack.get("count")) + count);
                return;
            }
        }
        addNewStack(hand, code, count, configMap);
    }

    private void addNewStack(List<Map<String, Object>> hand, String code, int count, Map<String, Map<String, Object>> configMap) {
        if (hand.size() >= STORAGE_SLOT_COUNT) throw new IllegalArgumentException("存卡区空位不足");
        Map<String, Object> def = configMap.get(code);
        Map<String, Object> stack = new LinkedHashMap<>();
        stack.put("id", random());
        stack.put("code", code);
        stack.put("name", def == null ? code : def.get("name"));
        stack.put("asset", def == null ? "placeholder" : def.get("asset_key"));
        stack.put("kind", def == null ? "resource" : def.get("type"));
        stack.put("count", count);
        hand.add(stack);
    }

    private int stackLimit(Map<String, Map<String, Object>> configMap, String code) {
        Map<String, Object> def = configMap.get(code);
        int limit = def == null ? 9 : (int) num(def.get("stack_limit"));
        return limit <= 0 ? 9 : limit;
    }

    private Map<String, Object> packConfig(String packType) {
        Map<String, Object> pack = queryOne("SELECT * FROM game_pack_config WHERE code = ?", packType);
        if (pack == null) throw new IllegalArgumentException("卡包配置不存在");
        return pack;
    }

    @SuppressWarnings("unchecked")
    private int packCost(Map<String, Object> state, String packType, Map<String, Object> pack) {
        Map<String, Object> purchases = (Map<String, Object>) state.computeIfAbsent("packPurchases", key -> new LinkedHashMap<String, Object>());
        long count = num(purchases.get(packType));
        return (int) (num(pack.get("initial_price")) + count * num(pack.get("price_increase")));
    }

    @SuppressWarnings("unchecked")
    private void incrementPackPurchase(Map<String, Object> state, String packType) {
        Map<String, Object> purchases = (Map<String, Object>) state.computeIfAbsent("packPurchases", key -> new LinkedHashMap<String, Object>());
        purchases.put(packType, num(purchases.get(packType)) + 1);
    }

    private void syncPackCosts(Map<String, Object> state) {
        Map<String, Object> building = packConfig("building");
        Map<String, Object> resource = packConfig("resource");
        state.put("buildingPackCostGold", packCost(state, "building", building));
        state.put("resourcePackCostGold", packCost(state, "resource", resource));
    }

    private Random packRandom(Map<String, Object> state, String packType) {
        long seed = num(state.get("seed"));
        long tick = num(state.get("tick"));
        long purchases = 0;
        Object packPurchases = state.get("packPurchases");
        if (packPurchases instanceof Map<?, ?> map) purchases = num(map.get(packType));
        return new Random(seed ^ (tick * 31L) ^ (purchases * 131L) ^ packType.hashCode());
    }

    private String drawFromPackPool(String packType, Random rng) {
        List<Map<String, Object>> pool = jdbcTemplate.queryForList("""
                SELECT card_code, weight
                FROM game_pack_pool_config
                WHERE pack_code = ? AND weight > 0
                ORDER BY id ASC
                """, packType);
        if (pool.isEmpty()) throw new IllegalArgumentException("卡包卡池为空");
        int totalWeight = 0;
        for (Map<String, Object> item : pool) totalWeight += (int) num(item.get("weight"));
        int cursor = rng.nextInt(Math.max(1, totalWeight));
        for (Map<String, Object> item : pool) {
            cursor -= (int) num(item.get("weight"));
            if (cursor < 0) return str(item.get("card_code"));
        }
        return str(pool.get(pool.size() - 1).get("card_code"));
    }

    private Map<String, Map<String, Object>> configMap() {
        List<Map<String, Object>> configs = jdbcTemplate.queryForList("SELECT * FROM game_card_config");
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> config : configs) map.put(str(config.get("code")), config);
        return map;
    }

    private Map<String, Object> stageConfig(int stageNo) {
        Map<String, Object> stage = queryOne("SELECT config_json FROM game_journey_stage WHERE stage_no = ?", stageNo);
        String configJson = stage == null ? "" : str(stage.get("config_json"));
        if (StrUtil.isBlank(configJson)) return new LinkedHashMap<>();
        return readMap(configJson);
    }

    private long insertAndReturnId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : new LinkedHashMap<>(rows.get(0));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> readList(String json) {
        if (StrUtil.isBlank(json)) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> append(List<Map<String, Object>> list, Map<String, Object> item) {
        List<Map<String, Object>> copy = new ArrayList<>(list);
        copy.add(item);
        return copy;
    }

    private List<String> eventTexts(List<Map<String, Object>> events) {
        return events.stream().map(item -> str(item.get("text"))).filter(StrUtil::isNotBlank).limit(8).toList();
    }

    private String required(Map<String, Object> req, String key) {
        String value = str(req.get(key));
        if (StrUtil.isBlank(value)) throw new IllegalArgumentException(key + "不能为空");
        return value;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long num(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || StrUtil.isBlank(String.valueOf(value))) return 0;
        return Long.parseLong(String.valueOf(value));
    }

    private String random() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record BattleSnapshot(long battleId, Map<String, Object> state, List<Map<String, Object>> commands, List<Map<String, Object>> events) {
        List<String> eventTexts() {
            return events.stream().map(item -> String.valueOf(item.get("text"))).toList();
        }
    }
}
