package org.droidplanner.core.drone.variables;

import org.droidplanner.core.drone.DroneInterfaces;
import org.droidplanner.core.drone.DroneInterfaces.DroneEventsType;
import org.droidplanner.core.drone.DroneVariable;
import org.droidplanner.core.helpers.coordinates.Coord2D;
import org.droidplanner.core.helpers.geoTools.GeoTools;
import org.droidplanner.core.model.Drone;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class GPS extends DroneVariable implements DroneInterfaces.OnDroneListener{
	public final static int LOCK_2D = 2;
	public final static int LOCK_3D = 3;

    /**
     * Period in milliseconds for the generation of the interpolated position.
     */
	private static final int INTERPOLATOR_NOTIFY_RATE = 33; //ms

    /**
     * Stores the interpolated drone position.
     * * The interpolated position is calculated lazily, and stored until the next update.
     */
    private final Coord2D reusableInterpolatedPosition = new Coord2D(0,0);

    /**
     * If stale, the interpolated position needs to be recomputed.
     */
    private final AtomicBoolean mIsInterpolatedPositionStale = new AtomicBoolean(true);

    /**
     * Used to notify listeners that a new interpolated position is available.
     */
    private final Runnable periodicInterpolatorNotifier = new Runnable(){
        public void run(){
            if(myDrone.isConnectionAlive()){
                mIsInterpolatedPositionStale.set(true);
                myDrone.notifyDroneEvent(DroneEventsType.GPS_INTERPOLATED);
            }
        }
    };

    /**
     * Used to periodically invalidate the calculate interpolated position, and notify listeners.
     */
    private ScheduledExecutorService scheduler;

	private double gps_eph = -1;
	private int satCount = -1;
	private int fixType = -1;
	private Coord2D position;

	private long timeOfPosition = System.currentTimeMillis();
	private double course = 0;

	public GPS(Drone myDrone) {
		super(myDrone);
		myDrone.addDroneListener(this);
	}

	public boolean isPositionValid() {
		return (position != null);
	}

	public Coord2D getPosition() {
		return position;
	}

	public double getGpsEPH() {
		return gps_eph;
	}

	public int getSatCount() {
		return satCount;
	}

	public String getFixType() {
		String gpsFix = "";
		switch (fixType) {
		case LOCK_2D:
			gpsFix = ("2D");
			break;
		case LOCK_3D:
			gpsFix = ("3D");
			break;
		default:
			gpsFix = ("NoFix");
			break;
		}
		return gpsFix;
	}

	public int getFixTypeNumeric() {
		return fixType;
	}

	public double getCourse(){
		return course;
	}

	public int getPositionAgeInMillis(){
		return (int) (System.currentTimeMillis()-timeOfPosition);
	}

	public Coord2D getInterpolatedPosition(){
		if(position != null && myDrone.isConnectionAlive()){
            return updateInterpolatedPosition();
		}
        else{
			return position;
		}
	}

    private Coord2D updateInterpolatedPosition(){
        if(mIsInterpolatedPositionStale.get()) {
            final int timeDelta = getPositionAgeInMillis();
            final double groundSpeed = myDrone.getSpeed().getGroundSpeed().valueInMetersPerSecond();

            if (timeDelta > 0 && groundSpeed > 0) {
                GeoTools.newCoordFromBearingAndDistance(position, getCourse(),
                        timeDelta / 1000.0 * groundSpeed, reusableInterpolatedPosition);
            }
            mIsInterpolatedPositionStale.set(false);
        }

        return reusableInterpolatedPosition;
    }

	public void setGpsState(int fix, int satellites_visible, int eph) {
		if (satCount != satellites_visible) {
			satCount = satellites_visible;
			gps_eph = (double) eph / 100; // convert from eph(cm) to gps_eph(m)
			myDrone.notifyDroneEvent(DroneEventsType.GPS_COUNT);
		}
		if (fixType != fix) {
			fixType = fix;
			myDrone.notifyDroneEvent(DroneEventsType.GPS_FIX);
		}
	}

	public void setPosition(Coord2D position) {
		this.timeOfPosition = System.currentTimeMillis();
		recalculateCourse(position);
		if (this.position != position) {
			this.position = position;
			myDrone.notifyDroneEvent(DroneEventsType.GPS);
		}
		resetInterpolatorNotifierScheduler();
	}

	private void recalculateCourse(Coord2D position) {
		if(this.position!=null){
			course = GeoTools.getHeadingFromCoordinates(this.position, position);
		}
	}

	@Override
	public void onDroneEvent(DroneEventsType event, Drone drone) {
		switch (event) {
			case HEARTBEAT_FIRST:
			case HEARTBEAT_RESTORED:
				resetInterpolatorNotifierScheduler();
                break;

			case HEARTBEAT_TIMEOUT:
			case DISCONNECTED:
				cancelInterpolatorNotifier();
                break;
		}
	}

	private void resetInterpolatorNotifierScheduler(){
        if(scheduler == null || scheduler.isShutdown()){
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(periodicInterpolatorNotifier, INTERPOLATOR_NOTIFY_RATE,
                    INTERPOLATOR_NOTIFY_RATE, TimeUnit.MILLISECONDS);
        }
	}

	private void cancelInterpolatorNotifier(){
		if(scheduler !=null){
			scheduler.shutdownNow();
		}
	}
}
