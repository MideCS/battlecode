package Selfmade;
import battlecode.common.*;
import scala.Unit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

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

//        System.out.println("Manage Resources - Saving Action: " + lastSavingAction);
        // Decrement saving turns until we reach zero, then initiate the cooldown period
        if (savingTurns > 0) {
            savingTurns--;
        }
        else if (savingTurns == 0) {
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

         else {
            isSaving = false;
        }
    }

    private static void processMessages(RobotController rc) throws GameActionException {
        for (Message message: rc.readMessages(-1)) {
            if (message.getBytes() == SavingAction.SAVE_CHIPS.ordinal()){
                savingTurns = 20;
                isSaving = true;
                lastSavingAction = SavingAction.SAVE_CHIPS;
            } else if (message.getBytes() == SavingAction.UPGRADE_TOWER.ordinal()){
                savingTurns = 30;
                isSaving = true;
                lastSavingAction = SavingAction.UPGRADE_TOWER;
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
            System.out.println("Painted current tile at " + rc.getLocation());
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
        if (nearestRuin != null) {
            System.out.println("Found Nearest ruin: " + nearestRuin);
        }
        return nearestRuin;
    }

    // This function will run a more effective search for important tiles by restricting movement to previously unchecked tiles.
    private static void explore(RobotController rc, boolean stayOnPaint) throws GameActionException {

        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation randomLoc = rc.getLocation().add(dir);
        // TODO: Function to explore more effectively.
        MapLocation nextLoc = rc.getLocation().add(dir);
        if (rc.canMove(dir) && !exploredTiles.contains(randomLoc)){
            boolean isEnemy =
                    (rc.senseMapInfo(randomLoc).getPaint() == PaintType.ENEMY_PRIMARY ||
                    rc.senseMapInfo(randomLoc).getPaint() == PaintType.ENEMY_SECONDARY);

            if (rc.senseMapInfo(randomLoc).getPaint().isAlly() || (!stayOnPaint &&  !isEnemy)) {
                rc.move(dir);
                exploredTiles.add(randomLoc);
                System.out.println("Moved randomly to " + randomLoc);

            }
        }

        // If no new location to move to, then move to random location if canMove.
        else if (rc.canMove(dir) && !stayOnPaint) {
            rc.move(dir);
//            System.out.println("Failed to move randomly towards " + dir);
        }

        // This function could also scan area/nearby tiles, and if it finds one it hasn't checked, it should go there.
    }

    // Finds the nearest unpainted tile to a given location.
    private static MapLocation findNearestUnpaintedTile(RobotController rc, MapLocation loc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();

        int minDist = Integer.MAX_VALUE;
        MapLocation nearestUnpaintedTile = null;

        for (MapInfo tile : tiles) {
            if (tile.getPaint() != PaintType.EMPTY) continue;
            if (tile.hasRuin()) continue;
            if (tile.isWall()) continue;

            MapLocation tileLoc = tile.getMapLocation();
            int distToLoc = loc.distanceSquaredTo(tileLoc);
            if (distToLoc < minDist && loc != tileLoc) {
                minDist = loc.distanceSquaredTo(tileLoc);
                nearestUnpaintedTile = tileLoc;
            }
        }
        if (nearestUnpaintedTile == null) return loc;
        return nearestUnpaintedTile;
    }


    // This function definitely has some bugs
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
    public static void runSoldier(RobotController rc) throws GameActionException{
        // Try to paint beneath us as we walk to avoid paint penalties.
        paintCurrentTile(rc);


        // Search for a nearby ruin to complete.
        MapLocation curRuin = findNearestUnexploredRuin(rc);



        if (curRuin != null){
            MapLocation unpaintedTile = findNearestUnpaintedTile(rc, curRuin);
            Direction dir = rc.getLocation().directionTo(unpaintedTile);
            if (rc.canMove(dir))
                rc.move(dir);
            else System.out.println("Cannot move towards " + dir);

            // Mark the pattern we need to draw to build a tower here if we haven't already.
            // Note: MapLocation does not have a subtract method. Instead, calculate the location manually.
            MapLocation shouldBeMarked = new MapLocation(unpaintedTile.x - dir.dx, unpaintedTile.y - dir.dy);
            MapInfo markTile = rc.senseMapInfo(shouldBeMarked);
            if (markTile.getMark() == PaintType.EMPTY && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin)){
                rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin);
                System.out.println("Marked tower pattern at " + curRuin);
            }

            // Fill in any spots in the pattern with the appropriate paint.
            MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(curRuin, 8);
            for (MapInfo patternTile : nearbyTiles){
                if (patternTile.getMark() != patternTile.getPaint() && patternTile.getMark() != PaintType.EMPTY){
                    boolean useSecondaryColor = patternTile.getMark() == PaintType.ALLY_SECONDARY;
                    if (rc.canAttack(patternTile.getMapLocation()))
                        rc.attack(patternTile.getMapLocation(), useSecondaryColor);
                }
            }

            // Complete the ruin if we can.
            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin)){
                rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, curRuin);
                rc.setTimelineMarker("Tower built", 0, 255, 0);
                // if we complete the ruin, let's add it to the list of explored ruins.
                exploredRuins.add(curRuin);
                knownTowers.add(curRuin); // This might need some work.

                System.out.println("Built a tower at " + curRuin + "!");
            }
        }

        // Move and attack randomly if no objective.
        explore(rc, false);
        Direction attackDir = getNearbyEnemiesDir(rc, 9);

        if (attackDir != null) {

            MapLocation attackLoc = rc.getLocation().add(attackDir);

            if (rc.canAttack(attackLoc)) {
                rc.canAttack(attackLoc);
            }

        }
        updateTowerLocations(rc);
    }

    
    public static void runMopper(RobotController rc) throws GameActionException{
        MapLocation loc = rc.getLocation();
        explore(rc, true);
        Direction swingDir = getNearbyEnemiesDir(rc, 8);

        if (swingDir != null) {

            if (rc.canMopSwing(swingDir)) {
                rc.mopSwing(swingDir);
            }

        }

        MapLocation attackLoc = null;

        MapInfo[] tileInfos = rc.senseNearbyMapInfos(loc, 2);
        for (MapInfo tile : tileInfos) {
            if (tile.getPaint() == PaintType.ENEMY_PRIMARY || tile.getPaint() == PaintType.ENEMY_SECONDARY) {
                attackLoc = tile.getMapLocation();
                break;
            }
        }


        if (attackLoc != null && rc.canAttack(attackLoc)) {
            rc.attack(attackLoc);
        }

    }

    public static void runSplasher(RobotController rc) throws GameActionException {
        explore(rc, true);
    }

    private static void updateTowerLocations(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (!ally.getType().isTowerType()) continue;

            MapLocation allyLoc = ally.getLocation();

            if (knownTowers.contains(allyLoc)) continue;
            knownTowers.add(allyLoc);

        }
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

     private static Direction getNearbyEnemiesDir(RobotController rc, int radius) throws GameActionException {
         RobotInfo[] enemyRobots = rc.senseNearbyRobots(radius, rc.getTeam().opponent());

         if (enemyRobots.length > 0) {
             // Attack the first enemy in the list
             RobotInfo targetEnemy = enemyRobots[0];
             return rc.getLocation().directionTo(targetEnemy.getLocation());
         }
         return null;
     }

}
