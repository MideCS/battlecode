package RPGbot;

import battlecode.common.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;



public class RobotPlayer {

    // Game State variables 
    static int turnCount = 0;
    static boolean isSaving = false;
    static int savingTurns = 0;
    static int savingCooldown = 0;

    private enum SavingAction {
        NONE, SAVE_CHIPS, UPGRADE_TOWER
    }

    static SavingAction lastSavingAction = SavingAction.NONE;
    static HashSet<MapLocation> exploredRuins = new HashSet<>();
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();

    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    // UNit rations for production
    static final double SOLDIER_RATIO = 0.5; // 50% Soldiers
    static final double MOPPER_RATIO = 0.3; // 30% Moppers
    static final double SPLASHER_RATIO = 0.2; // 20% Splashers
    static int soldierCount = 0;
    static int mopperCount = 0;
    static int splasherCount = 0;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
    

        while (true) {
            turnCount ++;  // Increment the turn counter 
            try {
                switch (rc.getType()){
                    case SOLDIER -> runSoldier(rc); break; 
                    case MOPPER -> runMopper(rc); break;
                    case SPLASHER -> break; 
                    default -> runTower(rc); break;
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
            savingTurns --;

            if (savingTurns == 0) {
                switch (lastSavingAction) {
                    case SAVE_CHIPS:
                        savingCooldown = 20; break;
                    case UPGRADE_TOWER;
                        savingCooldown = 30; break;
                    default:
                        savingCooldown = 0; break;
                }
                lastSavingAction = SavingAction.NONE
            }
        } else if (savingCooldown > 0) {
        savingCooldown--;
        } else {
        isSaving = false;
        }
    }

    private static void processMessages(RobotController rc) throws GameActionException {
        // TO be completed
    }

    private static void buildUnits(RobotController rc) throws GameActionException {
        if (isSaving) {
            return; // Do not produce units while saving
        }

        int totalProduced = soldierCount + mopperCount + splasherCount;
        double soldierFraction = totalProduced > 0 ? (double) soldierCount / totalProduced : 0; // Avoid division by zero 
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

        // Build two robots in random directions
        Direction dir1 = directions[rng.nextInt(directions.length)];
        Direction dir2 = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc1 = rc.getLocation().add(dir);
        MapLocation nextLoc2 = rc.getLocation().add(dir);

    }   

    public static void runTower(RobotController rc) throws GameActionException{

        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);

        int robotType = rng.nextInt(3);
        if (robotType == 0 && rc.canBuildRobot(UnitType.SOLDIER, nextLoc)){
            rc.buildRobot(UnitType.SOLDIER, nextLoc);
            System.out.println("BUILT A SOLDIER");
        }
        else if (robotType == 1 && rc.canBuildRobot(UnitType.MOPPER, nextLoc)){
            rc.buildRobot(UnitType.MOPPER, nextLoc);
            System.out.println("BUILT A MOPPER");
        }
        else if (robotType == 2 && rc.canBuildRobot(UnitType.SPLASHER, nextLoc)){


            rc.setIndicatorString("SPLASHER NOT IMPLEMENTED YET");
        }

        // Read incoming messages
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            System.out.println("Tower received message: '#" + m.getSenderID() + " " + m.getBytes());
        }

        // TODO: can we attack other bots?
    }


    public static void runSoldier(RobotController rc) throws GameActionException{

        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();

        // Search for a nearby ruin to complete.
        MapInfo curRuin = null;
        for (MapInfo tile : nearbyTiles){
            if (tile.hasRuin()){
                curRuin = tile;
            }
        }
        if (curRuin != null){
            MapLocation targetLoc = curRuin.getMapLocation();
            Direction dir = rc.getLocation().directionTo(targetLoc);
            if (rc.canMove(dir))
                rc.move(dir);

            // Mark the pattern we need to draw to build a tower here if we haven't already.
            MapLocation shouldBeMarked = curRuin.getMapLocation().subtract(dir);
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

        // Move and attack randomly if no objective.
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);
        if (rc.canMove(dir)){
            rc.move(dir);
        }

        // Try to paint beneath us as we walk to avoid paint penalties.
        // Avoiding wasting paint by re-painting our own tiles.
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())){
            rc.attack(rc.getLocation());
        }
    }


    public static void runMopper(RobotController rc) throws GameActionException{

        // Move and attack randomly.
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);
        if (rc.canMove(dir)){
            rc.move(dir);
        }
        if (rc.canMopSwing(dir)){
            rc.mopSwing(dir);
            System.out.println("Mop Swing! Booyah!");
        }
        else if (rc.canAttack(nextLoc)){
            rc.attack(nextLoc);
        }

        // We can also move our code into different methods or classes to better organize it!
        updateEnemyRobots(rc);
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
}

