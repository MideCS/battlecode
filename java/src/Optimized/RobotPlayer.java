package RPGbot;
import battlecode.common.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

// Final optimized RobotPlayer with inline comments explaining key logic
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

    // Enum for message types
    private enum MessageType {
        SAVE_CHIPS, SAVE_TOWER
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
        buildUnits(rc); // Build new robots if possible
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
                savingTurns = 18; // Begin saving for a predefined duration
                isSaving = true;
            }
        }
    }

    // Builds units (Soldiers, Moppers, or Splashers) based on conditions
    private static void buildUnits(RobotController rc) throws GameActionException {
        Direction randomDir = directions[rng.nextInt(directions.length)]; // Pick a random direction
        MapLocation nextLoc = rc.getLocation().add(randomDir);

        if (soldierCooldown == 0 && rc.canBuildRobot(UnitType.SOLDIER, nextLoc)) {
            rc.buildRobot(UnitType.SOLDIER, nextLoc); // Build a Soldier
            soldierCooldown = 40; // Reset cooldown
        } else if (rc.canBuildRobot(isMopper ? UnitType.SPLASHER : UnitType.MOPPER, nextLoc)) {
            rc.buildRobot(isMopper ? UnitType.SPLASHER : UnitType.MOPPER, nextLoc);
            isMopper = !isMopper; // Alternate between Mopper and Splasher
        }
        soldierCooldown = Math.max(0, soldierCooldown - 1); // Decrease cooldown
    }

    // Logic for Soldier robots
    public static void runSoldier(RobotController rc) throws GameActionException {
        if (target == null) {
            target = findStrategicTarget(rc); // Select a target dynamically
        }
        bug2(rc); // Use Bug2 algorithm for pathfinding
        engageNearbyEnemies(rc); // Attack nearby enemies if possible
    }

    // Finds a high-value target for the soldier
    private static MapLocation findStrategicTarget(RobotController rc) {
        return new MapLocation(10, 10); // Example target; replace with dynamic logic
    }

    // Engages nearby enemies by attacking them
    private static void engageNearbyEnemies(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            if (rc.canAttack(enemy.getLocation())) {
                rc.attack(enemy.getLocation()); // Attack the first valid enemy
                break;
            }
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






