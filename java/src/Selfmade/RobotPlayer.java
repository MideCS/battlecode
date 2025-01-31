package Selfmade;

import battlecode.common.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Iterator;

/**
 * Optimized RobotPlayer with focused ruin targeting, controlled expansion,
 * and coordinated unit production.
 */
public class RobotPlayer {

    // Address Communication
    // Attacking enemy
    // Game State variables 
    static int turnCount = 0;
    static boolean isSaving = false;
    static int savingTurns = 0;
    static int savingCooldown = 0;
    static UnitType nextUnit = null;

    // Track issues related to saving
    private enum SavingAction {
        NONE, SAVE_CHIPS, UPGRADE_TOWER
    }
    static SavingAction lastSavingAction = SavingAction.NONE;

    // Track map symmetry
    private enum MapSymmetry {
        HORIZONTAL,
        VERTICAL,
        ROTATIONAL,
        REFLECTIONAL,
        UNKNOWN
    }

    // Message types for robot communication
    private enum MessageType {
        SYMMETRY_FOUND,
        ENEMY_TOWER_SPOTTED,
        SAVE_CHIPS,
        UPGRADE_TOWER,
        RUIN_NEEDS_CLEANING,
        NEED_PAINT
    }

    static MapSymmetry currentSymmetry = MapSymmetry.UNKNOWN;
    static HashSet<MapLocation> confirmedEnemyRuins = new HashSet<>();
    static MapLocation symmetryTargetRuin = null;  // The tower we're currently trying to verify
    static MapLocation currentPredictedRuin = null;  // The predicted symmetric location we're checking
    static MapSymmetry currentCheckingSymmetry = null;  // Which symmetry type we're currently checking
    static boolean initialTowersFound = false;  // Whether we've found our initial towers
    static ArrayList<MapLocation> initialTowers = new ArrayList<>();  // List of our initial towers
    static boolean[] possibleSymmetries = new boolean[MapSymmetry.values().length];  // Track which symmetries are still possible
    static int confirmationsNeeded = 2;  // How many confirmations needed before accepting a symmetry
    static HashMap<MapSymmetry, Integer> symmetryConfirmations = new HashMap<>();  // Track confirmations for each symmetry

    // The random seed and the stored directions
    static final Random rng = new Random(6147);
    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    // Tracking other important stuff
    static HashSet<MapLocation> exploredRuins = new HashSet<>();
    static HashSet<MapLocation> exploredTiles = new HashSet<>();
    static HashSet<MapLocation> knownTowers = new HashSet<>();

    static MapSymmetry lastPrintedSymmetry = MapSymmetry.UNKNOWN;

    private static final double TOWER_ATTACK_RADIUS = Math.sqrt(80);
    private static final int TOWER_VISION_RADIUS = 80;
    private static Direction lastCircularDirection = null;

    private static final int MIN_ROBOT_SPACING = 16; // Minimum squared distance between friendly robots
    private static Direction lastExpansionDir = null;
    private static HashSet<MapLocation> visitedLocations = new HashSet<>();

    // Track ruin completion progress
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
    static final int SAVING_THRESHOLD = 200; // Minimum chips needed to complete a tower
    static final int MIN_PAINT_FOR_TOWER = 50; // Minimum paint needed to help complete a tower

    // Track ruins that need cleaning
    static HashSet<MapLocation> ruinsNeedingCleanup = new HashSet<>();
    static MapLocation currentCleanupTarget = null;
    static int lastCleanupMessageRound = 0;

