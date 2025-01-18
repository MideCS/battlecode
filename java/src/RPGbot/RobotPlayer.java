package RPGbot;
import battlecode.common.*;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import apple.laf.JRSUIConstants.Direction;
// RobotPlayer class that is going to have  all our functionality
public class RobotPlayer {

    // Some relevant fields that need to be stored in the RobotPlayer class for future use. 
    static int turnCount = 0;
    static int soldierCooldown = 0;
    static boolean spawnedMopper = false;
    static HashSet<MapLocation> line = null;
    static MapLocation prevDest = null;
    static int obstacleStarDist = 0;
    static MapLocation target = null;
    static boolean isTracing = false;
    static Direction tracingDirection = Direction.NORTH;

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


    // What happens when we actually hit run
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        
        // Intorudce ourselves
        rc.setIndicatorString("Hello world!");

        // Set off an infinite loop till the game is won or lost
        while (true) {

            // We have now been alive for one more turn
            turnCount += 1; 

            // For each robot, try to find out which type of robot it is and execute the necessary functionality
            try {
                switch (rc.getType()){
                    case SOLDIER: runSoldier(rc); break;
                    case MOPPER: runMopper(rc); break;
                    case SPLASHER: runSplasher(rc); break;
                    default: runTower(rc); break;
                }
            }

            // Throw exceptions if things are not working out
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

        // Pick a random location 
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);

        // Draw a soldier if possible, if the soldierCooldown allows
        if (soldierCooldown < 10) {
            if (rc.canBuildRobot(UnitType.SOLDIER, nextLoc)){
                rc.buildRobot(UnitType.SOLDIER, nextLoc);

            }
        }
        else {

            // else build a splasher or a mopper
            if (rc.canBuildRobot(UnitType.MOPPER, nextLoc) && !spawnedMopper){
                rc.buildRobot(UnitType.MOPPER, nextLoc);
                System.out.println("BUILT A MOPPER");
                spawnedMopper = true;
            }
            else if (rc.canBuildRobot(UnitType.SPLASHER, nextLoc)){
                rc.buildRobot(UnitType.SPLASHER, nextLoc);
                rc.setIndicatorString("BUILT A SPLASHER");
                spawnedMopper = false;
            }
        }

        soldierCooldown ++;
        soldierCooldown %= 40;
    }


    public static void runSoldier(RobotController rc) throws GameActionException{

        // Try to go to a ruin
        MapInfo curRuin = getNearestRuin(rc);
        if (curRuin != null){
            MapLocation targetLoc = curRuin.getMapLocation();
            Direction dir = rc.getLocation().directionTo(targetLoc);
            if (rc.canMove(dir))
                rc.move(dir);

            // Mark the pattern necessary to build a tower on that ruin if we haven't already done so 
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

        // As we are walking, if it is possible to paint the tile underneath us, please attempt to do so !
        // Not necessarily important
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())){
            rc.attack(rc.getLocation());
        }
    }


    public static void runMopper(RobotController rc) throws GameActionException{

        // For now it is very bad
        // Move in a random direction
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);
        if (rc.canMove(dir)){
            rc.move(dir);
        }

        // Attempt to swing at muliple enemies if possible
        if (rc.canMopSwing(dir)){
            rc.mopSwing(dir);
        }
        // If that isn't possible, just attack one robots
        else if (rc.canAttack(nextLoc)){
            rc.attack(nextLoc);
        }

        // Update your storage on where the enemy robots are, our moppers are the ones responsible
        // for taking out bad guys
        updateEnemyRobots(rc);
    }

    public static void runSplasher(RobotController rc) throws GameActionException {

        // Right now alsp very bad
        // Seems just to attack the closest ruin that it can ??
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

        // If it isn't doing that it tries to move in the direction of it.
        if (rc.canMove(dir)) {
            rc.move(dir);
        }

    }

    public static void updateEnemyRobots(RobotController rc) throws GameActionException{

        // Method which is used by the moppers.
        // Sense enemy robots within a moppers vicinty and then try to tell friends
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

        // Our method for getting the nearest tile
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
                }
            }
        }
        return curRuin;
    }

    // Bresenham's Line Algorithm

    public static HashSet<MapLocation> createLine(MapLocation a, MapLocation b) {
        HashSet<MapLocation> locs = new HashSet <>();

        int x = a.x, y = a.y;
        int dx = b.x - a.x;
        int dy = b.y - a.y;
        int sx = (int) Math.signum(dx);
        int sy = (int) Math.signum(dy);

        dx = Math.abs(dx);
        dy = Math.abs(dy);

        int d = Math.max(dx, dy);
        int r = d/2;
        if(dx > dy) {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y));
                x += sx;
                r += sy;
                if (r >= dx) {
                    locs.add(new MapLocation(x, y));
                    y += sy;
                    r -= dx;
                }
            }
        }

        else {
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

    public static void bug2(RobotController  rc) throws GameActionException{
        if (!target.equals(prevDest)) {
            prevDest = target;
            line = createLine(target, rc.getLocation());

        }

        if(!isTracing) {
            Direction dir = rc.getLocation().directionTo(target);
            if(rc.canMove(dir)) {
                rc.move(dir);
            }


            else {
                // go into tracing mode =
                isTracing = true;
                obstacleStarDist = rc.getLocation().distanceSquaredTo(target);
                tracingDirection = dir;

            }

        } else  {

            if(line.contains(rc.getLocation()) && rc.getLocation().distanceSquaredTo(target) < obstacleStarDist) {
                isTracing = false;
            }
            else {
                if(rc.canMove(tracingDirection)) {
                    tracingDirection = tracingDirection.rotateRight();
                    tracingDirection = tracingDirection.rotateRight();

                } else {
                    for (int i = 0; i < 8; i++) {
                        tracingDirection = tracingDirection.rotateLeft();
                        if (rc.canMove(tracingDirection)) {
                            rc.move(tracingDirection);
                            tracingDirection = tracingDirection.rotateRight();
                            tracingDirection = tracingDirection.rotateRight();
                            break;

                        }
                    }
                }
            }

        }
    }


}






