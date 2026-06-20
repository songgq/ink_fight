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
        place(grid, entity("playerBase", "playerBase", "我方基地", "base", "player", "staffWorkshop", 1, 1000, 0), 0, 2);
        place(grid, entity("wallTop", "playerWall", "城墙", "wall", "player", "shield", 1, 300, 0), 4, 1);
        place(grid, entity("wallMid", "playerWall", "城墙", "wall", "player", "shield", 1, 300, 0), 4, 2);
        place(grid, entity("wallBot", "playerWall", "城墙", "wall", "player", "shield", 1, 300, 0), 4, 3);
        place(grid, entity("enemyBase", "enemyBase", "敌方基地", "base", "enemy", "hammerWorkshop", 1, enemyBaseHp, 0), 8, 2);
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
        state.put("resources", new LinkedHashMap<>(Map.of("gold", 1250, "food", 860, "ore", 3)));
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
        } else if ("set-port".equals(type)) {
            setPort(state, command, events);
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
                : List.of("villager", "baby", "ore", "pickaxe", "sword", "bow", "staff", "shield", "spear", "worker", "swordsman", "archer", "spearman", "mage", "shieldman");
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
        Map<String, Object> stack = hand.stream().filter(item -> cardId.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> def = configMap().get(str(stack.get("code")));
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
                if (total > 9) throw new IllegalArgumentException("堆叠上限为 9");
                targetEntity.put("count", total);
                targetEntity.put("hp", num(targetEntity.get("hp")) + num(def.get("hp")) * placingCount);
                targetEntity.put("maxHp", num(targetEntity.get("maxHp")) + num(def.get("hp")) * placingCount);
            } else {
                throw new IllegalArgumentException("目标格已占用");
            }
        } else {
            if (row == 4 && !"unit".equals(type)) throw new IllegalArgumentException("备战区只能放入单位");
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
        Map<String, Object> targetEntity = (Map<String, Object>) to.get("entity");
        if (targetEntity != null) {
            if ("resource".equals(str(targetEntity.get("kind"))) && isCollector(str(entityMap.get("code")))) {
                attachCollector(targetEntity, str(entityMap.get("code")), (int) num(entityMap.get("count")));
                events.add(Map.of("text", entityMap.get("label") + " 开始采集 " + targetEntity.get("label")));
            } else if (canStack(targetEntity, str(entityMap.get("code")))) {
                targetEntity.put("count", num(targetEntity.get("count")) + num(entityMap.get("count")));
                targetEntity.put("hp", num(targetEntity.get("hp")) + num(entityMap.get("hp")));
                targetEntity.put("maxHp", num(targetEntity.get("maxHp")) + num(entityMap.get("maxHp")));
                events.add(Map.of("text", "同类卡牌已堆叠"));
            } else {
                throw new IllegalArgumentException("目标格已占用");
            }
        } else {
            if (toRow == 4 && !"unit".equals(str(entityMap.get("kind")))) throw new IllegalArgumentException("备战区只能放入单位");
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
        if ("output".equals(portKind) && targetRow >= 1 && targetRow <= 3) throw new IllegalArgumentException("不能直接产出到战斗区");
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
        gatherResources(state, resources, tick, events);
        runBuildings(state, resources, tick, events);
        runBelts(state, tick, events);
        if (tick % 3 == 0) {
            long enemyHp = Math.max(0, num(state.get("enemyBaseHp")) - 18);
            state.put("enemyBaseHp", enemyHp);
            if (enemyHp == 0 && StrUtil.isBlank(str(state.get("winner")))) {
                state.put("winner", "player");
                events.add(Map.of("text", "胜利！敌方基地被摧毁"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void gatherResources(Map<String, Object> state, Map<String, Object> resources, int tick, List<Map<String, Object>> events) {
        if (tick % 5 != 0) return;
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
                long power = villager * 5 + worker * 8;
                if (power <= 0) continue;
                String code = str(entity.get("code"));
                if ("goldMine".equals(code)) gold += power;
                if ("forest".equals(code)) food += power;
                if ("ironMine".equals(code)) ore += power;
            }
        }
        if (gold + food + ore <= 0) return;
        resources.put("gold", num(resources.get("gold")) + gold);
        resources.put("food", num(resources.get("food")) + food);
        resources.put("ore", num(resources.get("ore")) + ore);
        events.add(Map.of("text", "采集 +" + gold + " 金币 +" + food + " 粮食 +" + ore + " 矿石"));
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
                int interval = buildingInterval(code);
                if (interval <= 0) continue;
                entity.put("progress", Math.min(10, num(entity.get("progress")) + Math.max(1, 10 / interval)));
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
        if (tick % 2 != 0) return;
        List<List<Map<String, Object>>> grid = (List<List<Map<String, Object>>>) state.get("grid");
        Map<String, Map<String, Object>> configMap = configMap();
        for (List<Map<String, Object>> row : grid) {
            for (Map<String, Object> cell : row) {
                Map<String, Object> belt = (Map<String, Object>) cell.get("entity");
                if (belt == null || !"belt".equals(str(belt.get("code"))) || !"player".equals(str(belt.get("owner")))) continue;
                initPorts(belt, (int) num(cell.get("col")), (int) num(cell.get("row")));
                Map<String, Object> ports = (Map<String, Object>) belt.get("ports");
                List<Map<String, Object>> inputs = (List<Map<String, Object>>) ports.get("inputs");
                List<Map<String, Object>> outputs = (List<Map<String, Object>>) ports.get("outputs");
                for (int i = 0; i < inputs.size(); i++) {
                    Map<String, Object> source = entityAt(grid, inputs.get(i));
                    if (source == null || !canBeltMove(source)) continue;
                    String code = str(source.get("code"));
                    Map<String, Object> output = outputs.get(i);
                    if ((int) num(output.get("row")) == 4 && !"unit".equals(str(source.get("kind")))) continue;
                    if (!canOutput(grid, output, code)) continue;
                    consumeAt(grid, inputs.get(i), code, 1);
                    outputTo(grid, output, code, configMap);
                    events.add(Map.of("text", "传送带搬运 " + source.get("label")));
                    break;
                }
            }
        }
    }

    private int buildingInterval(String code) {
        return switch (code) {
            case "toolWorkshop", "swordWorkshop", "bowWorkshop", "spearWorkshop" -> 8;
            case "staffWorkshop", "shieldWorkshop", "maternityRoom", "nursery" -> 10;
            case "trainingCamp" -> 6;
            default -> 0;
        };
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
        return Map.of("battleNo", battleNo, "state", state, "events", events);
    }

    private void place(List<List<Map<String, Object>>> grid, Map<String, Object> entity, int col, int row) {
        grid.get(row).get(col).put("entity", entity);
    }

    private boolean isOperableCell(int col, int row) {
        return col >= 0 && col < GRID_COLS && row >= 4 && row <= 11;
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
        return ("unit".equals(kind) || "building".equals(kind) || "material".equals(kind) || "equipment".equals(kind)) && num(entity.get("count")) < 99;
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