    private static final int BASE_EARLY_GAME_ROUNDS = 200;
    private static final int BASE_ALLY_WAIT_ROUNDS = 10;
    private static final int MIN_ALLIES_NEEDED = 2;
    private static HashMap<Integer, Integer> unitWaitingStartRounds = new HashMap<>();

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {

        while (true) {
            try {
                // Only print symmetry from level 1 paint tower
                if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER && 
                    (rc.getRoundNum() % 50 == 0 || lastPrintedSymmetry != currentSymmetry)) {
//                    System.out.println("[Round " + rc.getRoundNum() + "] Map Symmetry: " + currentSymmetry);
                    lastPrintedSymmetry = currentSymmetry;
                }

                switch (rc.getType()){
                    case SOLDIER: runSoldier(rc); break; 
                    case MOPPER: runMopper(rc); break;
                    case SPLASHER: runSplasher(rc); break; 
                    default: runTower(rc); break;
                }
            }
            catch (GameActionException e) {
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

    private static void manageResources(RobotController rc) throws GameActionException {
        // Check if we need to start saving
        if (!isSaving) {
            MapLocation nearestRuin = findNearestUnexploredRuin(rc);
            if (nearestRuin != null) {
                // Calculate progress on this ruin
                int paintedCount = 0;
                int totalCount = 0;
                MapInfo[] ruinTiles = rc.senseNearbyMapInfos(nearestRuin, 8);
                
                for (MapInfo tile : ruinTiles) {
                    if (tile.getMark() != PaintType.EMPTY) {
                        totalCount++;
                        if (tile.getPaint() == tile.getMark()) {
                            paintedCount++;
                        }
                    }
                }
                
                // Update progress tracking
                RuinProgress progress = ruinProgressMap.getOrDefault(nearestRuin, 
                    new RuinProgress(nearestRuin, paintedCount, totalCount));
                progress.paintedTiles = paintedCount;
                progress.lastUpdateRound = rc.getRoundNum();
                ruinProgressMap.put(nearestRuin, progress);
                
                // If ruin is close to completion (>50% painted) and we're low on resources
                if (paintedCount > totalCount/2 && rc.getChips() < SAVING_THRESHOLD) {
                    isSaving = true;
                    savingTurns = 20; // Save for 20 turns
                    lastSavingAction = SavingAction.SAVE_CHIPS;
                    
                    // Notify nearby units to help save
                    RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
                    for (RobotInfo ally : allies) {
                        if (rc.canSendMessage(ally.getLocation(), MessageType.SAVE_CHIPS.ordinal())) {
                            rc.sendMessage(ally.getLocation(), MessageType.SAVE_CHIPS.ordinal());
                            rc.sendMessage(ally.getLocation(), 20); // Save for 20 turns
                        }
                    }
                }
            }
        }
        
        // Handle ongoing saving state
        if (savingTurns > 0) {
            savingTurns--;
        } else if (savingTurns == 0) {
            // Check if we should continue saving
            if (rc.getChips() < SAVING_THRESHOLD) {
                savingTurns = 10; // Continue saving for a bit longer
            } else {
                isSaving = false;
                savingCooldown = 20;
                lastSavingAction = SavingAction.NONE;
            }
        }
        
        // Clean up old ruin progress entries
        int currentRound = rc.getRoundNum();
        ruinProgressMap.entrySet().removeIf(entry -> 
            currentRound - entry.getValue().lastUpdateRound > 50);
    }

    private static void processMessages(RobotController rc) throws GameActionException {
        Message[] messages = rc.readMessages(-1);  // Read messages
        for (int i = 0; i < messages.length; i++) {
            int messageType = messages[i].getBytes();
            if (messageType == MessageType.SYMMETRY_FOUND.ordinal()) {
                // Next message contains the symmetry type
                if (i + 1 < messages.length) {
                    currentSymmetry = MapSymmetry.values()[messages[i + 1].getBytes()];
                    i++; // Skip the next message since we used it
                }
            } else if (messageType == MessageType.ENEMY_TOWER_SPOTTED.ordinal()) {
                // Next two messages contain x and y coordinates
                if (i + 2 < messages.length) {
                    MapLocation enemyTower = new MapLocation(
                        messages[i + 1].getBytes(), 
                        messages[i + 2].getBytes()
                    );
                    confirmedEnemyRuins.add(enemyTower);
                    i += 2; // Skip the next two messages since we used them
                }
            } else if (messageType == MessageType.SAVE_CHIPS.ordinal()) {
                isSaving = true;
                if (i + 1 < messages.length) {
                    savingTurns = messages[i + 1].getBytes();
                    i++; // Skip the next message since we used it
                } else {
                    savingTurns = 10; // Default value if no turns specified
                }
            } else if (messageType == MessageType.RUIN_NEEDS_CLEANING.ordinal()) {
                // Next two messages contain x and y coordinates
                if (i + 2 < messages.length) {
                    MapLocation ruinLoc = new MapLocation(
                        messages[i + 1].getBytes(),
                        messages[i + 2].getBytes()
                    );
                    ruinsNeedingCleanup.add(ruinLoc);
                    
                    // If we're a mopper and don't have a current target, take this one
                    if (rc.getType() == UnitType.MOPPER && 
                        (currentCleanupTarget == null || 
                         rc.getLocation().distanceSquaredTo(ruinLoc) < rc.getLocation().distanceSquaredTo(currentCleanupTarget))) {
                        currentCleanupTarget = ruinLoc;
                    }
                    i += 2;
                }
            }
        }
    }

    private static void buildUnits(RobotController rc) throws GameActionException {
//        System.out.println("Building units - is Saving: " + isSaving);

        // TODO: Bots generate slowly. Below is brute force Soldier Spawning.
//        Direction dir = directions[rng.nextInt(directions.length)];
//        MapLocation loc = rc.getLocation().add(dir);
//        if (rc.canBuildRobot(UnitType.SOLDIER, loc)) {
//            rc.buildRobot(UnitType.SOLDIER, loc);
//        }

        if (isSaving) {
            return; // Do not produce units while saving
        }

        // Build two robots in random directions
        Direction dir1 = directions[rng.nextInt(directions.length)];
        Direction dir2 = directions[rng.nextInt(directions.length)];
        MapLocation buildLoc1 = rc.getLocation().add(dir1);
        MapLocation buildLoc2 = rc.getLocation().add(dir2);

        // Selects a random unit with a non-uniform distribution
        if (nextUnit == null) {
            int robotType = rng.nextInt(6);
            if (robotType == 0 || robotType == 1 || robotType == 2 || robotType == 3) {
                nextUnit = UnitType.SOLDIER;
            } else if (robotType == 4 || robotType == 5) {
                nextUnit = UnitType.MOPPER;
            } else {
                nextUnit = UnitType.SPLASHER;
            }
        }


        // Makes sure that selected unit is spawned, otherwise waits until next time it can spawn.
        if (rc.canBuildRobot(nextUnit, buildLoc1)) {
            rc.buildRobot(nextUnit, buildLoc1);
            nextUnit = null;
        }
        else if (rc.canBuildRobot(nextUnit, buildLoc2)) {
            rc.buildRobot(nextUnit, buildLoc2);
            nextUnit = null;
        }

        /* Richard wanted this to spawn 2 at once, but the previous implementation
        only spawned Moppers because they were cheapest and had the highest chance of passing canBuildRobot.
        This implementation fixes that, but does not spawn two at once. This would require a bit more reworking
        which we can do if needed.
         */

    }   

    public static void runTower(RobotController rc) throws GameActionException{
        manageResources(rc);
        processMessages(rc);
        buildUnits(rc);
    }


    private static void paintCurrentTile(RobotController rc) throws GameActionException {
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            rc.attack(rc.getLocation(), false); 
        }
    }

    private static MapLocation findNearestUnexploredRuin(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation nearestRuin = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            MapLocation tileLoc = tile.getMapLocation();
            if (tile.hasRuin() && !knownTowers.contains(tileLoc) && !exploredRuins.contains(tileLoc)) {
                int dist = rc.getLocation().distanceSquaredTo(tileLoc);
                if (dist < minDist) {
                    nearestRuin = tileLoc;
                    minDist = dist;
                }
            }
        }
        return nearestRuin;
    }

