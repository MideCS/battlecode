package Selfmade;

import battlecode.common.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

/**
 * Optimized RobotPlayer with focused ruin targeting, controlled expansion,
 * and coordinated unit production.
 */
public class RobotPlayer {

    // Game State variables 
    static int turnCount = 0;
    static boolean isSaving = false;
    static int savingTurns = 0;
    static int savingCooldown = 0;

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

    // Tracking other important stuff
    static HashSet<MapLocation> exploredRuins = new HashSet<>();
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {

        while (true) {
            turnCount++;  // Increment the turn counter 
            try {
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

        // Decrement saving turns until we reach zero, then initiate the cooldown period
        if (savingTurns > 0) {
            savingTurns--;

            if (savingTurns == 0) {
                switch (lastSavingAction) {
                    case SAVE_CHIPS:
                        savingCooldown = 20; break;
                    case UPGRADE_TOWER:
                        savingCooldown = 30; break;
                    default:
                        savingCooldown = 0; break;
                }
                lastSavingAction = SavingAction.NONE;
            }
        } else if (savingCooldown > 0) {
            savingCooldown--;
        } else {
            isSaving = false;
        }
    }

    private static void processMessages(RobotController rc) throws GameActionException {
        for (Message message: rc.readMessages(-1)) {
            if (message.getBytes() == MessageType.SAVE_CHIPS.ordinal()){
                savingTurns = 20;
                isSaving = true;
                lastSavingAction = SavingAction.SAVE_CHIPS;
            } else if (message.getBytes() == MessageType.UPGRADE_TOWER.ordinal()){
                savingTurns = 30;
                isSaving = true;
                lastSavingAction = SavingAction.UPGRADE_TOWER;
            }
        }
    }

    private static void buildUnits(RobotController rc) throws GameActionException {
        if (isSaving) {
            return; // Do not produce units while saving
        }

        // Build two robots in random directions
        Direction dir1 = directions[rng.nextInt(directions.length)];
        Direction dir2 = directions[rng.nextInt(directions.length)];
        MapLocation buildLoc1 = rc.getLocation().add(dir1);
        MapLocation buildLoc2 = rc.getLocation().add(dir2);

        UnitType nextUnit = null;
        int robotType = rng.nextInt(5);
        if (robotType == 0 || robotType == 1 || robotType == 2) {
            nextUnit = UnitType.SOLDIER;
        } else if (robotType == 4) {
            nextUnit = UnitType.MOPPER;
        } else if (robotType == 5) {
            nextUnit = UnitType.SPLASHER;
        }

        if (nextUnit != null) {
            if (rc.canBuildRobot(nextUnit, buildLoc1)) {
                rc.buildRobot(nextUnit, buildLoc1);
            }
            if (rc.canBuildRobot(nextUnit, buildLoc2)) {
                rc.buildRobot(nextUnit, buildLoc2);
            }
        }

    }   

    public static void runTower(RobotController rc) throws GameActionException{
        manageResources(rc);
        processMessages(rc);
        buildUnits(rc);
    }

    private static void randomMove(RobotController rc) throws GameActionException {
        Direction randomDir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(randomDir)){
            rc.move(randomDir);
            System.out.println("Moved to " + rc.getLocation().add(randomDir));
        } else {
            System.out.println("Failed to move in direction: " + randomDir);
        }
    }

    private static void paintCurrentTile(RobotController rc) throws GameActionException {
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            rc.attack(rc.getLocation(), false); 
            System.out.println("Painted current tile at " + rc.getLocation());
        }
    }

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
        return nearestRuin;
    }

    // This function definitely has some bugs
    private static void sendMessengerToNotify(RobotController rc) throws GameActionException {
        RobotInfo [] friendlyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : friendlyRobots) {
            if (robot.getType() == UnitType.MOPPER && rc.canSendMessage(robot.getLocation())) {
                rc.setIndicatorDot(0, 255, 0); // Only going to send one Mopper back,
                rc.sendMessage(robot.getLocation(), MessageType.SAVE_CHIPS.ordinal());
                break;
            }
        }
    }

    // the build ruin thing could definitley be modularized a lot more.
    public static void runSoldier(RobotController rc) throws GameActionException{

        // Search for a nearby ruin to complete.
        MapLocation curRuin = findNearestUnexploredRuin(rc);

        if (curRuin != null){
            MapLocation targetLoc = curRuin;
            Direction dir = rc.getLocation().directionTo(targetLoc);
            if (rc.canMove(dir))
                rc.move(dir);
            else
                System.out.println("Cannot move towards " + dir);

            // Mark the pattern we need to draw to build a tower here if we haven't already.
            // Note: MapLocation does not have a subtract method. Instead, calculate the location manually.
            MapLocation shouldBeMarked = new MapLocation(targetLoc.x - dir.dx, targetLoc.y - dir.dy);
            MapInfo markTile = rc.senseMapInfo(shouldBeMarked);
            if (markTile.getMark() == PaintType.EMPTY && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)){
                rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
                System.out.println("Marked tower pattern at " + targetLoc);
            }

            // Fill in any spots in the pattern with the appropriate paint.
            MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(targetLoc, 8);
            for (MapInfo patternTile : nearbyTiles){
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
                // if we complete the ruin, let's add it to the list of explored ruins.
                exploredRuins.add(targetLoc);

                System.out.println("Built a tower at " + targetLoc + "!");
            }
        }

        // Move and attack randomly if no objective.
        Direction randomDir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(randomDir);
        if (rc.canMove(randomDir)){
            rc.move(randomDir);
            System.out.println("Moved randomly to " + rc.getLocation().add(randomDir));
        } else {
            System.out.println("Failed to move randomly towards " + randomDir);
        }

        // Try to paint beneath us as we walk to avoid paint penalties.
        paintCurrentTile(rc);
    }

    
    public static void runMopper(RobotController rc) throws GameActionException{
        randomMove(rc);
    }

    public static void runSplasher(RobotController rc) throws GameActionException {
        randomMove(rc);
    }

    // Methods that don't do anything yet but that could be changed.

    // public static void updateEnemyRobots(RobotController rc) throws GameActionException{
        
    //     // Sensing methods can be passed in a radius of -1 to automatically use the largest possible value.
    //     RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        
    //     if (enemyRobots.length != 0){
    //         MapLocation[] enemyLocations = new MapLocation[enemyRobots.length];
    //         for (int i = 0; i < enemyRobots.length; i++){
    //             enemyLocations[i] = enemyRobots[i].getLocation();
    //         }
            
    //         // Occasionally try to tell nearby allies how many enemy robots we see.
    //         RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
    //         if (rc.getRoundNum() % 20 == 0){
    //             for (RobotInfo ally : allyRobots){
    //                 if (rc.canSendMessage(ally.location, enemyRobots.length)){
    //                     rc.sendMessage(ally.location, enemyRobots.length);
    //                     System.out.println("Sent enemy count to ally at " + ally.location);
    //                 }
    //             }
    //         }
    //     }
    // }

    // private static void attackEnemies(RobotController rc) throws GameActionException {

    //     if (enemyRobots.length > 0) {
    //         // Attack the first enemy in the list
    //         RobotInfo targetEnemy = enemies[0];
    //         if (rc.canAttack(targetEnemy.getLocation())) {
    //             rc.attack(targetEnemy.getLocation());
    //             System.out.println("Attacked enemy at " + targetEnemy.getLocation());
    //         }
    //     }                      
    // }

}
