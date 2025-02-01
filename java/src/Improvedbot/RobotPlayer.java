package Improvedbot;
import battlecode.common.*;

import java.util.*;

/**
 * Optimized RobotPlayer with focused ruin targeting, controlled expansion,
 * map symmetry analysis, and coordinated unit production.
 */

 public class RobotPlayer {
    // ------- GAME STATE & SAVING --------
    static MapLocation curRuin = null;
    static UnitType[] towers_to_build = new UnitType[]{UnitType.LEVEL_ONE_PAINT_TOWER, UnitType.LEVEL_ONE_MONEY_TOWER};
    static UnitType towerType = null;

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

    // -------------- TOWERS AND RUINS TRACKING ---------------------
    static HashSet<MapLocation> knownTowers = new HashSet<>();
    static HashSet<MapLocation> exploredTiles = new HashSet<>();
    static HashSet<MapLocation> confirmedEnemyRuins = new HashSet<>();

    // -------------- TOWER AND RUINS TRACKING --------------
    static MapSymmetry lastPrintedSymmetry = MapSymmetry.UNKNOWN;
    private static final double TOWER_ATTACK_RADIUS = Math.sqrt(80);
    private static final int TOWER_VISION_RADIUS = 80;
    private static Direction lastCircularDirection = null;

    private static final int MIN_ROBOT_SPACING = 16; // Minimum squared distance between friendly robots
    private static Direction lastExpansionDir = null;
    private static HashSet<MapLocation> visitedLocations = new HashSet<>();

    // ---------------- SYMMETRY DATA STRUCTURES --------------------
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

    // -------------- CLEANUP LOGIC FOR MOPPERS --------------
    static HashSet<MapLocation> ruinsNeedingCleanup = new HashSet<>();
    static MapLocation currentCleanupTarget = null;
    static int lastCleanupMessageRound = 0;

    // -------------- PREFERRED BUILD ORDER --------------
    // Weighted approach: 1/3 of each class
    private static final UnitType[] PREFERRED_BUILD_ORDER = {
        UnitType.SOLDIER,
        UnitType.MOPPER,
        UnitType.SPLASHER,
    };

    public static void run(RobotController rc) throws GameActionException {

        if (rc.getChips() > 3000 && rc.getType() == UnitType.SOLDIER && rc.getID() % 2 == 0) {
            isExplorer = true;

        }
        
        while (true) {
            try {
                // Print symmetry occasionally if we are a level 1 paint tower
                if (rc.getType() == UnitType.LEVEL_ONE_PAINT_TOWER && 
                    (rc.getRoundNum() % 50 == 0 || lastPrintedSymmetry != currentSymmetry)) {
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

    public static void runTower(RobotController rc) throws GameActionException {
        processMessages(rc);
        buildUnits(rc);
    }


    private static void processMessages(RobotController rc) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        for (int i = 0; i < messages.length; i++) {
            int type = messages[i].getBytes();
            if (type == MessageType.SYMMETRY_FOUND.ordinal() && ++i < messages.length) {
                currentSymmetry = MapSymmetry.values()[messages[i].getBytes()];
            } else if (type == MessageType.ENEMY_TOWER_SPOTTED.ordinal()) {
                handleLocationMessage(messages, i+1, confirmedEnemyRuins);
                i += 2;
            } 
            else if (type == MessageType.RUIN_NEEDS_CLEANING.ordinal()) {
                MapLocation loc = readLocation(messages, i+1);
                if (loc != null) {
                    ruinsNeedingCleanup.add(loc);
                    if (rc.getType() == UnitType.MOPPER) updateCleanupTarget(rc, loc);
                    i += 2;
                }
            }
        }
    }

    private static void handleLocationMessage(Message[] messages, int startIdx, HashSet<MapLocation> confirmedEnemyRuins) {
        if (startIdx + 1 < messages.length) {
            confirmedEnemyRuins.add(new MapLocation(
                messages[startIdx].getBytes(),
                messages[startIdx + 1].getBytes()
            ));
        }
    }
    
    private static MapLocation readLocation(Message[] messages, int idx) {
        return (idx+1 < messages.length) 
            ? new MapLocation(messages[idx].getBytes(), messages[idx+1].getBytes())
            : null;
    }
    
    private static void updateCleanupTarget(RobotController rc, MapLocation loc) {
        if (currentCleanupTarget == null || 
            rc.getLocation().distanceSquaredTo(loc) < 
            rc.getLocation().distanceSquaredTo(currentCleanupTarget)) {
            currentCleanupTarget = loc;
        }
    }

    private static void buildUnits(RobotController rc) throws GameActionException {

        // Seeing if I can use this to escape the need to save
        if (rc.getChips() < 2500) {
            return;
        }
        
        UnitType nextUnit = PREFERRED_BUILD_ORDER[rng.nextInt(PREFERRED_BUILD_ORDER.length)];

        // Makes sure that if we can't build the robot its not because of direction 
        ArrayList<Direction> shuffledDirs = new ArrayList<>();
        for (Direction d : directions) shuffledDirs.add(d);
        java.util.Collections.shuffle(shuffledDirs, rng);

        for (Direction dir : shuffledDirs) {
            MapLocation buildLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(nextUnit, buildLoc)) {
                rc.buildRobot(nextUnit, buildLoc);
                break;
            }
        }
    }

    // -----------------------------------------------------------------------------
    //                            SOLDIER LOGIC
    // -----------------------------------------------------------------------------

    public static void runSoldier(RobotController rc) throws GameActionException {
        paintCurrentTile(rc);
        processMessages(rc);
        updateMapSymmetry(rc);

        // Find the nearest ruin and build on it
        if (curRuin == null) {
            curRuin = findNearestUnexploredRuin(rc);
        } else {
            handleRuinBuilding(rc);
        }

        // If we are the explorer type, explore else fill in where we are
        if (isExplorer) {
            expandAggressively(rc);
        } else {
            fill(rc, false);
        }
        updateTowerLocations(rc);
    }

    public static void runMopper(RobotController rc) throws GameActionException {
        updateTowerLocations(rc);
        paintCurrentTile(rc);
        processMessages(rc);
        handleCleanupOrExplore(rc);
        handlePaintRemoval(rc);
    }

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



    // ------------------------------------------------------------------
    //               COMMON HELPERS: RUIN, EXPLORATION, ETC.
    // ------------------------------------------------------------------

    /**
     * Separated logic for approaching & completing a ruin tower build.
     */
    private static void handleRuinBuilding(RobotController rc) throws GameActionException {
        if (knownTowers.contains(curRuin)) {
            curRuin = null;
            System.out.println("Tower Already Explored at " + curRuin);
            return;
        }

        MapLocation unpaintedTile = findNearestUnpaintedTile(rc, curRuin, 18);

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

        // Pick random tower to build
        if (towerType == null) {towerType = towers_to_build[rng.nextInt(towers_to_build.length)];} 

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
        
        MapLocation bestTile = null;
        int bestDist = Integer.MAX_VALUE;
        if (maxDisplacement != null) bestDist = maxDisplacement;
        
        for (MapInfo tile : tiles) {
            if (tile.getPaint() != PaintType.EMPTY) continue;
            if (tile.hasRuin()) continue;
            if (tile.isWall()) continue;
            
            MapLocation tileLoc = tile.getMapLocation();
            if (tileLoc.equals(loc)) continue;
            int dist = loc.distanceSquaredTo(tileLoc);
            
            if (dist < bestDist) {
                bestDist = dist;
                bestTile = tile.getMapLocation();
            }
        }
        return bestTile;
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

