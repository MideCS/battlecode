package Optimized;

import battlecode.common.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

/**
 * Optimized RobotPlayer with enhanced unit production, reliable ruin handling,
 * painting capabilities for soldiers, savingCooldown mechanism, and improved debugging.
 */
public class RobotPlayer {

    // Game state variables for tracking turn count and saving
    static int turnCount = 0;
    static boolean isSaving = false;
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();
    static int savingTurns = 0;
    static int savingCooldown = 0; // Cooldown after saving actions
    static final Random rng = new Random(6147); // Seeded for consistency

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

    // Enum to track the last saving action for cooldown purposes
    private enum SavingAction {
        NONE, SAVE_CHIPS, UPGRADE_TOWER
    }

    static SavingAction lastSavingAction = SavingAction.NONE;

    /**
     * Main loop for the robot controller.
     */
    public static void run(RobotController rc) throws GameActionException {
        System.out.println(rc.getType() + " initialized at " + rc.getLocation());

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
                System.err.println("GameActionException encountered by " + rc.getType());
                e.printStackTrace();
            } catch (Exception e) {
                System.err.println("Unexpected Exception encountered by " + rc.getType());
                e.printStackTrace();
            } finally {
                Clock.yield(); // Yield to end the turn
            }
        }
    }

    /**
     * Logic for Tower robots.
     */
    public static void runTower(RobotController rc) throws GameActionException {
        manageResources(rc); // Handle saving turns and resource management
        processMessages(rc); // Read and act on messages from other units
        buildUnits(rc); // Build new robots based on optimal ratios
        upgradeTowerIfAdvantageous(rc); // Upgrade tower if conditions allow
    }

    /**
     * Manages saving resources for specific actions and handles cooldowns.
     */
    private static void manageResources(RobotController rc) throws GameActionException {
        if (savingTurns > 0) {
            savingTurns--;
            rc.setIndicatorString("Saving for " + savingTurns + " more turns!");
            System.out.println("Saving active: " + savingTurns + " turns remaining.");

            if (savingTurns == 0) {
                // Set cooldown based on the last saving action
                switch (lastSavingAction) {
                    case SAVE_CHIPS:
                        savingCooldown = 20;
                        System.out.println("Saving cooldown of 20 turns initiated after SAVE_CHIPS.");
                        break;
                    case UPGRADE_TOWER:
                        savingCooldown = 30;
                        System.out.println("Saving cooldown of 30 turns initiated after UPGRADE_TOWER.");
                        break;
                    default:
                        savingCooldown = 0;
                        break;
                }
                lastSavingAction = SavingAction.NONE; // Reset the last saving action
            }
        } else if (savingCooldown > 0) {
            savingCooldown--;
            rc.setIndicatorString("Cooldown: " + savingCooldown + " turns remaining");
            System.out.println("Saving cooldown active: " + savingCooldown + " turns remaining.");
        } else {
            isSaving = false; // Reset saving mode
            rc.setIndicatorString("No active saving or cooldown.");
        }
    }

    /**
     * Processes incoming messages to coordinate actions.
     */
    private static void processMessages(RobotController rc) throws GameActionException {
        for (Message message : rc.readMessages(-1)) {
            if (message.getBytes() == MessageType.SAVE_CHIPS.ordinal()) {
                if (savingCooldown == 0 && !isSaving) {
                    savingTurns = 20; // Save for 20 turns
                    isSaving = true;
                    lastSavingAction = SavingAction.SAVE_CHIPS;
                    System.out.println("Received SAVE_CHIPS message. Initiating saving for 20 turns.");
                } else {
                    System.out.println("Received SAVE_CHIPS message but currently on cooldown or already saving.");
                }
            } else if (message.getBytes() == MessageType.UPGRADE_TOWER.ordinal()) {
                if (savingCooldown == 0 && !isSaving) {
                    savingTurns = 30; // Save for 30 turns
                    isSaving = true;
                    lastSavingAction = SavingAction.UPGRADE_TOWER;
                    System.out.println("Received UPGRADE_TOWER message. Initiating saving for 30 turns.");
                } else {
                    System.out.println("Received UPGRADE_TOWER message but currently on cooldown or already saving.");
                }
            }
        }
    }

    /**
     * Builds units based on predefined ratios, respecting the saving state.
     */
    private static void buildUnits(RobotController rc) throws GameActionException {
        if (isSaving) {
            System.out.println("Currently saving. Unit production is inhibited.");
            return; // Do not produce units while saving
        }

        int totalProduced = soldierCount + mopperCount + splasherCount;

        // Avoid division by zero
        double soldierFraction = totalProduced > 0 ? (double) soldierCount / totalProduced : 0;
        double mopperFraction = totalProduced > 0 ? (double) mopperCount / totalProduced : 0;
        double splasherFraction = totalProduced > 0 ? (double) splasherCount / totalProduced : 0;

        UnitType nextUnit = null;

        if (soldierFraction < SOLDIER_RATIO) {
            nextUnit = UnitType.SOLDIER;
        } else if (mopperFraction < MOPPER_RATIO) {
            nextUnit = UnitType.MOPPER;
        } else if (splasherFraction < SPLASHER_RATIO) {
            nextUnit = UnitType.SPLASHER;
        }

        if (nextUnit != null) {
            boolean built = false;
            // Iterate through all directions to find a valid build location
            for (Direction dir : directions) {
                MapLocation buildLocation = rc.getLocation().add(dir);
                if (rc.canBuildRobot(nextUnit, buildLocation)) {
                    rc.buildRobot(nextUnit, buildLocation);
                    incrementUnitCount(nextUnit);
                    System.out.println("Built " + nextUnit + " at " + buildLocation);
                    built = true;
                    break;
                }
            }
            if (!built) {
                System.out.println("Failed to build " + nextUnit + ". All directions blocked or insufficient resources.");
            }
        }
    }

    /**
     * Increments the count for the specified unit type.
     */
    private static void incrementUnitCount(UnitType unitType) {
        switch (unitType) {
            case SOLDIER -> soldierCount++;
            case MOPPER -> mopperCount++;
            case SPLASHER -> splasherCount++;
            default -> {
                // Do nothing for unsupported unit types
            }
        }
    }

    /**
     * Upgrades towers if advantageous.
     */
    private static void upgradeTowerIfAdvantageous(RobotController rc) throws GameActionException {
        if (isAdvantageousPosition(rc)) { // Check if advantageous position
            for (MapLocation loc : knownTowers) {
                if (rc.canMarkTowerPattern(UnitType.LEVEL_TWO_PAINT_TOWER, loc)) {
                    rc.markTowerPattern(UnitType.LEVEL_TWO_PAINT_TOWER, loc);
                    rc.setIndicatorString("Upgrading tower at " + loc);
                    System.out.println("Marked tower pattern for upgrade at " + loc);
                }
                if (rc.canCompleteTowerPattern(UnitType.LEVEL_TWO_PAINT_TOWER, loc)) {
                    rc.completeTowerPattern(UnitType.LEVEL_TWO_PAINT_TOWER, loc);
                    System.out.println("Upgraded tower at " + loc);
                }
            }
        }
    }

    /**
     * Checks if the team is in an advantageous position.
     */
    private static boolean isAdvantageousPosition(RobotController rc) throws GameActionException {
        int ourTowers = getTeamTowers(rc).length;
        int ourRobots = rc.senseNearbyRobots(-1, rc.getTeam()).length;

        int enemyTowers = getOpponentTowers(rc).length;
        int enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent()).length;

        int requiredTowers = (int) Math.ceil(1.5 * enemyTowers);
        int requiredRobots = 2 * enemyRobots;

        return ourTowers >= requiredTowers && ourRobots >= requiredRobots; // Check for advantageous position
    }

    /**
     * Retrieves all team towers.
     */
    private static MapLocation[] getTeamTowers(RobotController rc) throws GameActionException {
        ArrayList<MapLocation> towers = new ArrayList<>();
        for (RobotInfo robot : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (robot.getType().isTowerType()) {
                towers.add(robot.getLocation());
            }
        }
        return towers.toArray(new MapLocation[0]);
    }

    /**
     * Retrieves all opponent towers.
     */
    private static MapLocation[] getOpponentTowers(RobotController rc) throws GameActionException {
        ArrayList<MapLocation> towers = new ArrayList<>();
        for (RobotInfo robot : rc.senseNearbyRobots(-1, rc.getTeam().opponent())) {
            if (robot.getType().isTowerType()) {
                towers.add(robot.getLocation());
            }
        }
        return towers.toArray(new MapLocation[0]);
    }

    /**
     * Logic for Soldier robots.
     */
    public static void runSoldier(RobotController rc) throws GameActionException {
        // Attempt to paint the current tile if possible
        paintCurrentTile(rc);

        if (target == null || !rc.canSenseLocation(target) || !rc.senseMapInfo(target).hasRuin()) {
            target = findEmptyRuin(rc); // Find a new ruin target
        }

        if (target != null) {
            bug2(rc); // Use Bug2 algorithm for pathfinding

            // If at the target location and it's a ruin, attempt to build a tower
            if (rc.getLocation().equals(target) && rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target)) {
                buildTowerOnRuin(rc);
                sendMessengerToNotify(rc); // Notify towers to save resources
                target = null; // Reset target after building
            }
        } else {
            // No ruins found, perform default actions
            randomMove(rc);
        }

        // Attack enemies or perform additional actions
        attackEnemies(rc);
    }

    /**
     * Attempts to paint the current tile as an ally if possible.
     */
    private static void paintCurrentTile(RobotController rc) throws GameActionException {
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            rc.attack(rc.getLocation(), false); // Paint as ally
            System.out.println("Painted current tile at " + rc.getLocation());
        }
    }

    /**
     * Sends a message to friendly towers to start saving resources.
     */
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

    /**
     * Finds the nearest ruin on the map.
     */
    private static MapLocation findEmptyRuin(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation nearestRuin = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            if (tile.hasRuin()) { // Removed PaintType.EMPTY condition
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < minDist) {
                    nearestRuin = tile.getMapLocation();
                    minDist = dist;
                }
            }
        }
        if (nearestRuin != null) {
            System.out.println("Found ruin at " + nearestRuin + " with distance " + minDist);
        }
        return nearestRuin;
    }

    /**
     * Builds a tower on the ruin if possible.
     */
    private static void buildTowerOnRuin(RobotController rc) throws GameActionException {
        if (rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target)) {
            rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target);
            System.out.println("Marking tower pattern at " + target);
        }

        if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target)) {
            rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target);
            System.out.println("Tower built at " + target);
            knownTowers.add(target); // Track the newly built tower
        }
    }

    /**
     * Logic for Mopper robots.
     */
    public static void runMopper(RobotController rc) throws GameActionException {
        clearEnemyPaint(rc); // Remove enemy paint from nearby tiles
        randomMove(rc); // Move randomly if no specific task
    }

    /**
     * Clears enemy paint from tiles adjacent to the mopper.
     */
    private static void clearEnemyPaint(RobotController rc) throws GameActionException {
        MapLocation current = rc.getLocation();
        for (Direction dir : directions) {
            MapLocation adjacent = current.add(dir);
            if (rc.canAttack(adjacent) && rc.senseMapInfo(adjacent).getPaint().isEnemy()) {
                rc.attack(adjacent); // Remove enemy paint
                System.out.println("Cleared enemy paint at " + adjacent);
            }
        }
    }

    /**
     * Logic for Splasher robots.
     */
    public static void runSplasher(RobotController rc) throws GameActionException {
        MapInfo nearestRuin = getNearestRuin(rc); // Locate nearest ruin
        if (nearestRuin != null) {
            Direction dir = rc.getLocation().directionTo(nearestRuin.getMapLocation());
            if (rc.canMove(dir)) {
                rc.move(dir); // Move toward the ruin
                System.out.println("Splasher moving towards ruin at " + nearestRuin.getMapLocation());
            } else if (rc.canAttack(nearestRuin.getMapLocation())) {
                rc.attack(nearestRuin.getMapLocation()); // Attack the ruin
                System.out.println("Splasher attacking ruin at " + nearestRuin.getMapLocation());
            }
        } else {
            randomMove(rc); // Move randomly if no ruin is found
        }

        // Attack enemies or perform additional actions
        attackEnemies(rc);
    }

    /**
     * Finds the nearest ruin on the map.
     */
    private static MapInfo getNearestRuin(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapInfo nearest = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            if (tile.hasRuin()) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < minDist) {
                    nearest = tile;
                    minDist = dist;
                }
            }
        }
        return nearest;
    }

    /**
     * Moves the robot in a random direction.
     */
    private static void randomMove(RobotController rc) throws GameActionException {
        Direction randomDir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(randomDir)) {
            rc.move(randomDir);
            System.out.println(rc.getType() + " moved " + randomDir + " to " + rc.getLocation().add(randomDir));
        } else {
            System.out.println(rc.getType() + " failed to move " + randomDir);
        }
    }

    /**
     * Bresenham's Line Algorithm for creating a path between two points.
     */
    public static HashSet<MapLocation> createLine(MapLocation a, MapLocation b) {
        HashSet<MapLocation> locs = new HashSet<>();

        int x = a.x, y = a.y;
        int dx = b.x - a.x;
        int dy = b.y - a.y;
        int sx = Integer.signum(dx);
        int sy = Integer.signum(dy);

        dx = Math.abs(dx);
        dy = Math.abs(dy);

        int err = dx - dy;
        while (true) {
            locs.add(new MapLocation(x, y));

            if (x == b.x && y == b.y) break;

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
        return locs;
    }

    /**
     * Bug2 pathfinding algorithm for navigating around obstacles.
     */
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
                System.out.println("Bug2: Moved " + dir + " towards " + target);
            } else {
                isTracing = true; // Enter tracing mode if movement is blocked
                obstacleStarDist = rc.getLocation().distanceSquaredTo(target);
                tracingDirection = dir;
                System.out.println("Bug2: Entering tracing mode towards " + target);
            }
        } else {
            if (line.contains(rc.getLocation()) && rc.getLocation().distanceSquaredTo(target) < obstacleStarDist) {
                isTracing = false; // Exit tracing mode when back on path
                System.out.println("Bug2: Exiting tracing mode, back on path to " + target);
            } else {
                for (int i = 0; i < 8; i++) {
                    if (rc.canMove(tracingDirection)) {
                        rc.move(tracingDirection);
                        System.out.println("Bug2: Tracing move " + tracingDirection + " towards " + target);
                        break;
                    }
                    tracingDirection = tracingDirection.rotateLeft(); // Rotate to find valid direction
                }
            }
        }
    }

    /**
     * Attacks any visible enemy robots.
     */
    private static void attackEnemies(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0) {
            // Attack the first enemy in the list
            RobotInfo targetEnemy = enemies[0];
            if (rc.canAttack(targetEnemy.getLocation())) {
                rc.attack(targetEnemy.getLocation());
                System.out.println(rc.getType() + " attacked enemy at " + targetEnemy.getLocation());
            }
        }
    }
}
