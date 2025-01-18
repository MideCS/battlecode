package Optimized;
import battlecode.common.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

// Final optimized RobotPlayer with strategic saving and upgrading logic
public class RobotPlayer {

    // Game state variables for tracking turn count and cooldowns
    static int turnCount = 0;
    static int soldierCooldown = 0;
    static boolean isMopper = false;
    static boolean isSaving = false;
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();
    static int savingTurns = 0;
    static final Random rng = new Random(6147); // Random generator for decision-making

    // Predefined directions for movement
    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    // Pathfinding variables
    static MapLocation target;
    static MapLocation prevDest;
    static HashSet<MapLocation> line;
    static boolean isTracing = false;
    static Direction tracingDirection;
    static int obstacleStarDist;

    // Unit ratios for production
    static final double SOLDIER_RATIO = 0.5; // 50% Soldiers
    static final double MOPPER_RATIO = 0.3;  // 30% Moppers
    static final double SPLASHER_RATIO = 0.2; // 20% Splashers
    static int soldierCount = 0;
    static int mopperCount = 0;
    static int splasherCount = 0;

    // Enum for message types
    private enum MessageType {
        SAVE_CHIPS, SAVE_TOWER, UPGRADE_TOWER
    }

    // Main loop for the robot controller
    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            turnCount++; // Increment the turn counter
            try {
                // Determine behavior based on robot type
                switch (rc.getType()) {
                    case SOLDIER -> runSoldier(rc);
                    case MOPPER -> runMopper(rc);
                    case SPLASHER -> runSplasher(rc);
                    default -> runTower(rc);
                }
            } catch (GameActionException e) {
                System.err.println("GameActionException");
                e.printStackTrace();
            } catch (Exception e) {
                System.err.println("Unexpected Exception");
                e.printStackTrace();
            } finally {
                Clock.yield(); // Yield to end the turn
            }
        }
    }

    // Logic for tower robots
    public static void runTower(RobotController rc) throws GameActionException {
        manageResources(rc); // Handle saving turns and resource management
        processMessages(rc); // Read and act on messages from other units
        buildUnits(rc); // Build new robots based on optimal ratios
        upgradeTowerIfAdvantageous(rc); // Upgrade tower if conditions allow
    }

    // Manages saving resources for specific actions
    private static void manageResources(RobotController rc) throws GameActionException {
        if (savingTurns > 0) {
            savingTurns--;
            rc.setIndicatorString("Saving for " + savingTurns + " more turns!");
        } else {
            isSaving = false; // Reset saving mode
        }
    }

    // Processes incoming messages to coordinate actions
    private static void processMessages(RobotController rc) throws GameActionException {
        for (Message message : rc.readMessages(-1)) {
            if (message.getBytes() == MessageType.SAVE_CHIPS.ordinal()) {
                savingTurns = 20; // Save for 20 turns
                isSaving = true;
            } else if (message.getBytes() == MessageType.UPGRADE_TOWER.ordinal()) {
                savingTurns = 30; // Save for upgrading the tower
                isSaving = true;
            }
        }
    }

    // Builds units based on optimal ratios
    private static void buildUnits(RobotController rc) throws GameActionException {
        Direction randomDir = directions[rng.nextInt(directions.length)]; // Pick a random direction
        MapLocation nextLoc = rc.getLocation().add(randomDir);

        // Calculate the total count of robots produced
        int totalProduced = soldierCount + mopperCount + splasherCount;

        // Decide the next unit type to produce
        if (totalProduced == 0 || (double) soldierCount / totalProduced < SOLDIER_RATIO) {
            if (rc.canBuildRobot(UnitType.SOLDIER, nextLoc)) {
                rc.buildRobot(UnitType.SOLDIER, nextLoc);
                soldierCount++;
                return;
            }
        }
        if ((double) mopperCount / totalProduced < MOPPER_RATIO) {
            if (rc.canBuildRobot(UnitType.MOPPER, nextLoc)) {
                rc.buildRobot(UnitType.MOPPER, nextLoc);
                mopperCount++;
                return;
            }
        }
        if ((double) splasherCount / totalProduced < SPLASHER_RATIO) {
            if (rc.canBuildRobot(UnitType.SPLASHER, nextLoc)) {
                rc.buildRobot(UnitType.SPLASHER, nextLoc);
                splasherCount++;
            }
        }
    }

    // Upgrades towers if advantageous
    private static void upgradeTowerIfAdvantageous(RobotController rc) throws GameActionException {
        if (isAdvantageousPosition(rc)) { // Check if advantageous position
            for (MapLocation loc : knownTowers) {
                if (rc.canMarkTowerPattern(UnitType.LEVEL_TWO_PAINT_TOWER, loc)) {
                    rc.markTowerPattern(UnitType.LEVEL_TWO_PAINT_TOWER, loc);
                    rc.setIndicatorString("Upgrading tower at " + loc);
                }
                if (rc.canCompleteTowerPattern(UnitType.LEVEL_TWO_PAINT_TOWER, loc)) {
                    rc.completeTowerPattern(UnitType.LEVEL_TWO_PAINT_TOWER, loc);
                    System.out.println("Upgraded tower at " + loc);
                }
            }
        }
    }

    // Checks if the team is in an advantageous position
    private static boolean isAdvantageousPosition(RobotController rc) throws GameActionException {
        int ourTowers = rc.getTeamTowers().length;
        int ourRobots = rc.senseNearbyRobots(-1, rc.getTeam()).length;

        int enemyTowers = rc.getOpponentTowers().length;
        int enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent()).length;

        int requiredTowers = (int) Math.ceil(1.5 * enemyTowers);
        int requiredRobots = 2 * enemyRobots;

        return ourTowers >= requiredTowers && ourRobots >= requiredRobots; // Check for advantageous position
    }

    // Logic for Soldier robots
    public static void runSoldier(RobotController rc) throws GameActionException {
        if (target == null) {
            target = findEmptyRuin(rc); // Prioritize empty ruins
        }
        bug2(rc); // Use Bug2 algorithm for pathfinding
        if (rc.canSenseLocation(target) && rc.senseMapInfo(target).hasRuin()) {
            buildTowerOnRuin(rc); // Build tower if at the ruin
            sendMessengerToNotify(rc); // Notify towers to save resources
        }
    }

    // Sends a message to friendly towers to start saving resources
    private static void sendMessengerToNotify(RobotController rc) throws GameActionException {
        RobotInfo[] friendlyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : friendlyRobots) {
            if (robot.getType() == UnitType.MOPPER && rc.canSendMessage(robot.getLocation())) {
                rc.sendMessage(robot.getLocation(), MessageType.SAVE_CHIPS.ordinal());
                System.out.println("Messenger Mopper sent to notify towers");
                break;
            }
        }
    }

    // Finds the nearest empty ruin on the map
    private static MapLocation findEmptyRuin(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation nearestRuin = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            if (tile.hasRuin() && tile.getMark() == PaintType.EMPTY) { // Check for empty ruin
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < minDist) {
                    nearestRuin = tile.getMapLocation();
                    minDist = dist;
                }
            }
        }
        return nearestRuin;
    }

    // Builds a tower on the ruin if possible
    private static void buildTowerOnRuin(RobotController rc) throws GameActionException {
        if (rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target)) {
            rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target);
            System.out.println("Marking tower pattern at " + target);
        }

        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(target, 8);
        for (MapInfo tile : nearbyTiles) {
            if (tile.getMark() != tile.getPaint() && tile.getMark() != PaintType.EMPTY) {
                boolean useSecondaryColor = tile.getMark() == PaintType.ALLY_SECONDARY;
                if (rc.canAttack(tile.getMapLocation())) {
                    rc.attack(tile.getMapLocation(), useSecondaryColor); // Paint the required tiles
                }
            }
        }

        if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target)) {
            rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target);
            System.out.println("Tower built at " + target);
        }
    }

    // Logic for Mopper robots
    public static void runMopper(RobotController rc) throws GameActionException {
        clearEnemyPaint(rc); // Remove enemy paint from nearby tiles
        randomMove(rc); // Move randomly if no specific task
    }

    // Clears enemy paint from tiles adjacent to the mopper
    private static void clearEnemyPaint(RobotController rc) throws GameActionException {
        MapLocation current = rc.getLocation();
        for (Direction dir : directions) {
            MapLocation adjacent = current.add(dir);
            if (rc.canAttack(adjacent) && rc.senseMapInfo(adjacent).getPaint().isEnemy()) {
                rc.attack(adjacent); // Remove enemy paint
            }
        }
    }

    // Logic for Splasher robots
    public static void runSplasher(RobotController rc) throws GameActionException {
        MapInfo nearestRuin = getNearestRuin(rc); // Locate nearest ruin
        if (nearestRuin != null) {
            Direction dir = rc.getLocation().directionTo(nearestRuin.getMapLocation());
            if (rc.canMove(dir)) {
                rc.move(dir); // Move toward the ruin
            } else if (rc.canAttack(nearestRuin.getMapLocation())) {
                rc.attack(nearestRuin.getMapLocation()); // Attack the ruin
            }
        } else {
            randomMove(rc); // Move randomly if no ruin is found
        }
    }

    // Finds the nearest ruin on the map
    private static MapInfo getNearestRuin(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapInfo nearest = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            if (tile.hasRuin()) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < minDist) {
                    nearest = tile;
                    minDist = dist; // Update nearest ruin
                }
            }
        }
        return nearest;
    }

    // Moves the robot in a random direction
    private static void randomMove(RobotController rc) throws GameActionException {
        Direction randomDir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(randomDir)) {
            rc.move(randomDir);
        }
    }

    // Bresenham's Line Algorithm for creating a path between two points
    public static HashSet<MapLocation> createLine(MapLocation a, MapLocation b) {
        HashSet<MapLocation> locs = new HashSet<>();

        int x = a.x, y = a.y;
        int dx = b.x - a.x;
        int dy = b.y - a.y;
        int sx = (int) Math.signum(dx);
        int sy = (int) Math.signum(dy);

        dx = Math.abs(dx);
        dy = Math.abs(dy);

        int d = Math.max(dx, dy);
        int r = d / 2;
        if (dx > dy) {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y));
                x += sx;
                r += dy;
                if (r >= dx) {
                    locs.add(new MapLocation(x, y));
                    y += sy;
                    r -= dx;
                }
            }
        } else {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y));
                y += sy;
                r += dx;
                if (r >= dy) {
                    locs.add(new MapLocation(x, y));
                    x += sx;
                    r -= dy;
                }
            }
        }

        locs.add(new MapLocation(x, y));
        return locs;
    }

    // Bug2 pathfinding algorithm for navigating around obstacles
    public static void bug2(RobotController rc) throws GameActionException {
        if (target == null) return;

        if (!target.equals(prevDest)) {
            prevDest = target;
            line = createLine(rc.getLocation(), target); // Generate path to target
        }

        if (!isTracing) {
            Direction dir = rc.getLocation().directionTo(target);
            if (rc.canMove(dir)) {
                rc.move(dir); // Move directly toward the target
            } else {
                isTracing = true; // Enter tracing mode if movement is blocked
                obstacleStarDist = rc.getLocation().distanceSquaredTo(target);
                tracingDirection = dir;
            }
        } else {
            if (line.contains(rc.getLocation()) && rc.getLocation().distanceSquaredTo(target) < obstacleStarDist) {
                isTracing = false; // Exit tracing mode when back on path
            } else {
                for (int i = 0; i < 8; i++) {
                    if (rc.canMove(tracingDirection)) {
                        rc.move(tracingDirection);
                        break;
                    }
                    tracingDirection = tracingDirection.rotateLeft(); // Rotate to find valid direction
                }
            }
        }
    }
}







