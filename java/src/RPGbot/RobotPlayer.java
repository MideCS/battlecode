package RPGbot;

import battlecode.common.*;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Random;

/**
 * The main class that controls the robot's behavior.
 */
public class RobotPlayer {
    static Random rng = new Random();
    static Direction[] directions = Direction.values();
    
    // Robot state management
    static enum RobotState {
        STARTING,
        EXPLORING,
        ATTACKING,
        PAINTING_PATTERN
    }
    
    static enum MessageType {
        SAVE_CHIPS
    }

    static enum MapSymmetry {
        HORIZONTAL,
        VERTICAL,
        ROTATIONAL,
        UNKNOWN
    }
    
    static RobotState state = RobotState.STARTING;
    static MapSymmetry currentSymmetry = MapSymmetry.UNKNOWN;
    static MapLocation targetEnemyRuin = null;
    static MapLocation paintingRuinLoc = null;
    static UnitType paintingTowerType = null;
    static int paintingTurns = 0;
    static int turnsWithoutAttack = 0;
    static int savingTurns = 0;

    // Store learned patterns and known ruins
    static HashMap<UnitType, boolean[][]> learnedPatterns = new HashMap<>();
    static ArrayList<MapLocation> knownRuins = new ArrayList<>();
    static int turnCount = 0;
    static int soldierCooldown = 0;
    static int mopperCooldown = 0;
    static boolean spawnedMopper = false;
    static HashMap<MapLocation, Integer> checked = new HashMap<>();
    static int checked_limit = 1;
    static boolean isSaving = false;
    static boolean isMessenger = false;
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();

    /**
     * Run a robot.
     *
     * @param rc The RobotController for the robot to run.
     */
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        // Hello world! Standard output is very useful for debugging.
        // Everything you say here will be directly viewable in your terminal when you run a match!
//        System.out.println("I'm alive");

        rc.setIndicatorString("Hello world!");

        // Delegate some soldiers as messengers
        if (rc.getType() == UnitType.SOLDIER && rc.getID() % 3 == 0) {
            isMessenger = true;
        }

        while (true) {

            turnCount += 1;  // We have now been alive for one more turn!

            try {
                switch (rc.getType()){
                    case SOLDIER: runSoldier(rc); break;
                    case MOPPER: runMopper(rc); break;
                    case SPLASHER: runSplasher(rc); break; // Consider upgrading examplefuncsplayer to use splashers!
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

    public static void runTower(RobotController rc) throws GameActionException{

        // TODO: Add saving for Tower when Ruin is reached.
        if (savingTurns == 0) {
            isSaving = false;
            Direction dir = directions[rng.nextInt(directions.length)];
            MapLocation nextLoc = rc.getLocation().add(dir);

            if (soldierCooldown < 30) {
                if (rc.canBuildRobot(UnitType.SOLDIER, nextLoc)) {
                    rc.buildRobot(UnitType.SOLDIER, nextLoc);
                    //                System.out.println("BUILT A SOLDIER");
                }
            } else {
                //            System.out.println("Soldier cooldown exceeded");
                //            robotType = rng.nextInt(1,3);
                if (rc.canBuildRobot(UnitType.MOPPER, nextLoc) && mopperCooldown <= 1) {
                    rc.buildRobot(UnitType.MOPPER, nextLoc);
                    System.out.println("BUILT A MOPPER");
                    mopperCooldown++;
                } else if (rc.canBuildRobot(UnitType.SPLASHER, nextLoc)) {
                    rc.buildRobot(UnitType.SPLASHER, nextLoc);
                    rc.setIndicatorString("BUILT A SPLASHER");
                    mopperCooldown = 0;
                }
            }

        }
        else {
            rc.setIndicatorString("Saving for " + savingTurns + " more turns!");
            savingTurns --;
        }
        soldierCooldown ++;
        soldierCooldown %= 40;

        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            System.out.println("Tower received message: '#" + m.getSenderID() + " " + m.getBytes());

            if (m.getBytes() == MessageType.SAVE_CHIPS.ordinal() && !isSaving) {
                // TODO: Broadcasts to other towers to start saving paint
//                rc.broadcastMessage(MessageType.SAVE_CHIPS.ordinal());

                savingTurns = 20;
                System.out.println("Tower received SAVE_CHIPS message. Starting to save.");
                isSaving = true;
            }

        }
        // Attack any neighboring robots
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy: enemyRobots) {
            if (rc.canAttack(enemy.location)) {
                rc.attack((enemy.location));
                break;
            }
        }
    }

    public static void runSoldier(RobotController rc) throws GameActionException {
        if (state == RobotState.STARTING) {
            if(rc.getID() % 3 == 1) {
                state = RobotState.ATTACKING;
            } else {
                state = RobotState.EXPLORING;
            }
        }

        // Try to paint beneath us as we walk to avoid paint penalties.
        // Avoiding wasting paint by re-painting our own tiles.
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())){
            rc.attack(rc.getLocation());
        }

