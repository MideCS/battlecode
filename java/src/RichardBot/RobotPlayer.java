package RichardBot;
import battlecode.common.*;

import java.util.*;

/**
 * Optimized RobotPlayer with focused ruin targeting, controlled expansion,
 * map symmetry analysis, and coordinated unit production.
 */
public class RobotPlayer {

    // -------------- GAME STATE & SAVING --------------
    static int turnCount = 0;
    static boolean isSaving = false;
    static int spawnCount = 0;
    static int savingTurns = 0;
    static int savingCooldown = 0;
    static UnitType nextUnit = null;  // Used when trying to build the same unit across multiple attempts
    static MapLocation curRuin = null;
    static UnitType[] towers_to_build = new UnitType[]{UnitType.LEVEL_ONE_PAINT_TOWER, UnitType.LEVEL_ONE_MONEY_TOWER};
    static UnitType towerType = null;

    private enum SavingAction { NONE, SAVE_CHIPS, UPGRADE_TOWER }
    static SavingAction lastSavingAction = SavingAction.NONE;

    // -------------- MAP SYMMETRY --------------
    private enum MapSymmetry {
        HORIZONTAL,
        VERTICAL,
        ROTATIONAL,
        REFLECTIONAL,
        UNKNOWN
    }
    static MapSymmetry currentSymmetry = MapSymmetry.UNKNOWN;

    // -------------- MESSAGING SYSTEM --------------
    // We unify your various message types here.
    private enum MessageType {
        SYMMETRY_FOUND,
        ENEMY_TOWER_SPOTTED,
        SAVE_CHIPS,
        UPGRADE_TOWER,
        RUIN_NEEDS_CLEANING,
        NEED_PAINT
    }

    // -------------- TRACKING DATA STRUCTURES --------------
    static HashSet<MapLocation> confirmedEnemyRuins = new HashSet<>();
    static MapLocation symmetryTargetRuin = null;  
    static MapLocation currentPredictedRuin = null;  
    static MapSymmetry currentCheckingSymmetry = null;  
    static boolean initialTowersFound = false;  
    static ArrayList<MapLocation> initialTowers = new ArrayList<>();  
    static boolean[] possibleSymmetries = new boolean[MapSymmetry.values().length];  
    static int confirmationsNeeded = 2;  
    static HashMap<MapSymmetry, Integer> symmetryConfirmations = new HashMap<>();

