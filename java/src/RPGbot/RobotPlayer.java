package RPGbot;

import battlecode.common.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;


public class RobotPlayer {

    static int turnCount = 0;
    static int soldierCooldown = 0;
    static boolean spawnedMopper = false;


    // Communication
    static boolean isSaving = false;
    static boolean isMessenger = false;
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();
    private enum MessageType {
        SAVE_CHIPS,
        SAVE_TOWER,
    }
    static int savingTurns = 0;
    static HashSet<MapLocation> exploredRuins = new HashSet<>(); // Tracks explored ruins
    static int totalRuinsVisited = 0;


    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        // Hello world! Standard output is very useful for debugging.
        // Everything you say here will be directly viewable in your terminal when you run a match!
//        System.out.println("I'm alive");

        rc.setIndicatorString("Hello world!");

//      Delegate some soldiers as messengers
        if (rc.getType() == UnitType.SOLDIER && rc.getID() % 3 == 0) {
            isMessenger = true;
        }

        while (true) {
            turnCount += 1;  // We have now been alive for one more turn!

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

    public static void runTower(RobotController rc) throws GameActionException{

        // TODO: Add saving for Tower when Ruin is reached.
        if (savingTurns == 0) {
            isSaving = false;
            Direction dir = directions[rng.nextInt(directions.length)];
            MapLocation nextLoc = rc.getLocation().add(dir);

            if (soldierCooldown < 10) {
                if (rc.canBuildRobot(UnitType.SOLDIER, nextLoc)) {
                    rc.buildRobot(UnitType.SOLDIER, nextLoc);
//                System.out.println("BUILT A SOLDIER");
                }
            } else {
                if (rc.canBuildRobot(UnitType.MOPPER, nextLoc) && !spawnedMopper) {
                    rc.buildRobot(UnitType.MOPPER, nextLoc);
                    System.out.println("BUILT A MOPPER");
                    spawnedMopper = true;
                } else if (rc.canBuildRobot(UnitType.SPLASHER, nextLoc)) {
                    rc.buildRobot(UnitType.SPLASHER, nextLoc);
                    rc.setIndicatorString("BUILT A SPLASHER");
                    spawnedMopper = false;
                }
            }
        }   else {
            rc.setIndicatorString("Saving for " + savingTurns + " more turns!");
            savingTurns --;
        }

        soldierCooldown ++;
        soldierCooldown %= 40;

        // Read incoming messages
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            System.out.println("Tower received message: '#" + m.getSenderID() + " " + m.getBytes());

            if (m.getBytes() == MessageType.SAVE_CHIPS.ordinal() && !isSaving) {
                savingTurns = 18;
                System.out.println("Tower received SAVE_CHIPS message. Starting to save.");
            }

        }

        // TODO: can we attack other bots?
    }

    public static void runSoldier(RobotController rc) throws GameActionException{

        if (isMessenger) {
            rc.setIndicatorDot(rc.getLocation(), 0, 255, 0);
            updateFriendlyTowers(rc);
        }

        MapInfo curRuin = getNearestRuin(rc);

        if (isMessenger && isSaving && knownTowers.size() > 0) {
            MapLocation dst = knownTowers.get(rng.nextInt(knownTowers.size()));
            Direction dir = rc.getLocation().directionTo(dst);
            if (rc.canMove(dir)) {
                rc.move(dir);
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

        // TODO: implement strategy for direction
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

    public static void updateFriendlyTowers(RobotController rc) throws GameActionException{
        RobotInfo[] friendlyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : friendlyRobots){
            if (!robot.getType().isTowerType()) continue;

            MapLocation allyLoc = robot.getLocation();
            if (knownTowers.contains(allyLoc)){
                if (isSaving) {
                    if (rc.canSendMessage(allyLoc)) {
                        rc.sendMessage(allyLoc, MessageType.SAVE_CHIPS.ordinal());
                        isSaving = false;
                    }
                }

                continue;
            }

            knownTowers.add(allyLoc);
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

    public static MapInfo getNearestRuin(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo curRuin = null;
        int closestRuin = Integer.MAX_VALUE;
        Direction dir = null;
        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin() && rc.senseRobotAtLocation(tile.getMapLocation()) == null && !exploredRuins.contains(tile.getMapLocation())) {
//                Starting saving because seen a ruin
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());

                if (dist < closestRuin) {
                    curRuin = tile;
                    closestRuin = dist;
                    isSaving = true;
                }
            }
        }
        return curRuin;
    }
}