    // This function will run a more effective search for important tiles by restricting movement to previously unchecked tiles.
    private static void explore(RobotController rc, boolean stayOnPaint) throws GameActionException {
        // Divide map into zones (4x4 tiles) and track paint density
        int zoneSize = 4;
        MapLocation currentLoc = rc.getLocation();
        int currentZoneX = currentLoc.x / zoneSize;
        int currentZoneY = currentLoc.y / zoneSize;
        
        // Get nearby tiles and calculate paint density in each adjacent zone
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        double[][] zoneDensity = new double[3][3]; // 3x3 grid of adjacent zones
        
        for (MapInfo tile : tiles) {
            // Skip tiles we shouldn't explore
            if (tile.getPaint() != PaintType.EMPTY) continue;
            if (tile.hasRuin()) continue;
            if (tile.isWall()) continue;
            
            MapLocation tileLoc = tile.getMapLocation();
            if (tileLoc.equals(currentLoc)) continue;
            
            // Calculate zone coordinates
            int zoneX = (tileLoc.x / zoneSize) - currentZoneX + 1;
            int zoneY = (tileLoc.y / zoneSize) - currentZoneY + 1;
            
            if (zoneX >= 0 && zoneX < 3 && zoneY >= 0 && zoneY < 3) {
                if (tile.getPaint().isAlly()) {
                    zoneDensity[zoneX][zoneY] += 1.0;
                }
            }
        }
        
        // Find direction with lowest paint density
        Direction bestDir = null;
        double lowestDensity = Double.MAX_VALUE;
        
        for (Direction dir : directions) {
            MapLocation newLoc = currentLoc.add(dir);
            if (!rc.canMove(dir) || !isValidMapLocation(newLoc, rc)) continue;
            
            int newZoneX = (newLoc.x / zoneSize) - currentZoneX + 1;
            int newZoneY = (newLoc.y / zoneSize) - currentZoneY + 1;
            
            if (newZoneX >= 0 && newZoneX < 3 && newZoneY >= 0 && newZoneY < 3) {
                double density = zoneDensity[newZoneX][newZoneY];
                
                // Add penalty for previously visited locations
                if (exploredTiles.contains(newLoc)) {
                    density += 2.0;
                }
                
                // Add bonus for moving towards unpainted ruins
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
        
        // Move in the best direction, or random if no good direction found
        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
            exploredTiles.add(currentLoc.add(bestDir));
        } else {
            // Fallback to random movement if stuck
            Direction randomDir = directions[rng.nextInt(directions.length)];
            if (rc.canMove(randomDir)) {
                rc.move(randomDir);
            }
        }
    }

    // Finds the nearest unpainted tile to a given location.
    private static MapLocation findNearestUnpaintedTile(RobotController rc, MapLocation loc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();

        // Weight system for tile scoring
        class TileScore {
            MapLocation location;
            double score;
            
            TileScore(MapLocation loc, double s) {
                location = loc;
                score = s;
            }
        }
        
        TileScore bestTile = null;
        double bestScore = Double.MAX_VALUE;
        
        for (MapInfo tile : tiles) {
            if (tile.getPaint() != PaintType.EMPTY) continue;
            if (tile.hasRuin()) continue;
            if (tile.isWall()) continue;
            
            MapLocation tileLoc = tile.getMapLocation();
            if (tileLoc.equals(loc)) continue;
            
            double score = loc.distanceSquaredTo(tileLoc);
            
            // Add penalty for tiles near painted areas
            int paintedNeighbors = 0;
            for (Direction dir : directions) {
                MapLocation neighborLoc = tileLoc.add(dir);
                if (rc.canSenseLocation(neighborLoc)) {
                    MapInfo neighborTile = rc.senseMapInfo(neighborLoc);
                    if (neighborTile.getPaint().isAlly()) {
                        paintedNeighbors++;
                    }
                }
            }
            score += paintedNeighbors * 2;
            
            // Prefer tiles that haven't been explored
            if (exploredTiles.contains(tileLoc)) {
                score += 5;
            }
            
            if (score < bestScore) {
                bestScore = score;
                bestTile = new TileScore(tileLoc, score);
            }
        }
        
        return bestTile != null ? bestTile.location : loc;
    }

    // This function will run a more effective search for important tiles by restricting movement to previously unchecked tiles.
    private static void sendMessengerToNotify(RobotController rc) throws GameActionException {
        RobotInfo [] friendlyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : friendlyRobots) {
            if (robot.getType() == UnitType.MOPPER && rc.canSendMessage(robot.getLocation(), SavingAction.SAVE_CHIPS.ordinal())) {
                rc.setIndicatorDot(robot.getLocation(), 0, 255, 0); // Only going to send one Mopper back,
                rc.sendMessage(robot.getLocation(), SavingAction.SAVE_CHIPS.ordinal());
                break;
            }
        }
    }