    // -------------- BASIC CONSTANTS & RANDOMNESS --------------
    static final Random rng = new Random(6147);
    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };
    static boolean isMessenger = false;  // New: half the Moppers can be designated as "messengers"
    static boolean isExplorer = false;

    // -------------- TOWER AND RUINS TRACKING --------------
    static HashSet<MapLocation> exploredTiles = new HashSet<>();
    static HashSet<MapLocation> knownTowers = new HashSet<>();

    static MapSymmetry lastPrintedSymmetry = MapSymmetry.UNKNOWN;
    private static final double TOWER_ATTACK_RADIUS = Math.sqrt(80);
    private static final int TOWER_VISION_RADIUS = 80;
    private static Direction lastCircularDirection = null;

    private static final int MIN_ROBOT_SPACING = 16; // Minimum squared distance between friendly robots
    private static Direction lastExpansionDir = null;
    private static HashSet<MapLocation> visitedLocations = new HashSet<>();

    // -------------- RUIN PROGRESS TRACKING --------------
    static class RuinProgress {
        MapLocation location;
        int paintedTiles;
        int totalTiles;
        int lastUpdateRound;

        
        RuinProgress(MapLocation loc, int painted, int total) {
            location = loc;
            paintedTiles = painted;
            totalTiles = total;
            lastUpdateRound = 0;
        }
    }
    static HashMap<MapLocation, RuinProgress> ruinProgressMap = new HashMap<>();
    static final int SAVING_THRESHOLD = 200; 
    static final int MIN_PAINT_FOR_TOWER = 50; 

    // -------------- CLEANUP LOGIC FOR MOPPERS --------------
    static HashSet<MapLocation> ruinsNeedingCleanup = new HashSet<>();
    static MapLocation currentCleanupTarget = null;
    static int lastCleanupMessageRound = 0;

    // -------------- TIMING / ALLY WAIT LOGIC --------------
    private static final int BASE_EARLY_GAME_ROUNDS = 200;
    private static final int BASE_ALLY_WAIT_ROUNDS = 10;
    private static final int MIN_ALLIES_NEEDED = 2;
    private static HashMap<Integer, Integer> unitWaitingStartRounds = new HashMap<>();

    // -------------- PREFERRED BUILD ORDER --------------
    // Weighted approach: 3/5 Soldiers, 1/5 Moppers, 1/5 Splashers
    private static final UnitType[] PREFERRED_BUILD_ORDER = {
        UnitType.SOLDIER,
        UnitType.MOPPER,
        UnitType.SPLASHER
    };

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {

        // Assign messenger to ~ half of the Moppers
//        if (rc.getType() == UnitType.MOPPER && rc.getID() % 2 == 0) {
//            isMessenger = true;
//        }

        if (rc.getChips() > 2000 && rc.getType() == UnitType.SOLDIER && rc.getID() % 2 == 0) {
            isExplorer = true;
        }

        while (true) {
            try {
                // Print symmetry occasionally if we are a level 1 paint tower
                if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER && 
                    (rc.getRoundNum() % 50 == 0 || lastPrintedSymmetry != currentSymmetry)) {
//                    System.out.println("[Round " + rc.getRoundNum() + "] Map Symmetry: " + currentSymmetry);
                    lastPrintedSymmetry = currentSymmetry;
                }

                switch (rc.getType()) {
                    case SOLDIER:
                        runSoldier(rc);
                        break; 
                    case MOPPER:
                        runMopper(rc);
                        break;
                    case SPLASHER:
                        runSplasher(rc);
                        break; 
                    default: // TOWER types
                        runTower(rc);
                        break;
                }

            } catch (GameActionException e) {
                System.out.println("GameActionException");
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Exception");
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    // ------------------------------------------------------------------
    //                           TOWER LOGIC
    // ------------------------------------------------------------------

    public static void runTower(RobotController rc) throws GameActionException {
//        manageResources(rc);
        processMessages(rc);
        buildUnits(rc);
    }

    /**
     * Checks if we should begin saving, updates saving counters,
     * and manages ruin progress tracking.
     */
    private static void manageResources(RobotController rc) throws GameActionException {
        // Check if we need to start saving

        // Handle ongoing saving state
        if (savingTurns > 0) {
            savingTurns--;
        }
//        else if (savingTurns == 0) {
//            if (rc.getChips() < SAVING_THRESHOLD) {
//                savingTurns = 10; // Extend saving a bit
//            }
            else {
                isSaving = false;
                savingCooldown = 20;
                lastSavingAction = SavingAction.NONE;
            }
//        }

        // Clean up old ruin progress entries
//        int currentRound = rc.getRoundNum();
//        ruinProgressMap.entrySet().removeIf(entry ->
//            currentRound - entry.getValue().lastUpdateRound > 50
//        );
    }

    /**
     * Reads incoming messages (symmetry updates, saving commands, ruin cleaning, etc.)
     */
    private static void processMessages(RobotController rc) throws GameActionException {
        Message[] messages = rc.readMessages(-1);  // read all available

        for (int i = 0; i < messages.length; i++) {
            int messageType = messages[i].getBytes();
            
            if (messageType == MessageType.SYMMETRY_FOUND.ordinal()) {
                // Next message is the actual symmetry ID
                if (i + 1 < messages.length) {
                    currentSymmetry = MapSymmetry.values()[messages[i + 1].getBytes()];
                    i++;
                }
            }
            else if (messageType == MessageType.ENEMY_TOWER_SPOTTED.ordinal()) {
                // Next two messages: x and y
                if (i + 2 < messages.length) {
                    MapLocation enemyTower = new MapLocation(
                        messages[i + 1].getBytes(), 
                        messages[i + 2].getBytes()
                    );
                    confirmedEnemyRuins.add(enemyTower);
                    i += 2;
                }
            }
            else if (messageType == MessageType.SAVE_CHIPS.ordinal() && !isSaving) {
                isSaving = true;
                rc.setIndicatorDot(rc.getLocation(), 255, 0, 0);
//                System.out.println(rc.getLocation() + " IS SAVING");
                if (i + 1 < messages.length) {
                    savingTurns = messages[i + 1].getBytes();
                    i++;
                } else {
                    savingTurns = 10; 
                }
            }
            else if (messageType == MessageType.RUIN_NEEDS_CLEANING.ordinal()) {
                // Next two messages: x and y
                if (i + 2 < messages.length) {
                    MapLocation ruinLoc = new MapLocation(
                        messages[i + 1].getBytes(),
                        messages[i + 2].getBytes()
                    );
                    ruinsNeedingCleanup.add(ruinLoc);

                    // If we're a mopper lacking a target, adopt this one
                    if (rc.getType() == UnitType.MOPPER && 
                       (currentCleanupTarget == null ||
                        rc.getLocation().distanceSquaredTo(ruinLoc) <
                        rc.getLocation().distanceSquaredTo(currentCleanupTarget))) {
                        currentCleanupTarget = ruinLoc;
                    }
                    i += 2;
                }
            }
        }
    }

    /**
     * Merged approach to building:
     * - If we are saving, skip.
     * - If we have a 'nextUnit' in progress, keep trying.
     * - Otherwise pick from a "preferred build order" array with weighted distribution.
     */
    private static void buildUnits(RobotController rc) throws GameActionException {
//        if (isSaving) {
//            spawnCount = 0;
//            return; // skip building if saving
//        }

        if (rc.getChips() < 1400) { // rc.getType() == UnitType.LEVEL_ONE_MONEY_TOWER &&
            spawnCount = 0;
            return; // Do not build
        }
        if (spawnCount < 3) {
            nextUnit = UnitType.SOLDIER;
        }
        // If there's no "nextUnit" chosen, pick from a weighted array
        else if (nextUnit == null) {
            nextUnit = PREFERRED_BUILD_ORDER[rng.nextInt(PREFERRED_BUILD_ORDER.length)];
//            nextUnit = UnitType.SOLDIER;
        }


        // Attempt to build in a random (shuffled) direction
        ArrayList<Direction> shuffledDirs = new ArrayList<>();
        for (Direction d : directions) shuffledDirs.add(d);
        java.util.Collections.shuffle(shuffledDirs, rng);

        for (Direction dir : shuffledDirs) {
            MapLocation buildLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(nextUnit, buildLoc)) {
                rc.buildRobot(nextUnit, buildLoc);
                nextUnit = null;
                spawnCount++;
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    //                           SOLDIER LOGIC
    // ------------------------------------------------------------------
    public static void runSoldier(RobotController rc) throws GameActionException {
        paintCurrentTile(rc);
        processMessages(rc);
        updateTowerLocations(rc);
        updateMapSymmetry(rc);

        // Attack any nearby enemy first
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            MapLocation enemyLoc = enemies[0].getLocation();
            if (rc.canAttack(enemyLoc)) {
                rc.attack(enemyLoc);
            }
        }

        // Next, handle ruin building or exploration
        if (curRuin == null) curRuin = findNearestUnexploredRuin(rc);


        if (curRuin != null) {
            handleRuinBuilding(rc);
        }

        else {

//            System.out.println("Soldier is Exploring");
            if (isExplorer) expandAggressively(rc);
//            else if (currentSymmetry != MapSymmetry.UNKNOWN) moveTowardsEnemyTower(rc);
            else fill(rc, false);
//
        }

        // Try advanced small map logic
//        boolean isSmallMap = rc.getMapWidth() <= 20 && rc.getMapHeight() <= 20;
//        if (isSmallMap && currentSymmetry != MapSymmetry.UNKNOWN && rc.getRoundNum() < 200) {
//            MapLocation enemySpawn = getPredictedEnemySpawn(rc);
//            if (enemySpawn != null) {
//                RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
//                int nearbySoldiers = 0;
//                for (RobotInfo ally : allies) {
//                    if (ally.getType() == UnitType.SOLDIER) {
//                        nearbySoldiers++;
//                    }
//                }
//                // If enough soldiers or close to spawn, rush
//                if (nearbySoldiers >= 2 || rc.getLocation().distanceSquaredTo(enemySpawn) < 20) {
//                    Direction dir = rc.getLocation().directionTo(enemySpawn);
//                    if (rc.canMove(dir)) {
//                        rc.move(dir);
//                        return;
//                    }
//                } else {
//                    // Wait/circle around
//                    moveCircularlyAroundTarget(rc, rc.getLocation());
//                    return;
//                }
//            }
//        }
            // Larger map strategy
//            if (currentSymmetry != MapSymmetry.UNKNOWN) {
//                moveTowardsEnemyTower(rc);
//            }
//            else {
////            expandAggressively(rc);
//            }


    }



    /**
     * Separated logic for approaching & completing a ruin tower build.
     */
    private static void handleRuinBuilding(RobotController rc) throws GameActionException {
        // Complete tower if possible







        MapLocation unpaintedTile = findNearestUnpaintedTile(rc, curRuin, 18);
//        moveCircularlyAroundTarget(rc, curRuin);
        Direction dir = null;
        if (unpaintedTile != null) {

            dir = rc.getLocation().directionTo(unpaintedTile);

        }

        else {
            dir = rc.getLocation().directionTo(curRuin);
        }

        if (rc.canMove(dir)) {
            rc.move(dir);
        }




        if (towerType == null) {towerType = towers_to_build[rng.nextInt(towers_to_build.length)];} // Pick random tower to build

        // Try marking tower pattern
        MapLocation shouldBeMarked = new MapLocation(curRuin.x - dir.dx, curRuin.y - dir.dy);
        if (rc.canSenseLocation(shouldBeMarked)) {

            MapInfo markTile = rc.senseMapInfo(shouldBeMarked);
            if (markTile != null && markTile.getMark() == PaintType.EMPTY
                    && rc.canMarkTowerPattern(towerType, curRuin)) {
                rc.markTowerPattern(towerType, curRuin);
            }

        }

        // Fill pattern tiles
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(curRuin, 8);
        for (MapInfo patternTile : nearbyTiles) {
            if (patternTile.getMark() != patternTile.getPaint() && patternTile.getMark() != PaintType.EMPTY) {
                boolean useSecondaryColor = (patternTile.getMark() == PaintType.ALLY_SECONDARY);
                if (rc.canAttack(patternTile.getMapLocation())) {
                    rc.attack(patternTile.getMapLocation(), useSecondaryColor);
                }
            }
        }

        if (rc.canSenseRobotAtLocation(curRuin)) {
            if (rc.senseRobotAtLocation(curRuin).getType() == towerType) {
                knownTowers.add(curRuin);
                System.out.println("Tower Already Explored at " + curRuin);
                towerType = null;
                curRuin = null;
                return;
            }

        }

//        System.out.println("Can Complete Tower = " + rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin));
        for (UnitType tower: towers_to_build) {

            if (rc.canCompleteTowerPattern(tower, curRuin)) {
                rc.completeTowerPattern(tower, curRuin);
                knownTowers.add(curRuin);
                System.out.println("Built a tower!!");
                curRuin = null;
                towerType = null;

                break;

            }
        }


    }

    // ------------------------------------------------------------------
    //                           MOPPER LOGIC
    // ------------------------------------------------------------------
    public static void runMopper(RobotController rc) throws GameActionException {
        // Check for Towers nearby.
        updateTowerLocations(rc);

        // Paint current tile if needed
        paintCurrentTile(rc);
        // Read new messages (could indicate saving or cleaning requests)
        processMessages(rc);

        // 1. If we are messenger + currently saving, go toward the nearest tower & send message
        messengerMopperLogic(rc);

        // 2. Cleanup if we have a target, otherwise explore
        handleCleanupOrExplore(rc);

        // 3. Attempt to remove enemy paint
        handlePaintRemoval(rc);
    }

    /**
     * If this Mopper is a "messenger" and saving is active, 
     * move toward the nearest known tower and send a SAVE_CHIPS message.
     */
    private static void messengerMopperLogic(RobotController rc) throws GameActionException {
        if (!isMessenger || !isSaving) {
            return;
        }
        MapLocation nearestTower = findNearestTower(rc);
//        System.out.println("Nearest Tower = " + nearestTower);
        if (nearestTower == null) {
            return; // no known towers
        }

        // Move toward that tower
        Direction dir = rc.getLocation().directionTo(nearestTower);
        if (rc.canMove(dir)) {
            rc.move(dir);
        }

        // If in range, send a SAVE_CHIPS message
        if (rc.canSendMessage(nearestTower, MessageType.SAVE_CHIPS.ordinal())) {
            sendSaveChipsMessage(rc, nearestTower);
            // Optionally end saving or keep it going
            isSaving = false;
        }
    }

    /**
     * If we have a cleanup target, move & eventually remove paint. 
     * Otherwise, do normal BFS-like exploration.
     */
    private static void handleCleanupOrExplore(RobotController rc) throws GameActionException {
        if (currentCleanupTarget != null) {
            if (rc.getLocation().isAdjacentTo(currentCleanupTarget)) {
                // We will remove paint in handlePaintRemoval below,
                // or you could do additional cleanup logic right here.
            } else {
                // Move towards target
                Direction dir = rc.getLocation().directionTo(currentCleanupTarget);
                if (rc.canMove(dir)) {
                    rc.move(dir);
                } else {
                    // Basic pathing attempt around obstacles
                    for (Direction altDir : directions) {
                        if (rc.canMove(altDir) && 
                            rc.getLocation().add(altDir).distanceSquaredTo(currentCleanupTarget)
                             < rc.getLocation().distanceSquaredTo(currentCleanupTarget)) {
                            rc.move(altDir);
                            break;
                        }
                    }
                }
            }
        } else {
            // No cleanup target => normal exploration
            fill(rc, false);
        }
    }

    /**
     * Finds the nearest known tower from the 'knownTowers' set.
     */
    private static MapLocation findNearestTower(RobotController rc) {
        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int minDist = Integer.MAX_VALUE;
        for (MapLocation tower : knownTowers) {
            int d = myLoc.distanceSquaredTo(tower);
            if (d < minDist) {
                minDist = d;
                best = tower;
            }
        }
        return best;
    }

    /**
     * Send a SAVE_CHIPS message to the specified tower location.
     */
    private static void sendSaveChipsMessage(RobotController rc, MapLocation towerLoc) throws GameActionException {
        rc.setIndicatorDot(towerLoc, 255, 0, 0); // visual debug
        rc.sendMessage(towerLoc, MessageType.SAVE_CHIPS.ordinal());
//        rc.sendMessage(towerLoc, 20);
//        System.out.println("Mopper messenger: sent SAVE_CHIPS message to tower at " + towerLoc);
    }

    /**
     * Attempts to remove enemy paint around the Mopper.
     * If multiple tiles in one direction have enemy paint, use mopSwing.
     * Otherwise, single-tile attack.
     */
    static void handlePaintRemoval(RobotController rc) throws GameActionException {
        MapLocation currentLoc = rc.getLocation();
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(2);
        
        // Count enemy painted tiles in each direction
        Direction bestSwingDir = null;
        int maxPaintedTiles = 0;
        
        for (Direction dir : directions) {
            int paintedInLine = 0;
            MapLocation checkLoc = currentLoc;
            
            for (int i = 0; i < 3; i++) {
                checkLoc = checkLoc.add(dir);
                if (!rc.canSenseLocation(checkLoc)) break;
                
                MapInfo info = rc.senseMapInfo(checkLoc);
                if (info.getPaint() == PaintType.ENEMY_PRIMARY || info.getPaint() == PaintType.ENEMY_SECONDARY) {
                    paintedInLine++;
                }
            }
            
            if (paintedInLine > maxPaintedTiles) {
                maxPaintedTiles = paintedInLine;
                bestSwingDir = dir;
            }
        }
        
        // Decide whether to mop-swing or just single attack
        if (maxPaintedTiles >= 2 && bestSwingDir != null && rc.canMopSwing(bestSwingDir)) {
            rc.mopSwing(bestSwingDir);
        } else {
            // Single-tile removal
            for (MapInfo info : nearbyTiles) {
                if ((info.getPaint() == PaintType.ENEMY_PRIMARY || info.getPaint() == PaintType.ENEMY_SECONDARY)
                     && rc.canAttack(info.getMapLocation())) {
                    rc.attack(info.getMapLocation());
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //                           SPLASHER LOGIC
    // ------------------------------------------------------------------
    public static void runSplasher(RobotController rc) throws GameActionException {
        paintCurrentTile(rc);
        processMessages(rc);

        // Evaluate possible splash attacks
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapLocation bestTarget = null;
        double bestScore = 0;

        for (MapInfo tile : nearbyTiles) {
            MapLocation tileLoc = tile.getMapLocation();
            double score = evaluateSplashTarget(rc, tileLoc);
            if (score > bestScore) {
                bestScore = score;
                bestTarget = tileLoc;
            }
        }

        // Attack if we found a good target
        if (bestTarget != null && bestScore > 5.0) {
            if (rc.canAttack(bestTarget)) {
                rc.attack(bestTarget);
            }
        }

        // Movement logic: if low on paint, try retreating to ally paint
        if (rc.getPaint() < 10) {
            MapLocation nearestAllyPaint = null;
            int minDist = Integer.MAX_VALUE;
            for (MapInfo tile : nearbyTiles) {
                if (tile.getPaint().isAlly()) {
                    int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                    if (dist < minDist) {
                        minDist = dist;
                        nearestAllyPaint = tile.getMapLocation();
                    }
                }
            }
            if (nearestAllyPaint != null) {
                Direction d = rc.getLocation().directionTo(nearestAllyPaint);
                if (rc.canMove(d)) {
                    rc.move(d);
                    return;
                }
            }
        }
        // Otherwise, explore
        fill(rc, true);
    }

    private static double evaluateSplashTarget(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.canAttack(target)) return -1000;
        double score = 0;
        MapInfo[] affectedTiles = rc.senseNearbyMapInfos(target, 1);

        for (MapInfo tile : affectedTiles) {
            PaintType paint = tile.getPaint();
            // Strong positive for enemy paint
            if (paint == PaintType.ENEMY_PRIMARY || paint == PaintType.ENEMY_SECONDARY) {
                score += 10.0;
            }
            // Negative for ally paint
            else if (paint.isAlly()) {
                score -= 5.0;
            }
            // Minor bonus for empty
            else if (paint == PaintType.EMPTY) {
                score += 2.0;
            }

            // Additional bonus/penalty if part of a pattern
            if (tile.getMark() != PaintType.EMPTY && tile.getMark() != paint) {
                if (tile.getMark().isAlly()) {
                    score += 3.0;
                } else {
                    score += 5.0; // disrupt enemy pattern
                }
            }
        }

        // If we're low on paint, reduce the score
        if (rc.getPaint() < 20) {
            score *= 0.5;
        }
        return score;
    }

    // ------------------------------------------------------------------
    //               COMMON HELPERS: RUIN, EXPLORATION, ETC.
    // ------------------------------------------------------------------

    private static void paintCurrentTile(RobotController rc) throws GameActionException {
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        // If our tile isn't ally-painted and we can paint it, do so
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            boolean useSecondaryColor = (currentTile.getMark() == PaintType.ALLY_SECONDARY);
            rc.attack(rc.getLocation(), useSecondaryColor);
        }
    }

    /**
     * Searches for a nearby ruin that hasn't been fully explored or claimed.
     */
    private static MapLocation findNearestUnexploredRuin(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation nearestRuin = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            MapLocation tileLoc = tile.getMapLocation();
            // We skip if it's known to be a tower or already explored
            if (tile.hasRuin() && !knownTowers.contains(tileLoc)) {
                int dist = rc.getLocation().distanceSquaredTo(tileLoc);
                if (dist < minDist) {
                    nearestRuin = tileLoc;
                    minDist = dist;
                }
            }
        }
        return nearestRuin;
    }

    /**
     * This function attempts a BFS-like exploration by dividing the map into zones,
     * computing "paint density," and then moving toward less-painted areas.
     */
    private static void fill(RobotController rc, boolean stayOnPaint) throws GameActionException {
        int zoneSize = 4;
        MapLocation currentLoc = rc.getLocation();
        int currentZoneX = currentLoc.x / zoneSize;
        int currentZoneY = currentLoc.y / zoneSize;
        
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        double[][] zoneDensity = new double[3][3];
        
        // Compute paint density in adjacent zones
        for (MapInfo tile : tiles) {
            if (tile.getPaint() != PaintType.EMPTY) continue; 
            if (tile.hasRuin()) continue;  
            if (tile.isWall()) continue;
            
            MapLocation tileLoc = tile.getMapLocation();
            if (tileLoc.equals(currentLoc)) continue;
            
            int zoneX = (tileLoc.x / zoneSize) - currentZoneX + 1;
            int zoneY = (tileLoc.y / zoneSize) - currentZoneY + 1;
            
            if (zoneX >= 0 && zoneX < 3 && zoneY >= 0 && zoneY < 3) {
                if (tile.getPaint().isAlly()) {
                    zoneDensity[zoneX][zoneY] += 1.0;
                }
            }
        }
        
        Direction bestDir = null;
        double lowestDensity = Double.MAX_VALUE;
        
        for (Direction dir : directions) {
            if (!rc.canMove(dir)) {
                continue;
            }
            MapLocation newLoc = currentLoc.add(dir);
            int newZoneX = (newLoc.x / zoneSize) - currentZoneX + 1;
            int newZoneY = (newLoc.y / zoneSize) - currentZoneY + 1;
            
            if (newZoneX >= 0 && newZoneX < 3 && newZoneY >= 0 && newZoneY < 3) {
                double density = zoneDensity[newZoneX][newZoneY];
                
                // Add penalty for previously visited
                if (exploredTiles.contains(newLoc)) {
                    density += 2.0;
                }
                
                // If moving us closer to a ruin, reduce density
                MapLocation nearestRuin = findNearestUnexploredRuin(rc);
                if (nearestRuin != null) {
                    int currentDistToRuin = currentLoc.distanceSquaredTo(nearestRuin);
                    int newDistToRuin = newLoc.distanceSquaredTo(nearestRuin);
                    if (newDistToRuin < currentDistToRuin) {
                        density -= 1.0;
                    }
                }
                
                if (density < lowestDensity) {
                    lowestDensity = density;
                    bestDir = dir;
                }
            }
        }
        
        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
            exploredTiles.add(currentLoc.add(bestDir));
        } else {
            // fallback random movement
            Direction randomDir = directions[rng.nextInt(directions.length)];
            if (rc.canMove(randomDir)) {
                rc.move(randomDir);
            }
        }
    }

    /**
     * Finds the nearest unpainted tile around a given location,
     * factoring in distance, neighbor paint, etc.
     */
    private static MapLocation findNearestUnpaintedTile(RobotController rc, MapLocation loc, Integer maxDisplacement) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
//        class TileScore {
//            MapLocation location;
//            double score;
//            TileScore(MapLocation l, double s) {
//                location = l;
//                score = s;
//            }
//        }
        
        MapLocation bestTile = null;
        int bestDist = Integer.MAX_VALUE;
        if (maxDisplacement != null) bestDist = maxDisplacement;
        
        for (MapInfo tile : tiles) {
            if (tile.getPaint() != PaintType.EMPTY) continue;
            if (tile.hasRuin()) continue;
            if (tile.isWall()) continue;
            
            MapLocation tileLoc = tile.getMapLocation();
            if (tileLoc.equals(loc)) continue;
//            if (tileLoc.equals(rc.getLocation())) continue;

            int dist = loc.distanceSquaredTo(tileLoc);
            
            // Penalty for tiles near painted areas
//            int paintedNeighbors = 0;
//            for (Direction dir : directions) {
//                MapLocation neighborLoc = tileLoc.add(dir);
//                if (rc.canSenseLocation(neighborLoc)) {
//                    MapInfo neighborTile = rc.senseMapInfo(neighborLoc);
//                    if (neighborTile.getPaint().isAlly()) {
//                        paintedNeighbors++;
//                    }
//                }
//            }
//            score += paintedNeighbors * 2;
//
//            // Extra penalty if already explored
//            if (exploredTiles.contains(tileLoc)) {
//                score += 5;
//            }
            
            if (dist < bestDist) {
                bestDist = dist;
                bestTile = tile.getMapLocation();
            }
        }
        
        return bestTile;
    }

    private static void sendMessengerToNotify(RobotController rc) throws GameActionException {
        // Original function from your script, might be redundant
        RobotInfo [] friendlyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : friendlyRobots) {
            if (robot.getType() == UnitType.MOPPER && 
                rc.canSendMessage(robot.getLocation(), SavingAction.SAVE_CHIPS.ordinal())) {
                rc.setIndicatorDot(robot.getLocation(), 0, 255, 0);
                rc.sendMessage(robot.getLocation(), SavingAction.SAVE_CHIPS.ordinal());
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    //             MAP SYMMETRY & ADVANCED SOLDIER BEHAVIOR
    // ------------------------------------------------------------------

    /**
     * Updates our guess of the map symmetry by checking known towers
     * and predicted symmetrical locations.
     */
    private static void updateMapSymmetry(RobotController rc) throws GameActionException {
        // If we already know the symmetry, just rebroadcast it occasionally
        if (currentSymmetry != MapSymmetry.UNKNOWN) {
            if (rc.getRoundNum() % 3 == 0) {
                RobotInfo[] nearbyTowers = rc.senseNearbyRobots(80, rc.getTeam());
                for (RobotInfo nearbyTower : nearbyTowers) {
                    if (nearbyTower.getType() == UnitType.LEVEL_ONE_PAINT_TOWER 
                        || nearbyTower.getType() == UnitType.LEVEL_ONE_MONEY_TOWER) {
                        if (rc.canSendMessage(nearbyTower.getLocation(), MessageType.SYMMETRY_FOUND.ordinal())) {
                            rc.sendMessage(nearbyTower.getLocation(), MessageType.SYMMETRY_FOUND.ordinal());
                            rc.sendMessage(nearbyTower.getLocation(), currentSymmetry.ordinal());
                        }
                    }
                }
            }
            return; 
        }

        // Only proceed if we haven't found a symmetry yet, and we discovered initial towers
        if (rc.getRoundNum() > 1000 || !initialTowersFound) {
            return;
        }

        // Check for symmetry among the initial towers
        boolean[] symmetryValid = new boolean[MapSymmetry.values().length];
        int[] symmetryConfirms = new int[MapSymmetry.values().length];

        for (MapLocation towerLoc : initialTowers) {
            for (MapSymmetry sym : MapSymmetry.values()) {
                if (sym == MapSymmetry.UNKNOWN) continue;
                MapLocation predicted = getPredictedLocation(towerLoc, sym, rc);
                if (predicted == null) continue;

                if (rc.canSenseLocation(predicted)) {
                    boolean hasEnemy = isEnemyTowerOrRuin(rc, predicted);
                    if (hasEnemy) {
                        symmetryValid[sym.ordinal()] = true;
                        symmetryConfirms[sym.ordinal()]++;
                    }
                }
            }
        }

        // Check which symmetry was confirmed by both towers
        for (MapSymmetry sym : MapSymmetry.values()) {
            if (sym != MapSymmetry.UNKNOWN && symmetryConfirms[sym.ordinal()] == 2) {
                currentSymmetry = sym;
                // Broadcast
                RobotInfo[] nearbyTowers = rc.senseNearbyRobots(80, rc.getTeam());
                for (RobotInfo nearbyTower : nearbyTowers) {
                    if (nearbyTower.getType() == UnitType.LEVEL_ONE_PAINT_TOWER ||
                        nearbyTower.getType() == UnitType.LEVEL_ONE_MONEY_TOWER) {
                        if (rc.canSendMessage(nearbyTower.getLocation(), MessageType.SYMMETRY_FOUND.ordinal())) {
                            rc.sendMessage(nearbyTower.getLocation(), MessageType.SYMMETRY_FOUND.ordinal());
                            rc.sendMessage(nearbyTower.getLocation(), currentSymmetry.ordinal());
                            break;
                        }
                    }
                }
                return;
            }
        }
    }

    /**
     * Check if a location has an enemy tower or ruin, used when verifying symmetry predictions.
     */
    private static boolean isEnemyTowerOrRuin(RobotController rc, MapLocation loc) throws GameActionException {
        if (!isValidMapLocation(loc, rc) || !rc.canSenseLocation(loc)) {
            return false;
        }

        // Check ruin
        MapInfo info = rc.senseMapInfo(loc);
        if (info.hasRuin()) {
            return true;
        }

        // Check enemy tower
        RobotInfo robot = rc.senseRobotAtLocation(loc);
        if (robot != null && robot.getTeam() != rc.getTeam() && robot.getType().isTowerType()) {
            return true;
        }
        return false;
    }

    /**
     * Returns a predicted symmetrical location for 'loc' given a symmetry type.
     */
    private static MapLocation getPredictedLocation(MapLocation loc, MapSymmetry sym, RobotController rc) {
        int width = rc.getMapWidth();
        int height = rc.getMapHeight();
        
        MapLocation predicted = null;
        switch (sym) {
            case HORIZONTAL:
                predicted = new MapLocation(loc.x, height - 1 - loc.y);
                break;
            case VERTICAL:
                predicted = new MapLocation(width - 1 - loc.x, loc.y);
                break;
            case ROTATIONAL:
                predicted = new MapLocation(width - 1 - loc.x, height - 1 - loc.y);
                break;
            case REFLECTIONAL:
                // Check diagonal reflection
                MapLocation diag1 = new MapLocation(height - 1 - loc.y, width - 1 - loc.x);
                MapLocation diag2 = new MapLocation(loc.y, loc.x);
                if (!isValidMapLocation(diag1, rc) && !isValidMapLocation(diag2, rc)) {
                    return null;
                }
                if (!isValidMapLocation(diag1, rc)) {
                    predicted = diag2;
                } else if (!isValidMapLocation(diag2, rc)) {
                    predicted = diag1;
                } else {
                    // Choose whichever is closer
                    predicted = (loc.distanceSquaredTo(diag1) < loc.distanceSquaredTo(diag2))
                                ? diag1 : diag2;
                }
                break;
            case UNKNOWN:
            default:
                return null;
        }
        
        return isValidMapLocation(predicted, rc) ? predicted : null;
    }

    /**
     * Checks if a location is within the valid map bounds.
     */
    private static boolean isValidMapLocation(MapLocation loc, RobotController rc) {
        if (loc == null) return false;
        int width = rc.getMapWidth();
        int height = rc.getMapHeight();
        return (loc.x >= 0 && loc.x < width && loc.y >= 0 && loc.y < height);
    }

    /**
     * If we know a predicted enemy tower location, approach it (but avoid going into tower range).
     */
    private static void moveTowardsEnemyTower(RobotController rc) throws GameActionException {
        ArrayList<MapLocation> enemyTowers = getPredictedEnemyTowers(rc);
        if (enemyTowers.isEmpty()) {
            expandAggressively(rc);
            return;
        }

        MapLocation myLoc = rc.getLocation();
        MapLocation nearestTower = null;
        int minDist = Integer.MAX_VALUE;

        for (MapLocation tower : enemyTowers) {
            int dist = myLoc.distanceSquaredTo(tower);
            if (dist < minDist) {
                minDist = dist;
                nearestTower = tower;
            }
        }

        if (nearestTower == null) return;

        if (minDist <= TOWER_VISION_RADIUS) {
            // circle around tower
            moveCircularlyAroundTarget(rc, nearestTower);
        } else {
            Direction dirToTower = myLoc.directionTo(nearestTower);
            MapLocation targetLoc = myLoc.add(dirToTower);
            if (targetLoc.distanceSquaredTo(nearestTower) <= TOWER_VISION_RADIUS) {
                // Try sidestepping
                Direction[] perpDirs = { dirToTower.rotateLeft(), dirToTower.rotateRight() };
                for (Direction d : perpDirs) {
                    if (rc.canMove(d)) {
                        rc.move(d);
                        return;
                    }
                }
            } else if (rc.canMove(dirToTower)) {
                rc.move(dirToTower);
            }
        }
    }

    /**
     * Moves around a target in a circular pattern, staying within tower range or avoiding direct approach.
     */
    private static void moveCircularlyAroundTarget(RobotController rc, MapLocation target) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        Direction dirToTarget = myLoc.directionTo(target);

        if (lastCircularDirection == null) {
            lastCircularDirection = dirToTarget.rotateLeft();
        }

        Direction[] tryDirs = {
            lastCircularDirection,
            lastCircularDirection.rotateLeft(),
            lastCircularDirection.rotateRight(),
            lastCircularDirection.rotateLeft().rotateLeft(),
            lastCircularDirection.rotateRight().rotateRight()
        };

        for (Direction dir : tryDirs) {
            MapLocation newLoc = myLoc.add(dir);
            if (rc.canMove(dir) && newLoc.distanceSquaredTo(target) <= TOWER_VISION_RADIUS) {
                rc.move(dir);
                lastCircularDirection = dir;
                return;
            }
        }
    }

    private static ArrayList<MapLocation> getPredictedEnemyTowers(RobotController rc) {
        ArrayList<MapLocation> enemyTowers = new ArrayList<>();
        if (currentSymmetry == MapSymmetry.UNKNOWN || initialTowers.isEmpty()) {
            return enemyTowers;
        }

        for (MapLocation tower : initialTowers) {
            MapLocation predicted = getPredictedLocation(tower, currentSymmetry, rc);
            if (predicted != null) {
                enemyTowers.add(predicted);
            }
        }
        return enemyTowers;
    }

    /**
     * Basic check: if no enemies are nearby, we can expand more freely.
     */
    private static boolean isSafeToExpand(RobotController rc) throws GameActionException {
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        return (nearbyEnemies.length == 0);
    }

    /**
     * Picks a direction to expand, factoring in previously visited spots, ally spacing, etc.
     */
    private static Direction getExpansionDirection(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        Direction[] dirs;
        if (lastExpansionDir != null) {
            dirs = new Direction[]{
                lastExpansionDir,
                lastExpansionDir.rotateLeft(),
                lastExpansionDir.rotateRight(),
                lastExpansionDir.rotateLeft().rotateLeft(),
                lastExpansionDir.rotateRight().rotateRight(),
                lastExpansionDir.opposite().rotateLeft(),
                lastExpansionDir.opposite().rotateRight(),
                lastExpansionDir.opposite()
            };
        } else {
            dirs = directions;
        }

        Direction bestDir = null;
        double bestScore = -1;

        for (Direction dir : dirs) {
            if (!rc.canMove(dir)) continue;
            MapLocation newLoc = myLoc.add(dir);
            if (visitedLocations.contains(newLoc)) continue;

            double score = 100.0;

            // Penalty for being close to allies
            for (RobotInfo ally : allies) {
                int distToAlly = newLoc.distanceSquaredTo(ally.getLocation());
                if (distToAlly < MIN_ROBOT_SPACING) {
                    score -= (MIN_ROBOT_SPACING - distToAlly);
                }
            }
            // Bonus if not visited
            if (!visitedLocations.contains(newLoc)) {
                score += 50;
            }

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }
        return bestDir;
    }

    /**
     * If safe, move in an expansion direction. 
     * Otherwise do nothing (or possibly fight).
     */
    private static void expandAggressively(RobotController rc) throws GameActionException {
        if (!isSafeToExpand(rc)) {
            return;
        }

        Direction expandDir = getExpansionDirection(rc);
        if (expandDir != null) {
            rc.move(expandDir);
            lastExpansionDir = expandDir;
            visitedLocations.add(rc.getLocation());

            // Limit memory
            if (visitedLocations.size() > 50) {
                Iterator<MapLocation> iter = visitedLocations.iterator();
                if (iter.hasNext()) {
                    iter.next();
                    iter.remove();
                }
            }
        }
    }

    /**
     * Predicts the enemy spawn based on our first tower's location + known symmetry.
     */
    private static MapLocation getPredictedEnemySpawn(RobotController rc) throws GameActionException {
        if (currentSymmetry == MapSymmetry.UNKNOWN || initialTowers.isEmpty()) {
            return null;
        }
        return getPredictedLocation(initialTowers.get(0), currentSymmetry, rc);
    }

    /**
     * Update known tower locations. Also tries to identify
     * our initial two towers (level1 paint + money) in the first 100 rounds.
     */
    private static void updateTowerLocations(RobotController rc) throws GameActionException {
        // Try to find initial towers in first ~100 rounds
//        if (!initialTowersFound && rc.getRoundNum() <= 100) {
//            // Possibly add self if we're a tower
//            if ((rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER ||
//                 rc.getType() == UnitType.LEVEL_ONE_MONEY_TOWER) &&
//                !initialTowers.contains(rc.getLocation())) {
//                initialTowers.add(rc.getLocation());
//            }
//
//            // Check nearby allies
//            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
//            for (RobotInfo ally : allies) {
//                if (ally.getType() != UnitType.LEVEL_ONE_PAINT_TOWER
//                    && ally.getType() != UnitType.LEVEL_ONE_MONEY_TOWER) {
//                    continue;
//                }
//                MapLocation allyLoc = ally.getLocation();
//                if (!initialTowers.contains(allyLoc)) {
//                    initialTowers.add(allyLoc);
//                }
//            }
//            if (initialTowers.size() == 2) {
//                initialTowersFound = true;
//            }
//        }

        // Also track any new towers in knownTowers
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (!ally.getType().isTowerType()) {
                continue;
            }
            MapLocation allyLoc = ally.getLocation();
            if (!knownTowers.contains(allyLoc)) {
                knownTowers.add(allyLoc);
            }
        }
    }

}
