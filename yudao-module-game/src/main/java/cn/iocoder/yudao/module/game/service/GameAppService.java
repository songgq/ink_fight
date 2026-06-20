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

    private static final int VISIBLE_ROWS = 13;
    private static final int GRID_COLS = 9;
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
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            List<Map<String, Object>> line = new ArrayList<>();
            for (int col = 0; col < GRID_COLS; col++) line.add(new LinkedHashMap<>(Map.of("col", col, "row", row)));
            grid.add(line);
        }
        place(grid, entity("playerBase", "我方基地", "base", "player", "staffWorkshop", 1, 1000, 0), 0, 2);
        place(grid, entity("wallTop", "城墙", "wall", "player", "shield", 1, 300, 0), 4, 1);
        place(grid, entity("wallMid", "城墙", "wall", "player", "shield", 1, 300, 0), 4, 2);
        place(grid, entity("wallBot", "城墙", "wall", "player", "shield", 1, 300, 0), 4, 3);
        place(grid, entity("enemyBase", "敌方基地", "base", "enemy", "hammerWorkshop", 1, enemyBaseHp, 0), 8, 2);
        place(grid, unitEntity(configMap, "swordsman", "enemy"), 5, 1);
        place(grid, unitEntity(configMap, "swordsman", "enemy"), 6, 2);
        place(grid, unitEntity(configMap, "spearman", "enemy"), 7, 3);
        place(grid, resourceEntity(configMap, "ironMine"), 1, 11);
        place(grid, resourceEntity(configMap, "goldMine"), 4, 11);
        place(grid, resourceEntity(configMap, "forest"), 7, 11);

        List<Map<String, Object>> hand = new ArrayList<>();
        addStack(hand, "villager", 4, configMap);
        addStack(hand, "maternityRoom", 1, configMap);
        addStack(hand, "nursery", 1, configMap);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("battleNo", battleNo);
        state.put("stageNo", stageNo);
        state.put("seed", seed);
        state.put("tick", 0);
        state.put("visibleRows", 13);
        state.put("fullRows", 21);
        state.put("resources", new LinkedHashMap<>(Map.of("gold", 1250, "food", 860, "iron", 3)));
        state.put("hand", hand);
        state.put("grid", grid);
        state.put("buildingPackCostGold", 3);
        state.put("resourcePackCostGold", 3);
        state.put("enemyBaseHp", enemyBaseHp);
        state.put("playerBaseHp", 1000);
        state.put("playerWallHp", 900);
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
        } else if ("split-card".equals(type)) {
            splitCard(state, str(command.get("cardId")), events);
        } else if ("move-entity".equals(type)) {
            moveEntity(state, command, events);
        }
    }

    @SuppressWarnings("unchecked")
    private void buyPack(Map<String, Object> state, String packType, List<Map<String, Object>> events) {
        Map<String, Object> resources = (Map<String, Object>) state.get("resources");
        List<Map<String, Object>> hand = (List<Map<String, Object>>) state.get("hand");
        String costKey = "building".equals(packType) ? "buildingPackCostGold" : "resourcePackCostGold";
        int drawCount = "building".equals(packType) ? 2 : 4;
        int cost = (int) num(state.get(costKey));
        if (num(resources.get("gold")) < cost) throw new IllegalArgumentException("金币不足");
        if (hand.size() + drawCount > 9) throw new IllegalArgumentException("存卡区空位不足");
        resources.put("gold", num(resources.get("gold")) - cost);
        List<String> pool = "building".equals(packType)
                ? List.of("maternityRoom", "nursery", "trainingCamp", "belt", "toolWorkshop", "swordWorkshop", "ironMine", "goldMine", "forest")
                : List.of("villager", "worker", "swordsman", "archer", "spearman", "mage", "shieldman");
        Map<String, Map<String, Object>> configMap = configMap();
        int base = ((int) num(state.get("tick")) + hand.size() + cost) % pool.size();
        for (int i = 0; i < drawCount; i++) addStack(hand, pool.get((base + i * 2) % pool.size()), 1, configMap);
        state.put(costKey, cost + 1);
        events.add(Map.of("text", ("building".equals(packType) ? "建筑卡包" : "资源卡包") + "获得 " + drawCount + " 张卡"));
    }

    @SuppressWarnings("unchecked")
    private void placeCard(Map<String, Object> state, Map<String, Object> command, List<Map<String, Object>> events) {
        String cardId = str(command.get("cardId"));
        int col = (int) num(command.get("col"));
        int row = (int) num(command.get("row"));
        if (row < 4 || row > 11 || col < 0 || col >= 9) throw new IllegalArgumentException("只能放在我方可操作区域");
        List<Map<String, Object>> hand = (List<Map<String, Object>>) state.get("hand");
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Object> cell = grid.get(row).get(col);
        if (cell.get("entity") != null) throw new IllegalArgumentException("目标格已占用");
        Map<String, Object> stack = hand.stream().filter(item -> cardId.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> def = configMap().get(str(stack.get("code")));
        cell.put("entity", entity(random(), str(def.get("name")), str(def.get("type")), "player", str(def.get("asset_key")), 1, (int) num(def.get("hp")), (int) num(def.get("attack"))));
        int count = (int) num(stack.get("count")) - 1;
        if (count <= 0) hand.remove(stack); else stack.put("count", count);
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
    private void splitCard(Map<String, Object> state, String cardId, List<Map<String, Object>> events) {
        List<Map<String, Object>> hand = (List<Map<String, Object>>) state.get("hand");
        Map<String, Object> stack = hand.stream().filter(item -> cardId.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> def = configMap().get(str(stack.get("code")));
        if (def != null && "building".equals(str(def.get("type")))) throw new IllegalArgumentException("建筑不能拆分");
        if (num(stack.get("count")) < 2) throw new IllegalArgumentException("至少 2 张同卡才可拆分");
        stack.put("count", num(stack.get("count")) - 1);
        addStack(hand, str(stack.get("code")), 1, configMap());
        events.add(Map.of("text", "卡堆已拆分"));
    }

    @SuppressWarnings("unchecked")
    private void moveEntity(Map<String, Object> state, Map<String, Object> command, List<Map<String, Object>> events) {
        int fromCol = (int) num(command.get("fromCol"));
        int fromRow = (int) num(command.get("fromRow"));
        int toCol = (int) num(command.get("toCol"));
        int toRow = (int) num(command.get("toRow"));
        if (!isOperableCell(fromCol, fromRow) || !isOperableCell(toCol, toRow)) throw new IllegalArgumentException("只能移动己方生产区或备战区卡牌");
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Object> from = grid.get(fromRow).get(fromCol);
        Map<String, Object> to = grid.get(toRow).get(toCol);
        Object entity = from.get("entity");
        if (entity == null) throw new IllegalArgumentException("没有可移动卡牌");
        Map<String, Object> entityMap = (Map<String, Object>) entity;
        if (!"player".equals(str(entityMap.get("owner")))) throw new IllegalArgumentException("只能移动己方卡牌");
        if ("wall".equals(str(entityMap.get("kind"))) || "base".equals(str(entityMap.get("kind")))) throw new IllegalArgumentException("固定建筑不可移动");
        if (to.get("entity") != null) throw new IllegalArgumentException("目标格已占用");
        to.put("entity", entity);
        from.remove("entity");
        events.add(Map.of("text", "卡牌已移动"));
    }

    @SuppressWarnings("unchecked")
    private void tickState(Map<String, Object> state, List<Map<String, Object>> events) {
        int tick = (int) num(state.get("tick")) + 1;
        state.put("tick", tick);
        Map<String, Object> resources = (Map<String, Object>) state.get("resources");
        if (tick % 5 == 0) {
            resources.put("gold", num(resources.get("gold")) + 5);
            resources.put("food", num(resources.get("food")) + 5);
            events.add(Map.of("text", "采集 +5 金币 +5 粮食"));
        }
        if (tick % 8 == 0) {
            List<Map<String, Object>> hand = (List<Map<String, Object>>) state.get("hand");
            if (hand.size() < 9) {
                addStack(hand, "swordsman", 1, configMap());
                events.add(Map.of("text", "训练营产出剑士卡"));
            }
        }
        if (tick % 3 == 0) {
            long enemyHp = Math.max(0, num(state.get("enemyBaseHp")) - 18);
            state.put("enemyBaseHp", enemyHp);
            if (enemyHp == 0 && StrUtil.isBlank(str(state.get("winner")))) {
                state.put("winner", "player");
                events.add(Map.of("text", "胜利！敌方基地被摧毁"));
            }
        }
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
        return Map.of("battleNo", battleNo, "state", state, "events", events);
    }

    private void place(List<List<Map<String, Object>>> grid, Map<String, Object> entity, int col, int row) {
        grid.get(row).get(col).put("entity", entity);
    }

    private boolean isOperableCell(int col, int row) {
        return col >= 0 && col < GRID_COLS && row >= 4 && row <= 11;
    }

    private Map<String, Object> unitEntity(Map<String, Map<String, Object>> configMap, String code, String owner) {
        Map<String, Object> def = configMap.get(code);
        return entity(random(), str(def.get("name")), "unit", owner, str(def.get("asset_key")), 1, (int) num(def.get("hp")), (int) num(def.get("attack")));
    }

    private Map<String, Object> resourceEntity(Map<String, Map<String, Object>> configMap, String code) {
        Map<String, Object> def = configMap.get(code);
        return entity(random(), str(def.get("name")), "resource", "neutral", str(def.get("asset_key")), 1, (int) num(def.get("hp")), 0);
    }

    private Map<String, Object> entity(String id, String label, String kind, String owner, String asset, int count, int hp, int attack) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("label", label);
        map.put("kind", kind);
        map.put("owner", owner);
        map.put("asset", asset);
        map.put("count", count);
        map.put("hp", hp);
        map.put("maxHp", hp);
        map.put("attack", attack);
        map.put("progress", 0);
        return map;
    }

    private void addStack(List<Map<String, Object>> hand, String code, int count, Map<String, Map<String, Object>> configMap) {
        for (Map<String, Object> stack : hand) {
            if (code.equals(stack.get("code"))) {
                stack.put("count", num(stack.get("count")) + count);
                return;
            }
        }
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

    private Map<String, Map<String, Object>> configMap() {
        List<Map<String, Object>> configs = jdbcTemplate.queryForList("SELECT * FROM game_card_config");
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> config : configs) map.put(str(config.get("code")), config);
        return map;
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