    // the build ruin thing could definitely be modularized a lot more.
    public static void runSoldier(RobotController rc) throws GameActionException {
        // Try to paint current tile if needed
        paintCurrentTile(rc);
        
        // Attack any nearby enemies first
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            MapLocation enemyLoc = enemies[0].getLocation();
            if (rc.canAttack(enemyLoc)) {
                rc.attack(enemyLoc);
            }
        }

        // Determine map size strategy
        boolean isSmallMap = rc.getMapWidth() <= 20 && rc.getMapHeight() <= 20;

        if (isSmallMap && currentSymmetry != MapSymmetry.UNKNOWN && rc.getRoundNum() < 200) {
            // Small map rush strategy
            MapLocation enemySpawn = getPredictedEnemySpawn(rc);
            if (enemySpawn != null) {
                RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
                int nearbySoldiers = 0;
                for (RobotInfo ally : allies) {
                    if (ally.getType() == UnitType.SOLDIER) {
                        nearbySoldiers++;
                    }
                }
                
                if (nearbySoldiers >= 2 || rc.getLocation().distanceSquaredTo(enemySpawn) < 20) {
                    Direction dir = rc.getLocation().directionTo(enemySpawn);
                    if (rc.canMove(dir)) {
                        rc.move(dir);
                        rc.setIndicatorString("Small map rush with " + nearbySoldiers + " allies");
                        return;
                    }
                } else {
                    moveCircularlyAroundTarget(rc, rc.getLocation());
                    rc.setIndicatorString("Small map: Gathering allies");
                    return;
                }
            }
        } else {
            // Regular strategy for larger maps
            updateTowerLocations(rc);
            updateMapSymmetry(rc);

            if (currentSymmetry != MapSymmetry.UNKNOWN) {
                moveTowardsEnemyTower(rc);
            } else {
                expandAggressively(rc);
            }
        }

