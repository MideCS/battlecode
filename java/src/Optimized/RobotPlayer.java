package Optimized;

import battlecode.common.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

/**
 * Optimized RobotPlayer with focused ruin targeting, controlled expansion,
 * and coordinated unit production.
 */
public class RobotPlayer {

    // Game state variables
    static int turnCount = 0;
    static boolean isSaving = false;
    static int savingTurns = 0;
    static int savingCooldown = 0; // Cooldown after saving actions
    static final Random rng = new Random(6147); // Seeded for consistency

    // Predefined directions for movement and building
    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST
    };

    // Unit ratios for production
    static final double SOLDIER_RATIO = 0.5; // 50% Soldiers
    static final double MOPPER_RATIO = 0.3;  // 30% Moppers
    static final double SPLASHER_RATIO = 0.2; // 20% Splashers
    static int soldierCount = 0;
    static int mopperCount = 0;
    static int splasherCount = 0;

    // Enum for message types
    private enum MessageType {
        SET_TARGET,        // 0
        TARGET_COMPLETED   // 1
    }

    // Enum to track the last saving action for cooldown purposes
    private enum SavingAction {
        NONE, SAVE_CHIPS, UPGRADE_TOWER
    }

    static SavingAction lastSavingAction = SavingAction.NONE;

    // Tracking explored ruins
    static HashSet<MapLocation> exploredRuins = new HashSet<>();

    // Shared target variable (will be set via messages)
    static MapLocation sharedTarget = null;

    // Known towers
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();

    // Pathfinding variables
    static MapLocation prevDest = null;
    static HashSet<MapLocation> line = new HashSet<>();
    static boolean isTracing = false;
    static Direction tracingDirection = Direction.NORTH;
    static int obstacleStarDist = 0;

    // Define broadcast channels
    static final int SET_TARGET_CHANNEL = 0;
    static final int TARGET_COMPLETED_CHANNEL = 1;

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
     * Tower logic: Coordinate unit production and target assignment.
     */
    public static void runTower(RobotController rc) throws GameActionException {
        manageResources(rc); // Handle saving turns and resource management
        processMessages(rc);  // Read and act on messages from other units
        buildUnits(rc);       // Build new robots based on optimal ratios
        upgradeTowerIfAdvantageous(rc); // Upgrade tower if conditions allow
    }

    /**
     * Manages saving resources for specific actions and handles cooldowns.
     */
    private static void manageResources(RobotController rc) throws GameActionException {
        if (savingTurns > 0) {
            savingTurns--;
            rc.setIndicatorString("Saving: " + savingTurns + " turns left");
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
            rc.setIndicatorString("Cooldown: " + savingCooldown + " turns left");
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
        // Read messages from SET_TARGET_CHANNEL and TARGET_COMPLETED_CHANNEL
        int[] setTargetMessages = rc.readBroadcast(SET_TARGET_CHANNEL);
        if (setTargetMessages.length >= 3 && setTargetMessages[0] == MessageType.SET_TARGET.ordinal()) {
            int targetX = setTargetMessages[1];
            int targetY = setTargetMessages[2];
            MapLocation targetLoc = new MapLocation(targetX, targetY);
            if (!exploredRuins.contains(targetLoc)) {
                sharedTarget = targetLoc;
                System.out.println("Shared target set to: " + sharedTarget);
            }
        }

        int[] targetCompletedMessages = rc.readBroadcast(TARGET_COMPLETED_CHANNEL);
        if (targetCompletedMessages.length >= 3 && targetCompletedMessages[0] == MessageType.TARGET_COMPLETED.ordinal()) {
            int completedX = targetCompletedMessages[1];
            int completedY = targetCompletedMessages[2];
            MapLocation completedLoc = new MapLocation(completedX, completedY);
            if (sharedTarget != null && sharedTarget.equals(completedLoc)) {
                exploredRuins.add(sharedLoc);
                System.out.println("Shared target completed at: " + sharedTarget);
                sharedTarget = null; // Reset shared target
                savingCooldown = 20;  // Initiate cooldown after completion
                lastSavingAction = SavingAction.NONE;
            }
        }
    }

    /**
     * Broadcasts the SET_TARGET message to all soldiers.
     */
    private static void broadcastSetTarget(RobotController rc, MapLocation targetLoc) throws GameActionException {
        // Send the SET_TARGET message on SET_TARGET_CHANNEL
        rc.broadcast(SET_TARGET_CHANNEL, new int[]{
            MessageType.SET_TARGET.ordinal(),
            targetLoc.x,
            targetLoc.y
        });
        System.out.println("Broadcasted SET_TARGET message with target " + targetLoc);
    }

    /**
     * Broadcasts the TARGET_COMPLETED message to the tower.
     */
    private static void broadcastTargetCompleted(RobotController rc, MapLocation completedTarget) throws GameActionException {
        // Send the TARGET_COMPLETED message on TARGET_COMPLETED_CHANNEL
        rc.broadcast(TARGET_COMPLETED_CHANNEL, new int[]{
            MessageType.TARGET_COMPLETED.ordinal(),
            completedTarget.x,
            completedTarget.y
        });
        System.out.println("Broadcasted TARGET_COMPLETED message for target " + completedTarget);
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
                    System.out.println("Marking tower pattern for upgrade at " + loc);
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
     * Soldier logic: Concentrate resources on completing the shared target ruin.
     */
    public static void runSoldier(RobotController rc) throws GameActionException {
        // Listen for messages to set sharedTarget
        listenForTarget(rc);

        if (sharedTarget == null || exploredRuins.contains(sharedTarget)) {
            System.out.println("No active shared target for soldier at " + rc.getLocation());
            return; // No active target
        }

        // Move towards the shared target
        if (!rc.getLocation().equals(sharedTarget)) {
            Direction dir = rc.getLocation().directionTo(sharedTarget);
            if (rc.canMove(dir)) {
                rc.move(dir);
                System.out.println("Soldier moving towards shared target at " + sharedTarget);
            } else {
                // If blocked, try alternative movement or wait
                System.out.println("Soldier blocked while moving towards shared target at " + sharedTarget);
            }
        }

        // Paint required tiles and complete the tower
        if (rc.getLocation().equals(sharedTarget)) {
            if (rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, sharedTarget)) {
                rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, sharedTarget);
                System.out.println("Marking tower pattern at shared target: " + sharedTarget);
            }

            MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(sharedTarget, 8);
            for (MapInfo tile : nearbyTiles) {
                if (tile.getMark() != tile.getPaint() && tile.getMark() != PaintType.EMPTY) {
                    boolean useSecondaryColor = tile.getMark() == PaintType.ALLY_SECONDARY;
                    if (rc.canAttack(tile.getMapLocation())) {
                        rc.attack(tile.getMapLocation(), useSecondaryColor);
                        System.out.println("Painted tile at " + tile.getMapLocation());
                    }
                }
            }

            // Complete the tower if all required tiles are painted
            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, sharedTarget)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, sharedTarget);
                exploredRuins.add(sharedTarget);
                System.out.println("Tower completed at shared target: " + sharedTarget);
                
                // Notify tower about completion before resetting sharedTarget
                broadcastTargetCompleted(rc, sharedTarget);
                
                sharedTarget = null; // Reset shared target
            }
        }

        // Paint the current tile as the soldier moves
        paintCurrentTile(rc);

        // Attack enemies or perform additional actions
        attackEnemies(rc);
    }

    /**
     * Listens for messages to set sharedTarget.
     */
    private static void listenForTarget(RobotController rc) throws GameActionException {
        // Read messages from SET_TARGET_CHANNEL
        int[] setTargetMessages = rc.readBroadcast(SET_TARGET_CHANNEL);
        if (setTargetMessages.length >= 3 && setTargetMessages[0] == MessageType.SET_TARGET.ordinal()) {
            int targetX = setTargetMessages[1];
            int targetY = setTargetMessages[2];
            MapLocation targetLoc = new MapLocation(targetX, targetY);
            if (!exploredRuins.contains(targetLoc)) {
                sharedTarget = targetLoc;
                System.out.println("Soldier received new shared target: " + sharedTarget);
            }
        }
    }

    /**
     * Notifies the tower that the target has been completed.
     */
    private static void notifyTowerCompletion(RobotController rc, MapLocation completedTarget) throws GameActionException {
        if (completedTarget == null) return; // Avoid null pointer

        // Broadcast the TARGET_COMPLETED message
        rc.broadcast(TARGET_COMPLETED_CHANNEL, new int[]{
            MessageType.TARGET_COMPLETED.ordinal(),
            completedTarget.x,
            completedTarget.y
        });
        System.out.println("Broadcasted TARGET_COMPLETED message for target " + completedTarget);
    }

    /**
     * Mopper logic: Clear enemy paint near the shared target or move randomly.
     */
    public static void runMopper(RobotController rc) throws GameActionException {
        if (sharedTarget != null) {
            MapLocation current = rc.getLocation();
            for (Direction dir : directions) {
                MapLocation adjacent = current.add(dir);
                if (rc.canAttack(adjacent) && rc.senseMapInfo(adjacent).getPaint().isEnemy()) {
                    rc.attack(adjacent); // Remove enemy paint
                    System.out.println("Mopper cleared enemy paint at " + adjacent);
                }
            }
        } else {
            // Move randomly if no specific task
            randomMove(rc);
        }
    }

    /**
     * Splasher logic: Move towards the shared target or attack nearby ruins.
     */
    public static void runSplasher(RobotController rc) throws GameActionException {
        if (sharedTarget != null) {
            Direction dir = rc.getLocation().directionTo(sharedTarget);
            if (rc.canMove(dir)) {
                rc.move(dir);
                System.out.println("Splasher moving towards shared target at " + sharedTarget);
            } else if (rc.canAttack(sharedTarget)) {
                rc.attack(sharedTarget);
                System.out.println("Splasher attacking shared target at " + sharedTarget);
            }
        } else {
            randomMove(rc);
        }

        // Attack enemies or perform additional actions
        attackEnemies(rc);
    }

    /**
     * Finds the nearest unexplored ruin.
     */
    private static MapLocation findNearestUnexploredRuin(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation nearestRuin = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            if (tile.hasRuin() && !exploredRuins.contains(tile.getMapLocation())) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < minDist) {
                    nearestRuin = tile.getMapLocation();
                    minDist = dist;
                }
            }
        }
        if (nearestRuin != null) {
            System.out.println("Found nearest unexplored ruin at " + nearestRuin + " with distance " + minDist);
        }
        return nearestRuin;
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
     * Moves the robot in a random direction.
     */
    private static void randomMove(RobotController rc) throws GameActionException {
        Direction randomDir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(randomDir)) {
            rc.move(randomDir);
            System.out.println("Moved randomly to " + rc.getLocation().add(randomDir));
        } else {
            System.out.println("Failed to move randomly towards " + randomDir);
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
        if (sharedTarget == null) return;

        if (!sharedTarget.equals(prevDest)) {
            prevDest = sharedTarget;
            line = createLine(rc.getLocation(), sharedTarget); // Generate path to target
        }

        if (!isTracing) {
            Direction dir = rc.getLocation().directionTo(sharedTarget);
            if (rc.canMove(dir)) {
                rc.move(dir); // Move directly toward the target
                System.out.println("Bug2: Moved " + dir + " towards " + sharedTarget);
            } else {
                isTracing = true; // Enter tracing mode if movement is blocked
                obstacleStarDist = rc.getLocation().distanceSquaredTo(sharedTarget);
                tracingDirection = dir;
                System.out.println("Bug2: Entering tracing mode towards " + sharedTarget);
            }
        } else {
            if (line.contains(rc.getLocation()) && rc.getLocation().distanceSquaredTo(sharedTarget) < obstacleStarDist) {
                isTracing = false; // Exit tracing mode when back on path
                System.out.println("Bug2: Exiting tracing mode, back on path to " + sharedTarget);
            } else {
                for (int i = 0; i < 8; i++) {
                    if (rc.canMove(tracingDirection)) {
                        rc.move(tracingDirection);
                        System.out.println("Bug2: Tracing move " + tracingDirection + " towards " + sharedTarget);
                        break;
                    }
                    tracingDirection = tracingDirection.rotateLeft(); // Rotate to find valid direction
                }
            }
        }
    }
}
