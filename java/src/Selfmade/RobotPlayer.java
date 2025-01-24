package Selfmade;
import battlecode.common.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

/**
 * Optimized RobotPlayer with focused ruin targeting, controlled expansion,
 * and coordinated unit production.
 */
public class RobotPlayer {

    // Game State variables
    static int turnCount = 0;
    static boolean isSaving = false;
    static int saveTurns = 0;
    static boolean isMessenger = false;
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();

    // Track issues related to saving
    private enum SavingAction {
        NONE, SAVE_CHIPS, UPGRADE_TOWER
    }
    static SavingAction lastSavingAction = SavingAction.NONE;

    // The random seed and the stored directions
    static final Random rng = new Random(6147);
    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    // Example of a "preferred build order"
    // Weighted: 3/5 Soldiers, 1/5 Moppers, 1/5 Splashers
    private static final UnitType[] PREFERRED_BUILD_ORDER = new UnitType[]{
        UnitType.SOLDIER, UnitType.SOLDIER, UnitType.SOLDIER,
        UnitType.MOPPER,
        UnitType.SPLASHER
    };

    public static void run(RobotController rc) throws GameActionException {

        // Assign messenger to about half of our Moppers
        if (rc.getType() == UnitType.MOPPER && rc.getID() % 2 == 0) {
            isMessenger = true;
        }

        while (true) {
            turnCount++;  // Increment the turn counter
            try {
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
                    default:
                        runTower(rc);
                        break;
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

    // ===========================================================
    //                    TOWER LOGIC
    // ===========================================================
    public static void runTower(RobotController rc) throws GameActionException {
        manageResources(rc);
        processMessages(rc);
        buildUnits(rc);
    }

    private static void manageResources(RobotController rc) throws GameActionException {
        if (saveTurns == 0) {
            isSaving = false;
        } else {
            saveTurns--;
        }
    }

    private static void processMessages(RobotController rc) throws GameActionException {
        for (Message message : rc.readMessages(-1)) {
            // Basic message->action interpretation
            if (!isSaving && message.getBytes() == SavingAction.SAVE_CHIPS.ordinal()) {
                saveTurns = 20;
                isSaving = true;
                lastSavingAction = SavingAction.SAVE_CHIPS;
            } else if (!isSaving && message.getBytes() == SavingAction.UPGRADE_TOWER.ordinal()) {
                saveTurns = 30;
                isSaving = true;
                lastSavingAction = SavingAction.UPGRADE_TOWER;
            }
        }
    }

    /**
     * Build the next unit following a "preferred build order."
     */
    private static void buildUnits(RobotController rc) throws GameActionException {
        if (isSaving) {
            return; // Do not produce units while saving
        }

        // Pick next unit from the preferred build order
        UnitType nextUnit = PREFERRED_BUILD_ORDER[rng.nextInt(PREFERRED_BUILD_ORDER.length)];

        // Attempt to build in a random direction
        // Shuffle directions to avoid always trying the same order
        ArrayList<Direction> shuffled = new ArrayList<>(Arrays.asList(directions));
        Collections.shuffle(shuffled, rng);

        for (Direction dir : shuffled) {
            MapLocation buildLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(nextUnit, buildLoc)) {
                rc.buildRobot(nextUnit, buildLoc);
                break;
            }
        }
    }

    // ===========================================================
    //                    SOLDIER LOGIC
    // ===========================================================
    public static void runSoldier(RobotController rc) throws GameActionException {
        // 1. Try to complete a ruin if available
        boolean didBuild = attemptRuinCompletion(rc);

        // 2. If we did not build anything, do random movement/attack
        if (!didBuild) {
            performRandomMovementAndAttack(rc);
        }

        // 3. Paint current tile to avoid penalties
        paintCurrentTile(rc);
    }

    /**
     * Attempt to find and complete a nearby ruin. Returns true if we at least tried to build.
     */
    private static boolean attemptRuinCompletion(RobotController rc) throws GameActionException {
        MapLocation curRuin = findNearestUnexploredRuin(rc);
        if (curRuin == null) {
            return false;
        }

        // Move toward the ruin
        Direction dir = rc.getLocation().directionTo(curRuin);
        if (rc.canMove(dir)) {
            rc.move(dir);
        }

        // Attempt to mark the tower pattern if the tile is empty
        MapLocation shouldBeMarked = rc.getLocation().add(dir.opposite());
        MapInfo markTile = rc.senseMapInfo(shouldBeMarked);
        if (markTile != null && markTile.getMark() == PaintType.EMPTY
                && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin)) {
            rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin);
            System.out.println("Marked tower pattern at " + curRuin);
        }

        // Fill in any missing paint in the pattern
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(curRuin, 8);
        for (MapInfo patternTile : nearbyTiles) {
            if (patternTile.getMark() != patternTile.getPaint() && patternTile.getMark() != PaintType.EMPTY) {
                boolean useSecondaryColor = (patternTile.getMark() == PaintType.ALLY_SECONDARY);
                if (rc.canAttack(patternTile.getMapLocation())) {
                    rc.attack(patternTile.getMapLocation(), useSecondaryColor);
                }
            }
        }

        // Complete the tower if possible
        if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin)) {
            rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin);
            rc.setTimelineMarker("Tower built", 0, 255, 0);
            System.out.println("Built a tower at " + curRuin + "!");
        }

        return true; // We attempted ruin completion logic
    }

    /**
     * Randomly move and possibly attack an adjacent tile (sample logic).
     */
    private static void performRandomMovementAndAttack(RobotController rc) throws GameActionException {
        Direction randomDir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(randomDir)) {
            rc.move(randomDir);
        }

        // Optional random attack on adjacent tile
        MapLocation attackLoc = rc.getLocation().add(randomDir);
        if (rc.canAttack(attackLoc)) {
            rc.attack(attackLoc);
        }
    }

    // ===========================================================
    //                    MOPPER LOGIC
    // ===========================================================
    public static void runMopper(RobotController rc) throws GameActionException {
        // 1. If saving & messenger, move to nearest tower
        moveTowardNearestTowerIfSavingAndMessenger(rc);

        // 2. Do random movement & Mopper-specific attacking
        performMopperMovementAndAttack(rc);

        // 3. Always update known enemy robots
        updateEnemyRobots(rc);

        // 4. Messenger logic: update towers, check ruins
        if (isMessenger) {
            rc.setIndicatorDot(rc.getLocation(), 255, 0, 0);
            updateFriendlyTowers(rc);
            checkNearbyRuins(rc);
        }
    }

    private static void moveTowardNearestTowerIfSavingAndMessenger(RobotController rc) throws GameActionException {
        if (!isSaving || !isMessenger || knownTowers.isEmpty()) {
            return;
        }
        MapLocation nearestTower = null;
        int minDist = Integer.MAX_VALUE;
        for (MapLocation tower : knownTowers) {
            int dist = tower.distanceSquaredTo(rc.getLocation());
            if (dist < minDist) {
                minDist = dist;
                nearestTower = tower;
            }
        }
        if (nearestTower != null) {
            Direction dir = rc.getLocation().directionTo(nearestTower);
            if (rc.canMove(dir)) {
                rc.move(dir);
            }
        }
    }

    private static void performMopperMovementAndAttack(RobotController rc) throws GameActionException {
        // Move in a random direction
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);

        if (rc.canMove(dir)) {
            rc.move(dir);
        }

        // Mop-swing if possible, else normal attack
        if (rc.canMopSwing(dir)) {
            rc.mopSwing(dir);
        } else if (rc.canAttack(nextLoc)) {
            rc.attack(nextLoc);
        }
    }

    // ===========================================================
    //                    SPLASHER LOGIC
    // ===========================================================
    public static void runSplasher(RobotController rc) throws GameActionException {
        // Very simple behavior: random movement
        randomMove(rc);
    }

    private static void randomMove(RobotController rc) throws GameActionException {
        Direction randomDir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(randomDir)) {
            rc.move(randomDir);
            System.out.println("Moved to " + rc.getLocation().add(randomDir));
        } else {
            System.out.println("Failed to move in direction: " + randomDir);
        }
    }

    // ===========================================================
    //                HELPER METHODS / COMMON LOGIC
    // ===========================================================
    private static MapLocation findNearestUnexploredRuin(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation nearestRuin = null;
        int minDist = Integer.MAX_VALUE;
        for (MapInfo tile : tiles) {
            if (tile.hasRuin() && rc.senseRobotAtLocation(tile.getMapLocation()) == null) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < minDist) {
                    nearestRuin = tile.getMapLocation();
                    minDist = dist;
                }
            }
        }
        return nearestRuin;
    }

    public static void updateEnemyRobots(RobotController rc) throws GameActionException {
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemyRobots.length != 0) {
            MapLocation[] enemyLocations = new MapLocation[enemyRobots.length];
            for (int i = 0; i < enemyRobots.length; i++) {
                enemyLocations[i] = enemyRobots[i].getLocation();
            }
            // Example: send a message every 20 rounds
            RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
            if (rc.getRoundNum() % 20 == 0) {
                for (RobotInfo ally : allyRobots) {
                    if (rc.canSendMessage(ally.location, enemyRobots.length)) {
                        rc.sendMessage(ally.location, enemyRobots.length);
                    }
                }
            }
        }
    }

    public static void updateFriendlyTowers(RobotController rc) throws GameActionException {
        RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allyRobots) {
            if (!ally.getType().isTowerType()) {
                continue;
            }
            MapLocation allyLoc = ally.location;
            if (knownTowers.contains(allyLoc)) {
                // Send a message to the nearby tower if we are saving
                if (isSaving && rc.canSendMessage(allyLoc)) {
                    rc.sendMessage(allyLoc, SavingAction.SAVE_CHIPS.ordinal());
                    isSaving = false;
                }
                continue;
            }
            // Add to our known towers array
            knownTowers.add(allyLoc);
        }
    }

    public static void checkNearbyRuins(RobotController rc) throws GameActionException {
        // Search for nearby ruins
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        for (MapInfo tile : nearbyTiles) {
            MapLocation tileLoc = tile.getMapLocation();
            if (!tile.hasRuin() || rc.senseRobotAtLocation(tileLoc) != null) {
                continue;
            }
            // Heuristic to see if the ruin is being built on
            MapLocation markLoc = tileLoc.add(tileLoc.directionTo(rc.getLocation()));
            MapInfo markInfo = rc.senseMapInfo(markLoc);
            if (markInfo.getMark().isAlly()) {
                isSaving = true;
                return;
            }
        }
    }

    private static void paintCurrentTile(RobotController rc) throws GameActionException {
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        // If our tile is not ally-painted, paint it
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            rc.attack(rc.getLocation(), false);
            System.out.println("Painted current tile at " + rc.getLocation());
        }
    }

}