        // Handle ruins and exploration
        MapLocation curRuin = findNearestUnexploredRuin(rc);
        if (curRuin != null) {
            MapLocation unpaintedTile = findNearestUnpaintedTile(rc, curRuin);
            Direction dir = rc.getLocation().directionTo(unpaintedTile);
            if (rc.canMove(dir)) {
                rc.move(dir);
            }

            // Handle tower pattern marking and completion
            MapLocation shouldBeMarked = new MapLocation(unpaintedTile.x - dir.dx, unpaintedTile.y - dir.dy);
            MapInfo markTile = rc.senseMapInfo(shouldBeMarked);
            if (markTile.getMark() == PaintType.EMPTY && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin)) {
                rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin);
            }

            // Fill pattern and complete tower
            MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(curRuin, 8);
            for (MapInfo patternTile : nearbyTiles) {
                if (patternTile.getMark() != patternTile.getPaint() && patternTile.getMark() != PaintType.EMPTY) {
                    boolean useSecondaryColor = patternTile.getMark() == PaintType.ALLY_SECONDARY;
                    if (rc.canAttack(patternTile.getMapLocation())) {
                        rc.attack(patternTile.getMapLocation(), useSecondaryColor);
                    }
                }
            }

            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin);
                exploredRuins.add(curRuin);
                knownTowers.add(curRuin);
            }
        } else {
            explore(rc, false);
        }
    }

    /**
     * Gets the enemy location based on current symmetry guess
     * @param loc Our location
     * @param rc The RobotController
     * @return The predicted enemy location
     */
    private static MapLocation getEnemyLocation(MapLocation loc, RobotController rc) {
        int width = rc.getMapWidth();
        int height = rc.getMapHeight();
        
        MapLocation predicted = null;
        switch (currentSymmetry) {
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
                // For reflectional symmetry, we check both diagonal reflections and use the closer one
                MapLocation diag1 = new MapLocation(height - 1 - loc.y, width - 1 - loc.x); // Main diagonal
                MapLocation diag2 = new MapLocation(loc.y, loc.x); // Anti-diagonal
                // Validate both diagonal locations before choosing
                boolean diag1Valid = isValidMapLocation(diag1, rc);
                boolean diag2Valid = isValidMapLocation(diag2, rc);
                if (!diag1Valid && !diag2Valid) return null;
                if (!diag1Valid) return diag2;
                if (!diag2Valid) return diag1;
                predicted = (loc.distanceSquaredTo(diag1) < loc.distanceSquaredTo(diag2)) ? diag1 : diag2;
                break;
            case UNKNOWN:
            default:
                return null;
        }
        
        return isValidMapLocation(predicted, rc) ? predicted : null;
    }

    /**
     * Updates our guess of the map symmetry based on observed towers
     * @param rc The RobotController
     */
    private static void updateMapSymmetry(RobotController rc) throws GameActionException {
        if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
            System.out.println("\n[Round " + rc.getRoundNum() + "] === Symmetry Update ===");
            System.out.println("Current symmetry: " + currentSymmetry);
            System.out.println("Initial towers found: " + initialTowersFound);
            System.out.println("Initial towers count: " + initialTowers.size());
            System.out.println("Initial towers: " + initialTowers);
        }

        // Check for incoming symmetry messages first
        Message[] messages = rc.readMessages(-1);
        boolean receivedSymmetryMessage = false;
        
        for (int i = 0; i < messages.length; i++) {
            if (messages[i].getBytes() == MessageType.SYMMETRY_FOUND.ordinal()) {
                // Next message contains the symmetry type
                if (i + 1 < messages.length) {
                    MapSymmetry receivedSymmetry = MapSymmetry.values()[messages[i + 1].getBytes()];
                    if (receivedSymmetry != MapSymmetry.UNKNOWN) {
                        currentSymmetry = receivedSymmetry;
                        receivedSymmetryMessage = true;
                        if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                            System.out.println("[Round " + rc.getRoundNum() + "] Received symmetry message: " + currentSymmetry);
                        }
                    }
                    i++; // Skip the next message since we used it
                }
            }
        }

        // If we already know the symmetry, keep broadcasting it every few turns to prevent message expiration
        if (currentSymmetry != MapSymmetry.UNKNOWN) {
            if (rc.getRoundNum() % 3 == 0) {  // Broadcast every 3 turns to ensure message doesn't expire
                RobotInfo[] nearbyTowers = rc.senseNearbyRobots(80, rc.getTeam());
                for (RobotInfo nearbyTower : nearbyTowers) {
                    if (nearbyTower.getType() == UnitType.LEVEL_ONE_PAINT_TOWER || 
                        nearbyTower.getType() == UnitType.LEVEL_ONE_MONEY_TOWER) {
                        if (rc.canSendMessage(nearbyTower.getLocation(), MessageType.SYMMETRY_FOUND.ordinal())) {
                            rc.sendMessage(nearbyTower.getLocation(), MessageType.SYMMETRY_FOUND.ordinal());
                            rc.sendMessage(nearbyTower.getLocation(), currentSymmetry.ordinal());
                            if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                                System.out.println("[Round " + rc.getRoundNum() + "] Rebroadcasting symmetry: " + currentSymmetry);
                            }
                        }
                    }
                }
            }
            return;  // No need to check for symmetry if we already know it
        }

        // Only proceed with symmetry detection if we haven't received a message and don't know symmetry
        if (rc.getRoundNum() > 1000 || !initialTowersFound) {
            return;
        }
        
        // Store results for each symmetry type
        boolean[] symmetryValid = new boolean[MapSymmetry.values().length];
        int[] symmetryConfirms = new int[MapSymmetry.values().length];
        
        // Try to verify symmetry using both initial towers
        for (MapLocation towerLoc : initialTowers) {
            if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                System.out.println("\nChecking tower at: " + towerLoc);
            }
            
            for (MapSymmetry sym : MapSymmetry.values()) {
                if (sym == MapSymmetry.UNKNOWN) continue;

                MapLocation predicted = getPredictedLocation(towerLoc, sym, rc);
                if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                    System.out.println("\nTesting " + sym + ":");
                    System.out.println("Original: " + towerLoc);
                    System.out.println("Predicted: " + predicted);
                }
                
                if (predicted == null) {
                    if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                        System.out.println("Invalid prediction");
                    }
                    continue;
                }

                if (rc.canSenseLocation(predicted)) {
                    boolean hasEnemy = isEnemyTowerOrRuin(rc, predicted);
                    if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                        System.out.println("Can sense location, has enemy: " + hasEnemy);
                    }
                    
                    if (hasEnemy) {
                        symmetryValid[sym.ordinal()] = true;
                        symmetryConfirms[sym.ordinal()]++;
                        if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                            System.out.println("Confirmed " + sym + " symmetry! Confirms: " + symmetryConfirms[sym.ordinal()]);
                        }
                    }
                } else if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                    System.out.println("Cannot sense predicted location");
                }
            }
        }
        
        // Check which symmetry type was confirmed by both towers
        for (MapSymmetry sym : MapSymmetry.values()) {
            if (sym != MapSymmetry.UNKNOWN && symmetryConfirms[sym.ordinal()] == 2) {
                currentSymmetry = sym;
                if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                    System.out.println("Found confirmed symmetry: " + currentSymmetry);
                }
                
                // Try to relay through nearby towers
                RobotInfo[] nearbyTowers = rc.senseNearbyRobots(80, rc.getTeam());
                for (RobotInfo nearbyTower : nearbyTowers) {
                    if (nearbyTower.getType() == UnitType.LEVEL_ONE_PAINT_TOWER || 
                        nearbyTower.getType() == UnitType.LEVEL_ONE_MONEY_TOWER) {
                        // Send two messages: type and symmetry
                        if (rc.canSendMessage(nearbyTower.getLocation(), MessageType.SYMMETRY_FOUND.ordinal())) {
                            rc.sendMessage(nearbyTower.getLocation(), MessageType.SYMMETRY_FOUND.ordinal());
                            rc.sendMessage(nearbyTower.getLocation(), currentSymmetry.ordinal());
                            if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                                System.out.println("Sent symmetry messages: " + MessageType.SYMMETRY_FOUND.ordinal() + ", " + currentSymmetry.ordinal());
                            }
                            break;  // Only need to send to one tower
                        }
                    }
                }
                return;
            }
        }
    }

    private static boolean isEnemyTowerOrRuin(RobotController rc, MapLocation loc) throws GameActionException {
        if (!isValidMapLocation(loc, rc) || !rc.canSenseLocation(loc)) {
            if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                System.out.println("Cannot sense location: " + loc);
            }
            return false;
        }
        
        // Check for ruins
        MapInfo info = rc.senseMapInfo(loc);
        if (info.hasRuin()) {
            if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                System.out.println("Found ruin at " + loc);
            }
            return true;
        }
        
        // Check for enemy towers
        RobotInfo robot = rc.senseRobotAtLocation(loc);
        if (robot != null && robot.getTeam() != rc.getTeam() && robot.getType().isTowerType()) {
            if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                System.out.println("Found enemy tower at " + loc);
            }
            return true;
        }
        
        if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
            System.out.println("No enemy tower or ruin at " + loc);
        }
        return false;
    }

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
                // For reflectional, pick the closer diagonal to check
                MapLocation diag1 = new MapLocation(height - 1 - loc.y, width - 1 - loc.x);
                MapLocation diag2 = new MapLocation(loc.y, loc.x);
                predicted = (loc.distanceSquaredTo(diag1) < loc.distanceSquaredTo(diag2)) ? diag1 : diag2;
                break;
            default:
                return null;
        }
                
        return isValidMapLocation(predicted, rc) ? predicted : null;
    }

    private static boolean isValidMapLocation(MapLocation loc, RobotController rc) {
        if (loc == null) return false;
        int width = rc.getMapWidth();
        int height = rc.getMapHeight();
        return loc.x >= 0 && loc.x < width && loc.y >= 0 && loc.y < height;
    }

    private static void updateTowerLocations(RobotController rc) throws GameActionException {
        // Try to find initial towers in first 100 rounds
        if (!initialTowersFound && rc.getRoundNum() <= 100) {
            if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                System.out.println("\n=== Tower Search ===");
                System.out.println("Round: " + rc.getRoundNum());
                System.out.println("Current towers: " + initialTowers);
            }

            // First check if this robot itself is a level 1 tower
            if ((rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER || 
                 rc.getType() == UnitType.LEVEL_ONE_MONEY_TOWER) &&
                !initialTowers.contains(rc.getLocation())) {
                initialTowers.add(rc.getLocation());
                if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                    System.out.println("Added self as initial tower at " + rc.getLocation());
                }
            }

            // Then check nearby allies
            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : allies) {
                if (ally.getType() != UnitType.LEVEL_ONE_PAINT_TOWER && 
                    ally.getType() != UnitType.LEVEL_ONE_MONEY_TOWER) continue;

                MapLocation allyLoc = ally.getLocation();
                if (!initialTowers.contains(allyLoc)) {
                    initialTowers.add(allyLoc);
                    if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                        System.out.println("Found initial " + ally.getType() + " at " + allyLoc);
                    }
                }
            }
            
            // If we found exactly two towers (one paint, one money), mark that we've found initial towers
            if (initialTowers.size() == 2) {
                initialTowersFound = true;
                if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER) {
                    System.out.println("Found both initial towers!");
                }
            }
        }

        // Update known towers (for ongoing tracking)
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (!ally.getType().isTowerType()) continue;

            MapLocation allyLoc = ally.getLocation();
            if (!knownTowers.contains(allyLoc)) {
                knownTowers.add(allyLoc);
            }
        }
    }

    /**
     * Updates tracking of enemy-painted tiles in ruins and signals for cleanup if needed
     */
    static void updateEnemyPaintedTiles(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        if (rc.canSenseLocation(ruinLoc)) {
            MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(ruinLoc, 4);
            int enemyPaintCount = 0;
            
            for (MapInfo info : nearbyTiles) {
                if (info.hasRuin() && (info.getPaint() == PaintType.ENEMY_PRIMARY || info.getPaint() == PaintType.ENEMY_SECONDARY)) {
                    enemyPaintCount++;
                }
            }
            
            // If significant enemy paint is found, signal for cleanup
            if (enemyPaintCount >= 3 && rc.getRoundNum() - lastCleanupMessageRound > 50) {
                // Try to send message to nearby towers
                RobotInfo[] nearbyTowers = rc.senseNearbyRobots(-1, rc.getTeam());
                for (RobotInfo tower : nearbyTowers) {
                    if (tower.getType().isTowerType() && rc.canSendMessage(tower.getLocation(), MessageType.RUIN_NEEDS_CLEANING.ordinal())) {
                        rc.sendMessage(tower.getLocation(), MessageType.RUIN_NEEDS_CLEANING.ordinal());
                        rc.sendMessage(tower.getLocation(), ruinLoc.x);
                        rc.sendMessage(tower.getLocation(), ruinLoc.y);
                        lastCleanupMessageRound = rc.getRoundNum();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Smart paint removal for moppers
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
            
            // Check up to 3 tiles in this direction
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
        
        // Decide whether to mop swing or attack
        if (maxPaintedTiles >= 2 && bestSwingDir != null && rc.canMopSwing(bestSwingDir)) {
            rc.mopSwing(bestSwingDir);
        } else {
            // Find single tile with enemy paint to attack
            for (MapInfo info : nearbyTiles) {
                if ((info.getPaint() == PaintType.ENEMY_PRIMARY || info.getPaint() == PaintType.ENEMY_SECONDARY) 
                    && rc.canAttack(info.getMapLocation())) {
                    rc.attack(info.getMapLocation());
                    break;
                }
            }
        }
    }

    /**
     * Enhanced mopper behavior
     */
    static void runMopper(RobotController rc) throws GameActionException {
        // Try to paint our current tile if needed
        paintCurrentTile(rc);
        
        // If we have a cleanup target, move towards it
        if (currentCleanupTarget != null) {
            if (rc.getLocation().isAdjacentTo(currentCleanupTarget)) {
                handlePaintRemoval(rc);
            } else {
                // Move towards target
                Direction dir = rc.getLocation().directionTo(currentCleanupTarget);
                if (rc.canMove(dir)) {
                    rc.move(dir);
                } else {
                    // Try to move around obstacles
                    for (Direction altDir : directions) {
                        if (rc.canMove(altDir) && 
                            rc.getLocation().add(altDir).distanceSquaredTo(currentCleanupTarget) < 
                            rc.getLocation().distanceSquaredTo(currentCleanupTarget)) {
                            rc.move(altDir);
                            break;
                        }
                    }
                }
            }
        } else {
            // No specific target, explore and clean
            explore(rc, false);
            handlePaintRemoval(rc);
        }
    }

    /**
     * Find the nearest enemy ruin based on current symmetry guess
     * @param rc The RobotController
     * @return The location of the nearest predicted enemy ruin
     */
    private static MapLocation findNearestEnemyRuin(RobotController rc) throws GameActionException {
        if (currentSymmetry == MapSymmetry.UNKNOWN) {
            return null; // Don't try to predict enemy ruins if symmetry is unknown
        }

        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        MapLocation nearestEnemyRuin = null;
        int minDist = Integer.MAX_VALUE;

        for (MapLocation ruin : ruins) {
            if (!exploredRuins.contains(ruin)) {
                MapLocation predictedEnemy = getEnemyLocation(ruin, rc);
                if (predictedEnemy != null && !confirmedEnemyRuins.contains(predictedEnemy)) {
                    int dist = rc.getLocation().distanceSquaredTo(predictedEnemy);
                    if (dist < minDist) {
                        nearestEnemyRuin = predictedEnemy;
                        minDist = dist;
                    }
                }
            }
        }
        return nearestEnemyRuin;
    }

    public static void runSplasher(RobotController rc) throws GameActionException {
        // Try to paint beneath us as we walk
        paintCurrentTile(rc);
        
        // Get nearby tiles we could potentially attack
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapLocation bestTarget = null;
        double bestScore = 0;  // Only attack if score is positive
        
        // Evaluate each potential target
        for (MapInfo tile : nearbyTiles) {
            MapLocation tileLoc = tile.getMapLocation();
            double score = evaluateSplashTarget(rc, tileLoc);
            
            if (score > bestScore) {
                bestScore = score;
                bestTarget = tileLoc;
            }
        }
        
        // Attack if we found a good target
        if (bestTarget != null && bestScore > 5.0) {  // Threshold for attacking
            if (rc.canAttack(bestTarget)) {
                rc.attack(bestTarget);
            }
        }
        
        // Movement logic
        Direction moveDir = null;
        
        // If we're low on paint, retreat to friendly territory
        if (rc.getPaint() < 10) {
            // Find nearest ally paint
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
                moveDir = rc.getLocation().directionTo(nearestAllyPaint);
            }
        } 
        // Otherwise, move towards enemy paint or unpainted areas
        else {
            // Use existing explore function with stayOnPaint=false
            explore(rc, false);
            return;
        }
        
        // Execute movement
        if (moveDir != null && rc.canMove(moveDir)) {
            rc.move(moveDir);
        }
    }

    private static double evaluateSplashTarget(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.canAttack(target)) return -1000;  // Invalid target
        
        double score = 0;
        MapInfo[] affectedTiles = rc.senseNearbyMapInfos(target, 1);  // Get splash radius
        
        for (MapInfo tile : affectedTiles) {
            PaintType paint = tile.getPaint();
            
            // Strong positive score for enemy paint we can cover
            if (paint == PaintType.ENEMY_PRIMARY || paint == PaintType.ENEMY_SECONDARY) {
                score += 10.0;
            }
            // Negative score for ally paint we might disrupt
            else if (paint.isAlly()) {
                score -= 5.0;
            }
            // Small positive score for empty tiles
            else if (paint == PaintType.EMPTY) {
                score += 2.0;
            }
            
            // Add bonus for tiles that are part of a pattern
            if (tile.getMark() != PaintType.EMPTY && tile.getMark() != paint) {
                if (tile.getMark().isAlly()) {
                    score += 3.0;  // Bonus for completing our pattern
                } else {
                    score += 5.0;  // Extra bonus for disrupting enemy pattern
                }
            }
        }
        
        // Penalize for low paint
        if (rc.getPaint() < 20) {
            score *= 0.5;  // Reduce score if we're low on paint
        }
        
        return score;
    }

    private static Direction getNearbyEnemiesDir(RobotController rc, int radius) throws GameActionException {
         RobotInfo[] enemyRobots = rc.senseNearbyRobots(radius, rc.getTeam().opponent());

         if (enemyRobots.length > 0) {
             // Attack the first enemy in the list
             RobotInfo targetEnemy = enemyRobots[0];
             return rc.getLocation().directionTo(targetEnemy.getLocation());
         }
         return null;
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

    private static void moveTowardsEnemyTower(RobotController rc) throws GameActionException {
        ArrayList<MapLocation> enemyTowers = getPredictedEnemyTowers(rc);
        if (enemyTowers.isEmpty()) {
            expandAggressively(rc);  // If no known enemy towers, just expand
            return;
        }

        // Find nearest enemy tower
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

        // If we're within tower's attack range, move circularly
        if (minDist <= TOWER_VISION_RADIUS) {
            moveCircularlyAroundTarget(rc, nearestTower);
        } else {
            // Move towards tower while staying outside its range
            Direction dirToTower = myLoc.directionTo(nearestTower);
            MapLocation targetLoc = myLoc.add(dirToTower);
            
            // If moving would put us in tower's range, find a better spot
            if (targetLoc.distanceSquaredTo(nearestTower) <= TOWER_VISION_RADIUS) {
                // Try to move perpendicular to the tower
                Direction[] perpDirs = {dirToTower.rotateLeft(), dirToTower.rotateRight()};
                for (Direction dir : perpDirs) {
                    if (rc.canMove(dir)) {
                        rc.move(dir);
                        return;
                    }
                }
            } else if (rc.canMove(dirToTower)) {
                rc.move(dirToTower);
            }
        }
    }

    private static void moveCircularlyAroundTarget(RobotController rc, MapLocation target) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        Direction dirToTarget = myLoc.directionTo(target);
        
        // Initialize circular direction if not set
        if (lastCircularDirection == null) {
            lastCircularDirection = dirToTarget.rotateLeft();
        }
        
        // Try to move perpendicular to the direction to the target
        Direction[] tryDirs = {
            lastCircularDirection,
            lastCircularDirection.rotateLeft(),
            lastCircularDirection.rotateRight(),
            lastCircularDirection.rotateLeft().rotateLeft(),
            lastCircularDirection.rotateRight().rotateRight()
        };
        
        for (Direction dir : tryDirs) {
            MapLocation newLoc = myLoc.add(dir);
            // Check if new location maintains good distance from tower
            if (rc.canMove(dir) && newLoc.distanceSquaredTo(target) <= TOWER_VISION_RADIUS) {
                rc.move(dir);
                lastCircularDirection = dir;  // Remember the successful direction
                return;
            }
        }
    }

    private static boolean isSafeToExpand(RobotController rc) throws GameActionException {
        // Check for enemies in vision radius
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        return nearbyEnemies.length == 0;
    }

    private static Direction getExpansionDirection(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        
        // Get nearby allies to maintain spacing
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        
        // Try all directions, prioritizing unexplored areas
        Direction[] dirs;
        if (lastExpansionDir != null) {
            // Prefer continuing in same general direction
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
            dirs = Direction.values();
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
            
            // Bonus for unexplored directions
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

    private static void expandAggressively(RobotController rc) throws GameActionException {
        // Only expand if no enemies are visible
        if (!isSafeToExpand(rc)) {
            return;
        }
        
        Direction expandDir = getExpansionDirection(rc);
        if (expandDir != null) {
            rc.move(expandDir);
            lastExpansionDir = expandDir;
            visitedLocations.add(rc.getLocation());
            
            // Limit visited locations memory
            if (visitedLocations.size() > 50) {
                Iterator<MapLocation> iter = visitedLocations.iterator();
                iter.next();
                iter.remove();
            }
        }
    }

    // Calculate thresholds based on map size
    private static int getScaledEarlyGameRounds(RobotController rc) {
        int mapSize = rc.getMapWidth() * rc.getMapHeight();
        return (int)(BASE_EARLY_GAME_ROUNDS * Math.sqrt(mapSize) / 40.0); // 40 is baseline for small maps
    }

    private static int getScaledAttackDistance(RobotController rc) {
        int mapSize = rc.getMapWidth() * rc.getMapHeight();
        return (int)(20 * Math.sqrt(mapSize) / 40.0);
    }

    private static int getRequiredAllies(RobotController rc, MapLocation target) {
        int distanceToTarget = rc.getLocation().distanceSquaredTo(target);
        // Require more allies for longer distances
        return Math.min(MIN_ALLIES_NEEDED + (distanceToTarget / 200), 4);
    }

    private static boolean shouldStopWaiting(RobotController rc, int unitID) {
        if (!unitWaitingStartRounds.containsKey(unitID)) {
            unitWaitingStartRounds.put(unitID, rc.getRoundNum());
            return false;
        }

        int waitingRounds = rc.getRoundNum() - unitWaitingStartRounds.get(unitID);
        int mapSize = rc.getMapWidth() * rc.getMapHeight();
        int maxWaitTime = (int)(BASE_ALLY_WAIT_ROUNDS * Math.sqrt(mapSize) / 40.0);
        
        return waitingRounds > maxWaitTime;
    }

    // Gets predicted enemy spawn location based on current symmetry and initial tower locations
    private static MapLocation getPredictedEnemySpawn(RobotController rc) throws GameActionException {
        if (currentSymmetry == MapSymmetry.UNKNOWN || initialTowers.isEmpty()) {
            return null;
        }
        
        // Use the first initial tower location as reference point for enemy spawn
        return getPredictedLocation(initialTowers.get(0), currentSymmetry, rc);
    }
}