        if (state == RobotState.PAINTING_PATTERN) {
            runPaintPattern(rc);
            paintingTurns++;
        }
        else if (state == RobotState.EXPLORING) {
            MapInfo closestRuin = getNearestRuin(rc);
            int closestRuinDist = getDistanceToNearestRuin(rc);

            if (closestRuin != null) {
                MapLocation targetLoc = closestRuin.getMapLocation();
                moveSoldier(rc, targetLoc);

                // Mark the pattern we need to draw to build a tower here if we haven't already.
                Direction dir = rc.getLocation().directionTo(targetLoc);
                MapLocation shouldBeMarked = targetLoc.subtract(dir);
                if (rc.senseMapInfo(shouldBeMarked).getMark() == PaintType.EMPTY && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)){
                    rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
                    System.out.println("Trying to build a tower at " + targetLoc);
                }

                // Fill in any spots in the pattern with the appropriate paint.
                for (MapInfo patternTile : rc.senseNearbyMapInfos(targetLoc, 8)){
                    if (patternTile.getMark() != patternTile.getPaint() && patternTile.getMark() != PaintType.EMPTY){
                        boolean useSecondaryColor = patternTile.getMark() == PaintType.ALLY_SECONDARY;
                        if (rc.canAttack(patternTile.getMapLocation()))
                            rc.attack(patternTile.getMapLocation(), useSecondaryColor);
                    }
                }

                // Complete the ruin if we can.
                if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)){
                    rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
                    rc.setTimelineMarker("Tower built", 0, 255, 0);
                    System.out.println("Built a tower at " + targetLoc + "!");
                }
            }
        } else if (state == RobotState.ATTACKING) {
            // If currently not tracking an enemy tower to attack, choose an enemy ruin to investigate
            if (targetEnemyRuin == null) {
                // assume vertical symmetry :TODO check for what type of symmetry it is
                MapLocation[] infos = rc.senseNearbyRuins(-1);

                if (infos.length > 0) {
                    MapLocation ruin = infos[0];
                    MapLocation enemy = getEnemyLocation(ruin, rc);
                    targetEnemyRuin = enemy;
                }
            }

            if (targetEnemyRuin != null) {
                int dsquared = rc.getLocation().distanceSquaredTo(targetEnemyRuin);

                // Attack radius of soldier is 8; STRAT: attack then move away from enemy tower
                if (dsquared <= 8) {
                    // Attack enemy
                    if (rc.canAttack(targetEnemyRuin)) {
                        rc.attack(targetEnemyRuin);
                    }

                    // Move away from enemy ruin
                    Direction away = rc.getLocation().directionTo(targetEnemyRuin).opposite();
                    if (rc.canMove(away)) {
                        rc.move(away);
                    } else if (rc.canMove(away.rotateLeft())) {
                        rc.move(away.rotateLeft());
                    } else if (rc.canMove(away.rotateRight())) {
                        rc.move(away.rotateRight());
                    }
                } else {
                    // Check if adjacent tiles are within attack radius of tower
                    for (Direction dir : directions) {
                        MapLocation adjacent = rc.getLocation().add(dir);
                        if (adjacent.isWithinDistanceSquared(targetEnemyRuin, 8)) {
                            if (rc.canMove(dir)) {
                                rc.move(dir);

                                if (rc.canAttack(targetEnemyRuin)) {
                                    rc.attack(targetEnemyRuin);
                                }

                                break;
                            }
                        }
                    }

                    rc.setIndicatorDot(targetEnemyRuin, 0, 0, 255);
                    rc.setIndicatorString("Moving to enemy ruin at " + targetEnemyRuin);
                }
            }
        }

        if (isMessenger) {
            rc.setIndicatorDot(rc.getLocation(), 0, 255, 0);
            updateFriendlyTowers(rc);
        }

        if (isMessenger && isSaving && knownTowers.size() > 0) {
            MapLocation dst = knownTowers.get(rng.nextInt(knownTowers.size()));
            Direction dir = rc.getLocation().directionTo(dst);
            if (rc.canMove(dir)) {
                rc.move(dir);
            }
        }
    }

    public static void runPaintPattern(RobotController rc) throws GameActionException {
        // Move in a circle around the ruin. Move every 3 turns after you have chance to paint some tiles
        if (paintingTurns % 3 == 0) {
            Direction toRuin = rc.getLocation().directionTo(paintingRuinLoc);
            Direction tangent = toRuin.rotateRight().rotateRight();
            int distance = rc.getLocation().distanceSquaredTo(paintingRuinLoc);

            if (distance > 4) {
                tangent = tangent.rotateLeft();
            }

            if (rc.canMove(tangent)) {
                rc.move(tangent);
            }
        }

        // use helper function to determine primar/secondary and paint a tile if possible
        if (rc.isActionReady()) {
            MapInfo[] infos = rc.senseNearbyMapInfos(3);
            boolean attacked = false;
            for(MapInfo info : infos){
                MapLocation loc = info.getMapLocation();
                boolean isSecondary = getIsSecondary(paintingRuinLoc, loc, paintingTowerType);
                if (rc.canAttack(loc)
                        && (info.getPaint() == PaintType.EMPTY || info.getPaint().isSecondary() != isSecondary)
                        && isWithinPattern(paintingRuinLoc, loc))
                {
                    rc.attack(loc, isSecondary);
                    attacked = true;
                    turnsWithoutAttack = 0;
                    break;
                }
            }

        }

        if (rc.canCompleteTowerPattern(paintingTowerType, paintingRuinLoc)) {
            rc.completeTowerPattern(paintingTowerType, paintingRuinLoc);
            state = RobotState.EXPLORING;
        }
        if (turnsWithoutAttack > 3) {
            state = RobotState.EXPLORING;
        }
    }

    public static UnitType getNewTowerType(RobotController rc) {
        // Optimal to build money towers if few towers
        if (rc.getNumberTowers() < 4)
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        return rc.getNumberTowers() % 2 == 1 ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    public static boolean getIsSecondary(MapLocation ruinLoc, MapLocation paintLoc, UnitType towerType) {
        if (!isWithinPattern(ruinLoc, paintLoc)) return false;

        int col = paintLoc.x - ruinLoc.x + 2;
        int row = paintLoc.y - ruinLoc.y + 2;

        if (learnedPatterns.containsKey(towerType)) {
            return learnedPatterns.get(towerType)[row][col];
        } else {
            return false;
        }
    }

    public static boolean isWithinPattern(MapLocation ruinLoc, MapLocation paintLoc) {
        return Math.abs(paintLoc.x - ruinLoc.x) <= 2 && Math.abs(paintLoc.y - ruinLoc.y) <= 2 && !ruinLoc.equals(paintLoc);
    }

    public static void runMopper(RobotController rc) throws GameActionException{

        // Move and attack randomly.
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);
        moveSoldier(rc, nextLoc);
        if (rc.canMopSwing(dir)){
            rc.mopSwing(dir);
//            System.out.println("Mop Swing! Booyah!");
        }
        else if (rc.canAttack(nextLoc)){
            rc.attack(nextLoc);
        }

        // We can also move our code into different methods or classes to better organize it!
        updateEnemyRobots(rc);
    }

    public static void runSplasher(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo curRuin = null;
        int closestRuin = 1000000;
        Direction dir = null;
        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin()) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());

                if (dist < closestRuin) {
                    curRuin = tile;
                    closestRuin = dist;

                    dir = curRuin.getMapLocation().directionTo(rc.getLocation());

                    MapLocation target = curRuin.getMapLocation().subtract(dir);

                    if (rc.canAttack(target)) {
                        rc.attack(target);
                        System.out.println("HAHA a Splasher Attacked!!");

                    }
                }
            }
        }


        if (rc.canMove(dir)) {
            rc.move(dir);
        }

    }

    public static void updateEnemyRobots(RobotController rc) throws GameActionException{

        // Sensing methods can be passed in a radius of -1 to automatically
        // use the largest possible value.
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemyRobots.length != 0){
            rc.setIndicatorString("There are nearby enemy robots! Scary!");
            // Save an array of locations with enemy robots in them for possible future use.
            MapLocation[] enemyLocations = new MapLocation[enemyRobots.length];
            for (int i = 0; i < enemyRobots.length; i++){
                enemyLocations[i] = enemyRobots[i].getLocation();
            }

            RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());

            // Occasionally try to tell nearby allies how many enemy robots we see.
            if (rc.getRoundNum() % 20 == 0){
                for (RobotInfo ally : allyRobots){
                    if (rc.canSendMessage(ally.location, enemyRobots.length)){
                        rc.sendMessage(ally.location, enemyRobots.length);
                    }
                }
            }
        }
    }

    /**
     * Gets the nearest ruin to the robot
     * @param rc The RobotController
     * @return The MapInfo of the nearest ruin, or null if none found
     */
    public static MapInfo getNearestRuin(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo curRuin = null;
        int closestRuinDist = 1000000;

        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin()) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < closestRuinDist) {
                    curRuin = tile;
                    closestRuinDist = dist;
                }
            }
        }
        return curRuin;
    }

    /**
     * Gets the distance to the nearest ruin
     * @param rc The RobotController
     * @return The distance to the nearest ruin, or 1000000 if none found
     */
    public static int getDistanceToNearestRuin(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        int closestRuinDist = 1000000;

        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin()) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < closestRuinDist) {
                    closestRuinDist = dist;
                }
            }
        }
        return closestRuinDist;
    }

    public static void updateFriendlyTowers(RobotController rc) throws GameActionException{
        RobotInfo[] friendlyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : friendlyRobots){
            if (!robot.getType().isTowerType()) continue;

            MapLocation allyLoc = robot.getLocation();
            if (knownTowers.contains(allyLoc)){
                if (isSaving) {
                    if (rc.canSendMessage(allyLoc, MessageType.SAVE_CHIPS.ordinal())) {
                        rc.sendMessage(allyLoc, MessageType.SAVE_CHIPS.ordinal());
                        isSaving = false;
                    }
                }

                continue;
            }

            knownTowers.add(allyLoc);
        }

    }

    public static void moveSoldier(RobotController rc, MapLocation loc) throws GameActionException {
        boolean moved = false;

        if (loc != null) {
            Direction dir = rc.getLocation().directionTo(loc);
            MapLocation newLoc = rc.getLocation().add(dir);
            boolean needsPainting = !rc.senseMapInfo(newLoc).getPaint().isAlly();

            if (needsPainting && rc.canMove(dir) && rc.canAttack(newLoc)) {
                rc.move(dir);
                checked.put(newLoc, 1);
                return;
            } else {
                for (Direction unit_dir : directions) {
                    if (rc.canMove(unit_dir) && rc.canAttack(newLoc)) {
                        needsPainting = !rc.senseMapInfo(rc.getLocation().add(unit_dir)).getPaint().isAlly();
                        newLoc = rc.getLocation().add(unit_dir);
                        if (needsPainting) {
                            rc.move(unit_dir);
                            checked.put(newLoc, 1);
                            return;
                        }

                    }
                }
            }
        }


        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation newLoc = rc.getLocation().add(dir);

        if (rc.canMove(dir) && !checked.containsKey(newLoc) && rc.canAttack(newLoc)) {
            checked.put(newLoc, 1);
            rc.move(dir);
            return;
        }

        for (Direction unit_dir : directions) {
            newLoc = rc.getLocation().add(dir);
            if (rc.canMove(dir) && rc.canAttack(newLoc)) {
                rc.move(dir);
                return;
            }
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
        
        switch (currentSymmetry) {
            case HORIZONTAL:
                return new MapLocation(loc.x, height - 1 - loc.y);
            case VERTICAL:
                return new MapLocation(width - 1 - loc.x, loc.y);
            case ROTATIONAL:
                return new MapLocation(width - 1 - loc.x, height - 1 - loc.y);
            default:
                // If unknown, default to vertical symmetry as a guess
                return new MapLocation(width - 1 - loc.x, loc.y);
        }
    }

    /**
     * Checks if a ruin location matches the predicted enemy location based on symmetry
     * @param ruinLoc The ruin location to check
     * @param enemyLoc The predicted enemy location
     * @return true if the locations match the symmetry pattern
     */
    private static boolean matchesSymmetry(MapLocation ruinLoc, MapLocation enemyLoc, RobotController rc) {
        MapLocation predictedEnemy = getEnemyLocation(ruinLoc, rc);
        return predictedEnemy.equals(enemyLoc);
    }

    /**
     * Updates our guess of the map symmetry based on observed ruins
     * @param rc The RobotController
     */
    private static void updateMapSymmetry(RobotController rc) throws GameActionException {
        if (currentSymmetry != MapSymmetry.UNKNOWN) return;  // Already determined

        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        if (ruins.length == 0) return;

        // Check each type of symmetry
        boolean couldBeHorizontal = true;
        boolean couldBeVertical = true;
        boolean couldBeRotational = true;

        for (MapLocation ruin : ruins) {
            if (!knownRuins.contains(ruin)) {
                knownRuins.add(ruin);
                
                // Try to sense the predicted enemy location for each symmetry
                MapLocation horizontalEnemy = new MapLocation(ruin.x, rc.getMapHeight() - 1 - ruin.y);
                MapLocation verticalEnemy = new MapLocation(rc.getMapWidth() - 1 - ruin.x, ruin.y);
                MapLocation rotationalEnemy = new MapLocation(rc.getMapWidth() - 1 - ruin.x, rc.getMapHeight() - 1 - ruin.y);

                if (rc.canSenseLocation(horizontalEnemy)) {
                    MapInfo info = rc.senseMapInfo(horizontalEnemy);
                    if (!info.hasRuin()) couldBeHorizontal = false;
                }
                
                if (rc.canSenseLocation(verticalEnemy)) {
                    MapInfo info = rc.senseMapInfo(verticalEnemy);
                    if (!info.hasRuin()) couldBeVertical = false;
                }
                
                if (rc.canSenseLocation(rotationalEnemy)) {
                    MapInfo info = rc.senseMapInfo(rotationalEnemy);
                    if (!info.hasRuin()) couldBeRotational = false;
                }
            }
        }

        // Update our symmetry guess based on observations
        if (couldBeVertical && !couldBeHorizontal && !couldBeRotational) {
            currentSymmetry = MapSymmetry.VERTICAL;
        } else if (!couldBeVertical && couldBeHorizontal && !couldBeRotational) {
            currentSymmetry = MapSymmetry.HORIZONTAL;
        } else if (!couldBeVertical && !couldBeHorizontal && couldBeRotational) {
            currentSymmetry = MapSymmetry.ROTATIONAL;
        }
        // If multiple are still possible, keep as UNKNOWN and gather more data
    }
}
